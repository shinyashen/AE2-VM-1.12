package com.ae2vm.bench;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.DeadCycleGuard;
import com.ae2vm.vm.VMPlan;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static com.ae2vm.bench.Bench.k;
import static com.ae2vm.bench.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance baseline (informational — always green). Exercises the engine's
 * headline cost paths and prints one {@code [perf]} summary line per case to
 * stdout; the numbers feed the offline benchmark report.
 *
 * <p>Methodology: everything runs in ONE JVM (one {@code gradlew test} launch)
 * to remove cross-JVM JIT/state noise. Each case runs {@link #ROUNDS} rounds;
 * every round rebuilds its fixtures from fresh pattern objects, so the
 * compilation caches necessarily miss and EVERY round yields a genuine cold
 * sample (compile + capture + first execution) — cold gets a median too, not a
 * single-shot. Hot samples (repeated execution on the warm VM) are pooled
 * across all rounds; the summary reports the pooled median and the minimum
 * (the most stable estimator under OS scheduling noise).
 *
 * <p>Assertions pin CORRECTNESS only (feasibility invariants), never
 * wall-clock — CI machine speeds vary; the printed numbers are for offline
 * report comparison across tags.
 */
class PerfReportTest {

    /** Independent fixture rebuilds per case (each yields one true cold sample). */
    private static final int ROUNDS = 5;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    // ------------------------------------------------------------------
    // Deep chains
    // ------------------------------------------------------------------

    /** Single-line Fibonacci fixture (X_i ← X_{i-1} + X_{i-2}), request the top. */
    private static final class FibFixture {
        final int levels;
        final Map<IAEItemStack, ICraftingPatternDetails> byOutput = new HashMap<>();

        FibFixture(int levels) {
            this.levels = levels;
            for (int i = 2; i < levels; i++) {
                BenchPatternDetails p = pat("X" + i, 1, "X" + (i - 1), 1L, "X" + (i - 2), 1L);
                byOutput.put(new BenchAEItemStack("X" + i, 1), p);
            }
        }

        BenchSimulationState sim() {
            return new BenchSimulationState(); // stock intentionally empty
        }

        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(
                    byOutput.get(new BenchAEItemStack("X" + (levels - 1), 1)), amount);
        }
    }

    private static void assertOnlyLeavesMissing(VMPlan plan, FibFixture fx) {
        for (IAEItemStack key : plan.getMissingItems().keys()) {
            String id = ((BenchAEItemStack) key).id;
            assertTrue(id.equals("X0") || id.equals("X1"),
                    "only leaves X0/X1 may be missing, got " + id);
        }
        for (int i = 2; i < fx.levels; i++) {
            assertTrue(plan.getPatternTimes().containsKey(
                    fx.byOutput.get(new BenchAEItemStack("X" + i, 1))),
                    "X" + i + " must be synthesized, never missing");
        }
    }

    @Test
    void fib32Times1e9() {
        List<Double> colds = new ArrayList<>();
        List<Double> hot = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            FibFixture fx = new FibFixture(32);
            CraftingVM vm = new CraftingVM("perf-fib32-r" + round, fx.byOutput::get);
            long t0 = System.nanoTime();
            VMPlan cold = vm.execute(fx.request(1_000_000_000L), fx.sim());
            colds.add((System.nanoTime() - t0) / 1_000_000.0);
            assertOnlyLeavesMissing(cold, fx);
            for (int i = 0; i < 20; i++) {
                t0 = System.nanoTime();
                VMPlan p = vm.execute(fx.request(1_000_000_000L), fx.sim());
                hot.add((System.nanoTime() - t0) / 1_000_000.0);
                assertOnlyLeavesMissing(p, fx);
            }
        }
        System.out.printf(Locale.ROOT,
                "[perf] case=fib32x1e9 rounds=%d coldMedian=%.3fms coldMin=%.3fms "
                        + "hotMedian=%.3fms hotMin=%.3fms (%d hot samples)%n",
                ROUNDS, median(colds), min(colds), median(hot), min(hot), hot.size());
    }

    @Test
    void fib24Times1e9() {
        List<Double> colds = new ArrayList<>();
        List<Double> hot = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            FibFixture fx = new FibFixture(24);
            CraftingVM vm = new CraftingVM("perf-fib24-r" + round, fx.byOutput::get);
            long t0 = System.nanoTime();
            VMPlan cold = vm.execute(fx.request(1_000_000_000L), fx.sim());
            colds.add((System.nanoTime() - t0) / 1_000_000.0);
            assertOnlyLeavesMissing(cold, fx);
            for (int i = 0; i < 30; i++) {
                t0 = System.nanoTime();
                VMPlan p = vm.execute(fx.request(1_000_000_000L), fx.sim());
                hot.add((System.nanoTime() - t0) / 1_000_000.0);
            }
        }
        System.out.printf(Locale.ROOT,
                "[perf] case=fib24x1e9empty rounds=%d coldMedian=%.3fms coldMin=%.3fms "
                        + "hotMedian=%.3fms hotMin=%.3fms (%d hot samples)%n",
                ROUNDS, median(colds), min(colds), median(hot), min(hot), hot.size());
    }

    // ------------------------------------------------------------------
    // Full pipeline: greedy + multi-pattern allocation solver + confirm
    // ------------------------------------------------------------------

    @Test
    void multiPatternSolverScenario() {
        ReferenceScenario found = null;
        for (ReferenceScenario s : ThunderboltReferenceScenarios.all()) {
            if ("multi-dag/fibonacci/minimum".equals(s.id())) {
                found = s;
                break;
            }
        }
        final ReferenceScenario target = found;
        Ae2VmReferencePlanner planner = new Ae2VmReferencePlanner();
        // The first plan doubles as the cold reference (first execution for this
        // scenario's patterns in this JVM); the remaining rounds pool into
        // pipeline-hot. Note: if another test class already ran the same
        // scenario earlier in this JVM, the compilation caches are warm and the
        // "cold" reference reads low — treat it as indicative only.
        long t0 = System.nanoTime();
        var cold = planner.plan(target);
        double coldMs = (System.nanoTime() - t0) / 1_000_000.0;
        assertTrue(cold.supported() && cold.missing().isEmpty(),
                "the solver scenario must close exactly, got " + cold.missing());
        List<Double> hot = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < 3; i++) {
                t0 = System.nanoTime();
                var p = planner.plan(target);
                hot.add((System.nanoTime() - t0) / 1_000_000.0);
                assertTrue(p.supported() && p.missing().isEmpty(),
                        "the solver scenario must close exactly, got " + p.missing());
            }
        }
        System.out.printf(Locale.ROOT,
                "[perf] case=solverFib12Minimum cold=%.3fms hotMedian=%.3fms hotMin=%.3fms "
                        + "(%d hot samples)%n",
                coldMs, median(hot), min(hot), hot.size());
    }

    // ------------------------------------------------------------------
    // Conversion-ring scenario through the reference planner (cycle +
    // linear hybrid; mirrors the suite's cycle/conversion-ring family at
    // request scale 1000 instead of the suite's tiny scale)
    // ------------------------------------------------------------------

    @Test
    void conversionRingScenario() {
        ReferenceScenario found = null;
        for (ReferenceScenario s : ThunderboltReferenceScenarios.all()) {
            if ("cycle/conversion-ring/unbounded".equals(s.id())) {
                found = s;
                break;
            }
        }
        final ReferenceScenario target = found;
        Ae2VmReferencePlanner planner = new Ae2VmReferencePlanner();
        long t0 = System.nanoTime();
        var cold = planner.plan(target);
        double coldMs = (System.nanoTime() - t0) / 1_000_000.0;
        assertTrue(cold.supported() && cold.missing().isEmpty(),
                "the conversion-ring scenario must complete, missing=" + cold.missing());
        List<Double> hot = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < 5; i++) {
                t0 = System.nanoTime();
                var p = planner.plan(target);
                hot.add((System.nanoTime() - t0) / 1_000_000.0);
                assertTrue(p.supported() && p.missing().isEmpty(),
                        "repeat plans must stay complete, missing=" + p.missing());
            }
        }
        System.out.printf(Locale.ROOT,
                "[perf] case=conversionRing cold=%.3fms hotMedian=%.3fms hotMin=%.3fms "
                        + "(%d hot samples)%n",
                coldMs, median(hot), min(hot), hot.size());
    }

    // ------------------------------------------------------------------
    // Dead-cycle guard micro-cost
    // ------------------------------------------------------------------

    @Test
    void deadCycleGuardOverhead() {
        // 10-key ring: A_i ← A_{i+1 mod 10}; candidate P0 closes it (no stock).
        Map<IAEItemStack, List<ICraftingPatternDetails>> ring = new HashMap<>();
        BenchPatternDetails[] ringP = new BenchPatternDetails[10];
        for (int i = 0; i < 10; i++) {
            ringP[i] = pat("A" + i, 1, "A" + ((i + 1) % 10), 1L);
            ring.put(k("A" + i), List.of(ringP[i]));
        }
        Function<IAEItemStack, java.util.Collection<ICraftingPatternDetails>> ringLookup = ring::get;
        Function<IAEItemStack, Long> noStock = key -> 0L;
        // Warm + verify the guard actually detects the ring.
        assertTrue(DeadCycleGuard.wouldCloseDeadRing(ringLookup, ringP[0], k("A0"), noStock));

        // Control: 24-node linear chain, no ring.
        Map<IAEItemStack, List<ICraftingPatternDetails>> chain = new HashMap<>();
        BenchPatternDetails[] chainP = new BenchPatternDetails[24];
        for (int i = 0; i < 23; i++) {
            chainP[i] = pat("C" + i, 1, "C" + (i + 1), 1L);
            chain.put(k("C" + i), List.of(chainP[i]));
        }
        chainP[23] = pat("C23", 1, "LEAF", 1L);
        chain.put(k("C23"), List.of(chainP[23]));
        Function<IAEItemStack, java.util.Collection<ICraftingPatternDetails>> chainLookup = chain::get;
        assertFalse(DeadCycleGuard.wouldCloseDeadRing(chainLookup, chainP[0], k("C0"), noStock));

        // Pooled samples over ROUNDS rounds.
        List<Double> ringUs = new ArrayList<>();
        List<Double> chainUs = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < 2000; i++) {
                long t0 = System.nanoTime();
                DeadCycleGuard.wouldCloseDeadRing(ringLookup, ringP[0], k("A0"), noStock);
                ringUs.add((System.nanoTime() - t0) / 1000.0);
            }
            for (int i = 0; i < 2000; i++) {
                long t0 = System.nanoTime();
                DeadCycleGuard.wouldCloseDeadRing(chainLookup, chainP[0], k("C0"), noStock);
                chainUs.add((System.nanoTime() - t0) / 1000.0);
            }
        }
        System.out.printf(Locale.ROOT,
                "[perf] case=deadCycleGuard rounds=%d ringMedian=%.2fus ringMin=%.2fus "
                        + "chain24Median=%.2fus chain24Min=%.2fus (%d samples each)%n",
                ROUNDS, median(ringUs), min(ringUs), median(chainUs), min(chainUs), ringUs.size());
    }

    // ------------------------------------------------------------------
    // Statistics helpers
    // ------------------------------------------------------------------

    private static double median(List<Double> xs) {
        List<Double> copy = new ArrayList<>(xs);
        java.util.Collections.sort(copy);
        return copy.get(copy.size() / 2);
    }

    private static double min(List<Double> xs) {
        double m = Double.MAX_VALUE;
        for (double x : xs) {
            if (x < m) {
                m = x;
            }
        }
        return m;
    }
}
