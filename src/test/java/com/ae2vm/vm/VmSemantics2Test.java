package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.ae2vm.test.fakes.BenchPatternDetails.custom;
import static com.ae2vm.test.fakes.BenchPatternDetails.processing;
import static com.ae2vm.test.fakes.BenchPatternDetails.withSubstitute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ported benchmark families round 2: exponential chains (Fibonacci),
 * JIT cross-request reuse, self-growth cut, quantity-one boundary,
 * substitute slots, processing default fuzzy, durability chains.
 *
 * Keys are plain id strings here; the first output of a pattern is its
 * primary output.
 */
class VmSemantics2Test {

    @BeforeAll
    static void bootstrap() {
        // Forge guards Items/Blocks behind Bootstrap; vanilla registration is
        // self-contained and safe to run inside a plain JVM.
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static String dump(VMPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (var e : plan.getMissingItems().entrySet()) {
            sb.append(((BenchAEItemStack) e.getKey()).id).append('x').append(e.getValue()).append(' ');
        }
        return sb.toString();
    }

    private static BenchAEItemStack k(String id) {
        return new BenchAEItemStack(id, 1);
    }

    private static BenchAEItemStack item(String id, long amount) {
        return new BenchAEItemStack(id, amount);
    }

    private static BenchAEItemStack tool(String id, int damage) {
        return new BenchAEItemStack(id, damage, 10, 1);
    }

    private static BenchPatternDetails line(long[][] inputs, long[][] outputs) {
        IAEItemStack[] in = new IAEItemStack[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            in[i] = new BenchAEItemStack(inputs[i][0] + "", inputs[i][1]).setStackSize(inputs[i][1]);
        }
        IAEItemStack[] out = new IAEItemStack[outputs.length];
        for (int i = 0; i < outputs.length; i++) {
            out[i] = new BenchAEItemStack(outputs[i][0] + "", outputs[i][1]).setStackSize(outputs[i][1]);
        }
        return BenchPatternDetails.custom(in, out);
    }

    /**
     * Fibonacci chain: F(k) = F(k-1) + F(k-2), 11 patterns, request 1 F11.
     * Seeds F0=34, F1=55 cover the whole expansion -> feasible in O(patterns).
     */
    @Test
    void fibonacciChainCollapsesToLinearWork() {
        Map<String, BenchPatternDetails> pats = new HashMap<>();
        for (int k = 2; k <= 11; k++) {
            BenchPatternDetails p = line(
                    new long[][]{{k - 1, 1}, {k - 2, 1}}, new long[][]{{k, 1}});
            pats.put("F" + k, p);
            Bench.register(p);
        }
        CraftingVM vm = new CraftingVM("bench", Bench.PATTERNS::get);
        CraftingBytecode req = PatternCompiler.compileRequest(pats.get("F11"), 1);
        // leaves are ids "0" and "1": request 1xF11 needs 55x"0" + 89x"1"
        BenchSimulationState sim = new BenchSimulationState().seed("0", 55).seed("1", 89);
        VMPlan plan = vm.execute(req, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(plan.isSimulation(), "fibonacci chain must be feasible: missing=" + dump(plan));
        assertEquals(1L, plan.getPatternTimes().get(pats.get("F11")));
        assertEquals(1L, plan.getPatternTimes().get(pats.get("F10")));
    }

    /** Same VM instance across requests: bundles are reused, requests scale linearly. */
    @Test
    void jitCrossRequestReuseScalesLinearly() {
        BenchPatternDetails producer = processing(new long[][]{{1, 1}}, new long[][]{{0, 4}});
        Bench.register(producer);
        CraftingVM vm = new CraftingVM("bench", Bench.PATTERNS::get);
        BenchSimulationState s1 = new BenchSimulationState().seed("B", 100);
        VMPlan p1 = vm.execute(PatternCompiler.compileRequest(producer, 4), s1);
        CpuLifecycleAssert.auto(p1);
        assertFalse(p1.isSimulation());
        assertEquals(1L, p1.getPatternTimes().get(producer));
        BenchSimulationState s2 = new BenchSimulationState().seed("B", 100);
        VMPlan p2 = vm.execute(PatternCompiler.compileRequest(producer, 40), s2);
        CpuLifecycleAssert.auto(p2);
        assertFalse(p2.isSimulation());
        assertEquals(10L, p2.getPatternTimes().get(producer));
    }

    /** Self-growth A -> 2A: never dispatched; served from stock only. */
    @Test
    void selfGrowthCutServesFromStockOnly() {
        BenchPatternDetails selfLoop = line(new long[][]{{0, 1}}, new long[][]{{0, 2}});
        Bench.register(selfLoop);
        BenchSimulationState sim = new BenchSimulationState().seed("0", 5);
        VMPlan plan = vmRun(selfLoop, 3, sim);
        assertFalse(plan.isSimulation(), "stocked self-loop must serve from stock: missing=" + dump(plan));
        assertTrue(plan.getPatternTimes().isEmpty(), "self-growth pattern must never fire");
        assertEquals(3L, plan.getUsedItems().get(k("0")));
    }

    @Test
    void selfGrowthCutStarvedReportsMissing() {
        BenchPatternDetails selfLoop = line(new long[][]{{0, 1}}, new long[][]{{0, 2}});
        Bench.register(selfLoop);
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = vmRun(selfLoop, 2, sim);
        assertTrue(plan.isSimulation(), "starved self-loop: missing=" + dump(plan));
        assertEquals(2L, plan.getMissingItems().get(k("0")));
    }

    /** Quantity-one boundary: exactly one craft for a single-item request. */
    @Test
    void quantityOneBoundary() {
        BenchPatternDetails p = processing(new long[][]{{1, 1}, {2, 1}}, new long[][]{{0, 1}});
        Bench.register(p);
        BenchSimulationState sim = new BenchSimulationState().seed("B", 1).seed("C", 1);
        VMPlan plan = vmRun(p, 1, sim);
        assertFalse(plan.isSimulation(), "missing=" + dump(plan));
        assertEquals(1L, plan.getPatternTimes().get(p));
    }

    /**
     * Replacement-enabled slot (FUZZY_SLOT): the encoded input is absent but
     * its registered substitute is stocked -> the slot is satisfied and no
     * false missing is reported.
     */
    @Test
    void substituteSlotSatisfiedByVariant() {
        BenchPatternDetails consumer = withSubstitute(
                new long[][]{{0, 1}, {1, 1}}, new long[][]{{2, 1}}, "SUB");
        Bench.register(consumer);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("SUB", 5)
                .seed("B", 5);
        VMPlan plan = vmRun(consumer, 5, sim);
        assertFalse(plan.isSimulation(), "substitute must satisfy the slot: missing=" + dump(plan));
        assertEquals(5L, plan.getUsedItems().get(k("SUB")));
    }

    /** Processing-recipe default fuzzy: same-item NBT/damage variant stock satisfies the slot. */
    @Test
    void processingDefaultFuzzyConsumesVariantStock() {
        BenchPatternDetails p = processing(new long[][]{{1, 1}}, new long[][]{{0, 1}});
        Bench.register(p);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("B", 10)          // pattern encodes B(damage 0); stock is a damaged variant
                .seedVariant("B", 5, 10, 10);
        VMPlan plan = Bench.run(p, 10, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(plan.isSimulation(), "processing default fuzzy: missing=" + dump(plan));
        assertEquals(10L, plan.getPatternTimes().get(p));
    }

    // The chained durability closed-form test was removed with DURABILITY_TOOL
    // (see local/VM-AUDIT.md B3; tools compile as ordinary gross inputs now).

    private static VMPlan vmRun(BenchPatternDetails root, long amount, BenchSimulationState sim) {
        for (var p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(root, amount);
        CraftingVM vm = new CraftingVM("bench", Bench.PATTERNS::get);
        return vm.execute(req, sim);
    }
}
