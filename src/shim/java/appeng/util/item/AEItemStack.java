package appeng.util.item;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import io.netty.buffer.ByteBuf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Replay shim: THE virtual stack. Identity = (item instance, damage, NBT
 * payload) — the same triple the real stack uses, with the item anchored by
 * reference instead of a registry entry (HeadlessStackFactory mints one Item
 * per trace token, so equal tokens materialize equal stacks and different
 * tokens never collide). Semantics ported from the bench fake that backs the
 * whole engine test family.
 */
public final class AEItemStack implements IAEItemStack {

    private final Item item;
    private final int damage;
    private final NBTTagCompound tag;
    private long size;
    private long countRequestable;
    private boolean craftable;

    private AEItemStack(Item item, int damage, NBTTagCompound tag) {
        this.item = item;
        this.damage = damage;
        this.tag = tag;
    }

    public static AEItemStack fromItemStack(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        return new AEItemStack(stack.getItem(), stack.getItemDamage(), stack.getTagCompound());
    }

    @Override
    public boolean isSameType(IAEItemStack otherStack) {
        if (!(otherStack instanceof AEItemStack)) {
            return false;
        }
        AEItemStack o = (AEItemStack) otherStack;
        return o.item == item && o.damage == damage && tagEquals(o.tag, tag);
    }

    @Override
    public boolean isSameType(ItemStack stored) {
        return stored != null && stored.getItem() == item && stored.getItemDamage() == damage
                && tagEquals(stored.getTagCompound(), tag);
    }

    private static boolean tagEquals(NBTTagCompound a, NBTTagCompound b) {
        if (a == null || a.getString("aevm_nbt_token") == null) {
            return b == null || b.getString("aevm_nbt_token") == null;
        }
        return a.equals(b);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IAEItemStack && isSameType((IAEItemStack) obj);
    }

    @Override
    public int hashCode() {
        int h = System.identityHashCode(item) * 31 + damage * 7;
        return tag == null ? h : h * 31 + tag.hashCode();
    }

    @Override
    public String toString() {
        String base = "shim#" + Integer.toHexString(System.identityHashCode(item))
                + (damage != 0 ? "@" + damage : "");
        return tag != null ? base + "{" + tag + "}" : base;
    }

    @Override
    public IAEItemStack copy() {
        AEItemStack c = new AEItemStack(item, damage, tag);
        c.size = size;
        c.countRequestable = countRequestable;
        c.craftable = craftable;
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
    public void add(IAEItemStack option) {
        size += option.getStackSize();
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
    public IStorageChannel getChannel() {
        return null; // never exercised by the replay path
    }

    @Override
    public boolean fuzzyComparison(IAEItemStack other, FuzzyMode mode) {
        return other != null && other.getItem() == getItem();
    }

    @Override
    public ItemStack createItemStack() {
        ItemStack s = new ItemStack(item, 1, damage);
        if (tag != null) {
            s.setTagCompound(tag.copy());
        }
        return s;
    }

    @Override
    public boolean hasTagCompound() {
        return tag != null;
    }

    @Override
    public Item getItem() {
        return item;
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
        return is != null && isSameType(is);
    }

    @Override
    public void writeToNBT(NBTTagCompound i) {
    }

    @Override
    public void writeToPacket(ByteBuf data) {
    }

    @Override
    public ItemStack getCachedItemStack(long stackSize) {
        return null;
    }

    @Override
    public void setCachedItemStack(ItemStack is) {
    }
}
