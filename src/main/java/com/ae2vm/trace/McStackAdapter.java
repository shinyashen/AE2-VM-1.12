package com.ae2vm.trace;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * Live-network side of the identity boundary: real stacks ↔ vault
 * identities. AE2FC fluid fake drops need no special case here — they
 * are recorded as their item identity (FluidDrop item + FluidName NBT),
 * which is lossless at the VM level where drops ARE items; the 'f' spec
 * kind is reserved for the fluid storage channel (M1 snapshot).
 */
public final class McStackAdapter {

    private McStackAdapter() {
    }

    /** Real stack → vault identity (registry name, damage, SNBT text). */
    public static StackIdentity identityOf(IAEItemStack stack) {
        ItemStack rep = stack.createItemStack();
        Item item = rep.getItem();
        ResourceLocation rl = Item.REGISTRY.getNameForObject(item);
        String id = rl != null
                ? rl.toString()
                : "unknown:" + Integer.toHexString(System.identityHashCode(item));
        String nbt = rep.getTagCompound() != null ? rep.getTagCompound().toString() : null;
        return new StackIdentity(id, rep.getItemDamage(), nbt);
    }

    /**
     * Vault identity → real stack through the live registry; null when the
     * item is unknown to this environment (headless consumers should use
     * {@link HeadlessStackFactory} instead).
     */
    public static IAEItemStack fromIdentity(StackIdentity id, long count) {
        Item item = Item.getByNameOrId(id.id);
        if (item == null) {
            return null;
        }
        ItemStack st = new ItemStack(item, 1, id.damage);
        if (id.nbt != null) {
            try {
                st.setTagCompound(JsonToNBT.getTagFromJson(id.nbt));
            } catch (Exception malformedNbt) {
                return null;
            }
        }
        IAEItemStack ae = AEItemStack.fromItemStack(st);
        if (ae != null) {
            ae.setStackSize(count);
        }
        return ae;
    }
}
