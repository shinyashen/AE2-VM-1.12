package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.ae2vm.config.AE2VMConfig;
import net.minecraft.init.Bootstrap;

/**
 * Live-report scenario: a zero-stock amplification ring (1 ingot -> 12
 * spirits, 4 spirits -> 1 ingot) requested 10000 spirits must FOLD — both
 * patterns scheduled at the crafted-delivery fixed point 1250/1250 — and
 * disclose the faithful job-start capital: makeIngot's whole 4x1250 spirit
 * draw (the delivered root never circulates back, :265). The no-ring
 * baseline would be a flat ceil(10000/12) = 834-ingot shortfall with the
 * recycling pattern absent.
 */
class GaiaRingNoSeedTest {
    @BeforeAll
    static void enableRingFamily() {
        // The ring family is experimental and off by default; these tests pin its behavior.
        AE2VMConfig.ringSolverEnabled = true;
    }

    @AfterAll
    static void restoreRingFamilyGate() {
        AE2VMConfig.ringSolverEnabled = false;
    }

    @Test
    void zeroSeedAmplificationRingFolds() throws Exception {
        Bootstrap.register();
        Bench.reset();
        BenchPatternDetails makeSpirit = Bench.pat("S", 12, "I", 1L);
        BenchPatternDetails makeIngot = Bench.pat("I", 1, "S", 4L);
        Bench.register(makeSpirit);
        Bench.register(makeIngot);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);
        VMPlan plan = Bench.run(makeSpirit, 10000, new BenchSimulationState());
        CpuLifecycleAssert.auto(plan);

        Long ingotCrafts = plan.getPatternTimes().get(makeIngot);
        Long spiritCrafts = plan.getPatternTimes().get(makeSpirit);
        assertTrue(ingotCrafts != null && ingotCrafts > 0,
                "recycling pattern must join the plan (ring must fold)");
        assertEquals(Long.valueOf(closure() ? 834L : 1250L), spiritCrafts,
                "the fixed point crafts the 10000 delivery (closure least fixpoint / ring solver)");
        assertEquals(Long.valueOf(closure() ? 834L : 1250L), ingotCrafts,
                "the ring balances recycling at the same craft count");

        Map<String, Long> missing = new HashMap<>();
        for (var e : plan.getMissingItems().entrySet()) {
            missing.put(((BenchAEItemStack) e.getKey()).id, e.getValue());
        }
        assertEquals(Map.of("S", closure() ? 3336L : 5000L), missing,
                "the faithful disclosure is makeIngot's whole input draw — "
                        + "timing seeds alone would starve the CPU at t=0");
    }

    /** True when the closure bypass owns coverage (dual-mode expectations). */
    private static boolean closure() {
        return AE2VMConfig.closureEnabled;
    }

}
