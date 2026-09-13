package com.ae2vm.bench;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import appeng.api.storage.data.IAEItemStack;

/**
 * Live-report gaia ring: 1 terrasteel + 4 spirits -> 1 gaia ingot; 1 gaia
 * ingot -> 12 spirits + 1 dice. Order 10000 spirits with 499 spirits in
 * stock. The ring folds at ceil(9501/8) = 1188 crafts of BOTH patterns; the
 * out-of-ring terrasteel demand (1188) has its own pattern, and the E-case
 * must schedule it (1188 crafts of 2-iron -> 1 T) with the gap flowing down
 * the DAG — 2376 iron missing, NOT a raw 1188-terrasteel shortfall.
 */
class GaiaRingLiveReproTest {
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

        assertEquals(Long.valueOf(1188L), plan.getPatternTimes().get(recycle),
                "the recycling pattern balances at the ring solution");
        assertEquals(Long.valueOf(1188L), plan.getPatternTimes().get(craft),
                "the crafting pattern balances at the ring solution");
        assertEquals(Long.valueOf(1188L), plan.getPatternTimes().get(terrasteel),
                "the out-of-ring ingredient's own pattern must be scheduled for its deficit (E-case)");
        Map.Entry<IAEItemStack, Long> missingEntry = null;
        int missingKinds = 0;
        for (var e : plan.getMissingItems().entrySet()) {
            missingKinds++;
            missingEntry = e;
        }
        assertEquals(1, missingKinds,
                "only the deepest unscheduled level may be missing");
        assertEquals("Fe", ((BenchAEItemStack) missingEntry.getKey()).id,
                "the gap must flow down the DAG to iron, not stop at terrasteel");
        assertEquals(2376L, (long) missingEntry.getValue(),
                "1188 terrasteel crafts need 2376 iron");
        // The 499 seeded spirits are consumed INSIDE the ring: the schedule is
        // 1188 (= ceil((10000-499)/8)), not 1250 — stock awareness asserted by
        // the craft counts above.
    }
}
