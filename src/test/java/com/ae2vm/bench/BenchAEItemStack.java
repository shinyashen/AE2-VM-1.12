package com.ae2vm.bench;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Minimal IAEItemStack fake for VM semantics tests — no Minecraft bootstrap.
 * Type identity = (id, damage); the AE stack size carries the quantity (and
 * for AE2FC-style fluid fakes, the mB amount). An optional shared real Item
 * backs the damageable probes used by the durability detection.
 */
public final class BenchAEItemStack implements IAEItemStack {
    private static final java.util.Map<Integer, Item> ITEMS = new java.util.HashMap<>();

    public final String id;
    public final int damage;
    public final int maxDamage;
    private long size;
    private boolean craftable;
    private long countRequestable;

    public BenchAEItemStack(String id, long size) {
        this(id, 0, 0, size);
    }

    public BenchAEItemStack(String id, int damage, int maxDamage, long size) {
        this.id = id;
        this.damage = damage;
        this.maxDamage = maxDamage;
        this.size = size;
    }

    private Item item() {
        // Shared per maxDamage: container/durability probes compare items by
        // identity, so equal maxDamage must yield the same instance.
        return ITEMS.computeIfAbsent(maxDamage, md -> {
            Item it = new Item();
            if (md > 0) {
                it.setMaxDamage(md);
            }
            return it;
        });
    }

    public long size() {
        return size;
    }

    @Override
    public boolean isSameType(IAEItemStack other) {
        return other instanceof BenchAEItemStack b && b.id.equals(id) && b.damage == damage;
    }

    @Override
    public boolean isSameType(ItemStack stored) {
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IAEItemStack s && isSameType(s);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + damage;
    }

    @Override
    public IAEItemStack copy() {
        BenchAEItemStack c = new BenchAEItemStack(id, damage, maxDamage, size);
        c.craftable = craftable;
        c.countRequestable = countRequestable;
        return c;
    }

    @Override
    public IAEItemStack setStackSize(long stackSize) {
        this.size = stackSize;
        return this;
    }

    @Override
    public long getStackSize() {
        return size;
    }

    @Override
    public IAEItemStack setCountRequestable(long countRequestable) {
        this.countRequestable = countRequestable;
        return this;
    }

    @Override
    public long getCountRequestable() {
        return countRequestable;
    }

    @Override
    public IAEItemStack setCraftable(boolean state) {
        this.craftable = state;
        return this;
    }

    @Override
    public boolean isCraftable() {
        return craftable;
    }

    @Override
    public IAEItemStack reset() {
        size = 0;
        countRequestable = 0;
        craftable = false;
        return this;
    }

    @Override
    public boolean isMeaningful() {
        return size != 0 || countRequestable != 0 || craftable;
    }

    @Override
    public void incStackSize(long i) {
        size += i;
    }

    @Override
    public void decStackSize(long i) {
        size -= i;
    }

    @Override
    public void incCountRequestable(long i) {
        countRequestable += i;
    }

    @Override
    public void decCountRequestable(long i) {
        countRequestable -= i;
    }

    @Override
    public void writeToNBT(NBTTagCompound i) {
    }

    @Override
    public void writeToPacket(io.netty.buffer.ByteBuf buf) throws java.io.IOException {
    }

    @Override
    public IAEItemStack empty() {
        return copy().setStackSize(0);
    }

    @Override
    public boolean isItem() {
        return true;
    }

    @Override
    public boolean isFluid() {
        return false;
    }

    @Override
    public ItemStack asItemStackRepresentation() {
        return createItemStack();
    }

    @Override
    public appeng.api.storage.IStorageChannel getChannel() {
        // not exercised by the VM path; avoid touching the AE2 registry
        return null;
    }

    @Override
    public boolean fuzzyComparison(IAEItemStack other, FuzzyMode mode) {
        return other != null && other.getItem() == getItem();
    }

    @Override
    public void add(IAEItemStack option) {
        size += option.getStackSize();
    }

    @Override
    public ItemStack createItemStack() {
        ItemStack s = new ItemStack(item(), 1, damage);
        return s;
    }

    @Override
    public boolean hasTagCompound() {
        return false;
    }

    @Override
    public Item getItem() {
        return item();
    }

    @Override
    public int getItemDamage() {
        return damage;
    }

    @Override
    public boolean sameOre(IAEItemStack is) {
        return isSameType(is);
    }

    @Override
    public ItemStack getDefinition() {
        return createItemStack();
    }

    @Override
    public boolean equals(ItemStack is) {
        return false;
    }

    @Override
    public ItemStack getCachedItemStack(long stackSize) {
        return null;
    }

    @Override
    public void setCachedItemStack(ItemStack is) {
    }
}
