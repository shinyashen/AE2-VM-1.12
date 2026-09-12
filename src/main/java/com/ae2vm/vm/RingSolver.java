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
 * <p>Math: the turn counts come from a Jacobian fixed-point iteration over
 * the ring's PATTERNS, solved FROM BELOW — counts start at zero (the
 * external-root driver at its minimum) and a shortfall bumps the producing
 * pattern's count until {@code needed = Σ pattern crafts × per-craft input
 * + delivery (root key) + external demand floor} closes against
 * {@code cover = start stock + producing pattern × per-craft output}.
 * Starting below instead of at the propagation's counts converges to the
 * LEAST fixed point — the material-minimal plan, free of inherited ceil
 * granularity. A net-amplifying ring converges geometrically;
 * monotonically growing increments beyond the cap (a net-draining ring)
 * abandon the solve and fall back to the previous behavior.
 *
 * <p>Variables are per PATTERN (phase 7d): a pattern with a primary and a
 * byproduct output inside the ring is one variable feeding several keys —
 * key-level variables would double-count its crafts. The resolver hands the
 * solver the unique any-slot producer of each member key, so every key has
 * exactly one in-ring producer to bump.
 *
 * <p>The startup timing constraint — the first round's inputs must be on hand
 * before the first output lands — is reported separately as a seed shortfall
 * (timing capital, not net consumption; the ring pays it back within the
 * first round). Every pattern is probed as the forced round start, each
 * continuation fires the first ready pattern, and the smallest seed wins.
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

    /**
     * Tries to fold one strongly-connected set into a gross-flow plan.
     * Variables are the set's distinct PATTERNS; constraints are per member
     * KEY. Returns null whenever the shape, the solve or the gates decline —
     * the caller keeps the legacy propagation semantics for those keys.
     */
    private static RingPlan trySimpleRing(
            Set<IAEItemStack> scc,
            Map<IAEItemStack, BigInteger> total,
            Map<IAEItemStack, BigInteger> itemDemand,
            Function<IAEItemStack, RecipeView> recipeOf,
            Function<IAEItemStack, BigInteger> startStockOf,
            IAEItemStack rootKey,
            BigInteger rootDeliver) {

        // ---- variables: the SCC's distinct PATTERNS (phase 7d). A pattern
        // with a primary and a byproduct output inside the ring is ONE
        // variable feeding several keys — key-level variables would
        // double-count its crafts.
        Map<IAEItemStack, RecipeView> viewOfKey = new HashMap<>();
        LinkedHashMap<ICraftingPatternDetails, RecipeView> views = new LinkedHashMap<>();
        for (IAEItemStack k : scc) {
            RecipeView v = recipeOf.apply(k);
            viewOfKey.put(k, v);
            views.putIfAbsent(v.pattern(), v);
        }
        // self-adjacent pattern (one of its outputs re-enters its inputs):
        // the amplifier/recursion family — owned by correctRecursion.
        for (RecipeView v : views.values()) {
            for (IAEItemStack out : v.outputs().keySet()) {
                if (v.inputs().containsKey(out)) return null;
            }
        }
        // per member key: its own pattern's per-craft output of that key (the
        // resolver — primary or T4-unique — guarantees the pattern outputs it).
        Map<IAEItemStack, BigInteger> producerOut = new HashMap<>();
        for (IAEItemStack k : scc) {
            BigInteger outPer = BigInteger.ZERO;
            for (var e : viewOfKey.get(k).outputs().entrySet()) {
                if (e.getKey().isSameType(k)) { outPer = e.getValue(); break; }
            }
            if (outPer.signum() <= 0) return null; // cannot scale itself
            producerOut.put(k, outPer);
        }

        // ---- external-root driver (phase 2c): AE2 indexes patterns by every
        // output slot, so the REQUEST may be rooted at a key the ring only
        // produces as a byproduct (order C; the ring is 4A->B, B->12A+C+D).
        // A pattern producing the root becomes a minimum-craft driver — the
        // ring must run at least ceil(rootDeliver / outPer) rounds of it, with
        // the surplus landing as ring byproducts. When several patterns
        // qualify, the one with the largest per-craft output of the root is
        // picked (ties keep the first in SCC order — deterministic); a wrong
        // pick surfaces as a member drain in the gain gate and declines.
        // No producer means the root is unrelated to this ring — no driver.
        ICraftingPatternDetails driverPattern = null;
        BigInteger driverOutPer = BigInteger.ZERO;
        boolean rootIsMember = rootKey != null && isRingMember(scc, rootKey);
        if (rootKey != null && rootDeliver.signum() > 0 && !rootIsMember) {
            for (var pe : views.entrySet()) {
                for (var e : pe.getValue().outputs().entrySet()) {
                    if (!e.getKey().isSameType(rootKey) || e.getValue().signum() <= 0) continue;
                    if (driverPattern == null || e.getValue().compareTo(driverOutPer) > 0) {
                        driverPattern = pe.getKey();
                        driverOutPer = e.getValue();
                    }
                }
            }
        }
        BigInteger driverMin = BigInteger.ZERO;
        if (driverPattern != null) {
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
                        .multiply(viewOfKey.get(m).inputs().getOrDefault(k, BigInteger.ZERO)));
            }
            if (driverPattern != null) {
                inRing = inRing.add(total.getOrDefault(rootKey, BigInteger.ZERO)
                        .multiply(views.get(driverPattern).inputs().getOrDefault(k, BigInteger.ZERO)));
            }
            BigInteger floor = dem.subtract(inRing);
            if (floor.signum() > 0) ext.put(k, floor);
        }

        // ---- pass 1, structural solvability: the fixed point WITHOUT stock
        // in the cover. A ring that cannot close its own material balance
        // absent stock is structurally net-losing (lossy catalyst cycles) or
        // unsatisfiable — owned by the working-capital machinery, declined
        // here regardless of how much stock happens to exist.
        Map<ICraftingPatternDetails, BigInteger> ys = jacobian(views, scc, producerOut, viewOfKey,
                startStockOf, false, driverPattern, driverMin, rootKey, rootDeliver, rootIsMember, ext);
        if (ys == null) { System.out.println("[RS-DBG] pass1 diverged"); return null; } // diverged: structurally net-losing
        // anyGain on the structural solution: at least one MEMBER key must
        // net a gain — a ring whose members merely circulate while an outside
        // byproduct grows (raw catalyst loops) is owned by the
        // working-capital machinery, not by us.
        boolean anyGain = false;
        {
            Map<IAEItemStack, BigInteger> produced = new HashMap<>();
            Map<IAEItemStack, BigInteger> consumed = new HashMap<>();
            for (var pe : views.entrySet()) {
                BigInteger yp = ys.get(pe.getKey());
                if (yp.signum() <= 0) continue;
                for (var e : pe.getValue().inputs().entrySet()) {
                    consumed.merge(e.getKey(), yp.multiply(e.getValue()), BigInteger::add);
                }
                for (var e : pe.getValue().outputs().entrySet()) {
                    produced.merge(e.getKey(), yp.multiply(e.getValue()), BigInteger::add);
                }
            }
            for (IAEItemStack k : scc) {
                if (produced.getOrDefault(k, BigInteger.ZERO)
                        .subtract(consumed.getOrDefault(k, BigInteger.ZERO)).signum() > 0) {
                    anyGain = true;
                    break;
                }
            }
        }
        if (!anyGain) { System.out.println("[RS-DBG] no member gain"); return null; } // members merely circulate: nothing to amplify

        // ---- pass 2, material minimal: the same fixed point WITH stock in
        // the cover, again solved from below — the least fixed point is the
        // material-minimal plan. Stock spares the ring rounds, and a
        // stock-covered member draw is a bounded stock spend validated by the
        // closure check below.
        Map<ICraftingPatternDetails, BigInteger> y = jacobian(views, scc, producerOut, viewOfKey,
                startStockOf, true, driverPattern, driverMin, rootKey, rootDeliver, rootIsMember, ext);
        if (y == null) { System.out.println("[RS-DBG] pass2 diverged"); return null; } // did not converge: honest fallback

        // ---- stock-covered root: a from-below solve that stayed at zero
        // means the network stock covers every demand — the ring has nothing
        // to do. Return an empty plan carrying the ring keys so the caller
        // strips the propagation's unbacked counts (replaying them would
        // schedule crafts with no demand behind them).
        boolean allZero = true;
        for (var pe : views.entrySet()) {
            if (y.get(pe.getKey()).signum() > 0) { allZero = false; break; }
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
            for (var pe : views.entrySet()) {
                BigInteger yp = y.get(pe.getKey());
                if (yp.signum() <= 0) continue;
                for (var e : pe.getValue().inputs().entrySet()) {
                    needed.merge(e.getKey(), yp.multiply(e.getValue()), BigInteger::add);
                }
            }
            if (rootKey != null && rootDeliver.signum() > 0 && rootIsMember) {
                needed.merge(rootKey, rootDeliver, BigInteger::add);
            }
            for (var e : ext.entrySet()) {
                needed.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
            for (IAEItemStack k : scc) {
                BigInteger cov = nonNeg(startStockOf.apply(k))
                        .add(y.get(viewOfKey.get(k).pattern()).multiply(producerOut.get(k)));
                if (needed.getOrDefault(k, BigInteger.ZERO).compareTo(cov) > 0) {
                    System.out.println("[RS-DBG] closure broken at member");
                    return null; // closure broken: adopt nothing
                }
            }
        }

        // ---- gross-flow bundle. Emitted/used are keyed by ALL recipe
        // inputs/outputs (byproducts land in emitted, external materials in
        // used) so the plan reports and extracts the full material flow.
        RingPlan plan = new RingPlan();
        for (var pe : views.entrySet()) {
            BigInteger yp = y.get(pe.getKey());
            if (yp.signum() <= 0) continue;
            plan.patterns.put(pe.getKey(), yp);
            for (var e : pe.getValue().outputs().entrySet()) {
                plan.emitted.merge(e.getKey(), yp.multiply(e.getValue()), BigInteger::add);
            }
            for (var e : pe.getValue().inputs().entrySet()) {
                plan.used.merge(e.getKey(), yp.multiply(e.getValue()), BigInteger::add);
            }
        }
        plan.ringKeys.addAll(scc);
        if (driverPattern != null) {
            // driven byproduct root: the net bundle owns its crafting too (the
            // captured root bundle is the producer pattern and would double-fire
            // on replay); the request is delivered from the bundle's emitted
            plan.ringKeys.add(rootKey);
        }

        // ---- startup seed: the network must hold the first round's inputs
        // before the ring's first output lands — timing capital, not net
        // consumption; the ring pays it back within the first round. Each
        // pattern is probed as the forced START of the round; after it, the
        // probe fires the first READY pattern (all member inputs already on
        // the probe's ledger) and only forces when nothing is ready — a
        // continuation that can never record more deficits than a fixed
        // order. The smallest seed across starts wins. External (non-member)
        // inputs are skipped: their shortfall is disclosed in full by the net
        // bundle's extraction against network stock — a seed entry would
        // double-report.
        Map<IAEItemStack, BigInteger> best = null;
        List<RecipeView> orderBase = new ArrayList<>(views.values());
        for (RecipeView start : orderBase) {
            List<RecipeView> order = new ArrayList<>(orderBase.size());
            order.add(start);
            for (RecipeView v : orderBase) {
                if (v != start) order.add(v);
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
                    if (!fired[i] && isReady(order.get(i), available, scc)) pick = i;
                }
                if (pick < 0) {
                    for (int i = 0; i < order.size(); i++) {
                        if (!fired[i]) { pick = i; break; }
                    }
                }
                RecipeView v = order.get(pick);
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
     * One from-below Jacobian pass over the pattern counts; returns the solved
     * counts, or null when the iteration diverged. With {@code useStock} the
     * cover includes the start stock (the material solve); without it the
     * cycle must close on its own production (the structural solvability
     * pass — a net-losing cycle diverges here regardless of stock).
     */
    private static Map<ICraftingPatternDetails, BigInteger> jacobian(
            LinkedHashMap<ICraftingPatternDetails, RecipeView> views,
            Set<IAEItemStack> scc,
            Map<IAEItemStack, BigInteger> producerOut,
            Map<IAEItemStack, RecipeView> viewOfKey,
            Function<IAEItemStack, BigInteger> startStockOf,
            boolean useStock,
            ICraftingPatternDetails driverPattern, BigInteger driverMin,
            IAEItemStack rootKey, BigInteger rootDeliver, boolean rootIsMember,
            Map<IAEItemStack, BigInteger> ext) {
        Map<ICraftingPatternDetails, BigInteger> y = new HashMap<>();
        for (var pe : views.entrySet()) {
            y.put(pe.getKey(), BigInteger.ZERO);
        }
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // enforce the external-root driver minimum before deriving needs
            if (driverPattern != null) {
                BigInteger cur = y.get(driverPattern);
                if (cur.compareTo(driverMin) < 0) y.put(driverPattern, driverMin);
            }
            // needed[k] = Σ pattern crafts × per-craft input + root delivery
            //             + external demand floor
            Map<IAEItemStack, BigInteger> needed = new HashMap<>();
            for (var pe : views.entrySet()) {
                BigInteger yp = y.get(pe.getKey());
                if (yp.signum() <= 0) continue;
                for (var e : pe.getValue().inputs().entrySet()) {
                    needed.merge(e.getKey(), yp.multiply(e.getValue()), BigInteger::add);
                }
            }
            if (rootKey != null && rootDeliver.signum() > 0 && rootIsMember) {
                needed.merge(rootKey, rootDeliver, BigInteger::add);
            }
            for (var e : ext.entrySet()) {
                needed.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
            boolean changed = false;
            for (IAEItemStack k : scc) {
                BigInteger outPer = producerOut.get(k);
                BigInteger dem = needed.getOrDefault(k, BigInteger.ZERO);
                ICraftingPatternDetails producer = viewOfKey.get(k).pattern();
                BigInteger cov = y.get(producer).multiply(outPer);
                if (useStock) cov = cov.add(nonNeg(startStockOf.apply(k)));
                if (dem.compareTo(cov) > 0) {
                    BigInteger bump = dem.subtract(cov).add(outPer).subtract(BigInteger.ONE).divide(outPer);
                    BigInteger nx = y.get(producer).add(bump);
                    if (nx.compareTo(CRAFT_CAP) > 0) return null; // diverged
                    y.put(producer, nx);
                    changed = true;
                }
            }
            if (!changed) {
                return y;
            }
        }
        return null; // did not converge
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
