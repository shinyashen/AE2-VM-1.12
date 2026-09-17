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
import static com.ae2vm.test.fakes.BenchPatternDetails.withSlotSubstitute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Long/multi replacement-chain guards (1.12 port of the upstream
 * LongMultiReplacementChainTest + VariantCraftableSubstituteTest intent):
 * across REPEATED requests on the reused per-grid VM — with stock levels
 * changing underneath — a craftable intermediate must NEVER be reported as
 * missing ("有概率把已经有样板的物品报成缺少"), and a fuzzy-slot substitute that
 * has no stock but IS craftable must be scheduled instead of stalling the plan.
 *
 * <p><b>Slot substitution is a CRAFTING-pattern feature.</b>
 * AE2UEL encodes {@code canSubstitute = isCrafting && nbt} (PatternHelper :87)
 * and its CPU consults substitutes inside the {@code isCraftable()} branch
 * only (CraftingCPUCluster.executeCrafting) — processing patterns extract
 * their exact condensed keys. The substitute scenarios therefore run on
 * CRAFTABLE fakes and the faithful runtime fills their slots through the
 * craftable-branch hook: planner and runtime AGREE (COMPLETE — the former
 * "substitute-only fill stalls at S2" divergence is fixed at the source).
 * The processing twins pin the narrowed compiler: a substitute enabled on a
 * processing pattern's slot is ignored; the EXACT input is scheduled and, if
 * unstocked, disclosed missing.
 */
class VariantSubstituteChainTest {

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

    // ------------------------------------------------------------------
    // 1. A fuzzy-slot substitute with no stock but its own pattern must be
    //    crafted to satisfy the slot (fixed via the resolver's substitution-group
    //    fallback — same layer the upstream fix targets).
    // ------------------------------------------------------------------

    @Test
    void craftableSubstituteSatisfiesFuzzySlot() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        // comp needs gray (exact), the slot accepts white as a substitute;
        // NEITHER is stocked, but white has its own pattern (white ← raw).
        BenchPatternDetails comp = withSlotSubstitute(
                pat("comp", 1, "gray", 1L).asCraftable(), new int[]{0}, "white");
        BenchPatternDetails white = pat("white", 1, "raw", 1L);
        view.put(k("comp"), comp);
        view.put(k("white"), white);
        PatternCompiler.compileIfAbsent(comp);
        PatternCompiler.compileIfAbsent(white);
        // Resolver with the substitution-group fallback (mirrors the
        // production resolve()): the exact key resolves to nothing, but a
        // CRAFTABLE substitute variant's pattern is returned so the VM
        // schedules its sub-chain.
        CraftingVM vm = new CraftingVM("variant-substitute", key -> {
            ICraftingPatternDetails direct = view.get(key);
            if (direct != null) {
                return direct;
            }
            for (IAEItemStack variant : PatternCompiler.getFuzzyGroup(key)) {
                ICraftingPatternDetails vp = view.get(variant);
                if (vp != null) {
                    return vp;
                }
            }
            return null;
        });
        BenchSimulationState stocked = new BenchSimulationState();
        stocked.seed("raw", 5);

