package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static com.ae2vm.test.fakes.BenchPatternDetails.withSlotSubstitute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import net.minecraft.init.Bootstrap;

/**
 * Port of the original VideoFuzzyReplacementReproTest — regression test for the
 * 2026-08-09 video bug: a VM plan that AE2's CPU could never execute (some pattern
 * in patternTimes could never be pushed because its input was not extractable),
 * so the CPU stalled at zero progress.
 *
 * <p>ROOT CAUSE (fixed in v1.10.5): the fuzzy / item-replacement group is registered
 * GLOBALLY per key. Two places applied it to demand from EXACT slots (single possible
 * input, no replacement) that can only ever use their primary key at AE2 execution:
 * an exact-slot parent then found no primary in the plan's usedItems and could never
 * be pushed. The fix tracks which demand comes from replacement-ENABLED slots
 * (FUZZY_SLOT marker) and only lets substitute-variant stock satisfy that portion;
 * same-item NBT variants (processing default fuzzy) remain usable by any slot.
 *
 * <p><b>The substitute-slot parents here are CRAFTABLE
 * fakes.</b> AE2UEL's slot substitution is a crafting-pattern feature
 * (PatternHelper :87 {@code canSubstitute = isCrafting && nbt}; CPU consults
 * substitutes in the isCraftable() branch only), so a processing pattern with
 * slot substitutes compiles EXACT and the planner math below would not exist
 * for it. The processing shape is pinned by the twins in
 * VariantSubstituteChainTest / FuzzyGroupRegistrationTest.
 */
class VideoFuzzyReplacementReproTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static VMPlan run(BenchPatternDetails target, long amount, BenchSimulationState sim) {
        for (ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(target, amount);
        CraftingVM vm = new CraftingVM("video-repro", Bench.PATTERNS::get);
        return vm.execute(req, sim);
    }

    private static long timesFor(VMPlan plan, String outputId) {
        for (Map.Entry<ICraftingPatternDetails, Long> e
                : plan.getPatternTimes().entrySet()) {
            if (((BenchAEItemStack) e.getKey().getOutputs()[0]).id.equals(outputId)) {
                return e.getValue();
            }
        }
        return 0;
    }

    private static long used(VMPlan plan, String id) {
        return plan.getUsedItems().get(k(id));
    }

    private static long missing(VMPlan plan, String id) {
        return plan.getMissingItems().get(k(id));
    }

