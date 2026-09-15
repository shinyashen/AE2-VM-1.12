package com.ae2vm.trace;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.HashMap;
import java.util.Map;

/**
 * Trace → live stack for environments without the original registry
 * (standalone replay, CI): a dummy Item per stack token and a dummy NBT
 * payload per NBT token. The VM needs identity only — the same
 * (token, damage, nbtToken) always materializes an equal stack, different
 * ones never collide, and no real name is ever required (design doc §6.0
 * "哑注册表垫片").
 */
public final class HeadlessStackFactory {

    private final Map<String, Item> items = new HashMap<>();
    private final Map<String, NBTTagCompound> nbtTags = new HashMap<>();
    private final Map<IAEItemStack, StackSpec> issued = new java.util.IdentityHashMap<>();

    public IAEItemStack stack(StackSpec spec, long count) {
        Item item = items.get(spec.token);
        if (item == null) {
            item = new Item();
            items.put(spec.token, item);
        }
        ItemStack st = new ItemStack(item, 1, spec.damage);
        if (spec.nbtToken != null) {
            NBTTagCompound tag = nbtTags.get(spec.nbtToken);
            if (tag == null) {
                tag = new NBTTagCompound();
                tag.setString("aevm_nbt_token", spec.nbtToken);
                nbtTags.put(spec.nbtToken, tag);
            }
            st.setTagCompound(tag.copy());
        }
        IAEItemStack ae = AEItemStack.fromItemStack(st);
        if (ae == null) {
            throw new IllegalStateException("headless factory cannot materialize " + spec);
        }
        ae.setStackSize(count);
        issued.put(ae, spec);
        return ae;
    }

    /** Reverse lookup for diff reporting; null for stacks this factory did not issue. */
    public StackSpec specOf(IAEItemStack stack) {
        return issued.get(stack);
    }
}
