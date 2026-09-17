package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ae2vm.test.fakes.BenchPatternDetails.key;
import static com.ae2vm.test.fakes.BenchPatternDetails.processing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ported semantics tests (original AE2-VM benchmark families).
 *
 * Key id convention: key(n) -> "A"+n chars, so id 0="A", 1="B", 2="C", 3="D",
 * 4="E". The first output of a pattern is its primary output.
 */
class VmSemanticsTest {

    @BeforeAll
    static void bootstrap() {
        // Forge guards Items/Blocks behind Bootstrap; vanilla registration is
        // self-contained and safe to run inside a plain JVM.
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

    /**
     * Marker/catalyst: X + A -> B + X (product first, marker as byproduct) —
     * the executor hands the marker back every firing, so the whole order
     * needs exactly ONE circulating X seed.
     */
    @Test
    void markerPatternNeedsOneSeed() {
        BenchPatternDetails marker = processing(
                new long[][]{{0, 1}, {1, 1}}, new long[][]{{2, 1}, {0, 1}});
        Bench.register(marker);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", 1)   // X seed (id 0)
                .seed("B", 10); // ingredient A (id 1)
        VMPlan plan = Bench.run(marker, 5, sim);
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        CpuLifecycleAssert.auto(plan);
        assertFalse(plan.isSimulation(), "marker order must be feasible with 1 seed: missing=" + dump(plan));
        assertEquals(5L, plan.getPatternTimes().get(marker));
        assertEquals(5L, plan.getUsedItems().get(key(1)));
    }

    /** Amplifier A + B -> 2A: craft count driven by NET growth, seeded from stock. */
    @Test
    void recursionAmplifierUsesNetGrowth() {
        BenchPatternDetails amp = processing(
                new long[][]{{0, 1}, {1, 1}}, new long[][]{{0, 2}});
        Bench.register(amp);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", 1)
                .seed("B", 3);
        VMPlan plan = Bench.run(amp, 4, sim);
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        // Faithful runtime divergence: A is finalOutput AND self-consumed — a real
        // CPU delivers finalOutput returns (CraftingCPUCluster :265) instead of
        // circulating them, so the run starves once the seed is spent
        CpuLifecycleAssert.stalls(plan, "S2");
        assertFalse(plan.isSimulation(), "amplifier must be feasible: missing=" + dump(plan));
        // request 4, stocked seed 1, net gain 1 per craft -> 3 crafts
        assertEquals(3L, plan.getPatternTimes().get(amp));

        // no A stocked -> the loop cannot be primed -> exactly A=1 missing
        // (the original RecursionReferenceTest "恰报缺 1 种子" semantics)
        BenchSimulationState starved = new BenchSimulationState().seed("B", 3);
        VMPlan starvedPlan = Bench.run(amp, 4, starved);
        CpuLifecycleAssert.auto(starvedPlan);
        assertTrue(starvedPlan.isSimulation(), "starved amplifier must report its seed");
        assertEquals(1L, starvedPlan.getMissingItems().get(key(0)));
    }

    /** Catalyst feedback loop A -> 2B; 2B + C -> E + D: closes with C as working capital. */
    @Test
    void catalystFeedbackLoopIsFeasibleWithWorkingCapital() {
        BenchPatternDetails p1 = processing(new long[][]{{0, 1}}, new long[][]{{1, 2}});
        BenchPatternDetails p2 = processing(
                new long[][]{{1, 2}, {2, 1}}, new long[][]{{3, 1}, {4, 1}});
        BenchPatternDetails p3 = processing(new long[][]{{4, 1}}, new long[][]{{0, 1}});
        Bench.register(p1);
        Bench.register(p2);
        Bench.register(p3);
        // Without the A seed the loop cannot prime: exactly A=1 is missing
        // (original CatalystFeedbackLoopTest "starved" semantics).
        BenchSimulationState starved = new BenchSimulationState().seed("C", 1);
        VMPlan starvedPlan = Bench.run(p2, 1, starved);
        CpuLifecycleAssert.auto(starvedPlan);
        assertTrue(starvedPlan.isSimulation(), "starved loop must report its seed");
        assertEquals(1L, starvedPlan.getMissingItems().get(key(0)));

        // With A and C stocked the loop must close completely.
        BenchSimulationState seeded = new BenchSimulationState().seed("A", 1).seed("C", 1);
        VMPlan okPlan = Bench.run(p2, 1, seeded);
        CpuLifecycleAssert.auto(okPlan);
        assertTrue(okPlan.getMissingItems().isEmpty(), "seeded loop must close: missing=" + dump(okPlan));
        assertEquals(1L, okPlan.getPatternTimes().get(p2));
        // one craft of A -> 2B exactly covers the 2B that p2 consumes
        assertEquals(1L, okPlan.getPatternTimes().get(p1));
    }

    // The durability-tool closed-form test was removed with the DURABILITY_TOOL
    // feature: the amortization's premise — damage-fuzzy re-consumption
    // of the worn return — does not hold on the AE2UEL CPU (exact processing
    // extraction, CraftingCPUCluster :694). Degrading tools now compile as
    // ordinary gross inputs.

    /**
     * Stock-aware SUB-craft: a stocked child item is consumed from the network
     * before crafting the deficit. (Root requests keep native ignore(output)
     * semantics; only children get the stock-aware aggregation.)
     */
    @Test
    void stockAwareSubCraftUsesNetworkStock() {
        BenchPatternDetails producer = processing(
                new long[][]{{1, 1}}, new long[][]{{0, 4}});
        BenchPatternDetails consumer = processing(
                new long[][]{{0, 1}, {1, 1}}, new long[][]{{2, 1}});
        Bench.register(producer);
        Bench.register(consumer);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("A", 6)    // stocked X
                .seed("B", 12)   // raw input A
                .seed("B", 2);    // producer raw input Y (id 1) - exactly the 2 crafts needed
        VMPlan plan = Bench.run(consumer, 12, sim);
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        CpuLifecycleAssert.auto(plan);
        assertFalse(plan.isSimulation(), "stock-aware: missing=" + dump(plan));
        // demand 12 X, 6 stocked -> deficit 6 -> 2 crafts of 4
        assertEquals(2L, plan.getPatternTimes().get(producer));
    }

    /** Pure conversion ring 9B -> A; 1A -> 9B from nothing is infeasible. */
    @Test
    void conversionRingFromNothingIsInfeasible() {
        BenchPatternDetails toA = processing(new long[][]{{1, 9}}, new long[][]{{0, 1}});
        BenchPatternDetails toB = processing(new long[][]{{0, 1}}, new long[][]{{1, 9}});
        Bench.register(toA);
        Bench.register(toB);
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(toB, 9, sim);
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        CpuLifecycleAssert.auto(plan);
        assertTrue(plan.isSimulation(), "seedless value-conserving ring must report missing");
    }

    /**
     * Raw input without stock and without a pattern reports the exact missing
     * amount. Root demands keep ignore(output) semantics: no stock-aware
     * shrinking of the root itself (same as native AE2).
     */
    @Test
    void stockShortfallFeedsMissing() {
        BenchPatternDetails producer = processing(
                new long[][]{{1, 1}}, new long[][]{{0, 4}});
        Bench.register(producer);
        BenchSimulationState sim = new BenchSimulationState();
        VMPlan plan = Bench.run(producer, 8, sim);
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        CpuLifecycleAssert.auto(plan);
        assertTrue(plan.isSimulation());
        // 8 output needed -> 2 crafts (ceil(8/4)) -> 2 of input B (id 1) missing
        assertEquals(2L, plan.getMissingItems().get(key(1)));
    }
}
