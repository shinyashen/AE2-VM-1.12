package com.ae2vm.vm;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.vm.boundary.Ae2VmReferencePlanner;

import com.ae2vm.compiler.PatternCompiler;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceCapabilityRunner;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceRunResult;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceSupportStatus;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-pattern allocation solver ({@link PatternChoiceRepair}) — pins the fix
 * for the last unsolved engine issue inherited from the original AE2-VM:
 * {@code multi-dag/fibonacci/minimum}, the reference suite's only FALSE_POSITIVE
 * (optimal multi-pattern selection).
 *
 * <p>Two levels of coverage:
 * <ul>
 *   <li><b>End to end</b> through {@link Ae2VmReferencePlanner}: every
 *       multi-dag scenario (both families × three material modes) must report
 *       SUPPORTED — before the solver, fibonacci/minimum reported
 *       {@code missing={X0=4}} against the optimal-leaf stock; the solver's
 *       mixed assignment (splitting X3's crafts 4×A + 1×B, encoded as a
 *       virtual pattern) closes it exactly.</li>
 *   <li><b>Mechanics</b> on a hand-built two-pattern graph: the solver adopts
 *       a strictly-better mix, leaves an already-feasible greedy plan
 *       untouched, and keeps the original plan when no alternative reduces
 *       missing.</li>
 * </ul>
 */
