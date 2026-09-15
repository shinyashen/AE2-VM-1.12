package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original QuantityOneBoundaryTest — investigates the user report:
 * "合成 1 个/1b 缺失无法合成，但 2 个/100b 正常" — requesting ONE of an item
 * reports a missing ingredient even though the ingredients are craftable / stocked,
 * while requesting 2+ succeeds. This is a 1-craft capture boundary bug
 * (rootCraftTimes == 1) in the aggregation / stock-aware sub-craft path.
 */
class QuantityOneBoundaryTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static String missing(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : p.getMissingItems().keys()) {
            out.put(((BenchAEItemStack) key).id, p.getMissingItems().get(key));
        }
        return out.toString();
    }

    private static VMPlan run(BenchPatternDetails target, long amount, BenchSimulationState sim) {
        for (appeng.api.networking.crafting.ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(target, amount);
        CraftingVM vm = new CraftingVM("q1-bench", Bench.PATTERNS::get);
        return vm.execute(req, sim);
    }

    /** X_i = X_{i-1} + X_{i-2} Fibonacci chain, leaves stocked. */
    @Test
    void fibOneVsTwoNoFalseMissing() {
        int levels = 12;
        Map<String, BenchPatternDetails> byId = new java.util.HashMap<>();
        for (int i = 2; i < levels; i++) {
            BenchPatternDetails p = pat("X" + i, 1, "X" + (i - 1), 1L, "X" + (i - 2), 1L);
            byId.put("X" + i, p);
            Bench.register(p);
        }

        BenchPatternDetails top = byId.get("X" + (levels - 1));
        VMPlan p1 = run(top, 1, freshFibSim());
        VMPlan p2 = run(top, 2, freshFibSim());
        System.out.println("[Q1] fib12 x1 missing=" + missing(p1));
        System.out.println("[Q1] fib12 x2 missing=" + missing(p2));
        assertTrue(p1.getMissingItems().isEmpty(),
                "request 1 must be feasible, missing=" + missing(p1));
        assertTrue(p2.getMissingItems().isEmpty(),
                "request 2 must be feasible, missing=" + missing(p2));
    }

    private static BenchSimulationState freshFibSim() {
        return new BenchSimulationState()
                .seed("X0", 1000L)
                .seed("X1", 1000L);
    }

    /** Single step A <- B (B craftable from stocked C,D) + B stocked partially. */
    @Test
    void partialStockOneVsTwo() {
        BenchPatternDetails b = pat("B", 1, "C", 1L, "D", 1L);
        Bench.register(b);
        BenchPatternDetails a = pat("A", 1, "B", 1L);
        Bench.register(a);

        VMPlan p1 = run(a, 1, new BenchSimulationState()
                .seed("B", 1L) // partial: 1 B stocked, rest crafted
                .seed("C", 32L).seed("D", 32L));
        VMPlan p2 = run(a, 2, new BenchSimulationState()
                .seed("B", 1L)
                .seed("C", 32L).seed("D", 32L));
        System.out.println("[Q1] partial x1 missing=" + missing(p1));
        System.out.println("[Q1] partial x2 missing=" + missing(p2));
        assertTrue(p1.getMissingItems().isEmpty(),
                "request 1 must be feasible, missing=" + missing(p1));
        assertTrue(p2.getMissingItems().isEmpty(),
                "request 2 must be feasible, missing=" + missing(p2));
    }
}
