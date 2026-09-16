package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMCounter;
import com.ae2vm.vm.VMPlan;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan-memoization guards: the stock re-validation
 * ({@link VMPlan#planMatchesStock}) and the pattern-set-version
 * invalidation ({@link CraftingVM#invalidateCaches}) that together allow
 * same-request replays to return instantly without ever serving a stale
 * result.
 */
class PlanReuseGuardTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    private static VMPlan plan(Map<String, Long> used, Map<String, Long> missing) {
        VMCounter usedC = new VMCounter();
        used.forEach((id, v) -> usedC.add(k(id), v));
        VMCounter missingC = new VMCounter();
        missing.forEach((id, v) -> missingC.add(k(id), v));
        return new VMPlan(k("req"), 1, 0, true, usedC, missingC,
                new VMCounter(), new HashMap<>());
    }

    private static Function<IAEItemStack, Long> stock(Map<String, Long> map) {
        return key -> map.getOrDefault(((BenchAEItemStack) key).id, 0L);
    }

    // ------------------------------------------------------------------
    // planMatchesStock
    // ------------------------------------------------------------------

    @Test
    void feasiblePlanMatchesWhileStockCoversUsed() {
        VMPlan p = plan(Map.of("iron", 5L), Map.of());
        assertTrue(p.planMatchesStock(stock(Map.of("iron", 5L))));
        assertTrue(p.planMatchesStock(stock(Map.of("iron", 500L))),
                "extra stock beyond the plan is fine");
        assertFalse(p.planMatchesStock(stock(Map.of("iron", 4L))),
                "stock below the planned consumption invalidates the plan");
    }

    @Test
    void shortfallPlanStaysUntilTheShortfallIsToppedUp() {
        VMPlan p = plan(Map.of("iron", 5L), Map.of("powder", 3L));
        assertTrue(p.planMatchesStock(stock(Map.of("iron", 5L))),
                "still no powder → same shortfall, plan stands");
        assertTrue(p.planMatchesStock(stock(Map.of("iron", 5L, "powder", 2L))),
                "partial powder (< shortfall) does not change the conclusion");
        assertFalse(p.planMatchesStock(stock(Map.of("iron", 5L, "powder", 3L))),
                "powder topped up to the shortfall → world moved, re-run");
        assertFalse(p.planMatchesStock(stock(Map.of("iron", 5L, "powder", 9L))));
    }

    // ------------------------------------------------------------------
    // Pattern-set version gating
    // ------------------------------------------------------------------

    @Test
    void removedSubPatternReportsMissingAfterInvalidation() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "a", 1L);
        BenchPatternDetails a = pat("a", 1, "b", 1L);
        view.put(k("top"), top);
        view.put(k("a"), a);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(a);
        CraftingVM vm = new CraftingVM("plan-reuse", view::get);
        BenchSimulationState stocked = new BenchSimulationState();
        stocked.seed("b", 5);

        VMPlan p1 = vm.execute(PatternCompiler.compileRequest(top, 1), stocked);
        CpuLifecycleAssert.auto(p1);
        assertTrue(p1.getMissingItems().isEmpty(), "p1 must complete, missing=" + p1.getMissingItems());

        // The player breaks the A pattern: the pattern set changes, the version
        // bumps, and the VM's caches are dropped (the mixin's fingerprint hook
        // drives exactly this on the live grid).
        view.remove(k("a"));
        PatternCompiler.bumpPatternSetVersion();
        assertTrue(vm.cachesStale());
        vm.invalidateCaches();
        assertFalse(vm.cachesStale());

        // Fresh stock view: p1's simulated output insert (a) persists in the
        // old view and would satisfy the request without crafting.
        BenchSimulationState restocked = new BenchSimulationState();
        restocked.seed("b", 5);
        VMPlan p2 = vm.execute(PatternCompiler.compileRequest(top, 1), restocked);
        CpuLifecycleAssert.auto(p2);
        boolean missedA = false;
        for (IAEItemStack key : p2.getMissingItems().keys()) {
            if (((BenchAEItemStack) key).id.equals("a")) {
                missedA = true;
            }
        }
        assertTrue(missedA,
                "after removing A's pattern the request must miss a, missing=" + p2.getMissingItems());
    }
}
