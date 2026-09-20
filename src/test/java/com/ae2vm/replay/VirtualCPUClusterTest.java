package com.ae2vm.replay;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.trace.VirtualPatternDetails;
import com.ae2vm.vm.VMCounter;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

/**
 * Acceptance: the virtual CPU reproduces live AE2 semantics — delivery
 * completes on a sound plan (instant and lagged providers); plan-level
 * emitted surplus is NOT registered on the CPU (waitingFor is populated per
 * dispatch, :730-734 — the old gross-emitable S1 shape now completes, and
 * the plan-level EMITABLE-COVER invariant flags bad reports instead);
 * input starvation is S2 with the blocked input, and the extraction gap is
 * S3.
 */
class VirtualCPUClusterTest {

    private static final String IRON = "minecraft:iron_ingot";
    private static final String STONE = "minecraft:stone";

    private static BenchAEItemStack k(String id, long size) {
        return new BenchAEItemStack(id, size);
    }

    private static ICraftingPatternDetails pattern() {
        return new VirtualPatternDetails(
                new IAEItemStack[]{k(IRON, 3)},
                new IAEItemStack[]{k(STONE, 1)},
                false, true);
    }

    private static Map<ICraftingPatternDetails, Long> times(long n) {
        Map<ICraftingPatternDetails, Long> m = new LinkedHashMap<>();
        m.put(pattern(), n);
        return m;
    }

    private static VMPlan plan(long usedIron, long emittedRedstone) {
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), usedIron);
        VMCounter emitted = new VMCounter();
        if (emittedRedstone > 0) {
            emitted.add(k("minecraft:redstone", 1), emittedRedstone);
        }
        return new VMPlan(k(STONE, 1), 1000, 0, false, used, new VMCounter(), emitted, times(1000));
    }

    @Test
    void soundPlanCompletesInstant() {
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan(3000, 0), k(STONE, 1), 1000).run(10_000, 0);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
        assertEquals(1000, v.delivered);
    }

    @Test
    void soundPlanCompletesWithLaggedProvider() {
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan(3000, 0), k(STONE, 1), 1000).run(100_000, 3);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
    }

    @Test
    void planSurplusIsNotRegistered_cpuCompletes() {
        // live gaia bug shape, re-adjudicated: the plan reports 500 redstone
        // emitable that no task produces. The OLD setJob bridge registered it
        // in the CPU's waitingFor — delivery done, CPU busy forever (S1).
        // The bridge is gone (waitingFor is dispatch-populated, :730-734),
        // so a wrong report can no longer poison the CPU; the plan-level
        // EMITABLE-COVER invariant is the guard that flags such reports.
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan(3000, 500), k(STONE, 1), 1000).run(10_000, 0);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
        assertEquals(1000, v.delivered, "delivery must complete");
    }

    @Test
    void starvedInputIsS2WithBlockedInput() {
        // only 2000 iron for a plan that needs 3000: the task can never push
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan(2000, 0), k(STONE, 1), 1000).run(10_000, 0);
        assertEquals(VirtualCPUCluster.Verdict.Status.STALL, v.status, v.toString());
        assertEquals("S2", v.stallClass, v.toString());
        assertTrue(v.evidence.stream().anyMatch(s -> s.contains(IRON)), v.toString());
    }

    @Test
    void extractionGapIsS3() {
        List<String> gaps = VirtualCPUCluster.extractionGap(
                plan(3000, 0), snapshotWith(2000));
        assertEquals(1, gaps.size());
        assertTrue(gaps.get(0).startsWith("S3"));
    }

    private static VirtualCPUCluster.Stock snapshotWith(long iron) {
        VirtualCPUCluster.Stock s = new VirtualCPUCluster.Stock();
        s.add(k(IRON, 1), iron);
        return s;
    }

    // ------------------------------------------------------------------
    // Stock matching axes (§5.10): the exact-key reads are findPrecise-
    // faithful (real AEItemStack.isSameType compares the SharedStack — FULL
    // identity), while the craftable slot pool matches at ITEM level
    // (findFuzzy IGNORE_ALL). A per-identity craftable pool once
    // under-counted variant stock and made the gate falsely reject plans
    // the real CPU completes. (Bench items intern per maxDamage and the
    // project models damage as item identity — so the variant axis here is
    // NBT, which shares the Item instance.)

    @Test
    void processingSlotRejectsNbtVariantStock() {
        // a processing pattern's slot is findPrecise-exact (:445-453): an
        // NBT variant of the input in the CPU inventory can NEVER feed it
        BenchAEItemStack encoded = k("bonded_k", 1);
        BenchAEItemStack variant = k("bonded_k", 1).withNbt("B");
        ICraftingPatternDetails p = new VirtualPatternDetails(
                new IAEItemStack[]{encoded}, new IAEItemStack[]{k(STONE, 1)}, false, false);
        VirtualCPUCluster.Verdict v = runSingle(p, encoded, variant, 1000);
        assertEquals(VirtualCPUCluster.Verdict.Status.STALL, v.status, v.toString());
        assertEquals("S2", v.stallClass, v.toString());
    }

    @Test
    void craftableSlotPoolsNbtVariantsOfItsInput() {
        // craftable, no substitute list: findFuzzy(input, IGNORE_ALL) (:499)
        // matches every variant of the input's own item
        BenchAEItemStack encoded = new BenchAEItemStack("tf_alloy", 0, 0, 1);
        BenchAEItemStack variant = new BenchAEItemStack("tf_alloy", 0, 0, 1).withNbt("B");
        ICraftingPatternDetails p = new VirtualPatternDetails(
                new IAEItemStack[]{encoded}, new IAEItemStack[]{k(STONE, 1)}, true, false);
        VirtualCPUCluster.Verdict v = runSingle(p, encoded, variant, 1000);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
    }

    @Test
    void craftableSlotPoolsVariantsOfListedAlternates() {
        // a listed alternate's variant feeds the slot too (:466
        // findFuzzy(substitute, IGNORE_ALL))
        BenchAEItemStack encoded = new BenchAEItemStack("gray_dye", 0, 0, 1);
        BenchPatternDetails p = BenchPatternDetails.withSlotSubstitute(
                BenchPatternDetails.custom(
                        new IAEItemStack[]{encoded},
                        new IAEItemStack[]{k(STONE, 1)}).asCraftable(),
                new int[]{0}, "white");
        BenchAEItemStack whiteVariant = new BenchAEItemStack("white", 0, 0, 1).withNbt("D");
        VirtualCPUCluster.Verdict v = runSingle(p, encoded, whiteVariant, 1000);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
    }

    /** One input line of {@code perCraft}/craft, the whole supply in {@code stocked}. */
    private static VirtualCPUCluster.Verdict runSingle(ICraftingPatternDetails p,
                                                       IAEItemStack input, IAEItemStack stocked,
                                                       long crafts) {
        Map<ICraftingPatternDetails, Long> times = new LinkedHashMap<>();
        times.put(p, crafts);
        VMCounter used = new VMCounter();
        used.add(stocked, crafts * input.getStackSize());
        VMPlan plan = new VMPlan(k(STONE, 1), 1000, 0, false, used, new VMCounter(),
                new VMCounter(), times);
        return new VirtualCPUCluster(plan, k(STONE, 1), 1000).run(10_000, 0);
    }
}