        VMPlan plan = vm.execute(PatternCompiler.compileRequest(comp, 1), stocked);
        // Planner and runtime AGREE: the craftable branch fills the slot with
        // the crafted white (see class note — the former S2 divergence).
        assertTrue(plan.getMissingItems().isEmpty(),
                "the craftable white substitute must satisfy the fuzzy slot, missing="
                        + plan.getMissingItems());
        assertEquals(1L, plan.getUsedItems().get(k("raw")),
                "the white sub-chain must actually run");
        CpuLifecycleAssert.complete(plan, k("comp"), 1L, comp.slotAlternates());
    }

    /**
     * The processing twin: the SAME substitute-enabled slot on a PROCESSING
     * pattern compiles EXACT — no white sub-chain is scheduled and the
     * unstocked exact input is disclosed missing (a real CPU would refuse the
     * job instead of deadlocking on a fill it can never consume).
     */
    @Test
    void processingSubstituteSlotStaysExact() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        BenchPatternDetails comp = withSlotSubstitute(pat("comp", 1, "gray", 1L),
                new int[]{0}, "white");
        BenchPatternDetails white = pat("white", 1, "raw", 1L);
        view.put(k("comp"), comp);
        view.put(k("white"), white);
        PatternCompiler.compileIfAbsent(comp);
        PatternCompiler.compileIfAbsent(white);
        CraftingVM vm = new CraftingVM("variant-substitute-processing", view::get);
        BenchSimulationState stocked = new BenchSimulationState();
        stocked.seed("raw", 5);

        VMPlan plan = vm.execute(PatternCompiler.compileRequest(comp, 1), stocked);
        assertTrue(plan.isSimulation(),
                "the exact gray input is unstocked: missing=" + plan.getMissingItems());
        assertTrue(hasMissing(plan, "gray"),
                "the exact processing input must be disclosed, missing=" + plan.getMissingItems());
        assertFalse(hasMissing(plan, "white"),
                "the substitute must NOT be demanded for a processing slot, missing="
                        + plan.getMissingItems());
        assertEquals(0L, plan.getUsedItems().get(k("raw")),
                "no white sub-chain may be scheduled for a processing slot");
        CpuLifecycleAssert.auto(plan); // simulation plan: informational forced run
    }

    // ------------------------------------------------------------------
    // 2. Long replacement chain, reused VM, stock levels changing between
    //    requests: craftable intermediates are never missing.
    // ------------------------------------------------------------------

    @Test
    void longReplacementChainAcrossStockChanges() {
        PatternCompiler.clearCache();
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        // top ← m1 ← m2 ← m3 ← m4(slot r1|r2 + m5) ← m5 ← m6 ← leaf1 + leaf2
        BenchPatternDetails top = pat("top", 1, "m1", 1L);
        BenchPatternDetails m1 = pat("m1", 1, "m2", 1L);
        BenchPatternDetails m2 = pat("m2", 1, "m3", 1L);
        BenchPatternDetails m3 = pat("m3", 1, "m4", 1L);
        BenchPatternDetails m4 = withSlotSubstitute(
                pat("m4", 1, "r1", 1L, "m5", 1L).asCraftable(), new int[]{0}, "r2");
        BenchPatternDetails m5 = pat("m5", 1, "m6", 1L);
        BenchPatternDetails m6 = pat("m6", 1, "leaf1", 1L, "leaf2", 1L);
        view.put(k("top"), top);
        view.put(k("m1"), m1);
        view.put(k("m2"), m2);
        view.put(k("m3"), m3);
        view.put(k("m4"), m4);
        view.put(k("m5"), m5);
        view.put(k("m6"), m6);
        PatternCompiler.compileIfAbsent(top);
        PatternCompiler.compileIfAbsent(m1);
        PatternCompiler.compileIfAbsent(m2);
        PatternCompiler.compileIfAbsent(m3);
        PatternCompiler.compileIfAbsent(m4);
        PatternCompiler.compileIfAbsent(m5);
        PatternCompiler.compileIfAbsent(m6);
        CraftingVM vm = new CraftingVM("variant-chain", view::get);
        CraftingBytecode request = PatternCompiler.compileRequest(top, 2);

        // Request 1: leaves stocked → completes end to end.
        BenchSimulationState full = new BenchSimulationState();
        full.seed("leaf1", 10);
        full.seed("leaf2", 10);
        full.seed("r2", 10);
        VMPlan p1 = vm.execute(request, full);
        assertTrue(p1.getMissingItems().isEmpty(), "p1 must complete, missing=" + p1.getMissingItems());
        CpuLifecycleAssert.complete(p1, k("top"), 2L, m4.slotAlternates());

        // Request 2: stock drained → ONLY leaves (and the unstocked exact r1
        // slot input) may be missing; every craftable intermediate must appear
        // in patternTimes instead.
        VMPlan p2 = vm.execute(request, new BenchSimulationState());
        CpuLifecycleAssert.auto(p2);
        for (IAEItemStack key : p2.getMissingItems().keys()) {
            String id = ((BenchAEItemStack) key).id;
            assertFalse(id.equals("top") || id.startsWith("m"),
                    "craftable intermediate " + id + " must not be missing, missing="
                            + p2.getMissingItems());
        }

        // Request 3: stock restored → the SAME reused VM must re-execute the
        // shortfall capture (shortfallRetryable) instead of
        // replaying the stale one. Fresh stock view: the previous pass's
        // simulated inserts persist in the old view and would satisfy the
        // chain without crafting.
        BenchSimulationState restored = new BenchSimulationState();
        restored.seed("leaf1", 10);
        restored.seed("leaf2", 10);
        restored.seed("r2", 10);
        VMPlan p3 = vm.execute(request, restored);
        assertTrue(p3.getMissingItems().isEmpty(), "p3 must complete, missing=" + p3.getMissingItems());
        assertEquals(2L, p3.getUsedItems().get(k("leaf1")), "leaf1 consumed for 2 crafts");
        CpuLifecycleAssert.complete(p3, k("top"), 2L, m4.slotAlternates());
    }
}
