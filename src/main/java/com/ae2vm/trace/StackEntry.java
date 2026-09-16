package com.ae2vm.trace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A counted occurrence of a {@link StackSpec} — snapshot inventory lines,
 * plan used/missing/emitted lines, bytecode constant-pool entries.
 * Counts are JSON strings so BigInteger amounts (> 2^53) survive round
 * trips without precision loss.
 *
 * <p>Canonical JSON: {"s":{...spec...},"c":"1000"} with "cf":true added
 * only when craftable (snapshot craftable entries).
 */
public final class StackEntry {

    public final StackSpec spec;

    /** Amount as a decimal string (BigInteger-safe). */
    public final String count;

    /** Snapshot-only flag: this line is a craftable (no real stock) entry. */
    public final boolean craftable;

    public StackEntry(StackSpec spec, String count, boolean craftable) {
        this.spec = spec;
        this.count = count;
        this.craftable = craftable;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("s", spec.toJson());
        o.addProperty("c", count);
        if (craftable) {
            o.addProperty("cf", true);
        }
        return o;
    }

    public static StackEntry fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        StackSpec spec = StackSpec.fromJson(o.get("s"));
        JsonElement c = o.get("c");
        if (spec == null || c == null) {
            return null;
        }
        boolean craftable = o.has("cf") && o.get("cf").getAsBoolean();
        return new StackEntry(spec, c.getAsString(), craftable);
    }
}
