package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2vm.compat.AE2FCCompat;
import com.ae2vm.compat.PatternCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan invariants — cheap, always-on assertions over
 * every produced plan. Each rule is shaped by a real production bug:
 *
 * - OUTPUT-MISMATCH : plan delivers something other than the request
 * - DELIVER-MISMATCH: delivered amount differs from the ordered amount
 * - EMITABLE-COVER  : emitable surplus exceeding scheduled production
 *                     (the live gross-emitable CPU-stall bug)
 * - LEDGER-KEY      : missing entries referencing nothing in the ledger
 * - MISSING-COVERAGE: a net-negative ledger key absent from missing
 *                     (the live incomplete-missing-list bug)
 * - INPUT-REACH     : a scheduled pattern whose input has no stock, no
 *                     producer and no missing entry (silent stall shape)
 *
 * Fuzzy note: NBT-variant substitution moves demand between family
 * members, so rules that inspect per-key ledgers DEFER when the key
 * carries NBT (family-covered) — flagged as conservative; the strict
 * checks (deliver/output/emitable) are family-independent.
 */
public final class PlanInvariants {

    private PlanInvariants() {
    }

    /** @return human-readable violation ids; empty list = the plan is sound. */
    public static List<String> check(VMPlan plan, IAEItemStack what, long amount,
                                     IItemList<IAEItemStack> stock) {
        List<String> out = new ArrayList<>();
        if (plan == null || what == null) {
            return out;
        }

        // 1. the plan's output must be the requested key
        if (plan.getOutputKey() == null || !plan.getOutputKey().isSameType(what)) {
            out.add("OUTPUT-MISMATCH");
        }

        // 2. the delivered amount must be the ordered amount
        if (plan.getDeliverAmount() != amount) {
            out.add("DELIVER-MISMATCH:" + plan.getDeliverAmount() + "!=" + amount);
        }

        // per-key ledger over the scheduled patterns
        Ledger ledger = new Ledger();
        for (Map.Entry<ICraftingPatternDetails, Long> e : plan.getPatternTimes().entrySet()) {
            long times = e.getValue() == null ? 0L : e.getValue();
            if (times <= 0L || e.getKey() == null) {
                continue;
            }
            IAEItemStack[] inputs = e.getKey().getCondensedInputs();
            IAEItemStack[] outputs = safeOutputs(e.getKey());
            if (inputs != null) {
                for (IAEItemStack i : inputs) {
                    if (i != null) {
                        ledger.consume(i, satMul(times, i.getStackSize()));
                    }
                }
            }
            if (outputs != null) {
                for (IAEItemStack o : outputs) {
                    if (o != null) {
                        ledger.produce(o, satMul(times, o.getStackSize()));
                    }
                }
            }
        }

        // 3. emitable surplus must be covered by scheduled production
        for (Map.Entry<IAEItemStack, Long> e : plan.getEmittedItems().entrySet()) {
            long produced = ledger.producedOf(e.getKey());
            if (e.getValue() != null && e.getValue() > produced) {
                out.add("EMITABLE-COVER:" + token(e.getKey())
                        + " emitted=" + e.getValue() + " produced=" + produced);
            }
        }

        // net position per ledger key: stock + produced - consumed
        for (IAEItemStack k : ledger.keys()) {
            long net = stockOf(stock, k) + ledger.producedOf(k) - ledger.consumedOf(k);
            if (net < 0) {
                if (!missingCovers(plan, k) && !familyCovers(stock, k, -net)) {
                    out.add("MISSING-COVERAGE:" + token(k)
                            + " net=" + net);
                }
            }
        }

        // 4. scheduled inputs must be reachable: stock, a producer, or missing
        for (Map.Entry<ICraftingPatternDetails, Long> e : plan.getPatternTimes().entrySet()) {
            IAEItemStack[] inputs = e.getKey() == null ? null : e.getKey().getCondensedInputs();
            if (inputs == null) {
                continue;
            }
            for (IAEItemStack i : inputs) {
                if (i == null) {
                    continue;
                }
                long net = stockOf(stock, i) + ledger.producedOf(i) - ledger.consumedOf(i);
                if (net < 0 && !missingCovers(plan, i) && !familyCovers(stock, i, -net)) {
                    out.add("INPUT-REACH:" + token(i));
                }
            }
        }

        return out;
    }

