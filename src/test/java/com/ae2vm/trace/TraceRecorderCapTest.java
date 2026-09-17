package com.ae2vm.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

/** M1: the in-memory session ring — cap evicts the OLDEST events and marks the trace truncated. */
class TraceRecorderCapTest {

    @TempDir
    Path dir;

    @Test
    void capEvictsOldestAndFlagsTruncation() throws IOException {
        TokenVault v = TokenVault.inMemory();
        TraceRecorder rec = new TraceRecorder(v, "testcap-0001", 1000, dir);
        for (int i = 0; i < 1500; i++) {
            TreeMap<String, String> f = new TreeMap<>();
            f.put("i", Integer.toString(i));
            rec.emit(TraceSegment.CALC, "GEN", f);
        }
        assertFalse(rec.isClosed(), "the session stays open after plain events");
        assertTrue(rec.document().truncatedEvents, "overflow must mark the trace truncated");

        rec.finish("cap-test");
        assertTrue(rec.isClosed());

        Path p = dir.resolve("trace-testcap-0001.aevmtrace.json.gz");
        assertTrue(Files.exists(p), "finish must materialize the trace file");
        TraceLoader.Result r = TraceLoader.load(p);
        assertTrue(r.intact(), "the ring-evicted document must still verify");
        assertEquals(1000, TraceFileRoundTripTest.totalEvents(r.file), "cap bounds the document");
        // 1500 GEN events + 1 COMMIT: the COMMIT event itself participates in
        // the ring, so it squeezes out i=500 as well. Surviving GEN = 501..1499.
        assertEquals("501", r.file.segment(TraceSegment.CALC).events.get(0).f.get("i"),
                "the OLDEST events leave first");
        List<TraceEvent> calc = r.file.segment(TraceSegment.CALC).events;
        assertEquals("1499", calc.get(calc.size() - 1).f.get("i"));
        assertEquals("COMMIT", r.file.segment(TraceSegment.AUDIT).events.get(0).type);
    }

    @Test
    void commitEventClosesTheTimeline() throws IOException {
        TokenVault v = TokenVault.inMemory();
        TraceRecorder rec = new TraceRecorder(v, "testcap-0002", 1000, dir);
        rec.finish("started");
        assertTrue(rec.isClosed());
        assertFalse(rec.emit(TraceSegment.AUDIT, "LATE", new TreeMap<String, String>()),
                "events after close are refused");
        TraceLoader.Result r = TraceLoader.load(dir.resolve("trace-testcap-0002.aevmtrace.json.gz"));
        assertEquals("started", r.file.segment(TraceSegment.AUDIT)
                .events.get(r.file.segment(TraceSegment.AUDIT).events.size() - 1).f.get("status"));
    }
}
