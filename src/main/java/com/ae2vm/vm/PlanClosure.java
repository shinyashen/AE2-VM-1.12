package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Plan closure — the global-ledger fixpoint (CLOSURE-DESIGN.md §2). One
 * authority for job coverage: every scheduled pattern contributes its
 * per-craft inputs to {@code consumed} and outputs to {@code produced}; what
 * the network must supply is derived, not bookkept by pipeline stages.
 *
 * <p>Per key K over the final schedule:
 *
 * <pre>
 * refill(K)  = produced(K)          (any key: task returns re-enter the CPU)
 *            = 0                    (the delivery root: :265 siphon — its
 *                                    production is delivered, never returns)
 * netDraw(K) = max(0, consumed(K) − refill(K))
 * </pre>
 *
 * <p>The loop seeds {@code plans} from BELOW — the root pattern only, at
 * {@code ceil(deliver / outPer)} — and rounds over the whole ledger: a key
 * whose net draw exceeds its cover bumps its producer by
 * {@code ceil(gap / outPer)} (the new consumption joins the next round), a
 * producer-less key's gap is honest missing. Counts only ever grow, so the
 * exact convergence test ("no bump this round") is decidable — the ceil-jitter
 * failure of the reverted solve↔expand loop required counts to move both ways,
 * which the closure never does. Unbounded growth (a net-losing cycle) hits
 * {@link #CRAFT_CAP} and the result reports divergence; the caller falls back
 * to the legacy pipeline. The root key's gap is production-invariant — its
 * production siphons — so it discloses as missing directly instead of
 * diverging on dead bumps.
 *
 * <p><b>Family allocation (the propagation's stock-aware semantics, kept).</b>
 * Net draw is covered from stock in the same order the legacy aggregation
 * draws: the key's own stock first; then, for a processing input, its
 * same-item damage-equal NBT variants; then, for the FUZZY share of the draw
 * (demand from replacement-enabled slots — a compile-time registered
 * substitute group), the group members. What stock covers is booked on the
 * ACTUAL key (the CPU withdraws exactly that); what a family member covers is
 * consumption of that member (its own net draw grows — a scarce substitute is
 * shared, consumed once, and its own producer bumped or its gap disclosed).
 * Only the UNCOVERED remainder drives the producer bump. With no registered
 * groups and no NBT family the allocation degenerates to the pure exact-key
 * ledger the two live-web baselines pin.
 *
 * <p>All arithmetic is BigInteger; all maps keyed by type-normalized copies
 * ({@code copy/reset/size 1}) in deterministic insertion order, mirroring
 * {@link VMCounter}. The offline ground-truth generator
 * ({@code local/tools/closure-baseline.py}) implements the exact-key core and
 * the fixture tests replay both live-web baselines bit-for-bit against it.
 */
public final class PlanClosure {

    /** Iteration bound — A1ywmJB converges in 29 rounds, D65dvbu in 20. */
    public static final int MAX_ROUNDS = 64;

    /** Net-loss divergence escape: far above any honest craft count. */
    public static final BigInteger CRAFT_CAP = BigInteger.valueOf(1_000_000_000_000L);

    /**
     * Family-allocation work bound: each processing step is one undo/redo of
     * one key's draws; every step either settles a key whose input grew or
     * re-draws from a bounded pool. Far above any honest chain length.
     */
    private static final int ALLOC_STEP_CAP = 4096;

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    /** The closure's view of the recipe web; one producer per key. */
    public interface View {
        /**
         * The pattern producing {@code key} — the resolver's pick (the same
         * one the capture/propagation used), or {@code null} for a leaf.
         */
        ICraftingPatternDetails producerOf(IAEItemStack key);

        /** Per-craft typed inputs — ALL condensed lines, returned/catalyst included. */
        Map<IAEItemStack, BigInteger> inputsOf(ICraftingPatternDetails pattern);

        /** Per-craft typed outputs. */
        Map<IAEItemStack, BigInteger> outputsOf(ICraftingPatternDetails pattern);

        /** The job-start network stock of {@code key} (never negative). */
        BigInteger stockOf(IAEItemStack key);

        /**
         * Per-craft amounts on REPLACEMENT-ENABLED slots only (the compile
         * registered a substitute group for the slot's key). Default: none —
         * every slot exact.
         */
        default Map<IAEItemStack, BigInteger> fuzzyInputsOf(ICraftingPatternDetails pattern) {
            return new LinkedHashMap<>();
        }

        /**
         * Same-item damage-equal NBT variants PRESENT IN STOCK usable by a
         * processing slot (the default processing fuzzy). Default: none.
         */
        default List<IAEItemStack> nbtFamilyOf(IAEItemStack key) {
            return new ArrayList<>();
        }

        /** True when {@code key} is a processing-recipe input (default fuzzy). */
        default boolean isProcessingInput(IAEItemStack key) {
            return false;
        }

        /**
         * The registered substitute-group members of {@code key} OTHER than
         * itself (the replacement pool its fuzzy slots may draw). Default: none.
         */
        default List<IAEItemStack> substitutesOf(IAEItemStack key) {
            return new ArrayList<>();
        }
    }

    /** The closure's plan triple plus its convergence record. */
    public static final class Result {
        /** The schedule: pattern → crafts (root-seeded insertion order). */
        public final LinkedHashMap<ICraftingPatternDetails, BigInteger> plans;
        /** Startup capital per key — the stock-covered draw, on the ACTUAL keys. */
        public final LinkedHashMap<IAEItemStack, BigInteger> withdraw;
        /** Honest gaps: producer-less (or root) keys' uncovered net draw. */
        public final LinkedHashMap<IAEItemStack, BigInteger> missing;
        /** Full net draw per key — the floor probe's netDrawOf. */
        public final LinkedHashMap<IAEItemStack, BigInteger> netDraw;
        /** Emitable surplus per key: max(0, produced − consumed), root excluded. */
        public final LinkedHashMap<IAEItemStack, BigInteger> surplus;
        /** Rounds executed (including the confirming one). */
        public final int rounds;
        /** False when the CAP was hit — the plans above are NOT a plan. */
        public final boolean converged;

        Result(LinkedHashMap<ICraftingPatternDetails, BigInteger> plans,
               LinkedHashMap<IAEItemStack, BigInteger> withdraw,
               LinkedHashMap<IAEItemStack, BigInteger> missing,
               LinkedHashMap<IAEItemStack, BigInteger> netDraw,
               LinkedHashMap<IAEItemStack, BigInteger> surplus,
               int rounds, boolean converged) {
            this.plans = plans;
            this.withdraw = withdraw;
            this.missing = missing;
            this.netDraw = netDraw;
            this.surplus = surplus;
            this.rounds = rounds;
            this.converged = converged;
        }
    }

    private PlanClosure() {
    }

    /**
     * Runs the closure for one request. {@code rootPattern} must produce
     * {@code rootKey}; its per-craft output of the root key sets the seed.
     * Returns the converged triple, or a {@code converged == false} result
     * when the ledger diverged (net-loss cycle past the CAP, or the family
     * allocation failed to settle) — never adopt those plans.
     */
    public static Result close(IAEItemStack rootKey, BigInteger deliver,
                               ICraftingPatternDetails rootPattern, View view) {
        if (rootKey == null || rootPattern == null || deliver == null
                || deliver.signum() <= 0) {
            return new Result(new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), 0, false);
        }
        IAEItemStack root = norm(rootKey);
        LinkedHashMap<ICraftingPatternDetails, BigInteger> plans = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> consumedExact = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> consumedFuzzy = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> produced = new LinkedHashMap<>();

        // seed: the delivery must be CRAFTED (a real job plans against an
        // inventory that ignores the requested item's own stock), so the root
        // starts at ceil(deliver / outPer) regardless of stock — from below.
        BigInteger rootOutPer = outputOf(view, rootPattern, root);
        if (rootOutPer.signum() <= 0) {
            return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), 0, false);
        }
        // self-adjacent patterns (a catalyst or recursion amplifier re-consuming
        // its own output) have dedicated seed/amplifier semantics — the working-
        // capital machinery owns them, the plain ledger cannot count them
        if (isSelfAdjacent(view, rootPattern)) {
            return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), 0, false);
        }
        plans.put(rootPattern, ceilDiv(deliver, rootOutPer));

        int rounds = 0;
        boolean converged = false;
        while (rounds < MAX_ROUNDS) {
            rounds++;
            ledger(plans, view, consumedExact, consumedFuzzy, produced);
            Allocation alloc = allocate(view, root, consumedExact, consumedFuzzy, produced);
            if (alloc == null) {
                // the family allocation failed to settle: honest fallback
                return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                        new LinkedHashMap<>(), new LinkedHashMap<>(), rounds, false);
            }
            boolean changed = false;
            // aggregate the round's bumps PER PRODUCER PATTERN: a pattern
            // producing several deficient keys covers every output line once
            // per craft — its bump is the MAX over its keys' needs, never the
            // sum (key-level bumping double-fires byproduct producers, the
            // exact trap RingSolver's pattern-level variables avoid)
            Map<ICraftingPatternDetails, BigInteger> bumps = new LinkedHashMap<>();
            Set<ICraftingPatternDetails> selfAdjacentProducers = new LinkedHashSet<>();
            for (var e : alloc.uncovered.entrySet()) {
                BigInteger gap = e.getValue();
                if (gap.signum() <= 0) {
                    continue;
                }
                IAEItemStack k = e.getKey();
                // the root's production siphons to the delivery — its net draw
                // is production-invariant; bumping would never close the gap
                if (k.isSameType(root)) {
                    continue;
                }
                ICraftingPatternDetails producer = view.producerOf(k);
                BigInteger outPer = producer == null ? ZERO : outputOf(view, producer, k);
                if (producer == null || outPer.signum() <= 0) {
                    continue; // leaf: honest missing, pinned at the fixpoint
                }
                if (isSelfAdjacent(view, producer)) {
                    // catalyst/recursion amplifier: dedicated machinery's domain
                    selfAdjacentProducers.add(producer);
                    continue;
                }
                BigInteger need = ceilDiv(gap, outPer);
                BigInteger prev = bumps.getOrDefault(producer, ZERO);
                if (need.compareTo(prev) > 0) {
                    bumps.put(producer, need);
                }
            }
            if (!selfAdjacentProducers.isEmpty()) {
                return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                        new LinkedHashMap<>(), new LinkedHashMap<>(), rounds, false);
            }
            for (var e : bumps.entrySet()) {
                BigInteger next = plans.getOrDefault(e.getKey(), ZERO).add(e.getValue());
                if (next.compareTo(CRAFT_CAP) > 0) {
                    return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                            new LinkedHashMap<>(), new LinkedHashMap<>(), rounds, false);
                }
                plans.put(e.getKey(), next);
                changed = true;
            }
            if (!changed) {
                converged = true;
                break;
            }
        }
        if (!converged) {
            return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), rounds, false);
        }

        // fixpoint triple: the last round's ledger + allocation ARE it (no
        // bump happened) — derive the withdrawal (stock-covered draws on the
        // ACTUAL keys), the missing disclosure (root/leaf uncovered gaps) and
        // the emitable surplus.
        Allocation alloc = allocate(view, root, consumedExact, consumedFuzzy, produced);
        if (alloc == null) {
            return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), rounds, false);
        }
        LinkedHashMap<IAEItemStack, BigInteger> withdraw = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> missing = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> netDrawMap = new LinkedHashMap<>();
        for (IAEItemStack k : alloc.order) {
            BigInteger covered = alloc.coveredOnSelf.getOrDefault(k, ZERO);
            BigInteger drawn = alloc.drawn.getOrDefault(k, ZERO);
            BigInteger pending = alloc.pending.getOrDefault(k, ZERO);
            BigInteger cons = consumedExact.getOrDefault(k, ZERO)
                    .add(consumedFuzzy.getOrDefault(k, ZERO))
                    .add(pending);
            BigInteger refill = k.isSameType(root)
                    ? ZERO : produced.getOrDefault(k, ZERO);
            BigInteger net = cons.subtract(refill);
            if (net.signum() > 0) {
                netDrawMap.put(k, net);
            }
            if (covered.signum() > 0) {
                mergeInto(withdraw, k, covered);
            }
            BigInteger gap = alloc.uncovered.getOrDefault(k, ZERO);
            if (gap.signum() > 0) {
                mergeInto(missing, k, gap);
            }
        }
        for (var e : alloc.drawn.entrySet()) {
            if (e.getValue().signum() > 0) {
                mergeInto(withdraw, e.getKey(), e.getValue());
            }
        }
        LinkedHashMap<IAEItemStack, BigInteger> surplus = new LinkedHashMap<>();
        for (Map.Entry<IAEItemStack, BigInteger> e : produced.entrySet()) {
            BigInteger cons = consumedExact.getOrDefault(e.getKey(), ZERO)
                    .add(consumedFuzzy.getOrDefault(e.getKey(), ZERO))
                    .add(alloc.pending.getOrDefault(e.getKey(), ZERO));
            BigInteger net = e.getValue().subtract(cons);
            if (net.signum() > 0 && !e.getKey().isSameType(root)) {
                surplus.put(e.getKey(), net);
            }
        }
        return new Result(plans, withdraw, missing, netDrawMap, surplus, rounds, true);
    }

    // ------------------------------------------------------------------

    /**
     * One family-allocation pass: mutable stock pools, per-key draw records
     * (undoable), the pending family consumption per key, and the uncovered
     * remainders. {@code order} is the deterministic processing order.
     */
    private static final class Allocation {
        /** The delivery root (its production siphons — refill 0). */
        IAEItemStack root;
        final LinkedHashMap<IAEItemStack, BigInteger> coveredOnSelf = new LinkedHashMap<>();
        /** Family/substitute draws per ACTUAL key: key → total drawn from its pool. */
        final LinkedHashMap<IAEItemStack, BigInteger> drawn = new LinkedHashMap<>();
        /** Family draws per CONSUMER key: consumer → (family key → amount). */
        final Map<IAEItemStack, Map<IAEItemStack, BigInteger>> familyAlloc = new LinkedHashMap<>();
        /** Family draws landing on each key as pending consumption. */
        final LinkedHashMap<IAEItemStack, BigInteger> pending = new LinkedHashMap<>();
        /** Uncovered net draw per key (drives the bump scan / disclosure). */
        final LinkedHashMap<IAEItemStack, BigInteger> uncovered = new LinkedHashMap<>();
        final LinkedHashMap<IAEItemStack, Boolean> processed = new LinkedHashMap<>();
        final Set<IAEItemStack> order = new LinkedHashSet<>();
        final Map<IAEItemStack, BigInteger> pool = new LinkedHashMap<>();
        int steps;
    }

    /**
     * Covers each key's net draw from the stock pools in the legacy
     * aggregation's order (own stock → NBT family → fuzzy-substitute group)
     * and books what a family member covers as consumption of that member.
     * The delivery root's production siphons (refill 0), so its draw is
     * allocation-invariant. Processing order: insertion order of the consumed
     * keys; a key whose pending consumption grew after it was processed is
     * re-processed (its previous draws undone first). Returns null when the
     * work bound is hit (failed to settle — the caller declines).
     */
    private static Allocation allocate(View view, IAEItemStack root,
                                       LinkedHashMap<IAEItemStack, BigInteger> consumedExact,
                                       LinkedHashMap<IAEItemStack, BigInteger> consumedFuzzy,
                                       LinkedHashMap<IAEItemStack, BigInteger> produced) {
        Allocation a = new Allocation();
        a.root = root;
        Deque<IAEItemStack> queue = new ArrayDeque<>();
        for (IAEItemStack k : consumedExact.keySet()) {
            queue.add(k);
        }
        for (IAEItemStack k : consumedFuzzy.keySet()) {
            if (!queue.contains(k)) {
                queue.add(k);
            }
        }
        while (!queue.isEmpty()) {
            if (a.steps++ > ALLOC_STEP_CAP) {
                return null; // failed to settle
            }
            IAEItemStack k = queue.poll();
            if (a.processed.getOrDefault(k, false)) {
                undo(a, k);
            }
            allocateOne(view, a, k, consumedExact, consumedFuzzy, produced);
            // a family draw on an already-processed key changed its net:
            // re-queue it (its stale draws are undone and redone then)
            for (var e : a.familyAlloc.getOrDefault(norm(k),
                    new LinkedHashMap<IAEItemStack, BigInteger>()).entrySet()) {
                IAEItemStack v = e.getKey();
                if (e.getValue().signum() > 0 && !queue.contains(v)) {
                    queue.add(v);
                }
            }
        }
        return a;
    }

    /** Releases one key's draws back to the pools (before a re-processing). */
    private static void undo(Allocation a, IAEItemStack k) {
        BigInteger covered = a.coveredOnSelf.remove(k);
        if (covered != null) {
            releasePool(a, k, covered);
        }
        Map<IAEItemStack, BigInteger> fam = a.familyAlloc.remove(k);
        if (fam != null) {
            for (var e : fam.entrySet()) {
                releasePool(a, e.getKey(), e.getValue());
                BigInteger pend = a.pending.getOrDefault(e.getKey(), ZERO)
                        .subtract(e.getValue());
                if (pend.signum() > 0) {
                    a.pending.put(norm(e.getKey()), pend);
                } else {
                    a.pending.remove(norm(e.getKey()));
                }
            }
        }
        a.uncovered.remove(k);
        a.processed.put(k, false);
        a.order.remove(k);
    }

    /** One key's allocation against the pools (mirrors applyAggregation's split). */
    private static void allocateOne(View view, Allocation a, IAEItemStack k,
                                    LinkedHashMap<IAEItemStack, BigInteger> consumedExact,
                                    LinkedHashMap<IAEItemStack, BigInteger> consumedFuzzy,
                                    LinkedHashMap<IAEItemStack, BigInteger> produced) {
        a.processed.put(k, true);
        a.order.add(k);
        IAEItemStack nk = norm(k);
        boolean root = a.root != null && k.isSameType(a.root);
        BigInteger refill = root ? ZERO : produced.getOrDefault(k, ZERO);
        // draws OTHERS already took from this key's pool are its stock-covered
        // consumption — they must not draw from the pool a second time
        BigInteger pendingSelf = a.pending.getOrDefault(k, ZERO);
        BigInteger cons = consumedExact.getOrDefault(k, ZERO)
                .add(consumedFuzzy.getOrDefault(k, ZERO))
                .add(pendingSelf);
        BigInteger net = cons.subtract(refill);
        if (net.signum() <= 0) {
            a.uncovered.put(nk, ZERO);
            return;
        }
        BigInteger alreadyCovered = pendingSelf.min(net);
        net = net.subtract(alreadyCovered);
        if (net.signum() <= 0) {
            a.uncovered.put(nk, ZERO);
            return;
        }
        BigInteger fuzzyShare = consumedFuzzy.getOrDefault(k, ZERO).min(net);
        BigInteger exactNeed = net.subtract(fuzzyShare);

        // exact share: own stock, then (processing inputs) the NBT family
        BigInteger own = poolOf(view, a, k);
        BigInteger take = own.min(exactNeed);
        if (take.signum() > 0) {
            a.pool.put(nk, own.subtract(take));
            a.coveredOnSelf.put(nk, a.coveredOnSelf.getOrDefault(nk, ZERO).add(take));
        }
        BigInteger uncovered = exactNeed.subtract(take);
        if (uncovered.signum() > 0 && view.isProcessingInput(k)) {
            uncovered = uncovered.subtract(drawFamily(view, a, k,
                    view.nbtFamilyOf(k), uncovered));
        }

        // fuzzy share: own remaining stock, NBT family, then the substitute group
        if (fuzzyShare.signum() > 0) {
            own = poolOf(view, a, k);
            take = own.min(fuzzyShare);
            if (take.signum() > 0) {
                a.pool.put(nk, own.subtract(take));
                a.coveredOnSelf.put(nk, a.coveredOnSelf.getOrDefault(nk, ZERO).add(take));
            }
            BigInteger remFuzzy = fuzzyShare.subtract(take);
            if (remFuzzy.signum() > 0 && view.isProcessingInput(k)) {
                remFuzzy = remFuzzy.subtract(drawFamily(view, a, k,
                        view.nbtFamilyOf(k), remFuzzy));
            }
            if (remFuzzy.signum() > 0) {
                remFuzzy = remFuzzy.subtract(drawFamily(view, a, k,
                        view.substitutesOf(k), remFuzzy));
            }
            uncovered = uncovered.add(remFuzzy);
        }
        a.uncovered.put(nk, uncovered);
    }

    /**
     * Draws up to {@code need} from {@code family}'s stock pools, books the
     * draws (on the actual keys for the withdrawal, as pending consumption on
     * those keys for the ledger), and returns the total drawn.
     */
    private static BigInteger drawFamily(View view, Allocation a, IAEItemStack consumer,
                                         List<IAEItemStack> family, BigInteger need) {
        BigInteger drawn = ZERO;
        for (IAEItemStack v : family) {
            if (v == null || v.isSameType(consumer) || need.signum() <= 0) {
                continue;
            }
            BigInteger avail = poolOf(view, a, v);
            if (avail.signum() <= 0) {
                continue;
            }
            BigInteger take = avail.min(need);
            a.pool.put(norm(v), avail.subtract(take));
            a.drawn.put(norm(v), a.drawn.getOrDefault(norm(v), ZERO).add(take));
            a.pending.merge(norm(v), take, BigInteger::add);
            a.familyAlloc.computeIfAbsent(norm(consumer), x -> new LinkedHashMap<>())
                    .merge(norm(v), take, BigInteger::add);
            drawn = drawn.add(take);
            need = need.subtract(take);
        }
        return drawn;
    }

    /** The (mutable) stock pool of {@code k}, initialized from the view once. */
    private static BigInteger poolOf(View view, Allocation a, IAEItemStack k) {
        IAEItemStack nk = norm(k);
        BigInteger v = a.pool.get(nk);
        if (v == null) {
            v = nonNeg(view.stockOf(k));
            a.pool.put(nk, v);
        }
        return v;
    }

    /** Returns drawn units of {@code k}'s pool to the pool (an undo). */
    private static void releasePool(Allocation a, IAEItemStack k, BigInteger amount) {
        IAEItemStack nk = norm(k);
        BigInteger v = a.pool.get(nk);
        a.pool.put(nk, v == null ? amount : v.add(amount));
        BigInteger drawn = a.drawn.get(nk);
        if (drawn != null) {
            BigInteger rem = drawn.subtract(amount);
            if (rem.signum() > 0) {
                a.drawn.put(nk, rem);
            } else {
                a.drawn.remove(nk);
            }
        }
    }

    /**
     * True when {@code pattern} re-consumes its own output (the catalyst /
     * recursion-amplifier family — owned by the working-capital machinery,
     * never counted by the plain ledger).
     */
    private static boolean isSelfAdjacent(View view, ICraftingPatternDetails pattern) {
        Map<IAEItemStack, BigInteger> ins = view.inputsOf(pattern);
        Map<IAEItemStack, BigInteger> outs = view.outputsOf(pattern);
        if (ins == null || outs == null) {
            return false;
        }
        for (IAEItemStack i : ins.keySet()) {
            for (IAEItemStack o : outs.keySet()) {
                if (i.isSameType(o)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One full ledger pass over the current plans (slot-aware consumption). */
    private static void ledger(LinkedHashMap<ICraftingPatternDetails, BigInteger> plans,
                               View view,
                               LinkedHashMap<IAEItemStack, BigInteger> consumedExact,
                               LinkedHashMap<IAEItemStack, BigInteger> consumedFuzzy,
                               LinkedHashMap<IAEItemStack, BigInteger> produced) {
        consumedExact.clear();
        consumedFuzzy.clear();
        produced.clear();
        for (Map.Entry<ICraftingPatternDetails, BigInteger> e : plans.entrySet()) {
            BigInteger t = e.getValue();
            if (t == null || t.signum() <= 0) {
                continue;
            }
            Map<IAEItemStack, BigInteger> ins = view.inputsOf(e.getKey());
            if (ins != null) {
                Map<IAEItemStack, BigInteger> fuzzy = view.fuzzyInputsOf(e.getKey());
                for (Map.Entry<IAEItemStack, BigInteger> ie : ins.entrySet()) {
                    if (ie.getKey() == null || ie.getValue() == null
                            || ie.getValue().signum() <= 0) {
                        continue;
                    }
                    BigInteger amount = t.multiply(ie.getValue());
                    BigInteger fuzzyPer = fuzzy.getOrDefault(ie.getKey(), ZERO).multiply(t);
                    if (fuzzyPer.signum() > 0) {
                        // a key may sit on both an exact and a fuzzy slot of the
                        // same pattern: only the fuzzy slots' share may draw the group
                        BigInteger f = fuzzyPer.min(amount);
                        // the slot accepts any group member: when the key itself
                        // has no producer but a MEMBER does, the planner closes
                        // the demand by crafting the member (the CPU's craftable
                        // slot-fill consumes it) — book the demand there
                        IAEItemStack target = fuzzyTarget(view, ie.getKey());
                        if (target != null) {
                            mergeInto(consumedFuzzy, target, f);
                        } else {
                            mergeInto(consumedFuzzy, ie.getKey(), f);
                        }
                        if (f.compareTo(amount) < 0) {
                            mergeInto(consumedExact, ie.getKey(), amount.subtract(f));
                        }
                    } else {
                        mergeInto(consumedExact, ie.getKey(), amount);
                    }
                }
            }
            Map<IAEItemStack, BigInteger> outs = view.outputsOf(e.getKey());
            if (outs != null) {
                for (Map.Entry<IAEItemStack, BigInteger> oe : outs.entrySet()) {
                    if (oe.getKey() == null || oe.getValue() == null
                            || oe.getValue().signum() <= 0) {
                        continue;
                    }
                    mergeInto(produced, oe.getKey(), t.multiply(oe.getValue()));
                }
            }
        }
    }

    /**
     * The member a fuzzy slot's demand is bookable on: the key itself when
     * its producer crafts it, else the first group member with a producer
     * (the planner crafts the member; the CPU's slot-fill consumes it).
     * Null when neither holds — the demand stays on the key.
     */
    private static IAEItemStack fuzzyTarget(View view, IAEItemStack key) {
        ICraftingPatternDetails p = view.producerOf(key);
        if (p != null && outputOf(view, p, key).signum() > 0) {
            return null; // the key's own producer closes it — no transfer
        }
        for (IAEItemStack m : view.substitutesOf(key)) {
            if (m == null || m.isSameType(key)) {
                continue;
            }
            ICraftingPatternDetails mp = view.producerOf(m);
            if (mp != null && outputOf(view, mp, m).signum() > 0) {
                return norm(m);
            }
        }
        return null;
    }

    /** The pattern's per-craft output of {@code key} (zero when absent). */
    private static BigInteger outputOf(View view, ICraftingPatternDetails pattern,
                                       IAEItemStack key) {
        Map<IAEItemStack, BigInteger> outs = view.outputsOf(pattern);
        if (outs == null) {
            return ZERO;
        }
        for (Map.Entry<IAEItemStack, BigInteger> e : outs.entrySet()) {
            if (e.getKey() != null && e.getKey().isSameType(key)
                    && e.getValue() != null && e.getValue().signum() > 0) {
                return e.getValue();
            }
        }
        return ZERO;
    }

    private static void mergeInto(LinkedHashMap<IAEItemStack, BigInteger> map,
                                  IAEItemStack key, BigInteger amount) {
        if (amount.signum() == 0) {
            return;
        }
        IAEItemStack k = norm(key);
        map.merge(k, amount, BigInteger::add);
    }

    /** Type-normalized map key: same identity, size-independent. */
    private static IAEItemStack norm(IAEItemStack key) {
        IAEItemStack copy = key.copy();
        copy.reset();
        copy.setStackSize(1);
        return copy;
    }

    private static BigInteger nonNeg(BigInteger v) {
        return v == null || v.signum() < 0 ? ZERO : v;
    }

    private static BigInteger ceilDiv(BigInteger a, BigInteger b) {
        return a.add(b).subtract(ONE).divide(b);
    }
}
