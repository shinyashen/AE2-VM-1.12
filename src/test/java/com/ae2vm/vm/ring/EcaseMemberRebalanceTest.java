package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.config.AE2VMConfig;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * The solve↔expand loop: an E-case-injected producer chain that consumes a
 * ring MEMBER's output must re-balance the ring (ride more rounds) instead of
 * billing the draw as capital the network may not have. Live shape (trace
 * A1ywmJB): injected machinery chains drew 34,604 spirits off a ring
 * producing 9,720 — the draw fell through every ledger and the order shipped
 * false missing.
 *
 * <p>The mirror: g4's producer consumes 1 g5 (a ring MEMBER) per craft. The
 * first round sizes the ring at 125/125 and injects the producer ×32, whose
 * g5 draw feeds back as an extra external floor; the re-solve grows the ring
 * to 130/130 (the loop's gain covers the draw) and the plan lands feasible
 * with nothing missing.
 */
class EcaseMemberRebalanceTest {
    @BeforeAll
    static void enableRingFamily() {
        AE2VMConfig.ringSolverEnabled = true;
    }

    @AfterAll
    static void restoreRingFamilyGate() {
        AE2VMConfig.ringSolverEnabled = false;
    }

    @Test
    void injectedMemberDrawRebalancesTheRing() {
        Bootstrap.register();
        Bench.reset();
        BenchPatternDetails p0 = Bench.patEx(new String[]{"g5", "dice"}, new long[]{12, 1}, "g14", 1L);
        BenchPatternDetails p1 = Bench.patEx(new String[]{"g14"}, new long[]{1}, "g4", 1L, "g5", 4L);
        // g4's producer consumes a ring MEMBER (g5) — the draw must ride the
        // ring's gain instead of billing network capital
        BenchPatternDetails p2 = Bench.pat("g4", 4, "g5", 1L, "m0", 1L);
        Bench.register(p0);
        Bench.register(p1);
        Bench.register(p2);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("g5", 5000)
                .seed("m0", 10000);
        VMPlan plan = Bench.run(p0, 1000, sim);

        assertEquals(Long.valueOf(130L), plan.getPatternTimes().get(p0),
                "the ring re-balanced: 1000 delivery + the injected draw over the net gain");
        assertEquals(Long.valueOf(130L), plan.getPatternTimes().get(p1), "P1 tracks P0");
        assertEquals(Long.valueOf(33L), plan.getPatternTimes().get(p2),
                "the injected producer: ceil(130/4)");
        assertTrue(plan.getMissingItems().isEmpty(),
                "the ring covers the injected draw; nothing missing: " + plan.getMissingItems());
        assertFalse(plan.isSimulation(), "the order must be acceptable");
        // the withdrawal: P1's 4x130 draw plus the injected producer's 33
        assertEquals(553L, plan.getUsedItems().get(Bench.k("g5")), "P1 + injected draw");
        assertEquals(33L, plan.getUsedItems().get(Bench.k("m0")), "the injected producer's m0");
        CpuLifecycleAssert.complete(plan, PatternCompat.getPrimaryOutput(p0), 1000);
    }
}
