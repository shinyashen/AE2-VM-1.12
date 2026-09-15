package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downstream item whose chain contains the ring product, with the ring
 * product STOCKED: X <- 1 spirit, spices are plentiful. The ring must not be
 * engaged at all — no recycler/crafting-leg crafts, no phantom surplus — in
 * either gate state. With the gate ON this is the historical reservation
 * leak: the idle fold stripped the spirit keys and released their stock
 * reservation WITHOUT re-billing the downstream draw, so the plan shipped
 * with no spirit capital at all and the CPU stalled at t=0 (S2). The idle
 * plan now carries the out-of-ring draw as its whole used set.
 */
class DownstreamStockedRingTest {
    private static final boolean GATE_ON = true;
    private static final boolean GATE_OFF = false;

    private static VMPlan run(boolean gate) {
        net.minecraft.init.Bootstrap.register();
        Bench.reset();
        com.ae2vm.config.AE2VMConfig.ringSolverEnabled = gate;
        try {
            // ring: B (1 ingot -> 12 spirits), A (1 terrasteel + 4 spirits -> 1 ingot)
            BenchPatternDetails b = Bench.pat("S", 12, "I", 1L);
            BenchPatternDetails a = Bench.pat("I", 1, "T", 1L, "S", 4L);
            // downstream: X <- 1 spirit (+ a plain external material to keep it real)
            BenchPatternDetails x = Bench.pat("X", 1, "S", 1L, "Fe", 1L);
            Bench.register(b);
            Bench.register(a);
            Bench.register(x);
            for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

            BenchSimulationState sim = new BenchSimulationState()
                    .seed("S", 1_000_000L)
                    .seed("Fe", 1_000_000L);
            VMPlan plan = Bench.run(x, 1000, sim);

            // the downstream recipe runs for the whole request
            assertEquals(Long.valueOf(1000L), plan.getPatternTimes().get(x));
            // the stocked ring product must not engage either ring leg
            assertFalse(plan.getPatternTimes().containsKey(b));
            assertFalse(plan.getPatternTimes().containsKey(a));
            assertTrue(plan.getEmittedItems().isEmpty(),
                    "a fully stocked chain produces no surplus");
            assertFalse(plan.isSimulation(), "nothing is missing");
            // M5 bridge: an executable (non-simulation) plan must survive the CPU
            CpuLifecycleAssert.complete(plan,
                    com.ae2vm.compat.PatternCompat.getPrimaryOutput(x), 1000);
            return plan;
        } finally {
            com.ae2vm.config.AE2VMConfig.ringSolverEnabled = false;
        }
    }

    @Test
    void gateOffPlainPropagation() {
        run(GATE_OFF);
    }

    @Test
    void gateOnIdleFoldStillBillsTheDownstreamDraw() {
        run(GATE_ON);
    }
}