    /**
     * True when the key's SAME-ITEM family stock covers the gap — the plan's
     * family allocation legitimately withdraws a sibling variant (1.12 damage
     * axes, NBT variants) and the CPU's extraction consumes it for the slot.
     * The old NBT-only deferral (hasTagCompound) was blind to damage-variant
     * processing inputs (minecraft:log@3 backed by log@0 stock) and cried
     * wolf on the closure's correct plans.
     */
    private static boolean familyCovers(IItemList<IAEItemStack> stock, IAEItemStack k,
                                        long gap) {
        if (stock == null || gap <= 0) {
            return false;
        }
        try {
            // SIBLING variants only (same Item, other damage/NBT): the exact
            // key's stock is already inside `net` — counting it here again
            // would absolve real shortfalls. Iteration instead of findFuzzy:
            // the audit's stock views are plain lists whose findFuzzy is not
            // guaranteed to be a working fuzzy search.
            long family = 0;
            for (IAEItemStack v : stock) {
                if (v == null || v.isSameType(k)) {
                    continue;
                }
                if (v.getItem() != k.getItem()) {
                    continue;
                }
                boolean fluid;
                try {
                    fluid = AE2FCCompat.isFluidFakeItem(v);
                } catch (Throwable probeFailure) {
                    // probe failure: count the sibling, never exclude on it
                    fluid = false;
                }
                if (!fluid) {
                    family += Math.max(0L, v.getStackSize());
                }
            }
            return family >= gap;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------

    private static final class Ledger {
        final Set<IAEItemStack> keys = new LinkedHashSet<>();
        final List<IAEItemStack> producedKeys = new ArrayList<>();
        final List<Long> producedAmounts = new ArrayList<>();
        final List<IAEItemStack> consumedKeys = new ArrayList<>();
        final List<Long> consumedAmounts = new ArrayList<>();

        void produce(IAEItemStack k, long n) {
            if (n <= 0) return;
            keys.add(k);
            producedKeys.add(k);
            producedAmounts.add(n);
        }

        void consume(IAEItemStack k, long n) {
            if (n <= 0) return;
            keys.add(k);
            consumedKeys.add(k);
            consumedAmounts.add(n);
        }

        List<IAEItemStack> keys() {
            return new ArrayList<>(keys);
        }

        long producedOf(IAEItemStack k) {
            long s = 0;
            for (int i = 0; i < producedKeys.size(); i++) {
                if (producedKeys.get(i).isSameType(k)) {
                    s = satAdd(s, producedAmounts.get(i));
                }
            }
            return s;
        }

        long consumedOf(IAEItemStack k) {
            long s = 0;
            for (int i = 0; i < consumedKeys.size(); i++) {
                if (consumedKeys.get(i).isSameType(k)) {
                    s = satAdd(s, consumedAmounts.get(i));
                }
            }
            return s;
        }
    }

    private static boolean missingCovers(VMPlan plan, IAEItemStack k) {
        for (Map.Entry<IAEItemStack, Long> e : plan.getMissingItems().entrySet()) {
            if (e.getKey() != null && e.getValue() != null
                    && e.getValue() > 0 && e.getKey().isSameType(k)) {
                return true;
            }
        }
        return false;
    }

    private static long stockOf(IItemList<IAEItemStack> stock, IAEItemStack k) {
        try {
            IAEItemStack probe = k.copy();
            probe.setStackSize(0L);
            IAEItemStack stored = stock.findPrecise(probe);
            return stored == null ? 0L : Math.max(0L, stored.getStackSize());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static IAEItemStack[] safeOutputs(ICraftingPatternDetails d) {
        try {
            IAEItemStack[] out = d.getCondensedOutputs();
            if (out != null) {
                return out;
            }
            IAEItemStack primary = PatternCompat.getPrimaryOutput(d);
            return primary == null ? new IAEItemStack[0] : new IAEItemStack[]{primary};
        } catch (Throwable t) {
            return new IAEItemStack[0];
        }
    }

    private static String token(IAEItemStack k) {
        // stack toString carries the identity in both the real implementation
        // and the bench fake — never the exception-style definition rendering
        try {
            return String.valueOf(k);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static long satMul(long a, long b) {
        if (a <= 0 || b <= 0) return 0L;
        long r = a * b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    private static long satAdd(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }
}
