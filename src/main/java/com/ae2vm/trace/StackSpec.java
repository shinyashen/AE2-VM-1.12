package com.ae2vm.trace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Trace-side stack identity — the ONLY identity representation that ever
 * reaches a trace file. {@code token} is an opaque vault token ("i#0f3c")
 * substituting the registry/fluid name, {@code nbtToken} ("n#5d2") the
 * opaque substitute for the NBT payload; real names never enter the trace
 *. Damage is preserved verbatim: it is part of identity
 * but carries no naming information.
 *
 * <p>Immutable and count-free — quantities live in {@link StackEntry}.
 * Canonical JSON: {"k":"i","id":"i#0f3c","d":166,"n":"n#5d2"} with "n"
 * omitted when null.
 */
public final class StackSpec {

    /** True for a fluid-channel stack (kind 'f'); VM-level stacks are item kind. */
    public final boolean fluid;

    /** Opaque vault token substituting the registry/fluid name. */
    public final String token;

    /** Damage/meta, preserved verbatim. */
    public final int damage;

    /** Opaque NBT token, or null when the stack carries no NBT. */
    public final String nbtToken;

    public StackSpec(boolean fluid, String token, int damage, String nbtToken) {
        this.fluid = fluid;
        this.token = token;
        this.damage = damage;
        this.nbtToken = nbtToken;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("k", fluid ? "f" : "i");
        o.addProperty("id", token);
        o.addProperty("d", damage);
        if (nbtToken != null) {
            o.addProperty("n", nbtToken);
        }
        return o;
    }

    /** Parses a spec; returns null when {@code o} is not a trace spec shape. */
    public static StackSpec fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        JsonElement k = o.get("k");
        JsonElement id = o.get("id");
        if (k == null || id == null) {
            return null;
        }
        boolean fluid = "f".equals(k.getAsString());
        int damage = o.has("d") ? o.get("d").getAsInt() : 0;
        String nbtToken = o.has("n") && !o.get("n").isJsonNull() ? o.get("n").getAsString() : null;
        return new StackSpec(fluid, id.getAsString(), damage, nbtToken);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StackSpec)) return false;
        StackSpec s = (StackSpec) o;
        return fluid == s.fluid && damage == s.damage
                && token.equals(s.token)
                && (nbtToken == null ? s.nbtToken == null : nbtToken.equals(s.nbtToken));
    }

    @Override
    public int hashCode() {
        int r = token.hashCode();
        r = 31 * r + damage;
        r = 31 * r + (nbtToken == null ? 0 : nbtToken.hashCode());
        r = 31 * r + (fluid ? 1 : 0);
        return r;
    }

    @Override
    public String toString() {
        return (fluid ? "f" : "i") + "#" + token + "@" + damage + (nbtToken == null ? "" : "+" + nbtToken);
    }
}
