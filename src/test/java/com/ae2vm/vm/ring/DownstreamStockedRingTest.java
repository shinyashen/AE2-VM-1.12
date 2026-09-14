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
 * engaged at all — no recycler/crafting-leg crafts, no phantom surplus.
 */
class DownstreamStockedRingTest {
    @Test
    void dump() throws Exception {
        net.minecraft.init.Bootstrap.register();
        Bench.reset();
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
        // M5 bridge: an executable (non-simulation) plan must survive the CPU
        CpuLifecycleAssert.complete(plan, com.ae2vm.compat.PatternCompat.getPrimaryOutput(x), 1000);

        StringBuilder sb = new StringBuilder("[downstream] sim=").append(plan.isSimulation());
        sb.append(" patterns:");
        for (var e : plan.getPatternTimes().entrySet()) {
            sb.append(" ").append(e.getValue()).append("x[")
                    .append(java.util.Arrays.toString(e.getKey().getOutputs())).append("]");
        }
        sb.append(" emitted:");
        for (var e : plan.getEmittedItems().entrySet()) {
            sb.append(" ").append(e.getValue()).append("x").append(e.getKey());
        }
        sb.append(" missing:");
        for (var e : plan.getMissingItems().entrySet()) {
            sb.append(" ").append(e.getValue()).append("x").append(e.getKey());
        }
        System.out.println(sb);
        System.out.println("[downstream] done");

        assertEquals(Long.valueOf(1000L), plan.getPatternTimes().get(x),
                "the downstream recipe runs for the whole request");
        assertFalse(plan.getPatternTimes().containsKey(b),
                "the stocked ring product must not engage the ring's crafting leg");
        assertFalse(plan.getPatternTimes().containsKey(a),
                "the stocked ring product must not engage the ring's recycling leg");
        assertTrue(plan.getEmittedItems().isEmpty(),
                "a fully stocked chain produces no surplus");
        assertFalse(plan.isSimulation(), "nothing is missing");
    }
}
