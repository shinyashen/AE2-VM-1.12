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

import java.util.TreeMap;

import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original FluidBucketBoundaryTest — reproduces the player report:
 * "合成一个物品或液体，一个或一b缺失物品无法合成，但换成2个或100b就可以" —
 * requesting ONE item (or 1 bucket = 1000 mB of fluid) reports a missing
 * ingredient even though ingredients are craftable/stocked, while requesting 2+
 * (or 100b) succeeds. The boundary is rootCraftTimes == 1 in the aggregation /
 * stock-aware sub-craft path.
 */
class FluidBucketBoundaryTest {

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
        CraftingVM vm = new CraftingVM("fluid-bench", Bench.PATTERNS::get);
        return vm.execute(req, sim);
    }

    /** A <- B + FLUID; FLUID <- C x1000 (produces 1000 mB/craft); C stocked. */
    @Test
    void craftableFluidOneVsTwo() {
        BenchPatternDetails fluid = pat("fluid", 1000, "C", 1L);
        Bench.register(fluid);
        BenchPatternDetails b = pat("B", 1, "C", 1L);
        Bench.register(b);
        BenchPatternDetails a = pat("A", 1, "B", 1L, "fluid", 1000L);
        Bench.register(a);

        VMPlan p1 = run(a, 1, new BenchSimulationState().seed("B", 5L).seed("C", 10_000L));
        VMPlan p2 = run(a, 2, new BenchSimulationState().seed("B", 5L).seed("C", 10_000L));
        System.out.println("[FB] craftableFluid x1 missing=" + missing(p1));
        System.out.println("[FB] craftableFluid x2 missing=" + missing(p2));
        assertTrue(p1.getMissingItems().isEmpty(), "x1 must be feasible, missing=" + missing(p1));
        assertTrue(p2.getMissingItems().isEmpty(), "x2 must be feasible, missing=" + missing(p2));
    }

    /** A <- B + FLUID(1000) with FLUID NOT craftable but STOCKED partially (5000 mB). */
    @Test
    void stockedFluidOneVsTwo() {
        BenchPatternDetails b = pat("B", 1, "C", 1L);
        Bench.register(b);
        BenchPatternDetails a = pat("A", 1, "B", 1L, "fluid", 1000L);
        Bench.register(a);
        // FLUID has no pattern → leaf, stock 5000 mB

        VMPlan p1 = run(a, 1, new BenchSimulationState()
                .seed("B", 5L).seed("C", 10_000L).seed("fluid", 5000L));
        VMPlan p2 = run(a, 2, new BenchSimulationState()
                .seed("B", 5L).seed("C", 10_000L).seed("fluid", 5000L));
        System.out.println("[FB] stockedFluid x1 missing=" + missing(p1));
        System.out.println("[FB] stockedFluid x2 missing=" + missing(p2));
        assertTrue(p1.getMissingItems().isEmpty(), "x1 must be feasible, missing=" + missing(p1));
        assertTrue(p2.getMissingItems().isEmpty(), "x2 must be feasible, missing=" + missing(p2));
    }
}
