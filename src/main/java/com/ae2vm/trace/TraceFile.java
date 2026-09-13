package com.ae2vm.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The trace document — one order's lifecycle (design doc §2/§3). Field
 * set is deliberately minimal and forward-compatible: readers ignore
 * unknown members, unknown event types verify but carry no semantics
 * (WAL-style "skip, don't fail").
 *
 * <p>Integrity has two layers: the per-event chained hash ({@code chain},
 * final link) covering the event timeline, and {@code payloadHash} over
 * the raw payload subtree (snapshot/bytecode/plan) so editing a plan
 * without touching events is still detected. The loader verifies both and
 * truncates/flags at the first mismatch.
 */
public final class TraceFile {

    public int format = TraceJson.FORMAT;

    /** Human-facing handle: "yyyyMMdd-HHmmss-xxxx". */
    public String traceId;

    /** Which server vault maps this trace's tokens (multi-server bookkeeping). */
    public String vaultId;

    /** Version stamps and environment (aevm/mc/forge/ae2/modlist-hash/side/...). */
    public final Map<String, String> meta = new TreeMap<>();

    public final List<TraceSegment> segments = new ArrayList<>();

    public TraceSnapshot snapshot;

    public TraceBytecode bytecode;

    public TracePlan plan;

    /** True when the recorder dropped events at capture time (memory cap). */
    public boolean truncatedEvents;

    public TraceSegment segment(String phase) {
        for (TraceSegment s : segments) {
            if (s.phase.equals(phase)) {
                return s;
            }
        }
        TraceSegment s = new TraceSegment(phase);
        segments.add(s);
        return s;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", format);
        root.addProperty("traceId", traceId);
        root.addProperty("vaultId", vaultId);
        root.add("meta", TraceJson.sortedFields(meta));
        JsonArray segs = new JsonArray();
        for (TraceSegment s : segments) {
            JsonObject seg = new JsonObject();
            seg.addProperty("phase", s.phase);
            JsonArray evs = new JsonArray();
            for (TraceEvent e : s.events) {
                evs.add(e.toJson());
            }
            seg.add("events", evs);
            segs.add(seg);
        }
        root.add("segments", segs);
        root.add("payload", payloadOf(this));
        root.addProperty("truncatedEvents", truncatedEvents);
        root.addProperty("chain", chainOrNull());
        return root;
    }

    /** Canonical payload subtree (deterministic from the model). */
    public static JsonObject payloadOf(TraceFile f) {
        JsonObject payload = new JsonObject();
        if (f.snapshot != null) {
            payload.add("snapshot", f.snapshot.toJson());
        }
        if (f.bytecode != null) {
            payload.add("bytecode", f.bytecode.toJson());
        }
        if (f.plan != null) {
            payload.add("plan", f.plan.toJson());
        }
        return payload;
    }

    private String chainOrNull() {
        for (int s = segments.size() - 1; s >= 0; s--) {
            List<TraceEvent> evs = segments.get(s).events;
            for (int i = evs.size() - 1; i >= 0; i--) {
                if (evs.get(i).h != null) {
                    return evs.get(i).h;
                }
            }
        }
        return TraceChain.genesis(traceId == null ? "?" : traceId);
    }

    public static TraceFile fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            throw new TraceFormatException("trace root is not a JSON object");
        }
        JsonObject o = el.getAsJsonObject();
        int fmt = o.has("format") ? o.get("format").getAsInt() : -1;
        if (fmt != TraceJson.FORMAT) {
            throw new TraceFormatException("unsupported trace format: " + fmt);
        }
        TraceFile f = new TraceFile();
        f.traceId = o.has("traceId") ? o.get("traceId").getAsString() : "unknown";
        f.vaultId = o.has("vaultId") ? o.get("vaultId").getAsString() : null;
        if (o.has("meta") && o.get("meta").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("meta").entrySet()) {
                f.meta.put(e.getKey(), e.getValue().getAsString());
            }
        }
        if (o.has("segments")) {
            for (JsonElement se : o.getAsJsonArray("segments")) {
                JsonObject so = se.getAsJsonObject();
                TraceSegment seg = new TraceSegment(so.has("phase") ? so.get("phase").getAsString() : "?");
                if (so.has("events")) {
                    for (JsonElement ee : so.getAsJsonArray("events")) {
                        TraceEvent ev = TraceEvent.fromJson(ee);
                        if (ev != null) {
                            seg.events.add(ev);
                        }
                    }
                }
                f.segments.add(seg);
            }
        }
        if (o.has("payload") && o.get("payload").isJsonObject()) {
            JsonObject p = o.getAsJsonObject("payload");
            f.snapshot = TraceSnapshot.fromJson(p.get("snapshot"));
            f.bytecode = TraceBytecode.fromJson(p.get("bytecode"));
            f.plan = TracePlan.fromJson(p.get("plan"));
        }
        f.truncatedEvents = o.has("truncatedEvents") && o.get("truncatedEvents").getAsBoolean();
        return f;
    }
}
