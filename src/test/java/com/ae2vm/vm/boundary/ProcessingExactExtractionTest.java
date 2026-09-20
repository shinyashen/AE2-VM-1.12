package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static com.ae2vm.test.fakes.BenchPatternDetails.custom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import net.minecraft.init.Bootstrap;

/**
 * Processing patterns extract findPrecise-EXACT (2026-09-20 reversal,
 * CLOSURE-DESIGN §5.9): AE2UEL {@code CraftingCPUCluster.canCraft} :445-453
 * SIMULATE-extracts every condensed input of a {@code !isCraftable()} pattern
 * through {@code MECraftingInventory} → {@code findPrecise} — the FULL stack
 * identity (item + damage + NBT), so NO variant of the encoded input can
 * satisfy the slot. The old "processing default fuzzy" model here (mirroring
 * AE2 native's PLANNING-side {@code getValidItemTemplates} →
 * {@code findFuzzyTemplates}) produced plans the real CPU starves on — the
 * live "sometimes the CPU stalls" half. The family pools now serve only
 * craftable consumers ({@code canCraft} :454-516 fuzzy-extracts).
 *
 * <p>The damage axis is item IDENTITY in 1.12 (Thermal materials encode the
 * material there) and the NBT axis is identity too at the CPU — neither
 * substitutes for a processing slot.
 */
class ProcessingExactExtractionTest {

    /** The encoded input is greenhouse_block{NBT A}; the network holds NBT B. */
    private static final String ITEM = "greenhouse_block";

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static BenchPatternDetails processingPattern() {
        BenchAEItemStack encoded = new BenchAEItemStack(ITEM, 0, 0, 1);   // variant A
        BenchAEItemStack product = new BenchAEItemStack("virtual_greenhouse", 1);
        return custom(new IAEItemStack[]{encoded}, new IAEItemStack[]{product});
    }

    private static VMPlan run(long amount, BenchSimulationState sim) {
        BenchPatternDetails pattern = processingPattern();
        Bench.register(pattern);
        for (ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(pattern, amount);
        CraftingVM vm = new CraftingVM("fuzzy-nbt", k -> null); // input is a leaf
        return vm.execute(req, sim);
    }

    private static Map<String, Long> used(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : p.getUsedItems().keys()) {
            out.put(key.toString(), p.getUsedItems().get(key));
        }
        return out;
    }

    private static Map<String, Long> missing(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : p.getMissingItems().keys()) {
            out.put(key.toString(), p.getMissingItems().get(key));
        }
        return out;
    }

    /**
     * GTL greenhouse fake-craft: the processing pattern input is encoded as the exact
     * NBT variant {@code greenhouse_block{A}}, but the network holds
     * {@code greenhouse_block{B}} — a different NBT variant of the same item at the
     * same damage. The CPU's findPrecise-exact extraction can NEVER push the pattern
     * with B in the CPU inventory, so the plan must disclose the encoded variant as
     * missing (the job is refused) instead of booking a withdrawal that stalls.
     */
    @Test
    void differentNbtVariantDoesNotSatisfyAProcessingInput() {
        BenchAEItemStack encoded = new BenchAEItemStack(ITEM, 0, 0, 1);                  // NBT A
        BenchAEItemStack stored = new BenchAEItemStack(ITEM, 0, 0, 1).withNbt("B");      // NBT B
        BenchSimulationState sim = new BenchSimulationState().seedNbt(ITEM, "B", 5);

        VMPlan plan = run(5, sim);
        assertEquals(5L, plan.getMissingItems().get(encoded),
                "the exact-identity gap must disclose, not a family draw: missing="
                        + missing(plan));
        assertEquals(0L, plan.getUsedItems().get(stored),
                "the sibling variant must not be withdrawn, used=" + used(plan));
    }

    /**
     * Live-report regression (luminium bead craft consumed platinum ingots): a
     * DAMAGE variant of the same item is a different item in 1.12 and must NOT
     * satisfy the slot — the encoded input (damage 166) stays missing while
     * only damage 134 is stocked.
     */
    @Test
    void damageVariantIsNotSubstitute() {
        BenchAEItemStack encoded = new BenchAEItemStack("tf_material", 166, 0, 1);     // lumium-like
        BenchAEItemStack wrongDamage = new BenchAEItemStack("tf_material", 134, 0, 1); // platinum-like
        BenchPatternDetails pattern = custom(new IAEItemStack[]{encoded},
                new IAEItemStack[]{new BenchAEItemStack("lumen_bead", 1)});
        Bench.register(pattern);
        for (ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(pattern, 5);
        CraftingVM vm = new CraftingVM("fuzzy-damage", k -> null); // input is a leaf
        BenchSimulationState sim = new BenchSimulationState().seedKey(wrongDamage, 50);

        VMPlan plan = vm.execute(req, sim);
        CpuLifecycleAssert.auto(plan);
        assertEquals(5L, plan.getMissingItems().get(encoded),
                "a different-damage item must never satisfy the slot, missing=" + missing(plan));
        assertEquals(0L, plan.getUsedItems().get(wrongDamage),
                "the different-damage stock must not be consumed, used=" + used(plan));
    }

    /**
     * Sanity: when NO variant of the item is stocked, the processing input is genuinely
     * missing (the slot must not silently vanish).
     */
    @Test
    void processingInputTrulyMissingWhenNoVariantStocked() {
        BenchAEItemStack encoded = new BenchAEItemStack(ITEM, 0, 0, 1);
        BenchSimulationState sim = new BenchSimulationState();

        VMPlan plan = run(5, sim);
        assertEquals(5L, plan.getMissingItems().get(encoded),
                "no stocked variant → the encoded processing input is missing, missing=" + missing(plan));
    }
}
