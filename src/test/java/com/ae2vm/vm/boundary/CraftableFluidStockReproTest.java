package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import net.minecraft.init.Bootstrap;

/**
 * Port of the original CraftableFluidStockReproTest — exact reported scenario
 * (GT Lite): the blank pattern's last step is an assembler that consumes a FLUID
 * which is itself produced by a sub-craft, and the network has PARTIAL stock of
 * that fluid. The plan must still schedule the fluid sub-craft for the deficit AND
 * mark the stocked fluid as network-used, so the CPU can push the assembler
 * pattern N times — including the LAST one.
 *
 * <pre>
 *   BLANK (assembler, last) = BOARD x1 + FLUID x1000
 *   BOARD (machine)         = RAW x1
 *   FLUID (machine)         = WATER x1  -> produces 1000 FLUID per craft
 * </pre>
 */
class CraftableFluidStockReproTest {

    private static final long FLUID_PER_CRAFT = 1000L;

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    @Test
    void reproCraftableFluidWithStock() {
        BenchPatternDetails board = pat("circuit_board", 1, "raw", 1L);
        Bench.register(board);
        BenchPatternDetails fluid = pat("fluid_x", FLUID_PER_CRAFT, "water", 1L);
        Bench.register(fluid);
        BenchPatternDetails blank = pat("blank_pattern", 1,
                "circuit_board", 1L, "fluid_x", FLUID_PER_CRAFT);
        Bench.register(blank);

        for (long n : new long[]{1L, 2L, 3L, 4L, 5L}) {
            for (long fluidStock : new long[]{0L, 1000L, 2000L, 3000L, 10000L}) {
                BenchSimulationState sim = new BenchSimulationState()
                        .seed("raw", 1_000_000L)
                        .seed("water", 1_000_000L)
                        .seed("fluid_x", fluidStock);

                for (ICraftingPatternDetails p : Bench.PATTERNS.values()) {
                    PatternCompiler.compileIfAbsent(p);
                }
                CraftingBytecode req = PatternCompiler.compileRequest(blank, n);
                CraftingVM vm = new CraftingVM("cfluid-repro", Bench.PATTERNS::get);
                VMPlan plan = vm.execute(req, sim);
        CpuLifecycleAssert.auto(plan);

                long blankTimes = plan.getPatternTimes().getOrDefault(blank, 0L);
                long fluidCraft = plan.getPatternTimes().getOrDefault(fluid, 0L);
                long fluidUsed = plan.getUsedItems().get(k("fluid_x"));
                long fluidAvailable = fluidUsed + fluidCraft * FLUID_PER_CRAFT;

                System.out.println("[CFLUID] n=" + n + " fluidStock=" + fluidStock
                        + " -> blank=" + blankTimes
                        + " fluidCraft=" + fluidCraft
                        + " used[fluid]=" + fluidUsed
                        + " available=" + fluidAvailable
                        + " sim=" + plan.isSimulation());

                // The assembler must run exactly n times.
                assertEquals(n, blankTimes, "n=" + n + ": patternTimes[blank] off-by-one!");
                // Stock + crafted fluid must cover all n crafts.
                assertTrue(fluidAvailable >= n * FLUID_PER_CRAFT,
                        "n=" + n + " fluidStock=" + fluidStock
                                + ": only " + fluidAvailable + " fluid scheduled (used=" + fluidUsed
                                + " craft=" + fluidCraft + ") < " + (n * FLUID_PER_CRAFT)
                                + " -> last craft will NOT get its fluid!");
            }
        }
    }
}
