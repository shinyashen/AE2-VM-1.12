package com.ae2vm.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The inventory world the calculation saw (design doc §3.3): relevant
 * subset by default (request closure-reachable families + fluids), full
 * snapshot optional. Fluid-channel stacks use {@code fluid=true} specs;
 * AE2FC fake drops appear as item lines with their FluidName NBT.
 */
public final class TraceSnapshot {

    /** "relevant" or "full". */
    public final String mode;

    public final List<StackEntry> items = new ArrayList<>();
    public final List<StackEntry> fluids = new ArrayList<>();

    public TraceSnapshot(String mode) {
        this.mode = mode;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        JsonArray it = new JsonArray();
        for (StackEntry e : items) {
            it.add(e.toJson());
        }
        o.add("items", it);
        JsonArray fl = new JsonArray();
        for (StackEntry e : fluids) {
            fl.add(e.toJson());
        }
        o.add("fluids", fl);
        return o;
    }

    public static TraceSnapshot fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        TraceSnapshot s = new TraceSnapshot(o.has("mode") ? o.get("mode").getAsString() : "relevant");
        if (o.has("items")) {
            for (JsonElement e : o.getAsJsonArray("items")) {
                StackEntry entry = StackEntry.fromJson(e);
                if (entry != null) {
                    s.items.add(entry);
                }
            }
        }
        if (o.has("fluids")) {
            for (JsonElement e : o.getAsJsonArray("fluids")) {
                StackEntry entry = StackEntry.fromJson(e);
                if (entry != null) {
                    s.fluids.add(entry);
                }
            }
        }
        return s;
    }
}
