package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The startup priming-floor probe (the KEPT timing authority,
 * CLOSURE-DESIGN.md §2.3): given the plan closure's schedule, it derives the
 * minimal per-key startup inventory that keeps the plan deadlock-free on a
 * real CPU (closed local inventory; task returns are the only refill) and the
 * FIRING ORDER that inventory is valid for. The coverage ledger itself lives
 * in {@link PlanClosure}; the Jacobian ring solver this class once housed was
 * absorbed by the closure and deleted.
 */
final class RingSolver {

    /**
     * The floor probe's schedule input: the patterns (and their craft counts)
     * whose member keys the probe fires in task order. Since the plan closure
     * took over coverage, this carries the CLOSURE's schedule.
     */
    static final class RingPlan {
        /** Pattern → craft count. */
        final Map<ICraftingPatternDetails, BigInteger> patterns = new LinkedHashMap<>();
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
                    // Never INFLATE: counts above the real plan make the probe
                    // demand more than the real withdrawal covers, and every
                    // fire past the draw is FORCED — phantom floors the real
                    // plan never needs (the live 1000-spirit order: the
                    // root-siphoned spirit draw never refills, the inflated
                    // remainder billed +4 spirits +1 seed over the true net
                    // draw, and the doubled seed bill exceeded the 1-unit
                    // stock — a false infeasible). Real counts keep the
                    // probe's economy exact; larger plans still deflate.
                    remaining0.put(e.getKey(), scaled.min(e.getValue()).max(BigInteger.ONE));
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
        // the job-start withdrawal IS the CPU's starting inventory: seed every
        // member key's net draw up front. (The old getOrDefault-when-absent
        // fallback lost it once a refill put the key in the ledger at zero —
        // a pass-2+ reburn then phantom-FORCED fires and billed a floor the
        // real plan never needs.)
        for (IAEItemStack m : members) {
            BigInteger nd = netDrawOf.apply(m);
            if (nd != null && nd.signum() > 0) {
                ledger.put(m, nd);
            }
        }
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

    /** True when {@code key} is one of the closure plan's member keys. */
    private static boolean isRingMember(Set<IAEItemStack> members, IAEItemStack key) {
        for (IAEItemStack m : members) {
            if (m.isSameType(key)) return true;
        }
        return false;
    }
}
