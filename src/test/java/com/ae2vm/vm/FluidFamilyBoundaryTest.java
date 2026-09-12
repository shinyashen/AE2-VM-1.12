package com.ae2vm.vm;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2vm.compat.AE2FCCompat;
import com.glodblock.github.common.item.ItemFluidDrop;
import com.glodblock.github.common.item.fake.FakeFluids;
import com.glodblock.github.loader.FCItems;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AE2FC fluid-key contract behind the fuzzy-family fix: every fluid packs to
 * the SAME Item at damage 0 with the fluid identity in NBT (FluidName), so an
 * NBT-tolerant family would let ANY fluid substitute ANY other (water
 * covering a molten-platinum demand). {@link NetworkCraftingSandbox} must
 * answer fluid keys with an EMPTY family; fluids resolve through exact keys
 * and the packet-key index only.
 *
 * <p>The full ItemList cannot be built in plain JUnit (its item bucketing
 * touches Platform → FML), so the family itself is exercised through the
 * bench SimulationState fakes; this test pins the AE2FC key contract and the
 * sandbox's fluid short-circuit.
 */
class FluidFamilyBoundaryTest {

    @BeforeAll
    static void bootstrap() throws Exception {
        net.minecraft.init.Bootstrap.register();
        // Same ae2fc test setup as AE2FCCompatTest: the ObjectHolder fields are
        // only populated by the Forge registry, so hand them live fake items.
        // init() may already have run in another test class this JVM.
        if (FCItems.FLUID_DROP == null) {
            FCItems.FLUID_DROP = new ItemFluidDrop();
        }
        try {
            FakeFluids.init();
        } catch (IllegalArgumentException alreadyRegistered) {
            // another test class registered the handlers first
        }
    }

    @Test
    void fluidKeysHaveNoFamily() {
        IAEItemStack water = AE2FCCompat.packFluid(
                appeng.fluids.util.AEFluidStack.fromFluidStack(new FluidStack(FluidRegistry.WATER, 1000)));
        IAEItemStack lava = AE2FCCompat.packFluid(
                appeng.fluids.util.AEFluidStack.fromFluidStack(new FluidStack(FluidRegistry.LAVA, 1000)));
        assertNotNull(water, "packing water must produce a fake drop");
        assertNotNull(lava, "packing lava must produce a fake drop");
        assertTrue(AE2FCCompat.isFluidFakeItem(water), "the drop must be recognized as a fluid key");
        assertTrue(AE2FCCompat.isFluidFakeItem(lava), "the other drop must be recognized too");
        assertFalse(water.isSameType(lava), "different fluids must be different keys");

        NetworkCraftingSandbox sandbox = sandbox();
        assertTrue(sandbox.findFuzzyFamily(water).isEmpty(),
                "a fluid key must never see a fuzzy family");
        assertTrue(sandbox.findFuzzyFamily(lava).isEmpty(),
                "the other fluid direction must be family-free too");
    }

    private static NetworkCraftingSandbox sandbox() {
        IItemList<IAEItemStack> list = new appeng.util.item.ItemList();
        return new NetworkCraftingSandbox(list);
    }
}
