package com.ae2vm.trace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * One decision-point record inside a trace segment. Events carry only
 * stringly-typed structured fields (discipline: free-text
 * reasons must be pre-rendered through the codec, never raw exception
 * messages — they embed real item names).
 *
 * <p>Integrity: every event carries {@code h}, the chained SHA-256 over
 * all previous events plus this event's canonical JSON (computed by
 * {@link TraceWriter}). The hash covers an event WITHOUT its own "h" —
 * re-serialized canonically from (type, t, f) so the loader can verify
 * byte-identically regardless of the file's key order.
 */
public final class TraceEvent {

    /** Relative microseconds (monotonic clock anchor = session start). */
    public final long tUs;

    /** Event type string — open set; loaders skip unknown types but still verify them. */
    public final String type;

    /** Structured fields, sorted at construction for canonical serialization. */
    public final TreeMap<String, String> f;

    /** Chained hash up to and including this event; set by the writer. */
    public String h;

    public TraceEvent(long tUs, String type, Map<String, String> fields) {
        this.tUs = tUs;
        this.type = type;
        this.f = new TreeMap<>(fields);
    }

    /** Canonical payload — deliberately excludes {@link #h}. */
    public JsonObject canonical() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("t", tUs);
        o.add("f", TraceJson.sortedFields(f));
        return o;
    }

    public JsonObject toJson() {
        JsonObject o = canonical();
        if (h != null) {
            o.addProperty("h", h);
        }
        return o;
    }

    public static TraceEvent fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        JsonElement t = o.get("t");
        JsonElement type = o.get("type");
        if (t == null || type == null) {
            return null;
        }
        TreeMap<String, String> f = new TreeMap<>();
        if (o.has("f") && o.get("f").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("f").entrySet()) {
                JsonElement v = e.getValue();
                f.put(e.getKey(), v.isJsonNull() ? null : v.getAsString());
            }
        }
        TraceEvent ev = new TraceEvent(t.getAsLong(), type.getAsString(), f);
        if (o.has("h")) {
            ev.h = o.get("h").getAsString();
        }
        return ev;
    }
}
