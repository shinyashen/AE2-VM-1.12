package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Live-server regression (2026-09-19, trace 20260919-100717-f49d): ordering
 * 1000 spirits with stock {spirits 779, ingots 998, seeds (@14) 1, X plenty}.
 * The solve legitimately spends the 1-unit seed stock to spare one recycler
 * round (125/124) — but the floor probe inflated its counts to CAP without
 * scaling the withdrawal, forced phantom fires past the draw, and doubled the
 * seed bill (net 1 + phantom 1 = 2 > 1 stocked): a false missing seed blocked
 * an order the network can actually run. The probe now stays on real counts,
 * so the plan bills exactly its true net draw — nothing missing, and the
 * faithful CPU completes.
 */
class GaiaThousandOrderTest {

    @Test
    void stockedSeedSparesARoundWithoutPhantomFloors() {
        Bootstrap.register();
        Bench.reset();
        BenchPatternDetails p0 = Bench.patEx(new String[]{"g5", "dice"}, new long[]{12, 1}, "g14", 1L);
        BenchPatternDetails p1 = Bench.patEx(new String[]{"g14"}, new long[]{1}, "g4", 1L, "g5", 4L);
        BenchPatternDetails p2 = Bench.patEx(new String[]{"g4"}, new long[]{4},
                "m0", 1L, "m1", 1L, "m2", 1L, "X", 500L);
        Bench.register(p0);
        Bench.register(p1);
        Bench.register(p2);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("g5", 779)
                .seed("g4", 998)
                .seed("g14", 1)
                .seed("X", 222897314L);
        VMPlan plan = Bench.run(p0, 1000, sim);


        // the closure's least fixpoint: seed 84 root crafts (the delivery),
        // the recycler tops g14 up from its 1 stocked unit, and the ring's
        // own g5 production covers p1's draw from STOCK — same delivery,
        // fewer crafts, less startup capital (CLOSURE-DESIGN 5.5)
        assertEquals(Long.valueOf(84L), plan.getPatternTimes().get(p0), "P0 loops ");
        assertEquals(Long.valueOf(83L), plan.getPatternTimes().get(p1),
                "the stocked seed spares one recycler round");
        assertTrue(plan.getMissingItems().isEmpty(),
                "the least fixpoint is fully stocked: " + plan.getMissingItems());
        assertFalse(plan.isSimulation(), "the order must be acceptable");
        assertEquals(332L, plan.getUsedItems().get(Bench.k("g5")), "p1's in-plan spirit draw");
        assertEquals(1L, plan.getUsedItems().get(Bench.k("g14")), "the stocked seed");
        assertEquals(83L, plan.getUsedItems().get(Bench.k("g4")), "p1's ingot draw");
        assertEquals(84L, plan.getEmittedItems().get(Bench.k("dice")), "the passive dice surplus");
    
        CpuLifecycleAssert.complete(plan, PatternCompat.getPrimaryOutput(p0), 1000);
    }


}
