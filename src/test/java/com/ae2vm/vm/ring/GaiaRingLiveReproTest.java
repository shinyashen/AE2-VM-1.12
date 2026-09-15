package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-report gaia ring: 1 terrasteel + 4 spirits -> 1 gaia ingot; 1 gaia
 * ingot -> 12 spirits + 1 dice. Order 10000 spirits. The delivery is CRAFTED
 * (a real job ignores the requested item's own stock), so the ring folds at
 * ceil(10000/8) = 1250 crafts of BOTH patterns and bills makeIngot's whole
 * 4x1250 spirit draw as job-start capital; the out-of-ring terrasteel demand
 * (1250) has its own pattern, and the E-case must schedule it (1250 crafts of
 * 2-iron -> 1 T) with the gap flowing down the DAG — iron missing, NOT a raw
 * terrasteel shortfall, and NOT the ring's own spirit capital either.
 */
class GaiaRingLiveReproTest {
    @BeforeAll
    static void enableRingFamily() {
        // The ring family is experimental and off by default; these tests pin its behavior.
        com.ae2vm.config.AE2VMConfig.ringSolverEnabled = true;
    }

    @AfterAll
    static void restoreRingFamilyGate() {
        com.ae2vm.config.AE2VMConfig.ringSolverEnabled = false;
    }

    @Test
    void gaiaRingFoldsAndOutOfRingIngredientSchedules() throws Exception {
        net.minecraft.init.Bootstrap.register();
        Bench.reset();
        // A: terrasteel(T) + 4 spirits(S) -> gaia ingot(I)
        BenchPatternDetails recycle = Bench.pat("I", 1, "T", 1L, "S", 4L);
        // B: gaia ingot -> 12 spirits + dice(D)
        BenchPatternDetails craft = Bench.patEx(new String[]{"S", "D"}, new long[]{12, 1}, "I", 1L);
        // C: terrasteel IS craftable on its own (2 iron -> 1 T) — the E-case:
        // the ring's out-of-ring ingredient has its own pattern.
        BenchPatternDetails terrasteel = Bench.pat("T", 1, "Fe", 2L);
        Bench.register(recycle);
        Bench.register(craft);
        Bench.register(terrasteel);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        BenchSimulationState sim = new BenchSimulationState().seed("S", 499);
        VMPlan plan = Bench.run(craft, 10000, sim);
        CpuLifecycleAssert.auto(plan);

        assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(recycle),
                "the recycling pattern folds at the crafted-delivery fixed point");
        assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(craft),
                "the crafting pattern folds at the crafted-delivery fixed point");
        assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(terrasteel),
                "the out-of-ring ingredient's own pattern must be scheduled for its deficit (E-case)");
        Map<String, Long> missing = new LinkedHashMap<>();
        for (var e : plan.getMissingItems().entrySet()) {
            missing.put(((BenchAEItemStack) e.getKey()).id, e.getValue());
        }
        assertEquals(2, missing.size(),
                "the spirit capital and the iron gap are the only disclosures, got " + missing);
        assertEquals(Long.valueOf(4501L), missing.get("S"),
                "makeIngot's whole 4x1250 draw is job-start capital; 499 stocked");
        assertEquals(Long.valueOf(2500L), missing.get("Fe"),
                "1250 terrasteel crafts need 2500 iron — the gap flows down the DAG");
        assertFalse(missing.containsKey("T"),
                "the E-case supersedes the terrasteel shortfall with its own deeper disclosure");
    }

    @Test
    void gaiaRingCompletesWithFullCapital() throws Exception {
        net.minecraft.init.Bootstrap.register();
        Bench.reset();
        BenchPatternDetails recycle = Bench.pat("I", 1, "T", 1L, "S", 4L);
        BenchPatternDetails craft = Bench.patEx(new String[]{"S", "D"}, new long[]{12, 1}, "I", 1L);
        BenchPatternDetails terrasteel = Bench.pat("T", 1, "Fe", 2L);
        Bench.register(recycle);
        Bench.register(craft);
        Bench.register(terrasteel);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        // the whole input draw on hand: the plan must survive the virtual CPU
        BenchSimulationState sim = new BenchSimulationState().seed("S", 5000).seed("Fe", 2500);
        VMPlan plan = Bench.run(craft, 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(recycle));
        assertEquals(Long.valueOf(1250L), plan.getPatternTimes().get(craft));
        assertTrue(plan.getMissingItems().isEmpty(), "nothing missing: " + plan.getMissingItems());
    }
}
