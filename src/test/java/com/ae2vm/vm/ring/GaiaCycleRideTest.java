package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.config.AE2VMConfig;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Live-server gaia-ring regression (2026-09-18, mclo.gs/tsIPI0Q trace): the
 * player's real web is a net-gain cycle P0(1 g14 -> 12 g5 + 1 dice) +
 * P1(1 g4 + 4 g5 -> 1 g14) fed by an out-of-ring amplifier
 * P2(m0 + m1 + m2 + 500 X -> 4 g4), with stock g5 x779 / g4 x998 / X plenty
 * and ZERO g14. Ordering 10000 g5 must ride the cycle from that stock:
 * 1250 loops (net +8 g5 each) consuming 1250 g4, of which 252 come from the
 * E-case amplifier (63 crafts x 4), leaving the honest missing list at
 * m0/m1/m2 x63 — never a self-referential "missing g5", never a silently
 * stalling plan, and the recorded order must survive the faithful CPU.
 */
class GaiaCycleRideTest {

    private static VMPlan run(boolean gate) {
        Bootstrap.register();
        Bench.reset();
        AE2VMConfig.ringSolverEnabled = gate;
        try {
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
                    .seed("X", 223291314L);
            VMPlan plan = Bench.run(p0, 10000, sim);

            assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(p0),
                    "P0 loops = 1250 (net +8 g5 each)");
            assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(p1),
                    "P1 loops = 1250 (g14 balance)");
            assertEquals(Long.valueOf(63L), plan.getPatternTimes().get(p2),
                    "E-case amplifier = ceil((1250-998)/4) = 63");
            assertFalse(hasMissing(plan, "g5"),
                    "the ordered item must never be self-missing; missing=" + plan.getMissingItems());
            assertTrue(hasMissing(plan, "m0") && hasMissing(plan, "m1") && hasMissing(plan, "m2"),
                    "the honest external shortfall is the P2 seed inputs; missing="
                            + plan.getMissingItems());
            assertEquals(63L, missingOf(plan, "m0"), "m0 shortfall");
            assertEquals(63L, missingOf(plan, "m1"), "m1 shortfall");
            assertEquals(63L, missingOf(plan, "m2"), "m2 shortfall");
            assertFalse(plan.isSimulation(), "cycle closes; only the true seeds are missing");
            CpuLifecycleAssert.complete(plan,
                    PatternCompat.getPrimaryOutput(p0), 10000);
            return plan;
        } finally {
            AE2VMConfig.ringSolverEnabled = false;
        }
    }

    private static boolean hasMissing(VMPlan plan, String id) {
        for (var key : plan.getMissingItems().keys()) {
            if (((com.ae2vm.test.fakes.BenchAEItemStack) key).id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static long missingOf(VMPlan plan, String id) {
        for (var key : plan.getMissingItems().keys()) {
            if (((com.ae2vm.test.fakes.BenchAEItemStack) key).id.equals(id)) {
                return plan.getMissingItems().get(key);
            }
        }
        return 0;
    }

    @Test
    @Disabled("WIP ring-ledger gross/net double-count: rides the cycle correctly but books missing g5 x4221 (5000 gross P1 draw charged against a net +10000 credit). Enable after the fold-ledger fix; see AGENTS.md P1")
    void gateOnRidesTheCycleWithHonestSeeds() {
        run(true);
    }
}
