package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ae2vm.test.harness.Bench.UNBOUNDED_STOCK;
import static com.ae2vm.test.harness.Bench.feasible;
import static com.ae2vm.test.harness.Bench.infeasibleMatches;
import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static com.ae2vm.test.harness.Bench.patEx;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original RecursionReferenceTest — simulated-scenario tests for the
 * v1.10.3 RECURSION fix: a self-referential recipe (output key == one of its own
 * consumed inputs) must behave like a one-time seed plus an amplifier, never like
 * a per-craft consumption from stock.
 *
 * <ul>
 *   <li><b>recursion/amplifier</b> — {@code A + B -> 2A}: each craft nets +1 A.
 *       A batch of 8 A needs one {@code A=1} seed + 7 B. With no {@code A} stocked
 *       the loop cannot be primed → exactly {@code A=1} is missing.</li>
 *   <li><b>recursion/essence-catalyst</b> — {@code A + B -> A + C}: the catalyst A
 *       is both input and byproduct output, so it circulates forever (essence).
 *       A batch of 8 C needs one {@code A=1} seed + 8 B; without the seed exactly
 *       {@code A=1} is missing.</li>
 * </ul>
 * Fixture scale and the three material modes (MISSING / MINIMUM / UNBOUNDED at
 * 10^12) replicate ThunderboltReferenceScenarios#addRecursionAmplifier and
 * #addRecursionEssenceCatalyst exactly.
 *
 * <p><b>Planner vs runtime (M5 finding).</b> The amplifier's net-growth key A is
 * ALSO the requested output: a real AE2UEL CPU delivers finalOutput returns
 * (CraftingCPUCluster :265) instead of circulating them into inventory, so after
 * the pre-extracted seed is spent the amplifier starves — a faithful S2 the
 * engine's own execution model (network-as-circulation-space) cannot see. The
 * essence-catalyst shape has no such conflict (its circulating key A is a
 * byproduct, never the final output) and faithfully COMPLETES.
 */
class RecursionReferenceTest {

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

    /** Amplifier: consume one A + one B, return two A (net +1 A per craft). */
    private static BenchPatternDetails amplifier() {
        return pat("A", 2, "A", 1L, "B", 1L);
    }

    /** Essence catalyst: consume one A + one B, return A (byproduct) + C. */
    private static BenchPatternDetails essence() {
        return patEx(new String[]{"C", "A"}, new long[]{1, 1}, "A", 1L, "B", 1L);
    }

    // ---- recursion/amplifier: A + B -> 2A, seed = 1 A, B = n-1 ----

    @Test
    void amplifierMinimumFeasible() {
        // stock {A:1, B:7} — the 1-A seed primes the amplifier; 7 B net +7 A → 8 A.
        BenchPatternDetails amp = amplifier();
        Bench.register(amp);
        BenchSimulationState sim = new BenchSimulationState().seed("A", 1).seed("B", 7);
        VMPlan plan = Bench.run(amp, 8, sim);
        // Faithful runtime divergence: A is finalOutput AND self-consumed —
        // delivered units never circulate (AE2UEL :265; see class note)
        CpuLifecycleAssert.stalls(plan, "S2");
        assertTrue(feasible(plan),
                "amplifier with A=1 seed + B=n-1 must be feasible, got " + dump(plan));
    }

    @Test
    void amplifierUnboundedFeasible() {
        BenchPatternDetails amp = amplifier();
        Bench.register(amp);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", UNBOUNDED_STOCK).seed("B", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(amp, 8, sim);
        // Faithful runtime divergence (idle-plan shape): unbounded stock closes
        // the solve at zero crafts — the plan pre-extracts the 8 A but nothing
        // is scheduled to push a finalOutput return, so a real CPU sits on the
        // stock forever (S4; see class note)
        CpuLifecycleAssert.stalls(plan, "S4");
        assertTrue(feasible(plan),
                "amplifier with unbounded A/B must be feasible, got " + dump(plan));
    }

    @Test
    void amplifierStarvedMissingSeed() {
        // stock {B:7} — no A seed → the amplifier cannot be primed; missing exactly A=1.
        BenchPatternDetails amp = amplifier();
        Bench.register(amp);
        BenchSimulationState sim = new BenchSimulationState().seed("B", 7);
        VMPlan plan = Bench.run(amp, 8, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan), "starved amplifier must be infeasible, got missing=" + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("A", 1L)),
                "starved amplifier must report A>=1 missing, got " + dump(plan));
        assertTrue(plan.getMissingItems().get(k("A")) >= 1L);
    }

    // ---- recursion/essence-catalyst: A + B -> A + C, seed = 1 A, B = n ----

    @Test
    void essenceMinimumFeasible() {
        // stock {A:1, B:8} — the 1-A essence seed circulates; 8 B → 8 C.
        BenchPatternDetails ess = essence();
        Bench.register(ess);
        BenchSimulationState sim = new BenchSimulationState().seed("A", 1).seed("B", 8);
        VMPlan plan = Bench.run(ess, 8, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "essence catalyst with A=1 seed + B=n must be feasible, got " + dump(plan));
    }

    @Test
    void essenceUnboundedFeasible() {
        BenchPatternDetails ess = essence();
        Bench.register(ess);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", UNBOUNDED_STOCK).seed("B", UNBOUNDED_STOCK);
        VMPlan plan = Bench.run(ess, 8, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(feasible(plan),
                "essence catalyst with unbounded A/B must be feasible, got " + dump(plan));
    }

    @Test
    void essenceStarvedMissingSeed() {
        // stock {B:8} — no A seed → the essence cannot circulate; missing exactly A=1.
        BenchPatternDetails ess = essence();
        Bench.register(ess);
        BenchSimulationState sim = new BenchSimulationState().seed("B", 8);
        VMPlan plan = Bench.run(ess, 8, sim);
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan), "starved essence catalyst must be infeasible, got missing=" + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("A", 1L)),
                "starved essence catalyst must report A>=1 missing, got " + dump(plan));
        assertTrue(plan.getMissingItems().get(k("A")) >= 1L);
    }
}
