package net.minecraft.item;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Replay shim: an identity carrier (item reference + damage + NBT payload)
 * with none of the vanilla behaviour — nothing on the replay path needs it.
 */
public class ItemStack {
    private final Item item;
    private final int itemDamage;
    private int stackSize;
    private NBTTagCompound tag;

    public ItemStack(Item item) {
        this(item, 1, 0);
    }

    public ItemStack(Item item, int amount, int damage) {
        this.item = item;
        this.stackSize = amount;
        this.itemDamage = damage;
    }

    public Item getItem() {
        return item;
    }

    public int getItemDamage() {
        return itemDamage;
    }

    public int getCount() {
        return stackSize;
    }

    public NBTTagCompound getTagCompound() {
        return tag;
    }

    public void setTagCompound(NBTTagCompound tag) {
        this.tag = tag;
    }
}
