package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
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

import java.util.TreeMap;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static com.ae2vm.test.fakes.BenchPatternDetails.withSlotSubstitute;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original FuzzyGroupRegistrationTest — fuzzy / item-substitution group
 * registration. The encoded pattern has item-replacement enabled on a slot
 * (getSubstituteInputs returns [primary, substitute]); when the compiler registers
 * this as a fuzzy group, the VM's missing-check must see substitute stock as
 * satisfying the primary slot (no false "missing gray wool"), while a pattern
 * WITHOUT replacement (exact single input) still rejects substitutes.
 *
 * <p><b>Substitution groups are a CRAFTING-pattern feature.</b> AE2UEL encodes
 * {@code canSubstitute = isCrafting && nbt} (PatternHelper :87) and its CPU
 * consults substitutes in the {@code isCraftable()} branch only, so the
 * compiler registers substitute groups from CRAFTABLE patterns exclusively;
 * a processing pattern's slots stay exact even if it reports substitute
 * inputs.
 */
class FuzzyGroupRegistrationTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static String missingDump(VMPlan plan) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : plan.getMissingItems().keys()) {
            out.put(((BenchAEItemStack) key).id, plan.getMissingItems().get(key));
        }
        return out.toString();
    }

    /** Compiles and runs the given pattern against the stock, no sub-patterns. */
    private static VMPlan run(BenchPatternDetails pattern, BenchSimulationState sim) {
        PatternCompiler.compileIfAbsent(pattern);
        CraftingBytecode req = PatternCompiler.compileRequest(pattern, 100);
        CraftingVM vm = new CraftingVM("fuzzy-bench", k2 -> null);
        return vm.execute(req, sim);
    }

    @Test
    void fuzzyRegisteredGrayAcceptsWhiteStock() {
        BenchPatternDetails pattern = withSlotSubstitute(
                pat("product", 1, "gray_wool", 1L).asCraftable(), new int[]{0}, "white_wool");
        BenchSimulationState sim = new BenchSimulationState().seed("white_wool", 1000L);

        VMPlan plan = run(pattern, sim);
        assertTrue(plan.getMissingItems().isEmpty(),
                "white wool must satisfy the gray-wool fuzzy slot, missing=" + missingDump(plan));
    }

    /**
     * The same substitute table on a PROCESSING pattern registers NO
     * group — its slots are exact (PatternHelper :87), so white stock must
     * not satisfy the slot and the resolver's substitute-variant fallback
     * has nothing to resolve through.
     */
    @Test
    void processingSubstituteTableRegistersNoGroup() {
        BenchPatternDetails pattern = withSlotSubstitute(
                pat("product", 1, "gray_wool", 1L), new int[]{0}, "white_wool");
        PatternCompiler.compileIfAbsent(pattern);

        assertTrue(PatternCompiler.getFuzzyGroup(k("gray_wool")).size() <= 1,
                "a processing pattern's substitute table must not register a fuzzy group, group="
                        + PatternCompiler.getFuzzyGroup(k("gray_wool")));
        BenchSimulationState sim = new BenchSimulationState().seed("white_wool", 1000L);
        VMPlan plan = run(pattern, sim);
        assertFalse(plan.getMissingItems().isEmpty(),
                "processing slots are exact: white wool must NOT satisfy the slot");
    }

    @Test
    void unregisteredExactGrayRejectsWhiteStock() {
        BenchPatternDetails pattern = pat("product", 1, "gray_wool", 1L); // no replacement
        BenchSimulationState sim = new BenchSimulationState().seed("white_wool", 1000L);

        VMPlan plan = run(pattern, sim);
        assertFalse(plan.getMissingItems().isEmpty(),
                "without replacement, white wool must NOT satisfy gray-wool slot");
    }

    /**
     * v1.9.12 REGRESSION (the user's "好多原来有合成样板的东西报缺失"): a gray-wool
     * slot that IS craftable (gray <- black) with PARTIAL gray stock. The compile
     * must still schedule the gray sub-craft (CALL_BY_KEY full need BEFORE EXTRACT),
     * so partial stock + crafted deficit is feasible — NOT reported missing just
     * because the 1-craft capture saw stock cover 1 craft.
     */
    @Test
    void craftableGrayWithPartialStockStillSchedulesSubCraft() {
        BenchPatternDetails grayPattern = pat("gray_wool", 1, "black_wool", 1L);
        Bench.register(grayPattern);
        BenchPatternDetails product = withSlotSubstitute(
                pat("product", 1, "gray_wool", 1L).asCraftable(), new int[]{0}, "white_wool");
        Bench.register(product);

        // compile both so the fuzzy groups register before the request
        PatternCompiler.compileIfAbsent(grayPattern);
        PatternCompiler.compileIfAbsent(product);
        CraftingBytecode req = PatternCompiler.compileRequest(product, 100);
        CraftingVM vm = new CraftingVM("fuzzy-bench", Bench.PATTERNS::get);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("gray_wool", 40L)   // partial: 40 of 100 needed
                .seed("black_wool", 1000L);

        VMPlan plan = vm.execute(req, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(plan.getMissingItems().isEmpty(),
                "craftable gray with partial stock must schedule sub-craft (no false missing), missing="
                        + missingDump(plan));
    }
}
