package net.minecraft.util;

/** Replay shim: a plain named constant. */
public class ResourceLocation {
    private final String name;

    public ResourceLocation(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResourceLocation && ((ResourceLocation) o).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