class MultiPatternSolverTest {

    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofSeconds(1), Duration.ofMillis(100));
    private static final ReferencePlanner AE2_VM = new Ae2VmReferencePlanner();

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    private static void assertFamilySupported(String idPrefix) {
        for (ReferenceScenario scenario : ThunderboltReferenceScenarios.all()) {
            if (!scenario.id().startsWith(idPrefix)) {
                continue;
            }
            ReferenceRunResult result = RUNNER.run(AE2_VM, scenario);
            assertEquals(ReferenceSupportStatus.SUPPORTED, result.status(),
                    scenario.id() + " missing=" + (result.plan() == null
                            ? null : result.plan().missing()));
        }
    }

    /**
     * The former FALSE_POSITIVE (shortfall 4 beyond the optimal stock), now
     * fully closed by the allocation solver: the scenario's minimum stock
     * ({@code X0=1, X1=9, X2=11}) is not exactly realizable by any pure
     * per-node pattern choice (pure optimum: shortfall 1) — it requires a
     * MIXED assignment (splitting X3's 5 crafts 4×A + 1×B, encoded as a
     * virtual pattern). The solver finds the split, the engine confirms a
     * zero-missing plan, and all three material modes report SUPPORTED.
     */
    @Test
    void fibonacciMultiDagAllModesSupported() {
        assertFamilySupported("multi-dag/fibonacci");
    }

    /** No-regression guard for the other multi-pattern family. */
    @Test
    void greedyTrapMultiDagAllModesSupported() {
        assertFamilySupported("multi-dag/greedy-trap");
    }

    // ------------------------------------------------------------------
    // Mechanics on a hand-built graph:
    //   Z <- A + B ; A <- 2R ; B <- R | B <- S
    //   greedy (B <- R) over-demands R; the B <- S mix completes exactly.
    // ------------------------------------------------------------------

    private static final class TwoPatternFixture {
        final BenchPatternDetails z = pat("Z", 1, "A", 1L, "B", 1L);
        final BenchPatternDetails a = pat("A", 1, "R", 2L);
        final BenchPatternDetails bR = pat("B", 1, "R", 1L);
        final BenchPatternDetails bS = pat("B", 1, "S", 1L);
        /** Base resolver choices: the greedy B <- R pick. */
        final Map<IAEItemStack, ICraftingPatternDetails> base = new LinkedHashMap<>();
        final Map<IAEItemStack, List<ICraftingPatternDetails>> contended =
                new LinkedHashMap<>();
        final CraftingBytecode request;
        final long stockR;
        final long stockS;

        TwoPatternFixture(long stockR, long stockS) {
            this.stockR = stockR;
            this.stockS = stockS;
            base.put(k("Z"), z);
            base.put(k("A"), a);
            base.put(k("B"), bR);
            contended.put(k("B"), List.of(bR, bS));
            PatternCompiler.clearCache();
            PatternCompiler.compileIfAbsent(z);
            PatternCompiler.compileIfAbsent(a);
            PatternCompiler.compileIfAbsent(bR);
            PatternCompiler.compileIfAbsent(bS);
            request = PatternCompiler.compileRequest(z, 1);
        }

        BenchSimulationState sim() {
            BenchSimulationState s = new BenchSimulationState();
            s.seed("R", stockR);
            s.seed("S", stockS);
            return s;
        }

        /** Drives the production solver exactly like the planner does. */
        VMPlan run() {
            CraftingVM vm = new CraftingVM("multi-pattern-repair", base::get);
            BenchSimulationState stockView = sim();
            PatternChoiceRepair.Pass pass = prefs -> {
                Map<IAEItemStack, ICraftingPatternDetails> view =
                        new LinkedHashMap<>(base);
                view.putAll(prefs);
                vm.setPatternResolver(view::get);
                PatternChoiceRepair.Choices choices = new PatternChoiceRepair.Choices();
                for (Map.Entry<IAEItemStack, ICraftingPatternDetails> e : view.entrySet()) {
                    choices.record(e.getKey(), e.getValue(), contended.get(e.getKey()));
                }
                BenchSimulationState sim = sim();
                return new PatternChoiceRepair.PassResult(
                        vm.execute(request, sim), choices, stockView);
            };
            return PatternChoiceRepair.repair(pass, 8);
        }
    }

    /** The solver adopts the alternative mix that completes the request. */
    @Test
    void repairAdoptsStrictlyBetterMix() {
        TwoPatternFixture fx = new TwoPatternFixture(2, 1);
        VMPlan plan = fx.run();
        assertTrue(plan.getMissingItems().isEmpty(),
                "the B<-S mix must complete the request, missing=" + missingOf(plan));
        assertEquals(2L, plan.getUsedItems().get(k("R")));
        assertEquals(1L, plan.getUsedItems().get(k("S")));
    }

    private static String missingOf(VMPlan plan) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<IAEItemStack, Long> e : plan.getMissingItems().entrySet()) {
            sb.append(((BenchAEItemStack) e.getKey()).id).append('=')
                    .append(e.getValue()).append(',');
        }
        return sb.append('}').toString();
    }

    /** An already-feasible greedy plan is returned untouched (fast path). */
    @Test
    void feasibleGreedyPlanUntouched() {
        TwoPatternFixture fx = new TwoPatternFixture(3, 1);
        VMPlan plan = fx.run();
        assertTrue(plan.getMissingItems().isEmpty());
        // Greedy B <- R mix: no replay may have swapped it (equal-missing plans
        // keep the incumbent mix by design).
        assertEquals(3L, plan.getUsedItems().get(k("R")));
        assertEquals(0L, plan.getUsedItems().get(k("S")));
    }

    /** When no alternative reduces missing, the greedy plan is kept as-is. */
    @Test
    void unhelpfulAlternativeKeepsGreedyPlan() {
        TwoPatternFixture fx = new TwoPatternFixture(2, 0);
        VMPlan plan = fx.run();
        // Greedy: missing 1 R; the only alternative (B <- S) misses 1 S instead —
        // equal missing total, so the incumbent mix must be kept.
        assertEquals(1L, missingTotal(plan), "missing=" + plan.getMissingItems());
        assertEquals(0L, plan.getUsedItems().get(k("S")),
                "the B<-S mix must NOT have been adopted at an equal missing total");
    }

    private static long missingTotal(VMPlan plan) {
        long total = 0;
        for (Map.Entry<IAEItemStack, Long> e : plan.getMissingItems().entrySet()) {
            total += e.getValue() == null ? 0L : e.getValue();
        }
        return total;
    }
}
