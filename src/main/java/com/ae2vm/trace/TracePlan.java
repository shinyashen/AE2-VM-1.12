package com.ae2vm.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The plan summary that ends the CALC segment (the PLAN_RESULT event): the four maps a replay diff compares — patternTimes,
 * used, missing, emitted — plus the simulation flag. Pattern references
 * are indices into {@link TraceBytecode#patterns}.
 */
public final class TracePlan {

    public final List<StackEntry> used = new ArrayList<>();
    public final List<StackEntry> missing = new ArrayList<>();
    public final List<StackEntry> emitted = new ArrayList<>();
    public final List<PatternTime> patternTimes = new ArrayList<>();
    public boolean simulation;

    /** Delivered amount of the requested key (decimal string). */
    public String deliver;

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("used", entries(used));
        o.add("missing", entries(missing));
        o.add("emitted", entries(emitted));
        JsonArray pt = new JsonArray();
        for (PatternTime p : patternTimes) {
            JsonObject e = new JsonObject();
            e.addProperty("p", p.patternIndex);
            e.addProperty("t", p.times);
            pt.add(e);
        }
        o.add("pt", pt);
        o.addProperty("sim", simulation);
        if (deliver != null) {
            o.addProperty("deliver", deliver);
        }
        return o;
    }

    private static JsonArray entries(List<StackEntry> list) {
        JsonArray a = new JsonArray();
        for (StackEntry e : list) {
            a.add(e.toJson());
        }
        return a;
    }

    public static TracePlan fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        TracePlan p = new TracePlan();
        fill(o.get("used"), p.used);
        fill(o.get("missing"), p.missing);
        fill(o.get("emitted"), p.emitted);
        if (o.has("pt")) {
            for (JsonElement e : o.getAsJsonArray("pt")) {
                if (e.isJsonObject()) {
                    JsonObject po = e.getAsJsonObject();
                    p.patternTimes.add(new PatternTime(
                            po.has("p") ? po.get("p").getAsInt() : -1,
                            po.has("t") ? po.get("t").getAsString() : "0"));
                }
            }
        }
        p.simulation = o.has("sim") && o.get("sim").getAsBoolean();
        p.deliver = o.has("deliver") ? o.get("deliver").getAsString() : null;
        return p;
    }

    private static void fill(JsonElement el, List<StackEntry> into) {
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                StackEntry entry = StackEntry.fromJson(e);
                if (entry != null) {
                    into.add(entry);
                }
            }
        }
    }

    /** Schedule entry: pattern index → times (decimal string). */
    public static final class PatternTime {
        public final int patternIndex;
        public final String times;

        public PatternTime(int patternIndex, String times) {
            this.patternIndex = patternIndex;
            this.times = times;
        }
    }
}
