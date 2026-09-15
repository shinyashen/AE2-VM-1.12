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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original ProcessingDefaultFuzzyTest — processing recipes (处理配方)
 * DEFAULT to fuzzy matching. A processing pattern input is a single exact variant,
 * but the real ME network may hold the SAME item under a DIFFERENT NBT variant —
 * the GTL greenhouse fake-craft block / Mystical Agriculture essence ("材料缺失但
 * 不知道哪里缺失"). The VM must count the item's NBT family (same item, same
 * damage, different NBT) as satisfying the slot, mirroring AE2 native's
 * {@code getValidItemTemplates} → {@code findFuzzyTemplates}.
 *
 * <p>The damage axis is item IDENTITY in 1.12 (Thermal materials encode the
 * material there): a damage variant is a DIFFERENT item and must never
 * substitute — the family is supplied by {@code SimulationState.findFuzzyFamily}
 * and contains only same-damage NBT variants.
 */
class ProcessingDefaultFuzzyTest {

    /** The encoded input is greenhouse_block{NBT A}; the network holds NBT B. */
    private static final String ITEM = "greenhouse_block";

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
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
        for (appeng.api.networking.crafting.ICraftingPatternDetails p : Bench.PATTERNS.values()) {
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
     * same damage. The VM must treat B as satisfying the slot: NO missing, and
     * usedItems names the ACTUAL variant B (so the CPU extracts the real key at
     * submit time).
     */
    @Test
    void processingInputSatisfiedByDifferentNbtVariant() {
        BenchAEItemStack encoded = new BenchAEItemStack(ITEM, 0, 0, 1);                  // NBT A
        BenchAEItemStack stored = new BenchAEItemStack(ITEM, 0, 0, 1).withNbt("B");      // NBT B
        BenchSimulationState sim = new BenchSimulationState().seedNbt(ITEM, "B", 5);

        VMPlan plan = run(5, sim);
        assertTrue(plan.getMissingItems().isEmpty(),
                "processing input must be satisfied by a different-NBT variant of the same item, missing="
                        + missing(plan));
        assertEquals(5L, plan.getUsedItems().get(stored),
                "the ACTUAL variant (B) must be recorded in usedItems, used=" + used(plan));
        assertEquals(0L, plan.getUsedItems().get(encoded),
                "the empty encoded variant (A) must not be recorded, used=" + used(plan));
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
        for (appeng.api.networking.crafting.ICraftingPatternDetails p : Bench.PATTERNS.values()) {
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
