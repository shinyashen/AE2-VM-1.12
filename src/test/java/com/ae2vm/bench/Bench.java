package com.ae2vm.bench;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared VM test harness: register patterns by primary output, run requests. */
public final class Bench {
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
        for (ICraftingPatternDetails p : PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode request = PatternCompiler.compileRequest(root, amount);
        CraftingVM vm = new CraftingVM("bench", PATTERNS::get);
        return vm.execute(request, sim);
    }
}
