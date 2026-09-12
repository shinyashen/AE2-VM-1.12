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
        // The A request with C ALSO in the network stock: the ring passively
        // produces 962 C when covering 10000 A — passive byproduct output must
        // not break the A plan regardless of C stock. (Directly ORDERING C is
        // the phase-2c external-root driver, tested separately below.)
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        // A request unaffected by stocked C (under the passive 962 output)
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan under = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(under), "under-request must stay feasible, got " + dump(under));
        long cEmittedUnder = 0;
        for (var k : under.getEmittedItems().keys()) {
            if (((BenchAEItemStack) k).id.equals("C")) cEmittedUnder = under.getEmittedItems().get(k);
        }
        // ... and with C stocked beyond the passive output
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

    // ---- phase 2c: ordering a ring BYPRODUCT directly (external-root driver) ----
    // AE2 indexes patterns by every output slot, so a C request routes to the
    // recycler even though C is only its byproduct. The request must key off
    // C's per-craft output (5000 C = 5000 rounds, not 5000/12) and DRIVE the
    // ring: each recycler round nets +8 A via B synthesis.

    @Test
    void byproductRootedRequestDrivesRing() {
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan plan = Bench.run(loop[1], 5000, "C", sim);
        assertTrue(feasible(plan),
                "C-rooted request must drive the ring, got " + dump(plan));
        assertEquals(5000, timesOf(plan, "B"), "recycler (B->12A+C+D) turns");
        assertEquals(5000, timesOf(plan, "A"), "makeIngot (4A->B) turns");
    }

    @Test
    void byproductRootedRequestUnseededReportsSeed() {
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 5000, "C", sim);
        assertFalse(feasible(plan),
                "unseeded C-rooted request must be infeasible, got feasible");
        assertTrue(infeasibleMatches(plan, Map.of("B", 1L)),
                "unseeded C-rooted request must report the startup seed, got " + dump(plan));
    }

    @Test
    void externalConsumerFloorFeedsRingSolve() {
        // A ring key drawn on by an OUTSIDE consumer (widget takes 2 I per
        // craft): the from-below solve must floor the member at the external
        // demand — without the floor the solve stays at zero, the ring keys
        // are stripped with an idle plan, and the widget's 20 I come back as
        // a false missing. With the floor the counts close at makeIngot ×20
        // (the fold itself is declined — makeSpirit would idle and S would
        // just drain — so the propagation schedules I and covers the demand).
        BenchPatternDetails makeIngot = pat("I", 1, "S", 4L);
        BenchPatternDetails makeSpirit = pat("S", 12, "I", 1L);
        BenchPatternDetails makeWidget = pat("W", 1, "I", 2L, "R", 1L);
        Bench.register(makeIngot);
        Bench.register(makeSpirit);
        Bench.register(makeWidget);
        BenchSimulationState sim = new BenchSimulationState().seed("S", 100).seed("R", 100);
        VMPlan plan = Bench.run(makeWidget, 10, sim);
        assertTrue(feasible(plan),
                "external consumer demand must be floored into the ring solve, got " + dump(plan));
        assertEquals(20, timesOf(plan, "S"), "makeIngot (4S->I) turns floored at the widget demand");
        assertEquals(10, timesOf(plan, "I", "R"), "makeWidget (2I+R->W) turns");
    }

    // ---- phase 7d: rings routed THROUGH a byproduct-only intermediate key ----
    // X exists only as makeIngotBy's byproduct (no pattern is primarily X):
    // the resolver's T4 fallback hands the solver the intermediate, and the
    // pattern-level variables count makeIngotBy ONCE per round (key-level
    // variables would double-count it under B and X).

    /** Byproduct-intermediate ring: 4A -> B+X, B+X -> 12A. */
    private static BenchPatternDetails[] byproductIntermediateRing() {
        BenchPatternDetails makeIngotBy = patEx(new String[]{"B", "X"}, new long[]{1, 1}, "A", 4L);
        BenchPatternDetails recycler = patEx(new String[]{"A"}, new long[]{12}, "B", 1L, "X", 1L);
        return new BenchPatternDetails[]{makeIngotBy, recycler};
    }

    @Test
    void byproductIntermediateRingFeasible() {
        BenchPatternDetails[] loop = byproductIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        // Balance at the minimal fixed point: 962 rounds of both patterns —
        // 2304 + 12×962 − 4×962 = 10000 delivered, B and X internally balanced.
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertTrue(feasible(plan),
                "byproduct-intermediate ring must be feasible, got " + dump(plan));
        assertEquals(962, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(962, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    @Test
    void byproductIntermediateRingUnseededReportsSeed() {
        BenchPatternDetails[] loop = byproductIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        assertFalse(feasible(plan),
                "unseeded byproduct-intermediate ring must be infeasible, got feasible");
        // the cheapest priming order starts at the recycler: one B and one X
        assertTrue(infeasibleMatches(plan, Map.of("B", 1L, "X", 1L)),
                "unseeded byproduct-intermediate ring must report its startup seed, got " + dump(plan));
    }

    @Test
    void byproductIntermediateRootDrivesRing() {
        // Ordering the intermediate X directly: X is a ring MEMBER under T4,
        // so the request is a member-root delivery — the solve closes at
        // makeIngot ×7212 / recycler ×2212 (A stock fully spent: 2304 +
        // 12×2212 − 4×7212 = 0) delivering exactly 5000 X.
        BenchPatternDetails[] loop = byproductIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan plan = Bench.run(loop[0], 5000, "X", sim);
        assertTrue(feasible(plan),
                "X-rooted request must drive the ring, got " + dump(plan));
        assertEquals(7212, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(2212, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    @Test
    void byproductIntermediateAmbiguousProducerDegradesGracefully() {
        // X produced by TWO patterns: the T4 index resolves to nothing (the
        // multi-pattern choice domain), so X degrades to external-byproduct
        // semantics — the ring solves without X as a member, and X being
        // internally balanced (produced = consumed by the recycler) keeps the
        // plan feasible at the same 962 turns. Deterministic, no crash.
        BenchPatternDetails makeIngotBy = patEx(new String[]{"B", "X"}, new long[]{1, 1}, "A", 4L);
        BenchPatternDetails makeIngotC = patEx(new String[]{"C", "X"}, new long[]{1, 1}, "A", 4L, "Q", 1L);
        BenchPatternDetails recycler = patEx(new String[]{"A"}, new long[]{12}, "B", 1L, "X", 1L);
        Bench.register(makeIngotBy);
        Bench.register(makeIngotC);
        Bench.register(recycler);
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304).seed("Q", 50);
        VMPlan plan = Bench.run(recycler, 10000, sim);
        assertTrue(feasible(plan),
                "ambiguous X producer must degrade gracefully, got " + dump(plan));
        assertEquals(962, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(962, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    // ---- phase 7e: coupled rings (consumers-first solve + net write-back) ----

    @Test
    void coupledRingsShareAmplifiedDemand() {
        // ring1 (A economy: 4A->B, B+X->12A) draws X from ring2 (F economy:
        // 2F->X, X->3F). The consumers-first solve writes ring1's SOLVED X
        // draw (962) into ring2's floor — without the write-back ring2 would
        // size itself on the propagation's naive 834 and the plan would miss
        // X×128. Balance: X 2884 = 1922 (makeF) + 962 (recycler); F closes
        // exactly on its 2 stocked (2 + 3×1922 − 2×2884 = 0).
        BenchPatternDetails makeIngot = pat("B", 1, "A", 4L);
        BenchPatternDetails recycler = patEx(new String[]{"A"}, new long[]{12}, "B", 1L, "X", 1L);
        BenchPatternDetails makeX = pat("X", 1, "F", 2L);
        BenchPatternDetails makeF = pat("F", 3, "X", 1L);
        Bench.register(makeIngot);
        Bench.register(recycler);
        Bench.register(makeX);
        Bench.register(makeF);
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304).seed("F", 2);
        VMPlan plan = Bench.run(recycler, 10000, sim);
        assertTrue(feasible(plan),
                "coupled rings must close on the amplified demand, got " + dump(plan));
        assertEquals(962, timesOf(plan, "A"), "makeIngot (4A->B) turns");
        assertEquals(962, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
        assertEquals(2884, timesOf(plan, "F"), "makeX (2F->X) turns");
        assertEquals(1922, timesOf(plan, "X"), "makeF (X->3F) turns");
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
        // material-minimal turns: the fixed point is solved FROM BELOW, so
        // ample stock no longer inherits the propagation's ceil granularity —
        // ceil((10000-3400)/8) = 825 exactly
        assertEquals(825, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(825, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
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
        // the from-below solve stays at zero when stock covers the whole
        // request: crafting nothing IS the material-minimal plan, and the
        // idle ring plan strips the propagation's unbacked dissolve counts
        assertTrue(plan.getPatternTimes().isEmpty(),
                "stock-covered request must not schedule ring crafts");
    }
}
