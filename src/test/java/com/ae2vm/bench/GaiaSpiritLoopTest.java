package com.ae2vm.bench;

import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ae2vm.bench.Bench.UNBOUNDED_STOCK;
import static com.ae2vm.bench.Bench.feasible;
import static com.ae2vm.bench.Bench.infeasibleMatches;
import static com.ae2vm.bench.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-world gaia-spirit ring: {@code 4 spirits -> 1 ingot} and {@code 1 ingot -> 12
 * spirits} — a NET-AMPLIFYING ring (x3 spirit value per turn). Seeded by stocked
 * spirits, the loop can be primed and amplified arbitrarily, so a request of 10000
 * spirits with 2303 stocked must be fully feasible with NO missing items.
 *
 * <p>User report from live gameplay: the vanilla planner returns
 * {@code missing = 834 ingots} for this request — exactly ceil(10000/12), i.e. the
 * vanilla recursion assigns the whole spirit demand to the dissolve pattern, treats
 * the ingots as an external input, and fails to close the ring (2303 stocked spirits
 * are already counted toward delivery, so the ingot synthesis has no spirits left and
 * the cycle is never resolved).
 *
 * <p>Unbounded-stock mode must also stay feasible (the ring self-amplifies), and a
 * completely unseeded network must stay infeasible (the first ingot needs 4 spirits).
 *
 * <p><b>GAP-4 (currently @Disabled, pin the bug)</b>: the capture probe of the
 * ingot-synthesis pattern inserts its product into the shared simulation even when
 * its own inputs are missing; the dissolve probe then consumes that leaked ingot,
 * so the final plan schedules ONLY dissolve×834 with no synthesis crafts at all —
 * the CPU then reports exactly 834 missing ingots. The plan must schedule the
 * synthesis pattern (the ingot source) and an unseeded network must be infeasible.
 */
class GaiaSpiritLoopTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static String dump(VMPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (var e : plan.getMissingItems().entrySet()) {
            sb.append(((BenchAEItemStack) e.getKey()).id).append('x').append(e.getValue()).append(' ');
        }
        return sb.toString();
    }

    private static BenchPatternDetails[] loop() {
        BenchPatternDetails makeIngot = pat("I", 1, "S", 4L);   // 4 spirits -> 1 ingot
        BenchPatternDetails makeSpirit = pat("S", 12, "I", 1L); // 1 ingot -> 12 spirits
        return new BenchPatternDetails[]{makeIngot, makeSpirit};
    }

    @Test
    @Disabled("GAP-4: capture-probe product leak builds a dissolve-only plan")
    void tenThousandSpiritsFrom2303StockFeasible() {
        BenchPatternDetails[] loop = loop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", 2303);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(plan),
                "seeded amplifying ring must cover 10000 spirits, missing=" + dump(plan));
        // The dissolve crafts consume ingots — the plan must also schedule their
        // synthesis (a pattern whose input is the spirit). A dissolve-only plan
        // makes the CPU report missing ingots (GAP-4 symptom).
        boolean schedulesIngotSynthesis = false;
        for (var e : plan.getPatternTimes().entrySet()) {
            for (var in : e.getKey().getCondensedInputs()) {
                if (in != null && ((BenchAEItemStack) in).id.equals("S") && e.getValue() > 0) {
                    schedulesIngotSynthesis = true;
                }
            }
        }
        assertTrue(schedulesIngotSynthesis,
                "plan must schedule the ingot-synthesis pattern, times=" + plan.getPatternTimes());
    }

    @Test
    void unboundedStockFeasible() {
        BenchPatternDetails[] loop = loop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(plan),
                "unbounded-stock amplifying ring must be feasible, missing=" + dump(plan));
    }

    @Test
    @Disabled("GAP-4: unseeded ring currently yields an empty-missing 'feasible' plan")
    void unseededRingInfeasible() {
        BenchPatternDetails[] loop = loop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertFalse(feasible(plan),
                "unseeded ring cannot craft the first ingot, got a feasible plan");
        assertTrue(infeasibleMatches(plan, Map.of("S", 4L)),
                "unseeded ring must report the 4-spirit startup seed missing, got " + dump(plan));
    }
}
