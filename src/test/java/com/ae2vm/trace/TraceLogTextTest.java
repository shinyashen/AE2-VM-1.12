package com.ae2vm.trace;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paste rendering: vanilla-log-shaped lines (mclo.gs colors them natively)
 * whose level is derived from the event type, plus the reversible DATA record —
 * the full model must survive render → extract → fromJson.
 */
class TraceLogTextTest {

    @Test
    void rendersVanillaShapedLinesWithExactMicroseconds() {
        TraceFile f = TraceFileRoundTripTest.sample();
        String text = TraceLogText.render(f);
        for (String line : text.split("\n")) {
            if (line.contains(TraceLogText.DATA_PREFIX)) {
                continue; // the machine record: JSON payload, no level enum
            }
            assertTrue(line.matches("\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[A-Z]+/(INFO|WARN|ERROR)\\]: .+"),
                    "vanilla log shape: " + line);
        }
        assertTrue(text.startsWith("[00:00:00] [TRACE/INFO]: AE2-VM crafting trace " + f.traceId),
                "header identifies the trace");
        assertTrue(text.contains("] [CALC/INFO]: REQUEST t=0us count=10000"), "event line with exact t=");
    }

    @Test
    void timestampColumnFlowsWithProcessingTime() {
        TraceFile f = new TraceFile();
        f.traceId = "t";
        TraceSegment s = f.segment("CALC");
        s.events.add(new TraceEvent(3_723_000_000L, "REQUEST", new TreeMap<>()));
        String text = TraceLogText.render(f);
        assertTrue(text.contains("[01:02:03] [CALC/INFO]: REQUEST t=3723000000us"),
                "3,723s of processing renders as 01:02:03");
    }

    @Test
    void invariantViolationsRenderAsErrorsAndFallbacksAsWarnings() {
        TraceFile f = new TraceFile();
        f.traceId = "t";
        TraceSegment audit = f.segment("AUDIT");
        audit.events.add(new TraceEvent(10L, "INVARIANT_VIOLATION",
                single("rule", "MISSING-COVERAGE:k net=-252")));
        audit.events.add(new TraceEvent(11L, "FALLBACK", new TreeMap<>()));
        audit.events.add(new TraceEvent(12L, "REQUEST", new TreeMap<>()));
        String text = TraceLogText.render(f);
        assertTrue(text.contains("[AUDIT/ERROR]: INVARIANT_VIOLATION t=10us rule=MISSING-COVERAGE:k net=-252"));
        assertTrue(text.contains("[AUDIT/WARN]: FALLBACK t=11us"));
        assertTrue(text.contains("[AUDIT/INFO]: REQUEST t=12us"));
    }

    @Test
    void dataLineRoundTripsTheFullModel() {
        TraceFile f = TraceFileRoundTripTest.sample();
        String text = TraceLogText.render(f);
        String data = TraceLogText.extractDataJson(text);
        assertNotNull(data, "the rendered text must carry the DATA record");
        TraceFile back = TraceFile.fromJson(JsonParser.parseString(data).getAsJsonObject());
        assertEquals(f.traceId, back.traceId);
        assertEquals(f.meta, back.meta);
        assertEquals(TraceFileRoundTripTest.totalEvents(f), TraceFileRoundTripTest.totalEvents(back));
        assertEquals(TraceJson.canonical(f.toJson()), TraceJson.canonical(back.toJson()),
                "the DATA record must rebuild the model losslessly");
    }

    @Test
    void extractReturnsNullWithoutDataRecord() {
        assertNull(TraceLogText.extractDataJson("[00:00:00] [CALC/INFO]: just lines\nno data here\n"));
        String data = TraceLogText.extractDataJson(
                "[00:00:00] [TRACE/DATA]: {\"a\":1}\n[00:00:01] [TRACE/DATA]: {\"b\":2}\n");
        assertEquals("{\"b\":2}", data, "the LAST DATA record wins");
    }

    private static Map<String, String> single(String k, String v) {
        return Collections.singletonMap(k, v);
    }
}
