package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Partial-stock chain guard (port of the upstream IntermediateCraftableMissingTest
 * intent): across partial-leaf-stock chains of several levels, a CRAFTABLE
 * intermediate must never appear in missingItems — the shortfall belongs to the
 * leaf (and only in the un-covered amount). Drives a REUSED VM (the per-grid
 * instance) and re-checks determinism, the shape upstream reports as
 * state-dependent ("有概率").
 */
class PartialStockChainTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    private static boolean hasMissing(VMPlan plan, String id) {
        for (IAEItemStack key : plan.getMissingItems().keys()) {
            if (((BenchAEItemStack) key).id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static void assertOnlyLeafMissing(VMPlan plan, long expectedLeafShortfall) {
        assertTrue(hasMissing(plan, "leaf"),
                "only the leaf may be missing, missing=" + plan.getMissingItems());
        assertEquals(expectedLeafShortfall, plan.getMissingItems().get(k("leaf")),
                "leaf shortfall mismatch");
        int missingKeys = 0;
        for (IAEItemStack key : plan.getMissingItems().keys()) {
            missingKeys++;
        }
        assertEquals(1, missingKeys, "no other keys may be missing");
        assertFalse(hasMissing(plan, "A"), "craftable A must not be missing");
        assertFalse(hasMissing(plan, "B"), "craftable B must not be missing");
    }

    @Test
    void partialLeafStockReportsOnlyLeafShortfall() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "A", 5L);
        BenchPatternDetails a = pat("A", 1, "B", 5L);
        BenchPatternDetails b = pat("B", 1, "leaf", 5L);
        view.put(k("top"), top);
        view.put(k("A"), a);
        view.put(k("B"), b);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(a);
        PatternCompiler.compileIfAbsent(b);
        CraftingVM vm = new CraftingVM("partial-chain", view::get);
        BenchSimulationState sim = new BenchSimulationState();
        sim.seed("leaf", 100);

        VMPlan plan = vm.execute(PatternCompiler.compileRequest(top, 2), sim);
        CpuLifecycleAssert.auto(plan);
        // top×2 → A×10 → B×50 → leaf×250; stock 100 → shortfall 150.
        assertOnlyLeafMissing(plan, 150);
        assertEquals(100L, plan.getUsedItems().get(k("leaf")));
    }

    @Test
    void sameStockSecondRunDeterministic() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "A", 5L);
        BenchPatternDetails a = pat("A", 1, "B", 5L);
        BenchPatternDetails b = pat("B", 1, "leaf", 5L);
        view.put(k("top"), top);
        view.put(k("A"), a);
        view.put(k("B"), b);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(a);
        PatternCompiler.compileIfAbsent(b);
        CraftingVM vm = new CraftingVM("partial-chain", view::get);
        CraftingBytecode request = PatternCompiler.compileRequest(top, 2);

        BenchSimulationState s1 = new BenchSimulationState();
        s1.seed("leaf", 100);
        VMPlan p1 = vm.execute(request, s1);
        CpuLifecycleAssert.auto(p1);
        BenchSimulationState s2 = new BenchSimulationState();
        s2.seed("leaf", 100);
        VMPlan p2 = vm.execute(request, s2);
        CpuLifecycleAssert.auto(p2);

        assertEquals(p1.getMissingItems().get(k("leaf")), p2.getMissingItems().get(k("leaf")),
                "same stock must produce the same shortfall");
        assertEquals(p1.getUsedItems().get(k("leaf")), p2.getUsedItems().get(k("leaf")));
    }

    @Test
    void multiLevelPartialStockChain() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "A", 1L);
        BenchPatternDetails a = pat("A", 1, "B", 1L);
        BenchPatternDetails b = pat("B", 1, "C", 1L);
        BenchPatternDetails c = pat("C", 1, "leaf", 5L);
        view.put(k("top"), top);
        view.put(k("A"), a);
        view.put(k("B"), b);
        view.put(k("C"), c);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(a);
        PatternCompiler.compileIfAbsent(b);
        PatternCompiler.compileIfAbsent(c);
        CraftingVM vm = new CraftingVM("partial-chain-multi", view::get);
        BenchSimulationState sim = new BenchSimulationState();
        sim.seed("leaf", 3);

        VMPlan plan = vm.execute(PatternCompiler.compileRequest(top, 2), sim);
        CpuLifecycleAssert.auto(plan);
        // top×2 → … → C×2 → leaf×10; stock 3 → shortfall 7.
        assertTrue(hasMissing(plan, "leaf"), "leaf shortfall must be reported");
        assertEquals(7L, plan.getMissingItems().get(k("leaf")));
        for (String mid : new String[]{"top", "A", "B", "C"}) {
            assertTrue(!hasMissing(plan, mid), "craftable " + mid + " must not be missing");
        }
    }
}
