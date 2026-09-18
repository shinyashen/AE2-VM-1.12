package com.ae2vm.trace;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Renders a {@link TraceFile} as Minecraft-log-shaped lines so paste sites
 * with native MC-log highlighting (mclo.gs) colorize it for free: every line
 * matches the vanilla log regex {@code [HH:MM:SS] [PHASE/LEVEL]: message}.
 *
 * <p>Line anatomy:
 * <ul>
 *   <li>the timestamp column is the REAL engine processing time (event
 *       {@code tUs} scaled to seconds) — it flows for long jobs and honestly
 *       stays at zero for sub-second ones;</li>
 *   <li>{@code t=<us>us} is carried as an ordinary message field (the exact
 *       microseconds have no honest place in the timestamp column);</li>
 *   <li>the level is derived from the event type: invariant violations render
 *       as ERROR and solver fallbacks as WARN, everything else INFO.</li>
 * </ul>
 *
 * <p>The LAST line is the reversible machine record:
 * {@code [HH:MM:SS] [TRACE/DATA]: {original trace JSON}} — the full model
 * (snapshot, bytecode, plan, hash chain) survives verbatim, so an importer
 * can rebuild the TraceFile from a pasted link without a second transfer
 * ({@link #extractDataJson}). The in-memory file lives under
 * {@code logs/aevm/traces} and remains the source of truth.
 */
public final class TraceLogText {

    /** Marks the machine-record line inside rendered text. */
    public static final String DATA_PREFIX = "[TRACE/DATA]: ";

    private TraceLogText() {
    }

    /** Renders the whole trace as paste-ready log lines (LF separated). */
    public static String render(TraceFile f) {
        StringBuilder sb = new StringBuilder();
        line(sb, 0, "TRACE", "INFO", "AE2-VM crafting trace "
                + f.traceId + (f.vaultId == null ? "" : " (vault " + f.vaultId + ")"));
        if (!f.meta.isEmpty()) {
            StringBuilder meta = new StringBuilder();
            for (Map.Entry<String, String> e : f.meta.entrySet()) {
                meta.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            line(sb, 0, "TRACE", "INFO", "engine" + meta);
        }
        line(sb, 0, "TRACE", "INFO",
                "timeline = engine processing time since request start; t= field is exact microseconds");
        for (TraceSegment s : f.segments) {
            for (TraceEvent e : s.events) {
                StringBuilder msg = new StringBuilder(e.type).append(" t=").append(e.tUs).append("us");
                for (Map.Entry<String, String> kv : e.f.entrySet()) {
                    msg.append(' ').append(kv.getKey()).append('=').append(kv.getValue());
                }
                line(sb, e.tUs, s.phase, levelOf(e.type), msg.toString());
            }
        }
        if (f.plan != null) {
            StringBuilder pt = new StringBuilder();
            for (TracePlan.PatternTime p : f.plan.patternTimes) {
                if (pt.length() > 0) {
                    pt.append(',');
                }
                pt.append(p.patternIndex).append(':').append(p.times);
            }
            line(sb, 0, "PLAN", "INFO", "PLAN deliver=" + f.plan.deliver
                    + " simulation=" + f.plan.simulation + " patternTimes=" + pt);
            planEntries(sb, "USED", f.plan.used);
            planEntries(sb, "MISSING", f.plan.missing);
            planEntries(sb, "EMITTED", f.plan.emitted);
        }
        JsonObject json = f.toJson();
        sb.append(linePrefix(0)).append(" [TRACE/DATA]: ").append(json.toString()).append('\n');
        return sb.toString();
    }

    /**
     * Recovers the machine record from rendered text: returns the JSON of the
     * last {@link #DATA_PREFIX} line, or null when the text carries no DATA
     * line (a foreign paste, or a trace rendered before this format existed).
     */
    public static String extractDataJson(String renderedText) {
        int from = 0;
        String found = null;
        while (true) {
            int at = renderedText.indexOf(DATA_PREFIX, from);
            if (at < 0) {
                return found;
            }
            int jsonStart = at + DATA_PREFIX.length();
            int lineEnd = renderedText.indexOf('\n', jsonStart);
            found = lineEnd < 0
                    ? renderedText.substring(jsonStart)
                    : renderedText.substring(jsonStart, lineEnd);
            from = lineEnd < 0 ? renderedText.length() : lineEnd + 1;
        }
    }

    private static void planEntries(StringBuilder sb, String label, java.util.List<StackEntry> entries) {
        for (StackEntry e : entries) {
            String token = e.spec == null ? "?" : e.spec.token;
            line(sb, 0, "PLAN", "INFO", label + " " + e.count + "x " + token);
        }
    }

    /** ERROR for invariant violations, WARN for fallbacks, INFO otherwise. */
    private static String levelOf(String eventType) {
        if ("INVARIANT_VIOLATION".equals(eventType)) {
            return "ERROR";
        }
        if ("FALLBACK".equals(eventType)) {
            return "WARN";
        }
        return "INFO";
    }

    private static void line(StringBuilder sb, long tUs, String phase, String level, String message) {
        sb.append(linePrefix(tUs)).append(" [").append(phase).append('/').append(level)
                .append("]: ").append(message).append('\n');
    }

    /** Vanilla log timestamp shape {@code [HH:MM:SS]} from engine microseconds. */
    private static String linePrefix(long tUs) {
        long total = tUs / 1_000_000L;
        return String.format("[%02d:%02d:%02d]", total / 3600L, (total / 60L) % 60L, total % 60L);
    }
}
