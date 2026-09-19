package com.ae2vm.vm.closure;

import com.ae2vm.compat.PatternCompat;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.vm.VMPlan;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan closure end-to-end: a ring-with-root web
 * whose faithful plan uses LESS startup capital than the stage pipeline's
 * (the root's produced units cover the delivery, so the in-plan draw comes
 * from stock, not from double-fired production), the injected-chain
 * re-balance completing on the faithful CPU, and the net-loss divergence
 * falling back to the stage pipeline's plan unchanged.
 */
class PlanClosureEngineTest {
    @Test
    void rootedRingPlanCoversFromStockInsteadOfDoubleFiring() {
        Bootstrap.register();
        Bench.reset();
        // the GaiaThousandOrder web: 12 g5 + 1 dice <- 1 g14; 1 g4 + 4 g5 <- 1 g14.
        // Order 1000 g5 with stock {g5 779, g4 998, g14 1}: the stage pipeline
        // (ring solver) fires 125/124 and withdraws 496 g5; the closure's least
        // fixpoint seeds 84 root crafts (the delivery) and covers p1's 332 g5
        // draw from stock — same delivery, less crafting, less capital.
        BenchPatternDetails p0 = Bench.patEx(new String[]{"g5", "dice"}, new long[]{12, 1}, "g14", 1L);
        BenchPatternDetails p1 = Bench.patEx(new String[]{"g14"}, new long[]{1}, "g4", 1L, "g5", 4L);
        Bench.register(p0);
        Bench.register(p1);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("g5", 779)
                .seed("g4", 998)
                .seed("g14", 1);
        VMPlan plan = Bench.run(p0, 1000, sim);

        assertEquals(Long.valueOf(84L), plan.getPatternTimes().get(p0), "the delivery seed");
        assertEquals(Long.valueOf(83L), plan.getPatternTimes().get(p1),
                "one g14 short of stock, covered by the recycler");
        assertTrue(plan.getMissingItems().isEmpty(),
                "the least fixpoint is fully stocked: " + plan.getMissingItems());
        assertFalse(plan.isSimulation(), "the order must be acceptable");
        assertEquals(332L, plan.getUsedItems().get(Bench.k("g5")),
                "p1's whole in-plan spirit draw, withdrawn (the root's own production siphons)");
        assertEquals(83L, plan.getUsedItems().get(Bench.k("g4")));
        assertEquals(1L, plan.getUsedItems().get(Bench.k("g14")), "the stocked seed");
        CpuLifecycleAssert.complete(plan, PatternCompat.getPrimaryOutput(p0), 1000);
    }

    @Test
    void injectedChainEndToEndRebalancesAndCompletes() {
        Bootstrap.register();
        Bench.reset();
        // ring: 4 s -> 1 i, 1 i -> 12 s; root C draws 1 i + 1 X. Stock {s 8, i 2}
        // spares rounds: the closure fixpoint is pC=10 / pI=8 / pS=2 (the stage
        // pipeline would bill the member draw instead of re-balancing); the
        // priming floor keeps the cycle's first craft bootable and the faithful
        // CPU completes.
        BenchPatternDetails pI = Bench.patEx(new String[]{"i"}, new long[]{1}, "s", 4L);
        BenchPatternDetails pS = Bench.patEx(new String[]{"s"}, new long[]{12}, "i", 1L);
        BenchPatternDetails pC = Bench.patEx(new String[]{"C"}, new long[]{1}, "i", 1L, "X", 1L);
        Bench.register(pI);
        Bench.register(pS);
        Bench.register(pC);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("X", 100L)
                .seed("s", 8L)
                .seed("i", 2L);
        VMPlan plan = Bench.run(pC, 10, sim);

        assertEquals(Long.valueOf(10L), plan.getPatternTimes().get(pC));
        assertEquals(Long.valueOf(11L), plan.getPatternTimes().get(pI),
                "the ring re-balances to the injected draw: 10 C-draw + 3 pS-draw"
                        + " = 13 i, stock 2 spares 2 rounds -> pI = 11");
        assertEquals(Long.valueOf(3L), plan.getPatternTimes().get(pS),
                "pI's 44 s draw against pS's 36, stock 8 spares the rest");
        assertTrue(plan.getMissingItems().isEmpty(),
                "coverage, not billing: " + plan.getMissingItems());
        assertFalse(plan.isSimulation(), "the order must be acceptable");
        CpuLifecycleAssert.complete(plan, PatternCompat.getPrimaryOutput(pC), 10);
    }

    @Test
    void divergedClosureFallsBackToTheStagePipeline() {
        Bootstrap.register();
        // cycle 2 A -> 1 B, 1 B -> 1 A behind the root R (1 A -> 1 R):
        // net-losing on a non-root key — the closure diverges past its bound
        // and the stage pipeline's plan stays in force: two patterns at the
        // demand-derived counts, the honest shortfall disclosed.
        VMPlan plan = runNetLossWeb();
        assertEquals(2, schedule(plan).size(), "the pipeline schedules both cycle legs");
        assertTrue(schedule(plan).containsValue(10L), "the delivery-derived count: " + schedule(plan));
    }

    /** patternTimes keyed by primary output (stable across runs). */
    private static Map<String, Long> schedule(VMPlan plan) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var e : plan.getPatternTimes().entrySet()) {
            out.put(String.valueOf(PatternCompat.getPrimaryOutput(e.getKey())),
                    e.getValue());
        }
        return out;
    }

    private VMPlan runNetLossWeb() {
        Bench.reset();
        BenchPatternDetails pB = Bench.patEx(new String[]{"B"}, new long[]{1}, "A", 2L);
        BenchPatternDetails pA = Bench.patEx(new String[]{"A"}, new long[]{1}, "B", 1L);
        BenchPatternDetails pR = Bench.patEx(new String[]{"R"}, new long[]{1}, "A", 1L);
        Bench.register(pB);
        Bench.register(pA);
        Bench.register(pR);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);
        return Bench.run(pR, 10, new BenchSimulationState());
    }
}
