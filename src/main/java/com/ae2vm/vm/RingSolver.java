package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * GAP-4 phase 2: solver for net-amplifying mutual recipe rings ("gaia loops":
 * 4 spirits → 1 ingot, 1 ingot → 12 spirits). The propagation loop drops ring
 * back-edge demand (stock-only assumption), so a pure mutual ring ends up
 * scheduled only along the root direction — the CPU then stalls on missing
 * intermediates (834 missing ingots for the gaia report).
 *
 * <p>The solver folds a detected pure mutual ring into a <b>gross-flow super
 * bundle</b>: {@code patterns} = each ring recipe × its solved craft count,
 * {@code emitted} = gross production per key, {@code used} = gross
 * consumption per key (including the ring's internal traffic). One
 * {@code applyBundleDirect} applies the whole ring: emitted is inserted
 * before used is extracted, so the ring's own production provides the
 * working capital for its consumption — no order-of-application overdraft.
 *
 * <p>Math: the turn count comes from a Jacobian fixed-point iteration over
 * the ring keys, solved FROM BELOW — counts start at zero (the external-root
 * driver at its minimum) and a shortfall bumps the key's own craft count
 * (every key's producer is its own resolver result, so the bump target is
 * unambiguous even for shared intermediates) until {@code needed = Σ consumer
 * crafts × per-craft input + delivery (root key) + external demand floor}
 * closes against {@code cover = start stock + own craft × per-craft output}.
 * Starting below instead of at the propagation's counts converges to the
 * LEAST fixed point — the material-minimal plan, free of inherited ceil
 * granularity. A net-amplifying ring converges geometrically;
 * monotonically growing increments beyond the cap (a net-draining ring)
 * abandon the solve and fall back to the previous behavior.
 *
 * <p>The startup timing constraint — the first round's inputs must be on hand
 * before the first output lands — is reported separately as a seed shortfall
 * (timing capital, not net consumption; the ring pays it back within the
 * first round). Both ring firing directions are probed and the smaller seed
 * is kept.
 *
 * <p>Topology scope: strongly-connected sets with patterns and no
 * self-adjacency. Shared intermediates (a key consumed by multiple members)
 * and multi-input members are handled by the iteration; a net-drain on a
 * ring MEMBER (negative net effect on a key whose producer is in the SCC)
 * marks a conversion/lossy ring — the conversion-ring guard and the
 * working-capital simulation own those, and folding them into a gross bundle
 * would break their conservation semantics. A net-drain on an EXTERNAL key
 * (no producer in the SCC — the fuel {@code C} of {@code B+C -> D} in a
 * shared-intermediate ring) is ordinary ingredient demand: the net bundle
 * extracts it and any stock shortfall is reported honestly, so it does not
 * disqualify the ring.
 */
final class RingSolver {

    /** Result of a successful ring solve. */
    static final class RingPlan {
        /** Ring keys — the caller removes them from the aggregation total. */
        final List<IAEItemStack> ringKeys = new ArrayList<>();
        /** Ring recipe → turn count. */
        final Map<ICraftingPatternDetails, BigInteger> patterns = new LinkedHashMap<>();
        /** Gross production per key (own craft × per-craft output). */
        final Map<IAEItemStack, BigInteger> emitted = new LinkedHashMap<>();
        /** Gross consumption per key (Σ consumer crafts × per-craft input). */
        final Map<IAEItemStack, BigInteger> used = new LinkedHashMap<>();
        /** Startup seed shortfall per key (timing: first round's input vs stock). */
        final Map<IAEItemStack, BigInteger> seedShortfall = new LinkedHashMap<>();
    }

    /** Per-craft typed inputs/outputs of one ring recipe (returned inputs excluded). */
    interface RecipeView {
        ICraftingPatternDetails pattern();

        Map<IAEItemStack, BigInteger> inputs();

        Map<IAEItemStack, BigInteger> outputs();
    }

    private static final int MAX_RING_SIZE = 64;
    private static final int MAX_ITERATIONS = 64;
    private static final BigInteger CRAFT_CAP = BigInteger.valueOf(1_000_000_000_000L);

    private RingSolver() {
    }

    /**
     * Solves the simple mutual rings among the scheduled keys. Keys of solved
     * rings are listed in each plan's {@code ringKeys} and must be removed
     * from the aggregation total by the caller (the net bundle takes over
     * their scheduling).
     *
     * @param recipeOf     key → its pattern's per-craft typed inputs/outputs
     *                     ({@code null} for keys without a pattern — leaves)
     * @param itemDemand   key → aggregated demand (units) recorded by the
     *                     propagation for consumers OUTSIDE the solved rings —
     *                     the external demand floor of ring members
     * @param startStockOf key → starting stock (executeStartStock snapshot)
     * @param rootKey      the plan's delivery key
     * @param rootDeliver  the plan's delivery amount for {@code rootKey}
     */
    static List<RingPlan> solve(
            Map<IAEItemStack, BigInteger> total,
            Map<IAEItemStack, BigInteger> itemDemand,
            Function<IAEItemStack, RecipeView> recipeOf,
            Function<IAEItemStack, BigInteger> startStockOf,
            IAEItemStack rootKey,
            BigInteger rootDeliver) {

        List<RingPlan> plans = new ArrayList<>();
        // Node set: scheduled keys plus their pattern-having inputs — the
        // propagation loop drops ring back-edges, so a ring member reached only
        // through a dropped back-edge is not in total; the node set must look
        // through the recipes' condensed inputs to see the whole ring.
        Set<IAEItemStack> nodes = new LinkedHashSet<>();
        nodes.addAll(total.keySet());
        List<IAEItemStack> work = new ArrayList<>(total.keySet());
        while (!work.isEmpty()) {
            IAEItemStack k = work.remove(work.size() - 1);
            RecipeView v = recipeOf.apply(k);
            if (v == null) continue;
            for (IAEItemStack in : v.inputs().keySet()) {
                if (in.isSameType(k)) continue;
                if (recipeOf.apply(in) != null && nodes.add(in)) work.add(in);
            }
        }
        // dependency edges: member -> the recipe-having keys its recipe consumes
        Map<IAEItemStack, Set<IAEItemStack>> deps = new LinkedHashMap<>();
        for (IAEItemStack k : nodes) {
            RecipeView v = recipeOf.apply(k);
            if (v == null) continue;
            Set<IAEItemStack> d = new LinkedHashSet<>();
            for (IAEItemStack in : v.inputs().keySet()) {
                if (nodes.contains(in) && !in.isSameType(k)) d.add(in);
            }
            if (!d.isEmpty()) deps.put(k, d);
        }
        Set<IAEItemStack> handled = new HashSet<>();
        for (Set<IAEItemStack> scc : tarjan(deps, nodes)) {
            if (scc.size() < 2 || scc.size() > MAX_RING_SIZE) continue;
            boolean touched = false;
            for (IAEItemStack k : scc) {
                for (IAEItemStack h : handled) {
                    if (h.isSameType(k)) {
                        touched = true;
                        break;
                    }
                }
                if (touched) break;
            }
            if (touched) continue;
            RingPlan plan = trySimpleRing(scc, total, itemDemand, recipeOf, startStockOf, rootKey, rootDeliver);
            if (plan == null) continue;
            plans.add(plan);
            handled.addAll(plan.ringKeys);
        }
        return plans;
    }

    private static RingPlan trySimpleRing(
            Set<IAEItemStack> scc,
            Map<IAEItemStack, BigInteger> total,
            Map<IAEItemStack, BigInteger> itemDemand,
            Function<IAEItemStack, RecipeView> recipeOf,
            Function<IAEItemStack, BigInteger> startStockOf,
            IAEItemStack rootKey,
            BigInteger rootDeliver) {

        // ---- shape: strongly-connected set with patterns, no self-adjacency.
        // Shared intermediates (a key consumed by multiple members) and
        // multi-input members are fine — the Jacobian iteration aggregates all
        // consumers' demand per key and bumps each key's own (unique) producer.
        for (IAEItemStack m : scc) {
            RecipeView v = recipeOf.apply(m);
            for (IAEItemStack in : v.inputs().keySet()) {
                if (in.isSameType(m)) return null; // self-adjacent member
            }
        }

        // ---- external-root driver (phase 2c): AE2 indexes patterns by every
        // output slot, so the REQUEST may be rooted at a key the ring only
        // produces as a byproduct (order C; the ring is 4A->B, B->12A+C+D).
        // A member producing the root becomes a minimum-craft driver — the
        // ring must run at least ceil(rootDeliver / outPer) rounds of it, with
        // the surplus landing as ring byproducts. When several members
        // qualify, the one with the largest per-craft output of the root is
        // picked (ties keep the first in SCC order — deterministic); a wrong
        // pick surfaces as a member drain in the gain gate and declines.
        // No producer means the root is unrelated to this ring — no driver.
        IAEItemStack driverMember = null;
        BigInteger driverOutPer = BigInteger.ZERO;
        boolean rootIsMember = rootKey != null && isRingMember(scc, rootKey);
        if (rootKey != null && rootDeliver.signum() > 0 && !rootIsMember) {
            for (IAEItemStack m : scc) {
                RecipeView v = recipeOf.apply(m);
                for (var e : v.outputs().entrySet()) {
                    if (!e.getKey().isSameType(rootKey) || e.getValue().signum() <= 0) continue;
                    if (driverMember == null || e.getValue().compareTo(driverOutPer) > 0) {
                        driverMember = m;
                        driverOutPer = e.getValue();
                    }
                }
            }
        }
        BigInteger driverMin = BigInteger.ZERO;
        if (driverMember != null) {
            driverMin = rootDeliver.add(driverOutPer).subtract(BigInteger.ONE).divide(driverOutPer);
            if (driverMin.compareTo(CRAFT_CAP) > 0) return null; // diverged
        }

        // ---- external demand floor: consumers of a member that sit OUTSIDE
        // the ring (their demand was recorded in itemDemand at propagation
        // counts). Subtract the propagation-time contributions that the solve
        // re-models itself — the ring members' own counts, and, for a
        // byproduct root, the root carrier's (the driver pattern's) counts —
        // or the floor would double-count demand the Jacobian already covers.
        // Without the floor, a from-below solve would under-produce a member
        // that outside consumers draw on.
        Map<IAEItemStack, BigInteger> ext = new HashMap<>();
        for (IAEItemStack k : scc) {
            BigInteger dem = itemDemand.getOrDefault(k, BigInteger.ZERO);
            if (dem.signum() <= 0) continue;
            BigInteger inRing = BigInteger.ZERO;
            for (IAEItemStack m : scc) {
                inRing = inRing.add(total.getOrDefault(m, BigInteger.ZERO)
                        .multiply(recipeOf.apply(m).inputs().getOrDefault(k, BigInteger.ZERO)));
            }
            if (driverMember != null) {
                inRing = inRing.add(total.getOrDefault(rootKey, BigInteger.ZERO)
                        .multiply(recipeOf.apply(driverMember).inputs().getOrDefault(k, BigInteger.ZERO)));
            }
            BigInteger floor = dem.subtract(inRing);
            if (floor.signum() > 0) ext.put(k, floor);
        }

        // ---- Jacobian fixed point over the member craft counts, solved FROM
        // BELOW: counts start at zero (the driver at its minimum) and the
        // iteration bumps up until every constraint closes, converging to the
        // LEAST fixed point — the material-minimal plan. Starting from the
        // propagation's counts instead would inherit their ceil granularity
        // as permanent surplus.
        Map<IAEItemStack, BigInteger> x = new HashMap<>();
        for (IAEItemStack k : scc) {
            x.put(k, BigInteger.ZERO);
        }
        boolean converged = false;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // enforce the external-root driver minimum before deriving needs
            if (driverMember != null) {
                BigInteger cur = x.get(driverMember);
                if (cur.compareTo(driverMin) < 0) x.put(driverMember, driverMin);
            }
            // needed[k] = Σ consumer crafts × per-craft input + root delivery
            //             + external demand floor
            Map<IAEItemStack, BigInteger> needed = new HashMap<>();
            for (IAEItemStack m : scc) {
                RecipeView v = recipeOf.apply(m);
                BigInteger xf = x.get(m);
                if (xf.signum() <= 0) continue;
                for (var e : v.inputs().entrySet()) {
                    needed.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
                }
            }
            if (rootKey != null && rootDeliver.signum() > 0 && rootIsMember) {
                needed.merge(rootKey, rootDeliver, BigInteger::add);
            }
            for (var e : ext.entrySet()) {
                needed.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
            // cover[k] = start stock + own craft × per-craft output
            boolean changed = false;
            for (IAEItemStack k : scc) {
                RecipeView v = recipeOf.apply(k);
                BigInteger outPer = v.outputs().getOrDefault(k, BigInteger.ZERO);
                if (outPer.signum() <= 0) return null; // cannot scale itself
                BigInteger dem = needed.getOrDefault(k, BigInteger.ZERO);
                BigInteger cov = nonNeg(startStockOf.apply(k)).add(x.get(k).multiply(outPer));
                if (dem.compareTo(cov) > 0) {
                    BigInteger bump = dem.subtract(cov).add(outPer).subtract(BigInteger.ONE).divide(outPer);
                    BigInteger nx = x.get(k).add(bump);
                    if (nx.compareTo(CRAFT_CAP) > 0) return null; // diverged
                    x.put(k, nx);
                    changed = true;
                }
            }
            if (!changed) {
                converged = true;
                break;
            }
        }
        if (!converged) return null; // net-draining ring: honest fallback

        // ---- stock-covered root: a from-below solve that stayed at zero
        // means the network stock covers every demand — the ring has nothing
        // to do. Return an empty plan carrying the ring keys so the caller
        // strips the propagation's unbacked counts (replaying them would
        // schedule crafts with no demand behind them).
        boolean allZero = true;
        for (IAEItemStack k : scc) {
            if (x.get(k).signum() > 0) { allZero = false; break; }
        }
        if (allZero) {
            RingPlan idle = new RingPlan();
            idle.ringKeys.addAll(scc);
            return idle;
        }

        // ---- material closure post-check (the adoption gate): independently
        // re-verify the FINAL counts close material balance for every member —
        // own output plus stock must cover in-ring consumption, the root
        // delivery and the external floor. A violation means the iteration's
        // model diverged from the constraint set; adopting nothing keeps the
        // propagation plan in force (fail-safe, never adopt a broken solve).
        {
            Map<IAEItemStack, BigInteger> needed = new HashMap<>();
            for (IAEItemStack m : scc) {
                RecipeView v = recipeOf.apply(m);
                BigInteger xf = x.get(m);
                if (xf.signum() <= 0) continue;
                for (var e : v.inputs().entrySet()) {
                    needed.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
                }
            }
            if (rootKey != null && rootDeliver.signum() > 0 && rootIsMember) {
                needed.merge(rootKey, rootDeliver, BigInteger::add);
            }
            for (var e : ext.entrySet()) {
                needed.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
            for (IAEItemStack k : scc) {
                BigInteger outPer = recipeOf.apply(k).outputs().getOrDefault(k, BigInteger.ZERO);
                if (outPer.signum() <= 0) return null; // cannot scale itself
                BigInteger cov = nonNeg(startStockOf.apply(k)).add(x.get(k).multiply(outPer));
                if (needed.getOrDefault(k, BigInteger.ZERO).compareTo(cov) > 0) {
                    return null; // closure broken: adopt nothing
                }
            }
        }

        // ---- gain gate: only net-amplifying rings are ours. A DRAIN on a ring
        // MEMBER (a key whose own producer sits in this SCC) marks a
        // conversion/lossy ring — the conversion-ring guard and the
        // working-capital simulation own those, and folding them into a gross
        // bundle would break their conservation semantics — UNLESS the drain
        // is covered by the member's external demand floor or its stock
        // (working capital the plan may legitimately spend). At convergence
        // the stock clause is implied by the material constraints; it stays
        // as a defensive bound. A drain on an EXTERNAL key (no producer in
        // the SCC — e.g. the fuel C of {@code B+C -> D}) is ordinary
        // ingredient demand: the net bundle extracts it and the aggregation
        // reports any shortfall honestly.
        if (!isNetAmplifying(scc, x, recipeOf, startStockOf, ext)) {
            return null; // member drain beyond external demand and stock, or value-conserving
        }

        // ---- gross-flow bundle. Emitted/used are keyed by ALL recipe
        // inputs/outputs (byproducts land in emitted, external materials in
        // used) so the plan reports and extracts the full material flow.
        RingPlan plan = new RingPlan();
        for (IAEItemStack k : scc) {
            RecipeView v = recipeOf.apply(k);
            plan.patterns.put(v.pattern(), x.get(k));
        }
        for (IAEItemStack m : scc) {
            RecipeView v = recipeOf.apply(m);
            BigInteger xf = x.get(m);
            if (xf.signum() <= 0) continue;
            for (var e : v.outputs().entrySet()) {
                plan.emitted.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
            for (var e : v.inputs().entrySet()) {
                plan.used.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
        }
        plan.ringKeys.addAll(scc);
        if (driverMember != null) {
            // driven byproduct root: the net bundle owns its crafting too (the
            // captured root bundle is the producer pattern and would double-fire
            // on replay); the request is delivered from the bundle's emitted
            plan.ringKeys.add(rootKey);
        }

        // ---- startup seed: the network must hold the first round's inputs
        // before the ring's first output lands — timing capital, not net
        // consumption; the ring pays it back within the first round. Each
        // member is probed as the forced START of the round; after it, the
        // probe fires the first READY member (all member inputs already on
        // the probe's ledger) and only forces when nothing is ready — a
        // continuation that can never record more deficits than a fixed
        // order. The smallest seed across starts wins. External (non-member)
        // inputs are skipped: their shortfall is disclosed in full by the net
        // bundle's extraction against network stock — a seed entry would
        // double-report.
        Map<IAEItemStack, BigInteger> best = null;
        List<IAEItemStack> orderBase = new ArrayList<>(scc);
        for (IAEItemStack start : orderBase) {
            List<IAEItemStack> order = new ArrayList<>(orderBase.size());
            order.add(start);
            for (IAEItemStack k : orderBase) {
                if (!k.isSameType(start)) order.add(k);
            }
            Map<IAEItemStack, BigInteger> available = new HashMap<>();
            for (IAEItemStack k : scc) {
                BigInteger s = nonNeg(startStockOf.apply(k));
                if (s.signum() > 0) available.put(k, s);
            }
            Map<IAEItemStack, BigInteger> seed = new HashMap<>();
            boolean[] fired = new boolean[order.size()];
            for (int done = 0; done < order.size(); done++) {
                int pick = -1;
                for (int i = 0; i < order.size() && pick < 0; i++) {
                    if (!fired[i] && isReady(recipeOf.apply(order.get(i)), available, scc)) pick = i;
                }
                if (pick < 0) {
                    for (int i = 0; i < order.size(); i++) {
                        if (!fired[i]) { pick = i; break; }
                    }
                }
                RecipeView v = recipeOf.apply(order.get(pick));
                fired[pick] = true;
                for (var e : v.inputs().entrySet()) {
                    if (!isRingMember(scc, e.getKey())) continue; // used-extraction's domain
                    BigInteger avail = available.getOrDefault(e.getKey(), BigInteger.ZERO);
                    if (e.getValue().compareTo(avail) > 0) {
                        BigInteger deficit = e.getValue().subtract(avail);
                        BigInteger prev = seed.getOrDefault(e.getKey(), BigInteger.ZERO);
                        if (deficit.compareTo(prev) > 0) seed.put(e.getKey(), deficit);
                        available.put(e.getKey(), e.getValue()); // forced: ledger goes negative
                    } else {
                        available.put(e.getKey(), avail);
                    }
                }
                for (var e : v.outputs().entrySet()) {
                    available.merge(e.getKey(), e.getValue(), BigInteger::add);
                }
            }
            BigInteger totalSeed = seed.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger bestTotal = best == null ? null
                    : best.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
            if (best == null || totalSeed.compareTo(bestTotal) < 0) best = seed;
        }
        if (best != null) {
            plan.seedShortfall.putAll(best);
        }
        return plan;
    }

    /**
     * True when every ring-member input of the pattern is already covered by
     * the probe's ledger. The ledger never decreases (the seed model records
     * only each key's deepest unfunded need), so a funded or once-produced
     * key stays ready.
     */
    private static boolean isReady(RecipeView v, Map<IAEItemStack, BigInteger> available,
                                   Set<IAEItemStack> scc) {
        for (var e : v.inputs().entrySet()) {
            if (!isRingMember(scc, e.getKey())) continue;
            if (e.getValue().compareTo(available.getOrDefault(e.getKey(), BigInteger.ZERO)) > 0) {
                return false;
            }
        }
        return true;
    }

    private static BigInteger nonNeg(BigInteger v) {
        return v.signum() > 0 ? v : BigInteger.ZERO;
    }

    /** True when {@code key} is a member of the strongly-connected set. */
    private static boolean isRingMember(Set<IAEItemStack> scc, IAEItemStack key) {
        for (IAEItemStack k : scc) {
            if (k.isSameType(key)) return true;
        }
        return false;
    }

    /**
     * Net-amplifying gate on the solved counts: no ring MEMBER may drain
     * beyond its external demand floor plus its stock (a conversion/lossy
     * ring signals exactly that), and some key must net a gain — a
     * value-conserving ring has nothing to amplify. Drains on external keys
     * are ordinary fuel.
     */
    private static boolean isNetAmplifying(Set<IAEItemStack> scc,
                                           Map<IAEItemStack, BigInteger> x,
                                           Function<IAEItemStack, RecipeView> recipeOf,
                                           Function<IAEItemStack, BigInteger> startStockOf,
                                           Map<IAEItemStack, BigInteger> ext) {
        Map<IAEItemStack, BigInteger> produced = new HashMap<>();
        Map<IAEItemStack, BigInteger> consumed = new HashMap<>();
        for (IAEItemStack m : scc) {
            RecipeView v = recipeOf.apply(m);
            BigInteger xf = x.get(m);
            if (xf.signum() <= 0) continue;
            for (var e : v.inputs().entrySet()) {
                consumed.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
            for (var e : v.outputs().entrySet()) {
                produced.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
        }
        java.util.Set<IAEItemStack> keys = new HashSet<>();
        keys.addAll(produced.keySet());
        keys.addAll(consumed.keySet());
        boolean anyGain = false;
        for (IAEItemStack k : keys) {
            BigInteger net = produced.getOrDefault(k, BigInteger.ZERO)
                    .subtract(consumed.getOrDefault(k, BigInteger.ZERO));
            if (net.signum() < 0) {
                if (!isRingMember(scc, k)) continue; // external fuel: honest demand
                BigInteger allowance = ext.getOrDefault(k, BigInteger.ZERO)
                        .add(nonNeg(startStockOf.apply(k)));
                if (net.add(allowance).signum() < 0) return false; // true member drain
                continue; // drain serves outside consumers or spends stock: allowed
            }
            if (net.signum() > 0) anyGain = true;
        }
        return anyGain;
    }

    /** Tarjan SCC (iterative) over {@code deps}; returns SCCs of size ≥ 2. */
    private static List<Set<IAEItemStack>> tarjan(Map<IAEItemStack, Set<IAEItemStack>> deps,
                                                  Set<IAEItemStack> nodes) {
        Map<IAEItemStack, Integer> index = new HashMap<>();
        Map<IAEItemStack, Integer> low = new HashMap<>();
        Map<IAEItemStack, Boolean> onStack = new HashMap<>();
        List<IAEItemStack> stack = new ArrayList<>();
        int[] counter = {0};
        List<Set<IAEItemStack>> sccs = new ArrayList<>();
        for (IAEItemStack root : nodes) {
            if (index.containsKey(root)) continue;
            Deque<Object[]> frames = new ArrayDeque<>();
            frames.push(new Object[]{root, deps.getOrDefault(root, java.util.Collections.<IAEItemStack>emptySet()).iterator()});
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.add(root);
            onStack.put(root, true);
            while (!frames.isEmpty()) {
                Object[] frame = frames.peek();
                IAEItemStack v = (IAEItemStack) frame[0];
                @SuppressWarnings("unchecked")
                java.util.Iterator<IAEItemStack> it = (java.util.Iterator<IAEItemStack>) frame[1];
                boolean descended = false;
                while (it.hasNext()) {
                    IAEItemStack w = it.next();
                    if (!nodes.contains(w)) continue;
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.add(w);
                        onStack.put(w, true);
                        frames.push(new Object[]{w, deps.getOrDefault(w, java.util.Collections.<IAEItemStack>emptySet()).iterator()});
                        descended = true;
                        break;
                    } else if (onStack.getOrDefault(w, false)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                }
                if (descended) continue;
                frames.pop();
                if (!frames.isEmpty()) {
                    IAEItemStack parent = (IAEItemStack) frames.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    Set<IAEItemStack> comp = new LinkedHashSet<>(); // record order: deterministic iteration
                    IAEItemStack w;
                    do {
                        w = stack.remove(stack.size() - 1);
                        onStack.put(w, false);
                        comp.add(w);
                    } while (!w.isSameType(v));
                    if (comp.size() >= 2) sccs.add(comp);
                }
            }
        }
        return sccs;
    }
}
