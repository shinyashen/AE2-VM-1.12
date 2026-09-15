package com.ae2vm.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A pattern as the trace sees it: condensed inputs/outputs (tokenized
 * specs with their encoded sizes), crafting/processing kind, substitution
 * flag and priority. This is the closure-table entry (design doc §3.3)
 * that keeps a trace self-contained for --recompile.
 */
public final class TracePattern {

    public final List<StackEntry> condensedInputs = new ArrayList<>();
    public final List<StackEntry> condensedOutputs = new ArrayList<>();
    public final boolean crafting;
    public final boolean substitute;
    public int priority;

    /**
     * Schema v2 (design doc §6.1): this pattern's own compiled bytecode,
     * embedded so offline CALL execution never needs the live compiler.
     * Null for patterns that were never CALLed during recording.
     */
    public TraceBytecode compiled;

    public TracePattern(boolean crafting, boolean substitute, int priority) {
        this.crafting = crafting;
        this.substitute = substitute;
        this.priority = priority;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray in = new JsonArray();
        for (StackEntry e : condensedInputs) {
            in.add(e.toJson());
        }
        o.add("in", in);
        JsonArray out = new JsonArray();
        for (StackEntry e : condensedOutputs) {
            out.add(e.toJson());
        }
        o.add("out", out);
        o.addProperty("crafting", crafting);
        o.addProperty("sub", substitute);
        o.addProperty("prio", priority);
        if (compiled != null) {
            o.add("compiled", compiled.toJson());
        }
        return o;
    }

    public static TracePattern fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        TracePattern p = new TracePattern(
                o.has("crafting") && o.get("crafting").getAsBoolean(),
                o.has("sub") && o.get("sub").getAsBoolean(),
                o.has("prio") ? o.get("prio").getAsInt() : 0);
        if (o.has("in")) {
            for (JsonElement e : o.getAsJsonArray("in")) {
                StackEntry entry = StackEntry.fromJson(e);
                if (entry != null) {
                    p.condensedInputs.add(entry);
                }
            }
        }
        if (o.has("out")) {
            for (JsonElement e : o.getAsJsonArray("out")) {
                StackEntry entry = StackEntry.fromJson(e);
                if (entry != null) {
                    p.condensedOutputs.add(entry);
                }
            }
        }
        p.compiled = TraceBytecode.fromJson(o.get("compiled"));
        return p;
    }
}
