package com.ae2vm.trace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * Shared JSON plumbing for the trace schema. One code path serializes
 * every structure, which is what makes the chained checksum reproducible:
 * the loader re-serializes parsed models through the same canonical form
 * and the bytes must match what the writer produced.
 */
public final class TraceJson {

    /** Trace format version. Bump only for breaking schema changes. */
    public static final int FORMAT = 1;

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TraceJson() {
    }

    /** Canonical serialization — insertion order of the JsonObject is the wire order. */
    public static String canonical(JsonObject o) {
        return GSON.toJson(o);
    }

    /** Event fields must serialize identically across writer and loader: sort keys. */
    public static JsonObject sortedFields(Map<String, String> fields) {
        JsonObject f = new JsonObject();
        for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
            f.addProperty(e.getKey(), e.getValue());
        }
        return f;
    }
}
