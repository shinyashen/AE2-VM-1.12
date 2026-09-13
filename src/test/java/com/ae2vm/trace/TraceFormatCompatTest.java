package com.ae2vm.trace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 acceptance: forward compatibility and integrity semantics — unknown
 * event types/members pass through and verify, a tampered event truncates
 * the timeline at exactly that frame, a tampered payload is flagged, and
 * garbage is rejected with a clear error.
 */
class TraceFormatCompatTest {

    @TempDir
    Path dir;

    @Test
    void unknownEventTypesPassThroughAndVerify() throws IOException {
        TraceFile f = TraceFileRoundTripTest.sample();
        f.segment(TraceSegment.CALC).events.add(new TraceEvent(999, "FUTURE_OP_2099",
                TraceFileRoundTripTest.map("who", "someone-from-the-future")));
        Path p = dir.resolve("future.gz");
        TraceWriter.write(f, p);

        TraceLoader.Result r = TraceLoader.load(p);
        assertTrue(r.intact(), "unknown event types must not break the chain");
        TraceEvent future = r.file.segment(TraceSegment.CALC).events.get(3);
        assertEquals("FUTURE_OP_2099", future.type);
        assertEquals("someone-from-the-future", future.f.get("who"));
    }

    @Test
    void unknownRootMembersAreIgnored() throws IOException {
        byte[] json = TraceWriter.jsonBytes(TraceFileRoundTripTest.sample());
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        root.addProperty("futureRootField", 42);
        Path p = dir.resolve("rootextra.gz");
        writeGz(p, TraceJson.canonical(root).getBytes(StandardCharsets.UTF_8));

        TraceLoader.Result r = TraceLoader.load(p);
        assertTrue(r.intact(), "unknown root members must be tolerated");
        assertEquals(5, TraceFileRoundTripTest.totalEvents(r.file));
    }

    @Test
    void tamperedEventTruncatesAtTheFrame() throws IOException {
        TraceFile f = TraceFileRoundTripTest.sample(); // 3 CALC + 1 START + 1 AUDIT = 5? no: 3+1+1
        byte[] json = TraceWriter.jsonBytes(f);
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();

        // tamper with the second CALC event's field
        JsonObject calcSeg = root.getAsJsonArray("segments").get(0).getAsJsonObject();
        JsonObject ev = calcSeg.getAsJsonArray("events").get(1).getAsJsonObject();
        ev.getAsJsonObject("f").addProperty("picked", "i#EVIL");
        Path p = dir.resolve("tampered.gz");
        writeGz(p, TraceJson.canonical(root).getBytes(StandardCharsets.UTF_8));

        TraceLoader.Result r = TraceLoader.load(p);
        assertEquals(1, r.chainBrokenAt, "truncation must point at the tampered frame");
        assertFalse(r.payloadTruncated);
        assertEquals(1, TraceFileRoundTripTest.totalEvents(r.file), "only the verified prefix survives");
        assertEquals(1, r.file.segment(TraceSegment.CALC).events.size());
        assertEquals(0, r.file.segment(TraceSegment.START).events.size());
    }

    @Test
    void tamperedPayloadIsFlaggedButKept() throws IOException {
        byte[] json = TraceWriter.jsonBytes(TraceFileRoundTripTest.sample());
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        root.getAsJsonObject("payload").getAsJsonObject("snapshot")
                .getAsJsonArray("items").get(0).getAsJsonObject()
                .addProperty("c", "999999");
        Path p = dir.resolve("payload.gz");
        writeGz(p, TraceJson.canonical(root).getBytes(StandardCharsets.UTF_8));

        TraceLoader.Result r = TraceLoader.load(p);
        assertEquals(-1, r.chainBrokenAt, "event chain must still verify");
        assertTrue(r.payloadTruncated, "payload edits must be detected");
    }

    @Test
    void garbageIsRejectedWithAClearError() throws IOException {
        Path p = dir.resolve("garbage.gz");
        writeGz(p, "this is not json at all".getBytes(StandardCharsets.UTF_8));
        assertThrows(TraceFormatException.class, () -> TraceLoader.load(p));

        Path raw = dir.resolve("raw.bin");
        Files.write(raw, new byte[]{0x00, 0x01, 0x02, 0x03});
        assertThrows(Exception.class, () -> TraceLoader.load(raw));
    }

    private static void writeGz(Path p, byte[] json) throws IOException {
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(p))) {
            os.write(json);
        }
    }
}
