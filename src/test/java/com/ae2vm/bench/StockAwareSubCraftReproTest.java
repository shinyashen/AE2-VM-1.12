package com.ae2vm.bench;
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

/**
 * Port of the original StockAwareSubCraftReproTest — reproduces the reported bug
 * through the REAL stock-aware sub-craft path. GT Lite blank pattern chain
 * (last step = assembler with a fluid):
 * <pre>
 *   BLANK (assembler, last) = BOARD x1 + FLUID x1000
 *   BOARD (machine)         = RAW x1
 * </pre>
 * The network has PARTIAL stock of the sub-item (BOARD) and/or the fluid.
 * Expectation: plan must schedule EXACTLY N BLANK crafts, N (or deficit) BOARD
 * crafts, and usedItems[FLUID] = 1000 * N (the CPU must receive the fluid for
 * EVERY craft, including the last one).
 *
 * <p>The "fluid" is a plain amount-carrying key here, exactly like the original
 * bench (in-game it becomes an AE2FC fake drop whose size is the mB amount).
 */
class StockAwareSubCraftReproTest {

    private static final long FLUID_PER_CRAFT = 1000L;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    @Test
    void reproPartialSubItemStock() {
        for (long n : new long[]{1L, 2L, 3L, 4L, 5L}) {
            for (long boardStock : new long[]{0L, 1L, 2L, 3L, 4L, 5L, 10L}) {
                runCase("board-stock", n, boardStock, 1_000_000L);
            }
        }
    }

    @Test
    void reproPartialFluidStock() {
        for (long n : new long[]{1L, 2L, 3L, 4L, 5L}) {
            for (long fluidStock : new long[]{0L, 999L, 1000L, 1500L, 2500L, 5000L, 100000L}) {
                runCase("fluid-stock", n, 0L, fluidStock);
            }
        }
    }

    private static void runCase(String tag, long n, long boardStock, long fluidStock) {
        BenchPatternDetails board = pat("circuit_board", 1, "raw", 1L);
        Bench.register(board);
        BenchPatternDetails blank = pat("blank_pattern", 1,
                "circuit_board", 1L, "fluid_x", FLUID_PER_CRAFT);
        Bench.register(blank);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("raw", 1_000_000L)
                .seed("circuit_board", boardStock)
                .seed("fluid_x", fluidStock);

        for (appeng.api.networking.crafting.ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(blank, n);
        CraftingVM vm = new CraftingVM("stock-repro", Bench.PATTERNS::get);
        VMPlan plan = vm.execute(req, sim);

        long blankTimes = plan.getPatternTimes().getOrDefault(blank, 0L);
        long boardTimes = plan.getPatternTimes().getOrDefault(board, 0L);
        long fluidUsed = plan.getUsedItems().get(k("fluid_x"));
        long boardUsed = plan.getUsedItems().get(k("circuit_board"));

        System.out.println("[STOCK] " + tag + " n=" + n + " boardStock=" + boardStock
                + " fluidStock=" + fluidStock
                + " -> blank=" + blankTimes
                + " board=" + boardTimes
                + " used[fluid]=" + fluidUsed
                + " used[board]=" + boardUsed
                + " sim=" + plan.isSimulation());

        // The assembler (last step) must run exactly n times.
        assertEquals(n, blankTimes,
                tag + " n=" + n + " boardStock=" + boardStock + ": patternTimes[blank] off-by-one!");

        // Every craft of BLANK needs its fluid, so when the network has enough fluid
        // the plan must include 1000*N of it. "最后一份不发送物品" happens when
        // usedItems[fluid] < 1000*N despite enough stock.
        if (fluidStock >= n * FLUID_PER_CRAFT) {
            assertTrue(fluidUsed >= n * FLUID_PER_CRAFT,
                    tag + " n=" + n + " boardStock=" + boardStock + " fluidStock=" + fluidStock
                            + ": used[fluid]=" + fluidUsed + " < " + (n * FLUID_PER_CRAFT)
                            + " -> CPU will NOT send fluid for the last craft!");
        }

        // The CPU must be able to push the assembler n times: used[board] (from
        // network) + crafted boards must cover the n crafts. The bug under-counted
        // used[board] when the network had board stock, starving the LAST craft.
        long boardAvailable = boardUsed + boardTimes; // each BOARD craft yields 1 board
        if (plan.getMissingItems().isEmpty()) {
            assertTrue(boardAvailable >= n,
                    tag + " n=" + n + " boardStock=" + boardStock
                            + ": only " + boardAvailable + " boards scheduled (used=" + boardUsed
                            + " craft=" + boardTimes + ") < " + n
                            + " -> last craft will NOT get its board!");
        }
    }
}
