package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * whose net draw exceeds its stock bumps its producer by
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
 * <p>All arithmetic is BigInteger; all maps keyed by type-normalized copies
 * ({@code copy/reset/size 1}) in deterministic insertion order, mirroring
 * {@link VMCounter}. The offline ground-truth generator
 * ({@code local/tools/closure-baseline.py}) implements exactly this loop and
 * the fixture tests replay both live-web baselines bit-for-bit against it.
 */
public final class PlanClosure {

    /** Iteration bound — A1ywmJB converges in 29 rounds, D65dvbu in 20. */
    public static final int MAX_ROUNDS = 64;

    /** Net-loss divergence escape: far above any honest craft count. */
    public static final BigInteger CRAFT_CAP = BigInteger.valueOf(1_000_000_000_000L);

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
    }

    /** The closure's plan triple plus its convergence record. */
    public static final class Result {
        /** The schedule: pattern → crafts (root-seeded insertion order). */
        public final LinkedHashMap<ICraftingPatternDetails, BigInteger> plans;
        /** Startup capital per key: min(netDraw, stock) — the job-start withdrawal. */
        public final LinkedHashMap<IAEItemStack, BigInteger> withdraw;
        /** Honest gaps: producer-less (or root) keys' uncovered net draw. */
        public final LinkedHashMap<IAEItemStack, BigInteger> missing;
        /** Full net draw per consumed key — the floor probe's netDrawOf. */
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
     * when the ledger diverged past the CAP (never adopt those plans).
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
        LinkedHashMap<IAEItemStack, BigInteger> consumed = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> produced = new LinkedHashMap<>();

        // seed: the delivery must be CRAFTED (a real job plans against an
        // inventory that ignores the requested item's own stock), so the root
        // starts at ceil(deliver / outPer) regardless of stock — from below.
        BigInteger rootOutPer = outputOf(view, rootPattern, root);
        if (rootOutPer.signum() <= 0) {
            return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), 0, false);
        }
        plans.put(rootPattern, ceilDiv(deliver, rootOutPer));

        int rounds = 0;
        boolean converged = false;
        while (rounds < MAX_ROUNDS) {
            rounds++;
            ledger(plans, view, consumed, produced);
            boolean changed = false;
            for (Map.Entry<IAEItemStack, BigInteger> e : consumed.entrySet()) {
                IAEItemStack k = e.getKey();
                BigInteger refill = k.isSameType(root)
                        ? ZERO : produced.getOrDefault(k, ZERO);
                BigInteger net = e.getValue().subtract(refill);
                if (net.signum() <= 0) {
                    continue;
                }
                BigInteger gap = net.subtract(nonNeg(view.stockOf(k)));
                if (gap.signum() <= 0) {
                    continue;
                }
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
                BigInteger next = plans.getOrDefault(producer, ZERO).add(ceilDiv(gap, outPer));
                if (next.compareTo(CRAFT_CAP) > 0) {
                    return new Result(plans, new LinkedHashMap<>(), new LinkedHashMap<>(),
                            new LinkedHashMap<>(), new LinkedHashMap<>(), rounds, false);
                }
                plans.put(producer, next);
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

        // fixpoint: W = min(netDraw, stock); gaps of non-producible keys are
        // the missing disclosure; production beyond consumption is emitable.
        LinkedHashMap<IAEItemStack, BigInteger> withdraw = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> missing = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> netDrawMap = new LinkedHashMap<>();
        LinkedHashMap<IAEItemStack, BigInteger> surplus = new LinkedHashMap<>();
        for (Map.Entry<IAEItemStack, BigInteger> e : consumed.entrySet()) {
            IAEItemStack k = e.getKey();
            BigInteger refill = k.isSameType(root)
                    ? ZERO : produced.getOrDefault(k, ZERO);
            BigInteger net = e.getValue().subtract(refill);
            if (net.signum() > 0) {
                netDrawMap.put(k, net);
                BigInteger stock = nonNeg(view.stockOf(k));
                withdraw.put(k, net.min(stock));
                if (net.compareTo(stock) > 0) {
                    ICraftingPatternDetails producer = view.producerOf(k);
                    boolean producible = producer != null && !k.isSameType(root)
                            && outputOf(view, producer, k).signum() > 0;
                    if (!producible) {
                        missing.put(k, net.subtract(stock));
                    }
                }
            }
        }
        for (Map.Entry<IAEItemStack, BigInteger> e : produced.entrySet()) {
            BigInteger net = e.getValue().subtract(consumed.getOrDefault(e.getKey(), ZERO));
            if (net.signum() > 0 && !e.getKey().isSameType(root)) {
                surplus.put(e.getKey(), net);
            }
        }
        return new Result(plans, withdraw, missing, netDrawMap, surplus, rounds, true);
    }

    // ------------------------------------------------------------------

    /** One full ledger pass over the current plans. */
    private static void ledger(LinkedHashMap<ICraftingPatternDetails, BigInteger> plans,
                               View view,
                               LinkedHashMap<IAEItemStack, BigInteger> consumed,
                               LinkedHashMap<IAEItemStack, BigInteger> produced) {
        consumed.clear();
        produced.clear();
        for (Map.Entry<ICraftingPatternDetails, BigInteger> e : plans.entrySet()) {
            BigInteger t = e.getValue();
            if (t == null || t.signum() <= 0) {
                continue;
            }
            Map<IAEItemStack, BigInteger> ins = view.inputsOf(e.getKey());
            if (ins != null) {
                for (Map.Entry<IAEItemStack, BigInteger> ie : ins.entrySet()) {
                    if (ie.getKey() == null || ie.getValue() == null
                            || ie.getValue().signum() <= 0) {
                        continue;
                    }
                    merge(consumed, ie.getKey(), t.multiply(ie.getValue()));
                }
            }
            Map<IAEItemStack, BigInteger> outs = view.outputsOf(e.getKey());
            if (outs != null) {
                for (Map.Entry<IAEItemStack, BigInteger> oe : outs.entrySet()) {
                    if (oe.getKey() == null || oe.getValue() == null
                            || oe.getValue().signum() <= 0) {
                        continue;
                    }
                    merge(produced, oe.getKey(), t.multiply(oe.getValue()));
                }
            }
        }
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

    private static void merge(LinkedHashMap<IAEItemStack, BigInteger> map,
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
