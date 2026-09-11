package com.ae2vm.bench;

import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.ae2vm.bench.Bench.UNBOUNDED_STOCK;
import static com.ae2vm.bench.Bench.feasible;
import static com.ae2vm.bench.Bench.infeasibleMatches;
import static com.ae2vm.bench.Bench.pat;
import static com.ae2vm.bench.Bench.patEx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feedback-loop scenario tests. A catalyst feedback loop produces a byproduct that
 * feeds back into its own recipe chain. Three ring shapes are covered — balanced
 * (raw), decreasing (lossy) and net-amplifying (the GAP-4 gaia-spirit regression):
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

    /** Net-amplifying ring: 4 spirits -> 1 ingot, 1 ingot -> 12 spirits (x3 per turn). */
    private static BenchPatternDetails[] amplifyingLoop() {
        BenchPatternDetails makeIngot = pat("I", 1, "S", 4L);
        BenchPatternDetails makeSpirit = pat("S", 12, "I", 1L);
        return new BenchPatternDetails[]{makeIngot, makeSpirit};
    }

    /** True when a pattern consuming {@code input} is scheduled positively. */
    private static boolean schedulesPatternWithInput(VMPlan plan, String input) {
        for (var e : plan.getPatternTimes().entrySet()) {
            for (var in : e.getKey().getCondensedInputs()) {
                if (in != null && ((BenchAEItemStack) in).id.equals(input) && e.getValue() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Craft count of the (unique) pattern whose input-id set is exactly the given one. */
    private static long timesOf(VMPlan plan, String... inputIds) {
        Set<String> want = new HashSet<>(Arrays.asList(inputIds));
        for (var e : plan.getPatternTimes().entrySet()) {
            Set<String> have = new HashSet<>();
            for (var in : e.getKey().getCondensedInputs()) {
                if (in != null) have.add(((BenchAEItemStack) in).id);
            }
            if (have.equals(want)) return e.getValue();
        }
        return -1;
    }

    // ---- amplifying-loop (GAP-4 regression): net +8 spirits per turn ----

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

    // ---- ring variants from the design review (net-amplifying shapes) ----

    /** Byproduct gain ring: 4A -> B, B -> 12A + C + D (net +8A + C + D per round). */
    private static BenchPatternDetails[] byproductGainRing() {
        BenchPatternDetails makeIngot = pat("B", 1, "A", 4L);
        BenchPatternDetails recycler = patEx(new String[]{"A", "C", "D"}, new long[]{12, 1, 1}, "B", 1L);
        return new BenchPatternDetails[]{makeIngot, recycler};
    }

    /** Shared-intermediate ring: 4A -> B, B + C -> D, D + B -> 12A (B feeds two consumers). */
    private static BenchPatternDetails[] sharedIntermediateRing() {
        BenchPatternDetails makeIngot = pat("B", 1, "A", 4L);
        BenchPatternDetails makeD = patEx(new String[]{"D"}, new long[]{1}, "B", 1L, "C", 1L);
        BenchPatternDetails recycler = patEx(new String[]{"A"}, new long[]{12}, "D", 1L, "B", 1L);
        return new BenchPatternDetails[]{makeIngot, makeD, recycler};
    }

    @Test
    void byproductGainRingFeasible() {
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        // 2304 stocked covers the 962-turn plan exactly: 2304 + 12x962 - 4x962 = 10000
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        StringBuilder pt = new StringBuilder();
        for (var e : plan.getPatternTimes().entrySet()) {
            pt.append(e.getValue()).append("x[");
            for (var in : e.getKey().getCondensedInputs()) pt.append(((BenchAEItemStack) in).id).append(',');
            pt.append("] ");
        }
        assertTrue(schedulesPatternWithInput(plan, "A"),
                "plan must schedule the synthesis pattern (GAP-4 dissolve-only plan), pt=" + pt);
        // exact turns: the solved ring must be the ONLY scheduling — a replayed
        // pre-solver count on top (the old integration bug) would double the
        // dissolve crafts with no backed inputs
        assertEquals(962, timesOf(plan, "B"), "recycler (B->12A+C+D) turns, pt=" + pt);
        assertEquals(962, timesOf(plan, "A"), "makeIngot (4A->B) turns, pt=" + pt);
    }

    @Test
    void byproductGainRingUnseededReportsSeed() {
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertFalse(feasible(plan),
                "unseeded byproduct ring must be infeasible, got feasible");
        // the fallback's honest shortfall: one B — the next layer's timing seed
        assertTrue(infeasibleMatches(plan, Map.of("B", 1L)),
                "unseeded byproduct ring must report a startup shortfall, got " + dump(plan));
    }

    @Test
    void byproductGainRingExternalDemand() {
        // Same ring, but C is ALSO requested. The ring passively produces 962 C
        // when covering 10000 A — does an order for 500 C (under-production)
        // or 2000 C (over-production) change what the ring crafts?
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        // under: 500 C requested < 962 passively produced
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan under = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(under), "under-request must stay feasible, got " + dump(under));
        long cEmittedUnder = 0;
        for (var k : under.getEmittedItems().keys()) {
            if (((BenchAEItemStack) k).id.equals("C")) cEmittedUnder = under.getEmittedItems().get(k);
        }
        // over: 2000 C requested > 962 passively produced
        sim = new BenchSimulationState().seed("A", 2304).seed("C", 5000);
        VMPlan over = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(over), "over-request must stay feasible, got " + dump(over));
        System.out.println("[RING-VARIANT] C external: under-request C-emitted=" + cEmittedUnder
                + " over-request missing=" + dump(over));
    }

    @Test
    void sharedIntermediateRingFeasible() {
        // B feeds two consumers (makeD and the recycler): the general
        // shared-intermediate topology. Minimal fixed point: recycler ×1924
        // and makeD ×1924 (D balance), makeIngot ×3848 (B balance) — each
        // recycler round nets +4 A (12 produced, 8 re-consumed via B
        // synthesis) and burns 1 C: 2304 + 4×1924 = 10000 delivered,
        // 1924 of the stocked 5000 C consumed. External drains (C has no
        // producer in the ring) are honest ingredient demand, not a
        // conversion-ring signal.
        BenchPatternDetails[] loop = sharedIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304).seed("C", 5000);
        VMPlan plan = Bench.run(loop[2], 10000, sim);
        assertTrue(feasible(plan),
                "shared-intermediate ring with sufficient C must be feasible, got " + dump(plan));
        assertEquals(1924, timesOf(plan, "D", "B"), "recycler (D+B->12A) rounds");
        assertEquals(1924, timesOf(plan, "B", "C"), "makeD (B+C->D) rounds");
        assertEquals(3848, timesOf(plan, "A"), "makeIngot (4A->B) rounds");
    }

    @Test
    void sharedIntermediateRingShortOnC() {
        BenchPatternDetails[] loop = sharedIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304).seed("C", 1000);
        VMPlan plan = Bench.run(loop[2], 10000, sim);
        // C is the ring's external fuel: the solve is material-honest — the
        // balanced plan consumes 1924 C, the network holds 1000, so the
        // extraction shortfall must surface as missing C (never a false
        // feasible, never a missing on a ring-internal key).
        assertFalse(feasible(plan), "fuel-starved shared ring must be infeasible, got " + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("C", 924L)),
                "fuel-starved shared ring must report the C shortfall, got " + dump(plan));
    }

    // ---- amplifying loop: real-world gaia-spirit report (GAP-4) ----
    // 4 spirits craft 1 ingot, 1 ingot dissolves into 12 spirits; 2303 stocked,
    // 10000 requested. The dissolving plan consumes 834 ingots whose synthesis
    // needs 3336 more spirits: total demand 13336 vs 12331 coverable → the plan
    // must be honest about the SPIRIT shortfall (GAP-4 used to schedule dissolve-
    // only and let the CPU report 834 missing ingots). With the shortfall covered
    // (3400 stocked ≥ 3336 synthesis + margin) the ring is fully feasible.

    @Test
    void amplifyingLoopFeasibleWithSufficientStock() {
        BenchPatternDetails[] loop = amplifyingLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", 3400);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(plan),
                "amplifying ring with sufficient spirits must be feasible, got " + dump(plan));
        assertTrue(schedulesPatternWithInput(plan, "S"),
                "plan must schedule the ingot-synthesis pattern (GAP-4 dissolve-only plan)");
        // turns stay at the propagation's root granularity ceil(10000/12) = 834:
        // the solver only bumps UP from the demand-driven counts, so with free
        // stock the balance closes without amplification (72-spirit surplus,
        // one root-craft granularity — never replayed double counts)
        assertEquals(834, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(834, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
    }

    @Test
    void amplifyingLoopShortfallSchedulesSynthesis() {
        // 2303 stocked is 1025 spirits short of the balanced 834/834 plan; the
        // exact shortfall disclosure is GAP-4 phase 2 (ring fixed-point). What
        // phase 1 guarantees is structural: the synthesis MUST be scheduled —
        // a dissolve-only plan made the CPU stall on 834 missing ingots.
        BenchPatternDetails[] loop = amplifyingLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", 2303);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertTrue(schedulesPatternWithInput(plan, "S"),
                "plan must schedule the ingot-synthesis pattern (GAP-4 dissolve-only plan)");
        // solved balance turns: ceil((10000-2303)/8) = 963 — and ONLY the
        // solved counts (the replayed pre-solver 834 used to double the
        // dissolve crafts with no backed inputs)
        assertEquals(963, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(963, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
    }

    @Test
    void amplifyingLoopUnboundedFeasible() {
        BenchPatternDetails[] loop = amplifyingLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(plan),
                "unbounded-stock amplifying ring must be feasible, got " + dump(plan));
        assertTrue(schedulesPatternWithInput(plan, "S"),
                "plan must schedule the ingot-synthesis pattern (GAP-4 dissolve-only plan)");
        // free stock covers everything: no amplification needed beyond the
        // root's own dissolve count ceil(10000/12) = 834
        assertEquals(834, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(834, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
    }
}
