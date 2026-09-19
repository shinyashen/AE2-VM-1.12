package com.ae2vm.vm.boundary;

import com.moakiee.thunderbolt.core.planner.reference.ReferenceCapabilityRunner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceRunResult;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceSupportStatus;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import com.ae2vm.replay.VirtualCPUCluster;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import net.minecraft.init.Bootstrap;

/**
 * 1.12 port of the reference capability suite: runs the ported VM engine through
 * the full Thunderbolt reference capability suite (11 graph families, 33 material
 * cases, 1s deadline / 100ms grace per case).
 *
 * <p>Prints one {@code [reference-capability]} row per case (same format as
 * Thunderbolt's own suite) so the VM's computation speed and capability surface can
 * be compared against {@code CraftPlannerV2}. Unlike Thunderbolt's suite this does
 * NOT assert {@code SUPPORTED} — the point is measurement; capability claims are
 * pinned by the dedicated unit-test families instead.
 */
class Ae2VmReferenceCapabilitySuiteTest {

    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofSeconds(1), Duration.ofMillis(100));

    /** Accumulates per-scenario outcomes so the trailing summary test can report aggregates. */
    private static final ConcurrentLinkedQueue<ReferenceRunResult> RESULTS =
            new ConcurrentLinkedQueue<>();

    @BeforeAll
    static void bootstrap() {
        // The 1.12 fakes touch the vanilla registry (createItemStack); the
        // runner's worker threads need the registry initialized class-wide.
        Bootstrap.register();
    }

    @TestFactory
    Stream<DynamicTest> referenceCapabilities() {
        List<ReferenceScenario> scenarios = ThunderboltReferenceScenarios.all();
        List<DynamicTest> tests = new ArrayList<>(scenarios.size() + 1);
        for (ReferenceScenario scenario : scenarios) {
            tests.add(DynamicTest.dynamicTest(scenario.id(), () -> runOne(scenario)));
        }
        // Trailing summary: prints aggregate status counts + total elapsed across all 36 cases.
        tests.add(DynamicTest.dynamicTest("summary", () -> printSummary()));
        return tests.stream();
    }

    /**
     * The adjudication batch gate: a plan the planner judged EXECUTABLE
     * (non-simulation — the submit layer would accept the job) must COMPLETE
     * on the faithful CPU; a stall is a live t=0 deadlock the user would hit.
     * Simulation plans are refused before any CPU exists, so their forced-run
     * verdict is informational only. The allowlist below is the closed set of
     * faithful divergences this port accepts, each with its AE2UEL
     * citation; an executable plan stalling anywhere else fails the gate, and
     * an allowlisted scenario that stalls with a DIFFERENT class fails too
     * (the divergence moved — re-adjudicate it).
     */
    private static final Map<String, String> FAITHFUL_STALLS = new TreeMap<>(Map.of(
            "recursion/amplifier/minimum",
            "S2 — A is finalOutput AND self-consumed: a real CPU delivers the "
                    + "returned units (CraftingCPUCluster :265) instead of circulating "
                    + "them, so the run starves once the seeded unit is spent",
            "recursion/amplifier/unbounded",
            "S4 — unbounded stock closes the solve at zero crafts: an idle plan "
                    + "extracts the whole request but never pushes a finalOutput return",
            "cycle/self-growth-cut/unbounded",
            "S4 — idle plan: stock covers the whole request, no pattern is "
                    + "scheduled, so nothing ever arrives",
            "cycle/conversion-ring/minimum",
            "S2 — the closure declines the self-adjacent catalyst to the "
                    + "working-capital machinery, whose conversion-ring guard "
                    + "bills the seed and the job is honestly refused",
            "cycle/conversion-ring/unbounded",
            "S2 — the closure declines the self-adjacent catalyst to the "
                    + "working-capital machinery, whose conversion-ring guard "
                    + "bills the seed and the job is honestly refused"));
    // single-dag/fibonacci/unbounded left the table: the CONSTRUCTED
    // task order (TaskOrdering) is producers-first for this 32-deep DAG and
    // the faithful CPU completes it — the old entry masked a discovery-order
    // stall that the construction obsoleted.

    private static void runOne(ReferenceScenario scenario) {
        // A FRESH planner per scenario: the shared instance's probe fields
        // (lastPlan/lastRuntimeVerdict) are written by the scenario's worker
        // thread — and a NON_COOPERATIVE_TIMEOUT zombie from an earlier case
        // keeps writing its own late results through the shared fields, so a
        // gate read here could pair one case's "executable" with another's
        // stall verdict (the fibonacci/minimum phantom failure).
        Ae2VmReferencePlanner planner = new Ae2VmReferencePlanner();
        var result = RUNNER.run(planner, scenario);
        RESULTS.add(result);
        System.out.println("[reference-capability] engine=ae2vm id=" + scenario.id()
                + " capability=" + scenario.capability()
                + " mode=" + scenario.materialMode()
                + " scale=" + scenario.scale()
                + " status=" + result.status()
                + " missingOverhead=" + result.missingOverhead()
                + " missing=" + (result.plan() == null ? null : result.plan().missing())
                + " executable=" + planner.lastPlanExecutable
                + " runtime=" + planner.lastRuntimeVerdict
                + " elapsedMs=" + String.format("%.3f", result.elapsedNanos() / 1_000_000.0D));
        // The batch gate itself (see FAITHFUL_STALLS). A runner timeout means
        // the case never finished — its half-written probe fields are not
        // gate evidence and the pair-read below would race the (still
        // running) worker's writes.
        boolean timedOut = result.status() == ReferenceSupportStatus.ENGINE_TIMEOUT
                || result.status() == ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT;
        if (!timedOut && planner.lastPlanExecutable && planner.lastRuntimeVerdict != null) {
            VirtualCPUCluster.Verdict v = planner.lastRuntimeVerdict;
            if (v.status == VirtualCPUCluster.Verdict.Status.STALL) {
                String note = FAITHFUL_STALLS.get(scenario.id());
                if (note == null) {
                    throw new AssertionError("executable plan stalled on the faithful CPU: "
                            + scenario.id() + " -> " + v
                            + " (adjudicate: a faithful divergence must be allowlisted "
                            + "with its AE2UEL citation, anything else is a planner bug)", null);
                }
                if (v.stallClass == null || !note.startsWith(v.stallClass)) {
                    throw new AssertionError("scenario " + scenario.id()
                            + " was adjudicated as " + note + " but now stalls as "
                            + v.stallClass + " — the divergence moved, re-adjudicate", null);
                }
            }
        }
        if (result.failure() != null && result.status() == ReferenceSupportStatus.ENGINE_ERROR) {
            result.failure().printStackTrace(System.out);
        }
    }

    private static void printSummary() {
        Map<ReferenceSupportStatus, Integer> counts = new EnumMap<>(ReferenceSupportStatus.class);
        double totalElapsedMs = 0.0D;
        for (var result : RESULTS) {
            counts.merge(result.status(), 1, Integer::sum);
            totalElapsedMs += result.elapsedNanos() / 1_000_000.0D;
        }
        System.out.println("[reference-capability] engine=ae2vm SUMMARY cases=" + RESULTS.size()
                + " supported=" + counts.getOrDefault(ReferenceSupportStatus.SUPPORTED, 0)
                + " falsePositive=" + counts.getOrDefault(ReferenceSupportStatus.FALSE_POSITIVE, 0)
                + " engineError=" + counts.getOrDefault(ReferenceSupportStatus.ENGINE_ERROR, 0)
                + " timeout=" + (counts.getOrDefault(ReferenceSupportStatus.ENGINE_TIMEOUT, 0)
                        + counts.getOrDefault(ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT, 0))
                + " totalElapsedMs=" + String.format("%.1f", totalElapsedMs)
                + " (NOTE: first case includes JVM/class-load warmup)");
    }
}
