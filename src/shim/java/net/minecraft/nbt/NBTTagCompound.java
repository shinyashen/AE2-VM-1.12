package net.minecraft.nbt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replay shim: an opaque payload. The trace stores NBT as a token string;
 * equality over the stored string contents is all the identity the VM needs.
 */
public class NBTTagCompound {
    private final Map<String, String> strings = new LinkedHashMap<String, String>();

    public void setString(String key, String value) {
        strings.put(key, value);
    }

    public String getString(String key) {
        return strings.get(key);
    }

    public NBTTagCompound copy() {
        NBTTagCompound c = new NBTTagCompound();
        c.strings.putAll(this.strings);
        return c;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NBTTagCompound && ((NBTTagCompound) o).strings.equals(strings);
    }

    @Override
    public int hashCode() {
        return strings.hashCode();
    }

    @Override
    public String toString() {
        return strings.toString();
    }
}
