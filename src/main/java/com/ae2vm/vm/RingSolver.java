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
import java.util.Collections;
import java.util.Iterator;

/**
 * Solver for net-amplifying mutual recipe rings ("gaia loops":
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
 * <p>Variables are per PATTERN: a pattern with a primary and a
 * byproduct output inside the ring is one variable feeding several keys —
 * key-level variables would double-count its crafts. The resolver hands the
 * solver the unique any-slot producer of each member key, so every key has
 * exactly one in-ring producer to bump.
 *
 * <p>Runtime faithfulness: the folded plan must EXECUTE on a real CPU, whose
 * local inventory is exactly the plan's job-start withdrawal. Two rules
 * follow. (1) The delivery must be
 * CRAFTED — a real job plans against an inventory that ignores the requested
 * item's own stock (AE2UEL CraftingJob.run), so the root key's stock may not
 * cover {@code rootDeliver}; stock still spares rounds for non-root members'
 * net consumption (billed as withdrawal). (2) The engine bills each member
 * key's net CPU draw plus the {@link #startupFloors} priming floor into the
 * plan's usedItems — the net bundle's gross extraction alone would silently
 * net the ring's own production against its consumption and ship a plan the
 * CPU starves on at t=0 (the original live gaia stall).
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
        /**
         * Out-of-ring consumer demand per member key (the solver's external
         * floor). Idle plans carry it as their entire {@code used}: nothing is
         * crafted, but the stripped member keys must still bill the outside
         * draw their stock reservation used to cover.
         */
        final Map<IAEItemStack, BigInteger> ext = new LinkedHashMap<>();
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
        List<Set<IAEItemStack>> rings = new ArrayList<>();
        for (Set<IAEItemStack> scc : tarjan(deps, nodes)) {
            if (scc.size() >= 2 && scc.size() <= MAX_RING_SIZE) rings.add(scc);
        }
        // ---- consumers-first order: a ring is solved once every
        // consumer ring that draws on it has been solved — their write-backs
        // size the floor this ring amplifies against.
        int n = rings.size();
        Map<IAEItemStack, Integer> ringOf = new HashMap<>();
        for (int i = 0; i < rings.size(); i++) {
            for (IAEItemStack k : rings.get(i)) ringOf.put(k, i);
        }
        // producers[ci] = producer rings ci's members draw on; consumers[pi] =
        // the consumer rings drawing on pi
        List<Set<Integer>> producers = new ArrayList<>();
        List<Set<Integer>> consumers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            producers.add(new LinkedHashSet<>());
            consumers.add(new LinkedHashSet<>());
        }
        for (int ci = 0; ci < n; ci++) {
            for (IAEItemStack u : rings.get(ci)) {
                for (IAEItemStack v : deps.getOrDefault(u, Collections.<IAEItemStack>emptySet())) {
                    int pi = ringOf.getOrDefault(v, -1);
                    if (pi >= 0 && pi != ci) {
                        producers.get(ci).add(pi);
                        consumers.get(pi).add(ci);
                    }
                }
            }
        }
        int[] pending = new int[n];
        Deque<Integer> ready = new ArrayDeque<>();
        for (int ci = 0; ci < n; ci++) {
            pending[ci] = consumers.get(ci).size();
            if (pending[ci] == 0) ready.add(ci);
        }
        List<Integer> order = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            int r = ready.poll();
            order.add(r);
            for (int p : producers.get(r)) {
                if (--pending[p] == 0) ready.add(p);
            }
        }
        for (int ci = 0; ci < n; ci++) {
            if (!order.contains(ci)) order.add(ci); // defensive: unreachable in a DAG
        }
        // floors: solver-local copy of the propagation demand (never the
        // engine's maps) — folded rings write their solved net draw deltas
        // into it, and downstream solves read the updated floors.
        Map<IAEItemStack, BigInteger> floors = new HashMap<>(itemDemand);
        Set<IAEItemStack> handled = new HashSet<>();
        for (int ri : order) {
            Set<IAEItemStack> scc = rings.get(ri);
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
            RingPlan plan = trySimpleRing(scc, total, floors, recipeOf, startStockOf, rootKey, rootDeliver);
            if (plan == null) continue;
            plans.add(plan);
            handled.addAll(plan.ringKeys);
            // write-back: the fold's net draw per touched key, minus what the
            // propagation already counted (its own counts) — downstream rings
            // re-solve against the AMPLIFIED demand, not the naive one.
            Set<IAEItemStack> touchedKeys = new HashSet<>(plan.used.keySet());
            touchedKeys.addAll(plan.emitted.keySet());
            for (IAEItemStack k : touchedKeys) {
                BigInteger solved = plan.used.getOrDefault(k, BigInteger.ZERO)
                        .subtract(plan.emitted.getOrDefault(k, BigInteger.ZERO));
                BigInteger prop = BigInteger.ZERO;
                for (IAEItemStack m : scc) {
                    RecipeView v = recipeOf.apply(m);
                    prop = prop.add(total.getOrDefault(m, BigInteger.ZERO).multiply(
                            v.inputs().getOrDefault(k, BigInteger.ZERO)
                                    .subtract(v.outputs().getOrDefault(k, BigInteger.ZERO))));
                }
                BigInteger delta = solved.subtract(prop);
                if (delta.signum() != 0) floors.merge(k, delta, BigInteger::add);
            }
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

        // ---- variables: the SCC's distinct PATTERNS. A pattern
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
        // resolver — primary or byproduct-unique — guarantees the pattern outputs it).
        Map<IAEItemStack, BigInteger> producerOut = new HashMap<>();
        for (IAEItemStack k : scc) {
            BigInteger outPer = BigInteger.ZERO;
            for (var e : viewOfKey.get(k).outputs().entrySet()) {
                if (e.getKey().isSameType(k)) { outPer = e.getValue(); break; }
            }
            if (outPer.signum() <= 0) return null; // cannot scale itself
            producerOut.put(k, outPer);
        }

        // ---- external-root driver: AE2 indexes patterns by every
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
        if (ys == null) return null; // diverged: structurally net-losing
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
        if (!anyGain) return null; // members merely circulate: nothing to amplify

        // ---- pass 2, material minimal: the same fixed point WITH stock in
        // the cover, again solved from below — the least fixed point is the
        // material-minimal plan. Stock spares the ring rounds, and a
        // stock-covered member draw is a bounded stock spend validated by the
        // closure check below.
        Map<ICraftingPatternDetails, BigInteger> y = jacobian(views, scc, producerOut, viewOfKey,
                startStockOf, true, driverPattern, driverMin, rootKey, rootDeliver, rootIsMember, ext);
        if (y == null) return null; // did not converge: honest fallback

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
            // stock covers every round, but the stripped member keys still
            // owe their out-of-ring draw: the idle bundle bills it (the
            // released stock reservation would otherwise leak from the plan)
            idle.used.putAll(ext);
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
                // same coverage rule as the solve: the root key's stock may
                // not cover its delivery component (crafted, not stocked)
                boolean rootStockExcluded = rootIsMember && k.isSameType(rootKey);
                BigInteger cov = y.get(viewOfKey.get(k).pattern()).multiply(producerOut.get(k));
                if (!rootStockExcluded) cov = cov.add(nonNeg(startStockOf.apply(k)));
                if (needed.getOrDefault(k, BigInteger.ZERO).compareTo(cov) > 0) {
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

        // ---- startup floors are GLOBAL (across all folded rings, and
        // initialized from the job's own net draw, not network stock): see
        // {@link #startupFloors}, called once by the caller after all plans
        // are collected.
        return plan;
    }

    /**
     * One firing order's priming outcome: the per-key startup floor plus THE
     * ORDER THAT PRODUCED IT. The floor is only valid for its own order — a
     * real CPU consumes its per-tick budget strictly in task order, so the
     * caller must emit {@link #order()} as the plan's task sequence or the
     * priming guarantee does not transfer (the coupled-ring zero-slack
     * starvation).
     */
    public static final class FloorPlan {
        private final Map<IAEItemStack, BigInteger> floors;
        private final List<ICraftingPatternDetails> order;

        FloorPlan(Map<IAEItemStack, BigInteger> floors, List<ICraftingPatternDetails> order) {
            this.floors = floors;
            this.order = order;
        }

        /** Priming floor per member key (zero entries removed by the caller). */
        public Map<IAEItemStack, BigInteger> floors() {
            return floors;
        }

        /** The firing order the floor was probed under (ring task order). */
        public List<ICraftingPatternDetails> order() {
            return order;
        }
    }

    /**
     * Minimal per-key startup inventory that keeps the whole folded family
     * deadlock-free on a real CPU (closed local inventory; task returns are
     * the only refill). The engine's job-start withdrawal covers each key's
     * NET draw ({@code netDrawOf}); what the net draw cannot cover is the
     * priming of keys whose production CIRCULATES (intermediates and
     * self-returned catalysts net to zero, but the patterns consuming them
     * must still fire before any return lands).
     *
     * <p>The probe mirrors the cluster's execution shape: passes over the
     * ring patterns in TASK ORDER, each pass firing every pattern whose
     * member inputs the ledger covers (repeatedly, until inputs run dry),
     * outputs refilling the ledger next pass (returns arrive a tick later;
     * the delivered root key never returns). A pass in which nothing fires
     * with crafts left is a true deadlock — the first stuck pattern is then
     * FORCED one craft, its uncovered member inputs becoming the floor.
     * Counts are capped at a shared budget (scaled together so the flow
     * ratios survive) — priming deadlocks surface within the first handful
     * of rounds, never in the long tail. ORDER AND FLOOR RETURN TOGETHER
     * (see {@link FloorPlan}).
     */
    static FloorPlan startupFloors(
            List<RingPlan> plans,
            Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> perCraftPrimings,
            Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> perCraftOutputs,
            Function<IAEItemStack, BigInteger> netDrawOf,
            IAEItemStack outputKey,
            Set<IAEItemStack> members) {
        // distinct ring patterns in task order with proportionally capped counts
        List<ICraftingPatternDetails> orderBase = new ArrayList<>();
        BigInteger maxCount = BigInteger.ONE;
        for (RingPlan plan : plans) {
            for (var e : plan.patterns.entrySet()) {
                if (e.getValue().signum() <= 0) continue;
                if (perCraftPrimings.apply(e.getKey()) == null
                        || perCraftPrimings.apply(e.getKey()).isEmpty()) continue;
                if (!orderBase.contains(e.getKey())) orderBase.add(e.getKey());
                if (e.getValue().compareTo(maxCount) > 0) maxCount = e.getValue();
            }
        }
        if (orderBase.isEmpty()) return new FloorPlan(new HashMap<>(), orderBase);
        BigInteger CAP = BigInteger.valueOf(256);
        Map<ICraftingPatternDetails, BigInteger> remaining0 = new HashMap<>();
        for (RingPlan plan : plans) {
            for (var e : plan.patterns.entrySet()) {
                if (orderBase.contains(e.getKey())) {
                    BigInteger scaled = e.getValue().multiply(CAP).add(maxCount).subtract(BigInteger.ONE)
                            .divide(maxCount);
                    remaining0.put(e.getKey(), scaled.max(BigInteger.ONE));
                }
            }
        }
        Map<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> insOf = new HashMap<>();
        for (ICraftingPatternDetails d : orderBase) insOf.put(d, perCraftPrimings.apply(d));

        // FIRING-ORDER SEARCH: the floor is only meaningful for the order it
        // was probed under, and the strict task-priority scheduler drains
        // priming capital before a late bootstrap pair can close under some
        // orders (the coupled-ring starvation) — so probe ROTATIONS of the
        // task order and keep the completing one with the smallest floor.
        // An order that hits the pass bound with crafts left is a LIVELOCK
        // (progress elsewhere, one task starves forever): it is rejected, not
        // floor-patched, because no finite capital boots it.
        List<ICraftingPatternDetails> bestOrder = null;
        Map<IAEItemStack, BigInteger> bestFloor = null;
        BigInteger bestTotal = null;
        int rotations = Math.min(orderBase.size(), 8);
        for (int r = 0; r < rotations; r++) {
            List<ICraftingPatternDetails> candidate = new ArrayList<>(orderBase.size());
            for (int i = r; i < orderBase.size(); i++) candidate.add(orderBase.get(i));
            for (int i = 0; i < r; i++) candidate.add(orderBase.get(i));
            Map<IAEItemStack, BigInteger> floor = new HashMap<>();
            if (!probeFiringOrder(candidate, new HashMap<>(remaining0), insOf,
                    perCraftOutputs, netDrawOf, outputKey, members, floor)) {
                continue;
            }
            BigInteger total = floor.values().stream()
                    .reduce(BigInteger.ZERO, BigInteger::add);
            if (bestTotal == null || total.compareTo(bestTotal) < 0) {
                bestTotal = total;
                bestOrder = candidate;
                bestFloor = floor;
            }
        }
        if (bestOrder == null) {
            // no rotation completed within the cap: keep the given order and
            // its best-effort floor (the fold's closure gate adjudicates)
            Map<IAEItemStack, BigInteger> floor = new HashMap<>();
            probeFiringOrder(orderBase, new HashMap<>(remaining0), insOf,
                    perCraftOutputs, netDrawOf, outputKey, members, floor);
            return new FloorPlan(floor, orderBase);
        }
        return new FloorPlan(bestFloor, bestOrder);
    }

    /**
     * One priority-scheduled probe of a firing order. Returns TRUE when every
     * pattern fired its full (capped) count — the order runs the plan; FALSE
     * when the pass bound was hit with crafts left (a live- or deadlock this
     * order cannot escape; the caller rejects the order). Forced deficits
     * accumulate into {@code floor} (additive priming on top of the net draw).
     */
    private static boolean probeFiringOrder(
            List<ICraftingPatternDetails> order,
            Map<ICraftingPatternDetails, BigInteger> remaining,
            Map<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> insOf,
            Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> perCraftOutputs,
            Function<IAEItemStack, BigInteger> netDrawOf,
            IAEItemStack outputKey,
            Set<IAEItemStack> members,
            Map<IAEItemStack, BigInteger> floor) {
        Map<IAEItemStack, BigInteger> ledger = new HashMap<>();
        // passes: bounded by total capped crafts (each pass fires >= 1 craft
        // or forces); plenty for the feedback loops to flow
        BigInteger totalCrafts = remaining.values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
        for (BigInteger pass = BigInteger.ZERO;
                remaining.values().stream().anyMatch(v -> v.signum() > 0)
                        && pass.compareTo(totalCrafts.add(BigInteger.valueOf(order.size() * 2L))) < 0;
                pass = pass.add(BigInteger.ONE)) {
            boolean fired = false;
            Map<IAEItemStack, BigInteger> refill = new HashMap<>();
            for (ICraftingPatternDetails d : order) {
                BigInteger left = remaining.get(d);
                while (left.signum() > 0 && covered(insOf.get(d), ledger, netDrawOf, members)) {
                    fire(d, insOf, perCraftOutputs, ledger, refill, netDrawOf,
                            outputKey, members, floor, false);
                    left = left.subtract(BigInteger.ONE);
                    fired = true;
                }
                remaining.put(d, left);
            }
            if (!fired) {
                // true deadlock: force the first stuck pattern one craft
                for (ICraftingPatternDetails d : order) {
                    if (remaining.get(d).signum() > 0) {
                        fire(d, insOf, perCraftOutputs, ledger, refill, netDrawOf,
                                outputKey, members, floor, true);
                        remaining.put(d, remaining.get(d).subtract(BigInteger.ONE));
                        break;
                    }
                }
            }
            for (var e : refill.entrySet()) {
                ledger.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
        }
        for (BigInteger v : remaining.values()) {
            if (v.signum() > 0) return false;
        }
        return true;
    }

    /** True when every ring-member input is covered by ledger or net draw. */
    private static boolean covered(Map<IAEItemStack, BigInteger> inputs,
                                   Map<IAEItemStack, BigInteger> ledger,
                                   Function<IAEItemStack, BigInteger> netDrawOf,
                                   Set<IAEItemStack> members) {
        for (var e : inputs.entrySet()) {
            if (!isRingMember(members, e.getKey())) continue;
            BigInteger avail = ledger.getOrDefault(e.getKey(), netDrawOf.apply(e.getKey()));
            if (e.getValue().compareTo(avail) > 0) return false;
        }
        return true;
    }

    /**
     * Fires one probe craft: deducts member inputs from the ledger (a
     * {@code force} records uncovered amounts as floor and tops the ledger
     * up), and queues outputs into {@code refill} — the caller merges it into
     * the ledger at pass end, mirroring returns arriving a tick later. The
     * delivered root key is skipped: its production leaves the CPU for good.
     */
    private static void fire(ICraftingPatternDetails d,
                             Map<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> insOf,
                             Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> perCraftOutputs,
                             Map<IAEItemStack, BigInteger> ledger,
                             Map<IAEItemStack, BigInteger> refill,
                             Function<IAEItemStack, BigInteger> netDrawOf,
                             IAEItemStack outputKey,
                             Set<IAEItemStack> members,
                             Map<IAEItemStack, BigInteger> floor,
                             boolean force) {
        for (var e : insOf.get(d).entrySet()) {
            if (!isRingMember(members, e.getKey())) continue;
            BigInteger avail = ledger.getOrDefault(e.getKey(), netDrawOf.apply(e.getKey()));
            if (e.getValue().compareTo(avail) > 0) {
                if (force) {
                    BigInteger deficit = e.getValue().subtract(avail);
                    BigInteger prev = floor.getOrDefault(e.getKey(), BigInteger.ZERO);
                    if (deficit.compareTo(prev) > 0) floor.put(e.getKey(), deficit);
                    ledger.put(e.getKey(), e.getValue());
                }
            } else {
                ledger.put(e.getKey(), avail);
            }
            ledger.put(e.getKey(), ledger.get(e.getKey()).subtract(e.getValue()));
        }
        Map<IAEItemStack, BigInteger> outs = perCraftOutputs.apply(d);
        if (outs != null) {
            for (var e : outs.entrySet()) {
                if (outputKey != null && e.getKey().isSameType(outputKey)) continue;
                refill.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
        }
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
                // The delivery must be CRAFTED: a real job ignores the
                // requested item's own stock (AE2UEL CraftingJob.run plans
                // against an inventory with the output ignored), so the root
                // key's stock may not cover its rootDeliver component — only
                // its in-ring consumption is stock-sparable (billed as
                // job-start withdrawal).
                boolean rootStockExcluded = useStock && rootIsMember && k.isSameType(rootKey);
                if (useStock && !rootStockExcluded) cov = cov.add(nonNeg(startStockOf.apply(k)));
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
            frames.push(new Object[]{root, deps.getOrDefault(root, Collections.<IAEItemStack>emptySet()).iterator()});
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.add(root);
            onStack.put(root, true);
            while (!frames.isEmpty()) {
                Object[] frame = frames.peek();
                IAEItemStack v = (IAEItemStack) frame[0];
                @SuppressWarnings("unchecked")
                Iterator<IAEItemStack> it = (Iterator<IAEItemStack>) frame[1];
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
                        frames.push(new Object[]{w, deps.getOrDefault(w, Collections.<IAEItemStack>emptySet()).iterator()});
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