    private static String missingDump(VMPlan plan) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : plan.getMissingItems().keys()) {
            out.put(((BenchAEItemStack) key).id, plan.getMissingItems().get(key));
        }
        return out.toString();
    }

    /**
     * THE VIDEO BUG (craftable child): an EXACT slot and a fuzzy slot both consume the
     * same craftable child (gray). The global fuzzy group used to let the aggregation
     * satisfy the EXACT slot with the substitute (white), so no gray was crafted and the
     * exact pattern could never be pushed at AE2 execution → the CPU stalled at zero
     * progress. The plan must craft gray for the exact slot and use white only for the
     * fuzzy slot.
     */
    @Test
    void exactSlotForcesCraftOfPrimaryEvenWhenSubstituteStocked() {
        BenchPatternDetails target = pat("target", 1, "exact_comp", 1L, "fuzzy_comp", 1L);
        BenchPatternDetails exactComp = pat("exact_comp", 1, "gray", 1L);
        BenchPatternDetails fuzzyComp = withSlotSubstitute(
                pat("fuzzy_comp", 1, "gray", 1L).asCraftable(), new int[]{0}, "white");
        BenchPatternDetails gray = pat("gray", 1, "black", 1L);
        Bench.register(target);
        Bench.register(exactComp);
        Bench.register(fuzzyComp);
        Bench.register(gray);

        for (long amount : new long[]{1L, 2L, 10L, 100L, 1000L}) {
            // fresh sandbox per request (the underlying stock is read-only)
            VMPlan plan = run(target, amount,
                    new BenchSimulationState().seed("white", 1000L).seed("black", 100_000L));
            assertTrue(plan.getMissingItems().isEmpty(),
                    "amount=" + amount + ": plan must be feasible, missing=" + missingDump(plan));
            // The EXACT slot's whole demand must be crafted as the primary gray…
            assertEquals(amount, timesFor(plan, "gray"),
                    "amount=" + amount + ": gray must be crafted for the EXACT slot (no stall)");
            // …the FUZZY slot's demand uses the substitute white.
            assertEquals(amount, used(plan, "white"),
                    "amount=" + amount + ": white must satisfy exactly the fuzzy slot");
            // The gray craft needs black.
            assertEquals(amount, used(plan, "black"),
                    "amount=" + amount + ": black feeds the crafted gray");
        }
    }

    /**
     * Leaf variant: the child has NO pattern. The exact slot must report the primary as
     * MISSING (job refused, not silently feasible → stall). The fuzzy slot still uses the
     * substitute.
     */
    @Test
    void exactLeafSlotReportsMissingNotStall() {
        BenchPatternDetails target = pat("target", 1, "exact_comp", 1L, "fuzzy_comp", 1L);
        BenchPatternDetails exactComp = pat("exact_comp", 1, "gray", 1L);
        BenchPatternDetails fuzzyComp = withSlotSubstitute(
                pat("fuzzy_comp", 1, "gray", 1L).asCraftable(), new int[]{0}, "white");
        Bench.register(target);
        Bench.register(exactComp);
        Bench.register(fuzzyComp);
        // NOTE: gray has NO pattern here (leaf)

        for (long amount : new long[]{1L, 10L, 100L}) {
            // fresh sandbox per request
            VMPlan plan = run(target, amount, new BenchSimulationState().seed("white", 1000L));
            // The exact slot cannot be satisfied (gray not stocked, not craftable) — the
            // plan must report the missing primary so the job is refused, NOT stall.
            assertEquals(amount, missing(plan, "gray"),
                    "amount=" + amount + ": exact slot must report missing gray");
        }
    }

    /**
     * Baseline: a single FUZZY leaf slot (gray↔white, gray NOT craftable) is satisfied
     * by the stocked substitute white — no missing.
     */
    @Test
    void singleFuzzyLeafUsesSubstitute() {
        BenchPatternDetails target = withSlotSubstitute(
                pat("target", 1, "gray", 1L).asCraftable(), new int[]{0}, "white");
        Bench.register(target);
        BenchSimulationState stock = new BenchSimulationState().seed("white", 1000L);

        VMPlan plan = run(target, 100, stock);
        assertTrue(plan.getMissingItems().isEmpty(), "missing=" + missingDump(plan));
        assertEquals(100, used(plan, "white"));
    }

    /**
     * Baseline: a single FUZZY craftable slot (gray craftable ← black, white=1 stocked)
     * uses the substitute for the 1 unit it covers, and crafts gray for the deficit —
     * the v1.9.13 behavior must be preserved.
     */
    @Test
    void singleFuzzyCraftableStillUsesSubstituteForDeficit() {
        BenchPatternDetails target = withSlotSubstitute(
                pat("target", 1, "gray", 1L).asCraftable(), new int[]{0}, "white");
        BenchPatternDetails gray = pat("gray", 1, "black", 1L);
        Bench.register(target);
        Bench.register(gray);
        BenchSimulationState stock = new BenchSimulationState()
                .seed("white", 1L)
                .seed("black", 100_000L);

        VMPlan plan = run(target, 100, stock);
        assertTrue(plan.getMissingItems().isEmpty(), "missing=" + missingDump(plan));
        assertEquals(1, used(plan, "white"));
        assertEquals(99, timesFor(plan, "gray"));
        assertEquals(99, used(plan, "black"));
    }

    /**
     * Baseline: multiple FUZZY parents sharing one finite substitute pool — the pool must
     * be consumed once (shared), not once per parent, and the deficit crafted as primary.
     */
    @Test
    void sharedSubstitutePoolConsumedOnceAcrossFuzzyParents() {
        BenchPatternDetails target = pat("target", 1, "compA", 1L, "compB", 1L);
        BenchPatternDetails compA = withSlotSubstitute(
                pat("compA", 1, "gray", 1L, "leaf", 1L).asCraftable(), new int[]{0}, "white");
        BenchPatternDetails compB = withSlotSubstitute(
                pat("compB", 1, "gray", 1L, "leaf", 1L).asCraftable(), new int[]{0}, "white");
        BenchPatternDetails gray = pat("gray", 1, "black", 1L);
        Bench.register(target);
        Bench.register(compA);
        Bench.register(compB);
        Bench.register(gray);

        BenchSimulationState stock = new BenchSimulationState()
                .seed("white", 1L)      // one substitute unit shared by BOTH parents
                .seed("black", 100_000L)
                .seed("leaf", 100_000L);

        long amount = 100;
        VMPlan plan = run(target, amount, stock);
        assertTrue(plan.getMissingItems().isEmpty(), "missing=" + missingDump(plan));
        assertEquals(1, used(plan, "white"),
                "the single substitute unit must be consumed exactly once (shared)");
        assertEquals(2 * amount - 1, timesFor(plan, "gray"),
                "the 2*amount−1 deficit must be crafted as gray");
        assertEquals(2 * amount - 1, used(plan, "black"));
        assertFalse(plan.getPatternTimes().isEmpty());
    }
}
