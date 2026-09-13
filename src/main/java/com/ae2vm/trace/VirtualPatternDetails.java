package com.ae2vm.trace;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.Arrays;

/**
 * Data-backed pattern shim for headless replay (and tests): carries
 * condensed IO as plain stacks. Equality is IO identity + flags — exactly
 * the semantics the VM relies on when keying patternTimes, so rebuilt
 * pattern-pool entries and resolver answers agree without a World.
 * Behavioral crafting methods are meaningless offline and throw.
 */
public final class VirtualPatternDetails implements ICraftingPatternDetails {

    private final IAEItemStack[] condensedInputs;
    private final IAEItemStack[] condensedOutputs;
    private final boolean craftable;
    private final boolean substitute;
    private int priority;

    public VirtualPatternDetails(IAEItemStack[] condensedInputs, IAEItemStack[] condensedOutputs,
                                 boolean craftable, boolean substitute) {
        this.condensedInputs = condensedInputs;
        this.condensedOutputs = condensedOutputs;
        this.craftable = craftable;
        this.substitute = substitute;
    }

    @Override
    public ItemStack getPattern() {
        return new ItemStack(new Item());
    }

    @Override
    public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
        throw unsupported();
    }

    @Override
    public boolean isCraftable() {
        return craftable;
    }

    @Override
    public IAEItemStack[] getInputs() {
        return condensedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return condensedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return condensedOutputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return condensedOutputs;
    }

    @Override
    public boolean canSubstitute() {
        return substitute;
    }

    @Override
    public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
        throw unsupported();
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "virtual pattern is a data carrier for replay, not a crafter");
    }

    private static boolean sameStacks(IAEItemStack[] a, IAEItemStack[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null || b[i] == null) {
                if (a[i] != b[i]) {
                    return false;
                }
                continue;
            }
            if (!a[i].isSameType(b[i]) || a[i].getStackSize() != b[i].getStackSize()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VirtualPatternDetails)) return false;
        VirtualPatternDetails p = (VirtualPatternDetails) o;
        return craftable == p.craftable && substitute == p.substitute
                && sameStacks(condensedInputs, p.condensedInputs)
                && sameStacks(condensedOutputs, p.condensedOutputs);
    }

    @Override
    public int hashCode() {
        int h = (craftable ? 1 : 0) * 31 + (substitute ? 1 : 0);
        for (IAEItemStack s : condensedInputs) {
            h = 31 * h + (s == null ? 0 : (int) (s.getStackSize() ^ (s.getStackSize() >>> 32)) + s.hashCode());
        }
        for (IAEItemStack s : condensedOutputs) {
            h = 31 * h + (s == null ? 0 : (int) (s.getStackSize() ^ (s.getStackSize() >>> 32)) + s.hashCode());
        }
        return h;
    }

    @Override
    public String toString() {
        return "VirtualPattern{in=" + Arrays.toString(condensedInputs)
                + ", out=" + Arrays.toString(condensedOutputs)
                + ", crafting=" + craftable + ", sub=" + substitute + "}";
    }
}
