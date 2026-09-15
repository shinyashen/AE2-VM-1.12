package net.minecraft.item;

/**
 * Replay shim: an unregistered, identity-only item. Two tokens materialize
 * two distinct instances; one token always yields the same instance, so
 * instance identity IS the item identity.
 */
public class Item {
    private int maxDamage;

    public Item setMaxDamage(int max) {
        this.maxDamage = max;
        return this;
    }

    public int getMaxDamage() {
        return maxDamage;
    }
}
