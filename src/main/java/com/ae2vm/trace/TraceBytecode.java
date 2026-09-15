package com.ae2vm.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The serialized compiled bytecode (design doc §3.3): flat opcode bytes +
 * constant pool + pattern pool. Carrying the compilation product in the
 * trace is what lets the standalone replay jar execute the order with no
 * pattern resolution, recipe lookup or World at all; the closure table
 * (bytecode.patterns) additionally enables --recompile comparisons.
 */
public final class TraceBytecode {

    /** Opcode bytes, base64. */
    public String code;

    /** Constant pool — entries carry the encoded stack size (fluid packet key semantics). */
    public final List<StackEntry> pool = new ArrayList<>();

    public int outputIndex;

    /** Output amount per craft as a decimal string (long, but string for uniformity). */
    public String perCraft;

    public final List<TracePattern> patterns = new ArrayList<>();

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        JsonArray p = new JsonArray();
        for (StackEntry e : pool) {
            p.add(e.toJson());
        }
        o.add("pool", p);
        o.addProperty("out", outputIndex);
        o.addProperty("per", perCraft);
        JsonArray pats = new JsonArray();
        for (TracePattern tp : patterns) {
            pats.add(tp.toJson());
        }
        o.add("patterns", pats);
        return o;
    }

    public static TraceBytecode fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        TraceBytecode b = new TraceBytecode();
        b.code = o.has("code") ? o.get("code").getAsString() : "";
        if (o.has("pool")) {
            for (JsonElement e : o.getAsJsonArray("pool")) {
                StackEntry entry = StackEntry.fromJson(e);
                if (entry != null) {
                    b.pool.add(entry);
                }
            }
        }
        b.outputIndex = o.has("out") ? o.get("out").getAsInt() : -1;
        b.perCraft = o.has("per") ? o.get("per").getAsString() : "0";
        if (o.has("patterns")) {
            for (JsonElement e : o.getAsJsonArray("patterns")) {
                TracePattern tp = TracePattern.fromJson(e);
                if (tp != null) {
                    b.patterns.add(tp);
                }
            }
        }
        return b;
    }
}
