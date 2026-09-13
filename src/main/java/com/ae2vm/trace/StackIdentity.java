package com.ae2vm.trace;

/**
 * Vault-side REAL stack identity — lives only inside the server-local
 * token vault, never in a trace file. {@code nbt} is the full SNBT text;
 * the vault substitutes it with an opaque "n#xxxx" token in traces.
 *
 * <p>AE2FC fluid fake drops are recorded as their item identity (the
 * FluidDrop item + FluidName NBT) — lossless at the VM level, where drops
 * ARE items; the 'f' kind of {@link StackSpec} is reserved for the fluid
 * storage channel (M1 snapshot work).
 */
public final class StackIdentity {

    public final String id;
    public final int damage;
    public final String nbt; // nullable SNBT text

    public StackIdentity(String id, int damage, String nbt) {
        this.id = id;
        this.damage = damage;
        this.nbt = nbt;
    }

    /** Canonical vault key: identity without display concerns. */
    public String key() {
        return id + "\u0000" + damage + "\u0000" + (nbt == null ? "" : nbt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StackIdentity)) return false;
        StackIdentity s = (StackIdentity) o;
        return damage == s.damage && id.equals(s.id)
                && (nbt == null ? s.nbt == null : nbt.equals(s.nbt));
    }

    @Override
    public int hashCode() {
        int r = id.hashCode();
        r = 31 * r + damage;
        r = 31 * r + (nbt == null ? 0 : nbt.hashCode());
        return r;
    }

    @Override
    public String toString() {
        return id + "/" + damage + (nbt == null ? "" : " " + nbt);
    }
}
