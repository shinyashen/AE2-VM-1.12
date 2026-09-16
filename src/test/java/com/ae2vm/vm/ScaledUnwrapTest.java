package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defensive wrapper unwrap (port of the upstream ScaledPatternReproTest
 * intent): addons can hand the VM a runtime WRAPPER around a real pattern
 * (upstream: UselessMod's ScaledProcessingPattern, which multiplies inputs and
 * outputs by operationsPerPush). Compiling the wrapper bakes the multiplier
 * into the bytecode and makes the wrapper the plan's patternTimes key — which
 * the CPU, the providers and the machine sources do not recognize.
 *
 * <p>PatternCompiler unwraps "Scaled*" wrappers (reflective
 * {@code getOriginal()}) at every compile entry, so the bytecode and all plan
 * keys stay anchored to the original pattern. 1.12 has no known shipping
 * wrapper addon today; this harness pins the contract so the first one to
 * appear is compatible on arrival.
 */
class ScaledUnwrapTest {

    /** Minimal "Scaled*" wrapper: class-name convention + getOriginal(). */
    public static final class ScaledFakePattern implements ICraftingPatternDetails {
        public final ICraftingPatternDetails original;

        public ScaledFakePattern(ICraftingPatternDetails original) {
            this.original = original;
        }

        public ICraftingPatternDetails getOriginal() {
            return original;
        }

        @Override
        public IAEItemStack[] getInputs() {
            return original.getInputs();
        }

        @Override
        public IAEItemStack[] getCondensedInputs() {
            return original.getCondensedInputs();
        }

        @Override
        public IAEItemStack[] getCondensedOutputs() {
            return original.getCondensedOutputs();
        }

        @Override
        public IAEItemStack[] getOutputs() {
            return original.getOutputs();
        }

        @Override
        public boolean isCraftable() {
            return original.isCraftable();
        }

        @Override
        public boolean canSubstitute() {
            return original.canSubstitute();
        }

        @Override
        public List<IAEItemStack> getSubstituteInputs(int slot) {
            return original.getSubstituteInputs(slot);
        }

        @Override
        public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
            return original.isValidItemForSlot(slotIndex, itemStack, world);
        }

        @Override
        public ItemStack getPattern() {
            return original.getPattern();
        }

        @Override
        public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
            return original.getOutput(craftingInv, world);
        }

        @Override
        public int getPriority() {
            return original.getPriority();
        }

        @Override
        public void setPriority(int priority) {
            original.setPriority(priority);
        }
    }

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @Test
    void unwrapReturnsOriginal() {
        BenchPatternDetails original = pat("prod", 1, "raw", 1L);
        ScaledFakePattern wrapper = new ScaledFakePattern(original);
        assertSame(original, PatternCompiler.unwrapScaled(wrapper),
                "the wrapper must unwrap to its original pattern");
        assertSame(original, PatternCompiler.unwrapScaled(original),
                "a plain pattern unwraps to itself");
    }

    @Test
    void compileAnchorsBytecodeAndPlanKeyToOriginal() {
        PatternCompiler.clearCache();
        BenchPatternDetails original = pat("prod", 1, "raw", 1L);
        ScaledFakePattern wrapper = new ScaledFakePattern(original);

        PatternCompiler.compileIfAbsent(wrapper);
        assertSame(PatternCompiler.getCompiled(wrapper),
                PatternCompiler.getCompiled(original),
                "wrapper and original must share ONE compiled bytecode");

        // A request compiled through the wrapper runs the original's bytecode:
        // the plan's patternTimes key is the ORIGINAL pattern.
        Map<IAEItemStack, ICraftingPatternDetails> view = new HashMap<>();
        view.put(k("prod"), original);
        CraftingVM vm = new CraftingVM("scaled-unwrap", view::get);
        BenchSimulationState sim = new BenchSimulationState();
        sim.seed("raw", 10);

        CraftingBytecode request = PatternCompiler.compileRequest(wrapper, 3);
        VMPlan plan = vm.execute(request, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(plan.getMissingItems().isEmpty(),
                "the wrapped request must complete, missing=" + plan.getMissingItems());
        assertEquals(1, plan.getPatternTimes().size(),
                "exactly one pattern firing entry");
        assertTrue(plan.getPatternTimes().containsKey(original),
                "the plan key must be the ORIGINAL pattern, not the wrapper");
        assertEquals(3L, plan.getUsedItems().get(k("raw")));
    }
}
