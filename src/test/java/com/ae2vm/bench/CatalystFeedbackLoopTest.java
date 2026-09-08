package com.ae2vm.bench;

import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ae2vm.bench.Bench.UNBOUNDED_STOCK;
import static com.ae2vm.bench.Bench.feasible;
import static com.ae2vm.bench.Bench.infeasibleMatches;
import static com.ae2vm.bench.Bench.pat;
import static com.ae2vm.bench.Bench.patEx;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original CatalystFeedbackLoopTest — scenario tests for the v1.10.x
 * CATALYST feedback-loop fix. A catalyst feedback loop produces a byproduct that
 * feeds back into its own recipe chain:
 *
 * <ul>
 *   <li><b>raw-feedback-loop</b> — {@code A -> 2B, 2B + C -> E + D, D -> A}: a BALANCED
 *       catalyst cycle. One {@code A} seed circulates forever (1 A → 2 B → E + D → A), so
 *       a batch of 8 E consumes only 8 C plus ONE {@code A} seed. With no {@code A}
 *       stocked the loop cannot be primed → exactly {@code A=1} is missing.</li>
 *   <li><b>lossy-feedback-loop</b> — {@code 3 A -> 2 B, 2 B -> D + 2 A}: a DECREASING
 *       loop. Each D nets −1 A and retains a 2-A startup state, so a batch of 8 D needs
 *       10 A. With only 8 A stocked, exactly {@code A=2} (the startup state) is missing.</li>
 * </ul>
 * The VM previously reported the byproduct as a false missing leaf; these tests pin the
 * correct feasibility + missing domain/amount for all three material modes.
 */
class CatalystFeedbackLoopTest {

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

    /** Ordinary balanced catalyst cycle: A->2B, 2B+C->E+D, D->A. */
    private static BenchPatternDetails[] rawLoop() {
        BenchPatternDetails makeB = pat("B", 2, "A", 1L);
        BenchPatternDetails makeE = patEx(new String[]{"E", "D"}, new long[]{1, 1}, "B", 2L, "C", 1L);
        BenchPatternDetails makeA = pat("A", 1, "D", 1L);
        return new BenchPatternDetails[]{makeB, makeE, makeA};
    }

    /** Ordinary decreasing feedback: each D consumes one A net and retains a two-A startup state. */
    private static BenchPatternDetails[] lossyLoop() {
        BenchPatternDetails makeB = pat("B", 2, "A", 3L);
        BenchPatternDetails makeD = patEx(new String[]{"D", "A"}, new long[]{1, 2}, "B", 2L);
        return new BenchPatternDetails[]{makeB, makeD};
    }

    // ---- raw-feedback-loop: balanced catalyst cycle, seed = 1 A ----

    @Test
    void rawLoopMinimumFeasible() {
        // stock {A:1, C:8} — the 1-A seed primes the loop; 8 C consumed; no missing.
        BenchPatternDetails[] loop = rawLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 1).seed("C", 8);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        assertTrue(feasible(plan),
                "balanced catalyst cycle with A=1 seed must be feasible, got " + dump(plan));
    }

    @Test
    void rawLoopUnboundedFeasible() {
        BenchPatternDetails[] loop = rawLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", UNBOUNDED_STOCK).seed("C", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        assertTrue(feasible(plan),
                "balanced catalyst cycle with unbounded A must be feasible, got " + dump(plan));
    }

    @Test
    void rawLoopStarvedMissingSeed() {
        // stock {C:8} — no A seed → the loop cannot be primed; missing exactly A=1.
        BenchPatternDetails[] loop = rawLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("C", 8);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        assertFalse(feasible(plan), "starved balanced cycle must be infeasible, got missing=" + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("A", 1L)),
                "starved balanced cycle must report A>=1 missing, got " + dump(plan));
    }

    // ---- lossy-feedback-loop: decreasing loop, needs amount+2 A ----

    @Test
    void lossyLoopMinimumFeasible() {
        // stock {A:10} = 8 net + 2 startup → feasible, no missing.
        BenchPatternDetails[] loop = lossyLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 10);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        assertTrue(feasible(plan),
                "lossy cycle with A=amount+2 must be feasible, got " + dump(plan));
    }

    @Test
    void lossyLoopUnboundedFeasible() {
        BenchPatternDetails[] loop = lossyLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        assertTrue(feasible(plan),
                "lossy cycle with unbounded A must be feasible, got " + dump(plan));
    }

    @Test
    void lossyLoopStarvedMissingStartup() {
        // stock {A:8} — only 8 of the 10 needed → missing exactly the 2-A startup state.
        BenchPatternDetails[] loop = lossyLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 8);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        assertFalse(feasible(plan), "starved lossy cycle must be infeasible, got missing=" + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("A", 2L)),
                "starved lossy cycle must report A>=2 missing, got " + dump(plan));
    }
}
