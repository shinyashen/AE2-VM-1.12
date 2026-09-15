package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pattern-lifecycle invalidation guard (1.12 port of the upstream
 * JITCachePatternUpdateTest + PatternRefreshReuseTest intent): the per-grid VM
 * and its JIT bundle cache persist ACROSS requests, so adding / replacing /
 * removing a pattern between requests must never serve a stale cached result.
 *
 * <p>The failure mode upstream (1.20.1 report): an intermediate key captured as
 * a capture-time missing leaf stays "missing" after the player writes its
 * pattern, because the parent bundle is replayed without re-resolving — until
 * a game restart. On 1.12 the (key, pattern bytecode)-qualified bundle keys and
 * the transitive choice re-validation must prevent this; these tests pin it.
 */
class PatternLifecycleTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    /** Mutable-view VM: the map IS the resolver (changes take effect live). */
    private static CraftingVM vmOver(Map<IAEItemStack, ICraftingPatternDetails> view) {
        return new CraftingVM("pattern-lifecycle", view::get);
    }

    private static boolean hasMissing(VMPlan plan, String id) {
        for (IAEItemStack key : plan.getMissingItems().keys()) {
            if (((BenchAEItemStack) key).id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 1. Intermediate pattern added after the chain was captured
    // ------------------------------------------------------------------

    @Test
    void newlyAddedIntermediatePatternRecognizedByReusedVm() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "inter", 1L);
        BenchPatternDetails inter2 = pat("inter2", 1, "leaf", 1L);
        view.put(k("top"), top);
        view.put(k("inter2"), inter2);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(inter2);
        CraftingVM vm = vmOver(view);

        // Request 1: inter has NO pattern → the only leaf-missing is inter.
        BenchSimulationState stocked = new BenchSimulationState();
        stocked.seed("leaf", 100);
        VMPlan p1 = vm.execute(PatternCompiler.compileRequest(top, 1), stocked);
        CpuLifecycleAssert.auto(p1);
        assertTrue(hasMissing(p1, "inter"), "p1 must miss inter, missing=" + p1.getMissingItems());
        assertEquals(0L, p1.getUsedItems().get(k("leaf")));

        // The player writes the inter pattern; same reused VM, same request.
        BenchPatternDetails inter = pat("inter", 1, "inter2", 1L);
        view.put(k("inter"), inter);
        PatternCompiler.compileIfAbsent(inter);

        VMPlan p2 = vm.execute(PatternCompiler.compileRequest(top, 1), stocked);
        CpuLifecycleAssert.auto(p2);
        assertTrue(p2.getMissingItems().isEmpty(),
                "after inter's pattern is added the chain must complete, missing="
                        + p2.getMissingItems());
        assertEquals(1L, p2.getUsedItems().get(k("leaf")), "leaf must be consumed once");
    }

    // ------------------------------------------------------------------
    // 2. A key that failed before its pattern existed must re-resolve
    // ------------------------------------------------------------------

    @Test
    void negativeResultInvalidatedByNewPattern() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        CraftingVM vm = vmOver(view);
        BenchSimulationState stocked = new BenchSimulationState();
        stocked.seed("leaf", 1000);

        // Request inter directly while it has no pattern → missing.
        BenchPatternDetails inter = pat("inter", 1, "inter2", 1L);
        VMPlan p1 = vm.execute(PatternCompiler.compileRequest(inter, 1), stocked);
        CpuLifecycleAssert.auto(p1);
        assertTrue(hasMissing(p1, "inter2"), "p1 must miss inter2, missing=" + p1.getMissingItems());

        // Write the rest of the chain, then re-request inter on the SAME VM.
        BenchPatternDetails inter2 = pat("inter2", 1, "leaf", 1L);
        view.put(k("inter"), inter);
        view.put(k("inter2"), inter2);
        PatternCompiler.compileIfAbsent(inter);
        PatternCompiler.compileIfAbsent(inter2);

        VMPlan p2 = vm.execute(PatternCompiler.compileRequest(inter, 1), stocked);
        CpuLifecycleAssert.auto(p2);
        assertTrue(p2.getMissingItems().isEmpty(),
                "inter must be craftable once its chain exists, missing=" + p2.getMissingItems());
        assertEquals(1L, p2.getUsedItems().get(k("leaf")));
    }

    // ------------------------------------------------------------------
    // 3. Replacing a key's pattern must switch to the new bytecode
    // ------------------------------------------------------------------

    @Test
    void replacedPatternOnSameKeyUsesNewInputs() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "a", 1L);
        BenchPatternDetails aFromB = pat("a", 1, "b", 1L);
        view.put(k("top"), top);
        view.put(k("a"), aFromB);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(aFromB);
        CraftingVM vm = vmOver(view);
        BenchSimulationState noB = new BenchSimulationState(); // b not stocked

        VMPlan p1 = vm.execute(PatternCompiler.compileRequest(top, 1), noB);
        CpuLifecycleAssert.auto(p1);
        assertTrue(hasMissing(p1, "b"), "p1 must miss b, missing=" + p1.getMissingItems());

        // Replace A's pattern with an input-C variant (C is stocked).
        BenchPatternDetails aFromC = pat("a", 1, "c", 1L);
        view.put(k("a"), aFromC);
        PatternCompiler.compileIfAbsent(aFromC);
        BenchSimulationState stockedC = new BenchSimulationState();
        stockedC.seed("c", 5);

        VMPlan p2 = vm.execute(PatternCompiler.compileRequest(top, 1), stockedC);
        CpuLifecycleAssert.auto(p2);
        assertTrue(p2.getMissingItems().isEmpty(),
                "the replacement pattern must complete the request, missing=" + p2.getMissingItems());
        assertEquals(1L, p2.getUsedItems().get(k("c")), "must consume the NEW pattern's input c");
        assertEquals(0L, p2.getUsedItems().get(k("b")));
    }

    // ------------------------------------------------------------------
    // 4. Byproduct keys sharing ids with chain inputs must not cross-wire
    // ------------------------------------------------------------------

    @Test
    void byproductAlongChainDoesNotInterfere() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails top = pat("top", 1, "inter", 1L);
        // inter emits inter + a SCRAP byproduct; SCRAP is ALSO widget's input.
        BenchAEItemStack interOut = new BenchAEItemStack("inter", 1);
        interOut.setStackSize(1);
        BenchAEItemStack scrapOut = new BenchAEItemStack("scrap", 1);
        scrapOut.setStackSize(1);
        BenchPatternDetails inter = BenchPatternDetails.custom(
                new IAEItemStack[]{new BenchAEItemStack("ore", 1).setStackSize(1)},
                new IAEItemStack[]{interOut, scrapOut});
        BenchPatternDetails widget = pat("widget", 1, "scrap", 1L);
        view.put(k("top"), top);
        view.put(k("inter"), inter);
        view.put(k("widget"), widget);
        // AE2 registers multi-output patterns under their byproduct outputs too
        // (getCraftingFor(scrap) finds the inter pattern).
        view.put(k("scrap"), inter);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(inter);
        PatternCompiler.compileIfAbsent(widget);
        CraftingVM vm = vmOver(view);
        BenchSimulationState stocked = new BenchSimulationState();
        stocked.seed("ore", 10);

        VMPlan topPlan = vm.execute(PatternCompiler.compileRequest(top, 1), stocked);
        CpuLifecycleAssert.auto(topPlan);
        assertTrue(topPlan.getMissingItems().isEmpty(),
                "top chain must complete, missing=" + topPlan.getMissingItems());
        assertEquals(1L, topPlan.getUsedItems().get(k("ore")));

        // Widget request on a FRESH stock view: it must run its own scrap
        // sub-chain (through the byproduct-registered inter pattern) instead of
        // sharing top's capture.
        BenchSimulationState fresh = new BenchSimulationState();
        fresh.seed("ore", 10);
        VMPlan widgetPlan = vm.execute(PatternCompiler.compileRequest(widget, 1), fresh);
        CpuLifecycleAssert.auto(widgetPlan);
        assertTrue(widgetPlan.getMissingItems().isEmpty(),
                "widget (via scrap) must complete, missing=" + widgetPlan.getMissingItems());
        assertEquals(1L, widgetPlan.getUsedItems().get(k("ore")),
                "widget must consume its OWN ore, not share top's capture");
    }
}
