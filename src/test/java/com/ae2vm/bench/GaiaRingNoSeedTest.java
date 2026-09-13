package com.ae2vm.bench;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-report scenario: a zero-stock amplification ring (1 ingot -> 12
 * spirits, 4 spirits -> 1 ingot) requested 10000 spirits must FOLD — both
 * patterns scheduled at the balanced 1250/1250 count — and report only the
 * tiny startup seed (1 ingot) as missing. The no-ring baseline would be a
 * flat ceil(10000/12) = 834-ingot shortfall with the recycling pattern
 * absent.
 */
class GaiaRingNoSeedTest {
    @BeforeAll
    static void enableRingFamily() {
        // The ring family is feature-gated off by default; these tests pin its behavior.
        com.ae2vm.config.AE2VMConfig.ringSolverEnabled = true;
    }

    @AfterAll
    static void restoreRingFamilyGate() {
        com.ae2vm.config.AE2VMConfig.ringSolverEnabled = false;
    }

    @Test
    void zeroSeedAmplificationRingFolds() throws Exception {
        net.minecraft.init.Bootstrap.register();
        Bench.reset();
        BenchPatternDetails makeSpirit = Bench.pat("S", 12, "I", 1L);
        BenchPatternDetails makeIngot = Bench.pat("I", 1, "S", 4L);
        Bench.register(makeSpirit);
        Bench.register(makeIngot);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);
        VMPlan plan = Bench.run(makeSpirit, 10000, new BenchSimulationState());

        Long ingotCrafts = plan.getPatternTimes().get(makeIngot);
        Long spiritCrafts = plan.getPatternTimes().get(makeSpirit);
        assertTrue(ingotCrafts != null && ingotCrafts > 0,
                "recycling pattern must join the plan (ring must fold)");
        assertEquals(Long.valueOf(1250L), spiritCrafts,
                "1 ingot -> 12 spirits covers 10000 at 1250 crafts, not ceil(10000/12)");
        assertEquals(Long.valueOf(1250L), ingotCrafts,
                "the ring balances recycling at the same craft count");

        long totalMissing = 0;
        for (var e : plan.getMissingItems().entrySet()) {
            totalMissing += e.getValue();
        }
        assertEquals(1L, totalMissing,
                "only the startup seed may be missing; the ring pays it back in round one");
    }
}
