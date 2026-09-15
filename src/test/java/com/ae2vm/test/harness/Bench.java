package com.ae2vm.test.harness;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchAEItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared VM test harness: register patterns by primary output, run requests. */
public final class Bench {
    /** The reference scenarios' "unbounded" stock amount. */
    public static final long UNBOUNDED_STOCK = 1_000_000_000_000L;

    public static final Map<IAEItemStack, ICraftingPatternDetails> PATTERNS = new LinkedHashMap<>();

    private Bench() {
    }

    public static void reset() {
        PATTERNS.clear();
        PatternCompiler.clearCache();
    }

    public static void register(BenchPatternDetails pattern) {
        PATTERNS.put(pattern.getOutputs()[0], pattern);
    }

    public static VMPlan run(BenchPatternDetails root, long amount, BenchSimulationState sim) {
        return run(root, amount, null, sim);
    }

    /**
     * Like {@link #run(BenchPatternDetails, long, BenchSimulationState)} but rooted at
     * {@code requestedKey} (e.g. a byproduct output of {@code root}), mirroring AE2's
     * every-output pattern index. Null keeps the primary-rooted request.
     */
    public static VMPlan run(BenchPatternDetails root, long amount, String requestedKey,
                             BenchSimulationState sim) {
        for (ICraftingPatternDetails p : PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode request = PatternCompiler.compileRequest(root, amount,
                requestedKey == null ? null : k(requestedKey));
        CraftingVM vm = new CraftingVM("bench", PATTERNS::get);
        return vm.execute(request, sim);
    }

    public static BenchAEItemStack k(String id) {
        return new BenchAEItemStack(id, 1);
    }

    /** Pattern with one primary output and {id, amount} input pairs. */
    public static BenchPatternDetails pat(String out, long outAmt, Object... inPairs) {
        return patEx(new String[]{out}, new long[]{outAmt}, inPairs);
    }

    /** Pattern with byproducts: {primary, byproducts...} outputs, {id, amount} input pairs. */
    public static BenchPatternDetails patEx(String[] outs, long[] outAmts, Object... inPairs) {
        IAEItemStack[] in = new IAEItemStack[inPairs.length / 2];
        for (int i = 0; i < inPairs.length; i += 2) {
            String id = (String) inPairs[i];
            long amt = ((Number) inPairs[i + 1]).longValue();
            in[i / 2] = new BenchAEItemStack(id, amt).setStackSize(amt);
        }
        IAEItemStack[] out = new IAEItemStack[outs.length];
        for (int i = 0; i < outs.length; i++) {
            out[i] = new BenchAEItemStack(outs[i], outAmts[i]).setStackSize(outAmts[i]);
        }
        return BenchPatternDetails.custom(in, out);
    }

    /** Reference-scenario feasibility: a concrete plan with nothing missing. */
    public static boolean feasible(VMPlan plan) {
        return !plan.isSimulation() && plan.getMissingItems().isEmpty();
    }

    /**
     * Reference-scenario infeasibility check: the plan must report missing, its
     * domain must be within the baseline's keys, and it must report AT LEAST the
     * baseline amount of each baseline key.
     */
    public static boolean infeasibleMatches(VMPlan plan, Map<String, Long> baseline) {
        if (!plan.isSimulation()) {
            return false;
        }
        for (IAEItemStack key : plan.getMissingItems().keys()) {
            if (!baseline.containsKey(((BenchAEItemStack) key).id)) {
                return false;
            }
        }
        for (Map.Entry<String, Long> e : baseline.entrySet()) {
            if (plan.getMissingItems().get(k(e.getKey())) < e.getValue()) {
                return false;
            }
        }
        return true;
    }
}
