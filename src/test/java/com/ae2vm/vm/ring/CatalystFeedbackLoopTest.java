package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.ae2vm.test.harness.Bench.UNBOUNDED_STOCK;
import static com.ae2vm.test.harness.Bench.feasible;
import static com.ae2vm.test.harness.Bench.infeasibleMatches;
import static com.ae2vm.test.harness.Bench.pat;
import static com.ae2vm.test.harness.Bench.patEx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.ae2vm.config.AE2VMConfig;
import net.minecraft.init.Bootstrap;

/**
 * Feedback-loop scenario tests. A catalyst feedback loop produces a byproduct that
 * feeds back into its own recipe chain. Three ring shapes are covered — balanced
 * (raw), decreasing (lossy) and net-amplifying (the gaia-spirit regression):
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
 *
 * <p><b>Planner ⇔ runtime agreement.</b> Folded ring plans are billed so a
 * real AE2UEL CPU can execute them: the delivery is CRAFTED (a job plans against an
 * inventory that ignores the requested item's own stock — CraftingJob.run), and every
 * member key's NET CPU draw plus a priming floor lands in usedItems (the CPU owns
 * only its job-start withdrawal: closed local inventory, CraftingCPUCluster :694;
 * final-output returns deliver instead of circulating, :265). Consequently the
 * capital numbers are grosser than the old net math — the amplifying family needs
 * the FULL input draw up front (5000 spirits for 10000 delivered, not 3300) — and
 * every executable scenario here must, and does, reach COMPLETE on the virtual CPU.
 * The closed-form scenarios (rawLoop/lossyLoop) decline the fold (members merely
 * circulate) and run on the propagation's catalyst-seed machinery.
 */
class CatalystFeedbackLoopTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
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

    @BeforeAll
    static void enableRingFamily() {
        // The ring family is feature-gated off by default; these tests pin its behavior.
        AE2VMConfig.ringSolverEnabled = true;
    }

    @AfterAll
    static void restoreRingFamilyGate() {
        AE2VMConfig.ringSolverEnabled = false;
    }

    @Test
    void rawLoopMinimumFeasible() {
        // stock {A:1, C:8} — the 1-A seed primes the loop; 8 C consumed; no missing.
        BenchPatternDetails[] loop = rawLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 1).seed("C", 8);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        CpuLifecycleAssert.auto(plan);
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
        CpuLifecycleAssert.auto(plan);
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
        CpuLifecycleAssert.auto(plan);
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

    /** Billed job-start withdrawal of one key id (0 when unbilled). */
    private static long usedOf(VMPlan plan, String id) {
        return plan.getUsedItems().get(Bench.k(id));
    }

    // ---- amplifying-loop (gaia regression): net +8 spirits per turn ----

    @Test
    void lossyLoopMinimumFeasible() {
        // stock {A:10} = 8 net + 2 startup → feasible, no missing.
        BenchPatternDetails[] loop = lossyLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 10);
        VMPlan plan = Bench.run(loop[1], 8, sim);
        CpuLifecycleAssert.auto(plan);
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
        CpuLifecycleAssert.auto(plan);
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
        CpuLifecycleAssert.auto(plan);
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
        // Faithful capital: A is the DELIVERED root, so its production never
        // circulates back (:265) — makeIngot's whole draw (4x1250) must be
        // withdrawn at job start, and the solve targets a CRAFTED delivery
        // (12x1250 >= 10000, the root's own stock may not cover it).
        BenchSimulationState sim = new BenchSimulationState().seed("A", 5000);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "byproduct ring with the full input draw stocked must be feasible, got " + dump(plan));
        assertEquals(5000, usedOf(plan, "A"), "the root's gross input draw is the job-start capital");
        assertEquals(0, usedOf(plan, "B"), "B circulates: primed by the recycler's own seed order, no capital");
        // exact turns: the solved ring must be the ONLY scheduling — a replayed
        // pre-solver count on top (the old integration bug) would double the
        // dissolve crafts with no backed inputs
        assertEquals(1250, timesOf(plan, "B"), "recycler (B->12A+C+D) turns");
        assertEquals(1250, timesOf(plan, "A"), "makeIngot (4A->B) turns");
    }

    @Test
    void byproductGainRingUnseededReportsSeed() {
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan),
                "unseeded byproduct ring must be infeasible, got feasible");
        // the faithful disclosure: makeIngot's whole draw (4x1250) is
        // job-start capital the empty network cannot cover
        assertTrue(infeasibleMatches(plan, Map.of("A", 5000L)),
                "unseeded byproduct ring must report its input-draw capital, got " + dump(plan));
    }

    @Test
    void byproductGainRingExternalDemand() {
        // The A request with C ALSO in the network stock: the ring passively
        // produces 1250 C when covering 10000 A — passive byproduct output must
        // not break the A plan regardless of C stock. (Directly ORDERING C is
        // the external-root driver, tested separately below.)
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        // A request unaffected by stocked C (under the passive output)
        BenchSimulationState sim = new BenchSimulationState().seed("A", 5000);
        VMPlan under = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(under);
        assertTrue(feasible(under), "under-request must stay feasible, got " + dump(under));
        long cEmittedUnder = 0;
        for (var k : under.getEmittedItems().keys()) {
            if (((BenchAEItemStack) k).id.equals("C")) cEmittedUnder = under.getEmittedItems().get(k);
        }
        // ... and with C stocked beyond the passive output
        sim = new BenchSimulationState().seed("A", 5000).seed("C", 5000);
        VMPlan over = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(over);
        assertTrue(feasible(over), "over-request must stay feasible, got " + dump(over));
        assertEquals(1250, cEmittedUnder, "the passive byproduct surplus is emitable");
        System.out.println("[RING-VARIANT] C external: under-request C-emitted=" + cEmittedUnder
                + " over-request missing=" + dump(over));
    }

    @Test
    void sharedIntermediateRingFeasible() {
        // B feeds two consumers (makeD and the recycler): the general
        // shared-intermediate topology. Faithful fixed point (delivery
        // crafted, A's stock may not cover it): recycler ×2500 and makeD
        // ×2500 (D balance), makeIngot ×5000 (B balance) — each recycler
        // round nets +4 A delivered and burns 1 C: 12×2500 − 4×5000 = 10000
        // delivered, 2500 of the stocked 5000 C consumed. C is external
        // fuel: its whole draw (2500) is job-start capital like A's.
        BenchPatternDetails[] loop = sharedIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 20000).seed("C", 5000);
        VMPlan plan = Bench.run(loop[2], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "shared-intermediate ring with sufficient capital must be feasible, got " + dump(plan));
        assertEquals(20000, usedOf(plan, "A"), "the root's gross input draw is the job-start capital");
        assertEquals(2500, usedOf(plan, "C"), "the external fuel draw is job-start capital too");
        assertEquals(2500, timesOf(plan, "D", "B"), "recycler (D+B->12A) rounds");
        assertEquals(2500, timesOf(plan, "B", "C"), "makeD (B+C->D) rounds");
        assertEquals(5000, timesOf(plan, "A"), "makeIngot (4A->B) rounds");
    }

    @Test
    void sharedIntermediateRingShortOnC() {
        BenchPatternDetails[] loop = sharedIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 20000).seed("C", 1000);
        VMPlan plan = Bench.run(loop[2], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        // C is the ring's external fuel: the solve is material-honest — the
        // faithful plan consumes 2500 C, the network holds 1000, so the
        // extraction shortfall must surface as missing C (never a false
        // feasible, never a missing on a ring-internal key).
        assertFalse(feasible(plan), "fuel-starved shared ring must be infeasible, got " + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("C", 1500L)),
                "fuel-starved shared ring must report the C shortfall, got " + dump(plan));
    }

    // ---- ordering a ring BYPRODUCT directly (external-root driver) ----
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
        // C is not a member: the recycler is the driver (ceil(5000/1) turns)
        // and the ring self-primes from ONE B of capital — the recycler sits
        // first in the task order, so the priming floor bills that seed; the
        // stocked B also spares the solve exactly one makeIngot craft
        // (4999 = 5000 minus the stocked unit — non-root stock is legitimate
        // round-sparing, billed as withdrawal).
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304).seed("B", 1);
        VMPlan plan = Bench.run(loop[1], 5000, "C", sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "C-rooted request must drive the ring, got " + dump(plan));
        assertEquals(1, usedOf(plan, "B"), "one B of priming capital is the whole job-start bill");
        assertEquals(0, usedOf(plan, "A"), "A circulates: the recycler's returns feed makeIngot");
        assertEquals(5000, timesOf(plan, "B"), "recycler (B->12A+C+D) turns");
        assertEquals(4999, timesOf(plan, "A"), "makeIngot (4A->B) turns, one spared by the stocked B");
    }

    @Test
    void byproductRootedRequestUnseededReportsSeed() {
        BenchPatternDetails[] loop = byproductGainRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 5000, "C", sim);
        CpuLifecycleAssert.auto(plan);
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
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "external consumer demand must be floored into the ring solve, got " + dump(plan));
        assertEquals(20, timesOf(plan, "S"), "makeIngot (4S->I) turns floored at the widget demand");
        assertEquals(10, timesOf(plan, "I", "R"), "makeWidget (2I+R->W) turns");
    }

    // ---- rings routed THROUGH a byproduct-only intermediate key ----
    // X exists only as makeIngotBy's byproduct (no pattern is primarily X):
    // the resolver's byproduct fallback hands the solver the intermediate, and the
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
        // Faithful fixed point: 1250 rounds of both patterns — 12×1250 −
        // 4×1250 = 10000 A crafted for delivery, B and X internally balanced
        // (makeIngotBy's whole 4A draw is job-start capital).
        BenchSimulationState sim = new BenchSimulationState().seed("A", 5000);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "byproduct-intermediate ring must be feasible, got " + dump(plan));
        assertEquals(5000, usedOf(plan, "A"), "the root's gross input draw is the job-start capital");
        assertEquals(1250, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(1250, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    @Test
    void byproductIntermediateRingUnseededReportsSeed() {
        BenchPatternDetails[] loop = byproductIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan),
                "unseeded byproduct-intermediate ring must be infeasible, got feasible");
        // the faithful disclosure: makeIngotBy's whole draw (4x1250) is
        // job-start capital the empty network cannot cover
        assertTrue(infeasibleMatches(plan, Map.of("A", 5000L)),
                "unseeded byproduct-intermediate ring must report its input-draw capital, got " + dump(plan));
    }

    @Test
    void byproductIntermediateRootDrivesRing() {
        // Ordering the intermediate X directly: X is a ring MEMBER whose
        // production the ring re-consumes (the recycler's input). A real CPU
        // DELIVERS final-output returns instead of circulating them (:265),
        // so the recycler's X draw (2212) must be pre-stocked and is billed —
        // honest missing X when the network holds none. The solve itself
        // still uses the stocked A to spare rounds: 2304 covers part of the
        // recycling leg, closing at makeIngot ×7212 / recycler ×2212.
        BenchPatternDetails[] loop = byproductIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304);
        VMPlan plan = Bench.run(loop[0], 5000, "X", sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan),
                "member-root re-consumption without X stock must be infeasible, got " + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("X", 2212L)),
                "the recycler's re-consumed X draw must be disclosed, got " + dump(plan));
        assertEquals(7212, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(2212, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    @Test
    void byproductIntermediateRootDrivesRingWithXStocked() {
        // Same request with the re-consumed draw pre-stocked: the recycler
        // draws its X from the job-start withdrawal, every other flow
        // circulates, and the virtual CPU delivers the full 5000 X.
        BenchPatternDetails[] loop = byproductIntermediateRing();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("A", 2304).seed("X", 2212);
        VMPlan plan = Bench.run(loop[0], 5000, "X", sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "member-root request with the re-consumed draw stocked must be feasible, got " + dump(plan));
        assertEquals(2212, usedOf(plan, "X"), "the re-consumed root draw is job-start capital");
        assertEquals(7212, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(2212, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    @Test
    void byproductIntermediateAmbiguousProducerDegradesGracefully() {
        // X produced by TWO patterns: the byproduct index resolves to nothing (the
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
        BenchSimulationState sim = new BenchSimulationState().seed("A", 5000).seed("Q", 50);
        VMPlan plan = Bench.run(recycler, 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "ambiguous X producer must degrade gracefully, got " + dump(plan));
        assertEquals(1250, timesOf(plan, "A"), "makeIngotBy (4A->B+X) turns");
        assertEquals(1250, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
    }

    // ---- coupled rings (consumers-first solve + net write-back) ----

    @Test
    void coupledRingsShareAmplifiedDemand() {
        // ring1 (A economy: 4A->B, B+X->12A) draws X from ring2 (F economy:
        // 2F->X, X->3F). The consumers-first solve writes ring1's SOLVED X
        // draw (1250) into ring2's floor — without the write-back ring2 would
        // size itself on the propagation's naive count. Faithful fixed point:
        // X 3748 = 2498 (makeF) + 1250 (recycler); F closes exactly on its 2
        // stocked (2 + 3×2498 − 2×3748 = 0); the delivery is crafted
        // (12×1250 − 4×1250 = 10000). The global bill: A's whole draw (5000)
        // plus F's net 2, plus ONE unit of priming capital for whichever
        // circulating key the minimal firing order forces (B or X — the
        // probe's tie, asserted order-independently).
        //
        // under the cluster's strict task-priority scheduling the priming
        // capital bootstraps the X/F economy and the whole fold delivers —
        // the faithful bill is all this shape ever needed.
        BenchPatternDetails makeIngot = pat("B", 1, "A", 4L);
        BenchPatternDetails recycler = patEx(new String[]{"A"}, new long[]{12}, "B", 1L, "X", 1L);
        BenchPatternDetails makeX = pat("X", 1, "F", 2L);
        BenchPatternDetails makeF = pat("F", 3, "X", 1L);
        Bench.register(makeIngot);
        Bench.register(recycler);
        Bench.register(makeX);
        Bench.register(makeF);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", 5000).seed("B", 1).seed("X", 1).seed("F", 2);
        VMPlan plan = Bench.run(recycler, 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "coupled rings must close on the amplified demand, got " + dump(plan));
        // the stocked B spares the solve one makeIngot craft (non-root member
        // stock is round-sparing), so the root draw is 4×1249
        assertEquals(4996, usedOf(plan, "A"), "the root's input draw is the job-start capital");
        assertEquals(2, usedOf(plan, "F"), "F's net draw: 7496 consumed minus 7494 circulating");
        assertTrue(usedOf(plan, "B") + usedOf(plan, "X") >= 1
                        && usedOf(plan, "B") <= 1 && usedOf(plan, "X") <= 1,
                "priming capital stays at unit scale across the circulating keys");
        assertEquals(1249, timesOf(plan, "A"), "makeIngot (4A->B) turns");
        assertEquals(1250, timesOf(plan, "B", "X"), "recycler (B+X->12A) turns");
        // the three seeded units (B, X, F-stock) spare coupled rounds down
        // the write-back chain: the from-below fixed point absorbs them
        assertEquals(3745, timesOf(plan, "F"), "makeX (2F->X) turns");
        assertEquals(2496, timesOf(plan, "X"), "makeF (X->3F) turns");
    }

    // ---- amplifying loop: the real-world gaia-spirit report ----
    // 4 spirits craft 1 ingot, 1 ingot dissolves into 12 spirits. The
    // faithful plan CRAFTS the delivery (a job ignores the requested item's
    // own stock) at the net-gain fixed point: 1250 rounds net 8 spirits each
    // = 10000 delivered, and makeIngot's whole 4×1250 draw is job-start
    // capital — 5000 spirits on hand, not the naive net math's 3300.

    @Test
    void amplifyingLoopFeasibleWithSufficientStock() {
        BenchPatternDetails[] loop = amplifyingLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", 5000);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "amplifying ring with the full input draw stocked must be feasible, got " + dump(plan));
        assertEquals(5000, usedOf(plan, "S"), "the root's gross input draw is the job-start capital");
        assertEquals(0, usedOf(plan, "I"), "I circulates: no priming capital beyond the net draw");
        assertTrue(schedulesPatternWithInput(plan, "S"),
                "plan must schedule the ingot-synthesis pattern ");
        assertEquals(1250, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(1250, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
    }

    @Test
    void amplifyingLoopShortfallSchedulesSynthesis() {
        // 2303 stocked is honest missing capital: the faithful plan draws
        // 5000 spirits at job start, and the shortfall must surface as
        // missing S — never a silently under-capitalized executable plan
        // (the original live gaia stall).
        BenchPatternDetails[] loop = amplifyingLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", 2303);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan), "under-capitalized amplifying ring must be infeasible, got " + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("S", 2697L)),
                "the input-draw shortfall must be disclosed, got " + dump(plan));
        assertTrue(schedulesPatternWithInput(plan, "S"),
                "plan must schedule the ingot-synthesis pattern ");
        assertEquals(1250, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(1250, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
    }

    @Test
    void amplifyingLoopUnboundedFeasible() {
        BenchPatternDetails[] loop = amplifyingLoop();
        for (BenchPatternDetails p : loop) {
            Bench.register(p);
        }
        BenchSimulationState sim = new BenchSimulationState().seed("S", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(loop[1], 10000, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "unbounded-stock amplifying ring must be feasible, got " + dump(plan));
        // the delivery is CRAFTED even when stock could cover it (a job
        // ignores the requested item's own stock) — the idle plan is gone
        assertEquals(1250, timesOf(plan, "S"), "makeIngot (4S->I) turns");
        assertEquals(1250, timesOf(plan, "I"), "makeSpirit (I->12S) turns");
        assertEquals(5000, usedOf(plan, "S"), "the input draw is billed no matter how deep the stock");
    }
}
