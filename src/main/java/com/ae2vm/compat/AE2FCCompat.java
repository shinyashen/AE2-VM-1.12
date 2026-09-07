package com.ae2vm.compat;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import com.glodblock.github.common.item.fake.FakeFluids;
import com.glodblock.github.common.item.fake.FakeItemRegister;
import com.glodblock.github.loader.FCItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * Small, source-level AE2FC-Rework bridge (ported from AE2-Quick-Calculation's
 * proven compat layer).
 *
 * AE2FC exposes two fake item forms. A drop stores the fluid identity in NBT
 * and its AE stack size is the fluid amount. A packet stores the real
 * FluidStack, including its amount, in NBT and is used by processing patterns.
 * The VM uses drops as its canonical key because that is the form exposed by
 * AE2FC's item-channel network monitor.
 */
public final class AE2FCCompat {
    private static final String FC_CLASS = "com.glodblock.github.common.item.fake.FakeFluids";
    private static volatile Boolean available;

    private AE2FCCompat() {
    }

    public static boolean isAvailable() {
        Boolean flag = available;
        if (flag == null) {
            try {
                Class.forName(FC_CLASS);
                flag = true;
            } catch (ClassNotFoundException ignored) {
                flag = false;
            }
        }
        return flag;
    }

    public static boolean isFluidFakeItem(ItemStack stack) {
        return isAvailable() && stack != null && !stack.isEmpty()
                && FakeFluids.isFluidFakeItem(stack);
    }

    public static boolean isFluidFakeItem(IAEItemStack stack) {
        return isAvailable() && stack != null && isFluidFakeItem(stack.getDefinition());
    }

    public static IAEItemStack packFluid(IAEFluidStack fluid) {
        return FakeFluids.packFluid2AEDrops(fluid);
    }

    /**
     * Converts either AE2FC fake form to the canonical drop form. The drop's
     * amount is copied from the source stack; packet amounts are read from the
     * packet NBT rather than from its item count.
     */
    public static IAEItemStack normalizeFluidItem(IAEItemStack stack) {
        if (stack == null) {
            return null;
        }
        if (!isFluidFakeItem(stack)) {
            return stack.copy();
        }

        FluidStack fluid = decodeFluid(stack);
        if (fluid == null || fluid.amount <= 0) {
            return null;
        }

        IAEItemStack drop = FakeFluids.packFluid2AEDrops(fluid);
        if (drop == null) {
            return null;
        }

        if (stack.getItem() == FCItems.FLUID_DROP) {
            // A drop's count is the AE quantity and may be larger than the
            // int-sized Minecraft ItemStack count used for decoding.
            drop.setStackSize(stack.getStackSize());
        }
        return drop;
    }

    /**
     * Creates the exact packet key used by a pattern provider. For a
     * canonical drop the caller must provide the amount encoded by that
     * pattern input/output. A large order quantity is not a valid packet
     * amount and is rejected instead of being truncated.
     */
    public static IAEItemStack packFluidPacket(IAEItemStack stack, long amountHint) {
        if (!isFluidFakeItem(stack)) {
            return null;
        }

        if (stack.getItem() == FCItems.FLUID_DROP && amountHint <= 0L) {
            return null;
        }

        FluidStack fluid = decodeFluid(stack);
        if (fluid == null || fluid.amount <= 0) {
            return null;
        }

        if (amountHint > 0L) {
            if (amountHint > Integer.MAX_VALUE) {
                return null;
            }
            fluid.amount = (int) amountHint;
        }
        return fluid.amount <= 0 ? null : FakeFluids.packFluid2AEPacket(fluid);
    }

    private static FluidStack decodeFluid(IAEItemStack stack) {
        if (stack.getItem() == FCItems.FLUID_DROP) {
            // FLUID_DROP encodes its amount in the AE stack size. Decode only
            // the identity so long quantities never pass through ItemStack.
            IAEItemStack identityProbe = stack.copy().setStackSize(1L);
            return FakeItemRegister.getStack(identityProbe);
        }
        return FakeItemRegister.getStack(stack);
    }
}
