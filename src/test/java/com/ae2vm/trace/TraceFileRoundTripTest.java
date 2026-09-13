package com.ae2vm.trace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M0 acceptance: a full trace document survives write(gzip, atomic) → load with intact integrity. */
class TraceFileRoundTripTest {

    @TempDir
    Path dir;

    @Test
    void fullDocumentRoundTripsWithIntactChain() throws IOException {
        TraceFile f = sample();
        Path p = dir.resolve("trace.aevmtrace.json.gz");
        TraceWriter.write(f, p);

        TraceLoader.Result r = TraceLoader.load(p);
        assertTrue(r.intact(), "freshly written trace must verify cleanly");
        assertEquals(-1, r.chainBrokenAt);
        assertEquals(5, totalEvents(r.file));

        // canonical re-serialization of the loaded model must be byte-equal
        assertEquals(TraceJson.canonical(f.toJson()), TraceJson.canonical(r.file.toJson()));
    }

    @Test
    void payloadHashCoversSnapshotBytecodeAndPlan() throws IOException {
        TraceFile f = sample();
        JsonObject root = new com.google.gson.JsonParser()
                .parse(new String(TraceWriter.jsonBytes(f), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertTrue(root.has("payload"), "payload subtree must be present");
        assertTrue(root.has("payloadHash"), "payload hash must be present");
        String recomputed = TraceChain.sha256Hex(TraceJson.canonical(root.getAsJsonObject("payload")));
        assertEquals(root.get("payloadHash").getAsString(), recomputed);
    }

    static int totalEvents(TraceFile f) {
        int n = 0;
        for (TraceSegment s : f.segments) {
            n += s.events.size();
        }
        return n;
    }

    static TraceFile sample() {
        TraceFile f = new TraceFile();
        f.traceId = "20260913-120000-00ff";
        f.vaultId = "abcd1234";
        f.meta.put("aevm", "1.1.0");
        f.meta.put("mc", "1.12.2");
        f.meta.put("side", "SERVER");
        f.meta.put("note", "中文与符号 \"quotes\" {braces}");

        TraceSegment calc = f.segment(TraceSegment.CALC);
        calc.events.add(new TraceEvent(0, "REQUEST", map(
                "what", "i#0f3c", "count", "10000", "source", "player", "player", "p#01")));
        calc.events.add(new TraceEvent(150, "FUZZY_SUBSTITUTE", map(
                "picked", "i#0f9a", "family", "i#0f9a|i#10b2|f#0001",
                "reason", "same-item-same-damage-different-nbt")));
        calc.events.add(new TraceEvent(800, "PLAN_RESULT", map("patterns", "3", "missing", "1")));

        TraceSegment start = f.segment(TraceSegment.START);
        start.events.add(new TraceEvent(9000, "START_EXTRACT", map(
                "item", "i#0f3c", "count", "2500", "ok", "true")));

        f.segment(TraceSegment.AUDIT).events.add(new TraceEvent(9100, "COMMIT", map("state", "plan-complete")));

        f.snapshot = new TraceSnapshot("relevant");
        f.snapshot.items.add(new StackEntry(new StackSpec(false, "i#0f3c", 166, null), "499", false));
        f.snapshot.items.add(new StackEntry(new StackSpec(false, "i#0f9a", 134, "n#5d2"), "64", true));
        f.snapshot.fluids.add(new StackEntry(new StackSpec(true, "f#0007", 0, null), "1000", false));

        f.bytecode = new TraceBytecode();
        f.bytecode.code = Base64.getEncoder().encodeToString(new byte[]{0x01, 0x02, 0x03, 0x40});
        f.bytecode.pool.add(new StackEntry(new StackSpec(false, "i#0f3c", 166, null), "1", false));
        f.bytecode.pool.add(new StackEntry(new StackSpec(false, "i#0f9a", 134, "n#5d2"), "4", false));
        f.bytecode.outputIndex = 1;
        f.bytecode.perCraft = "12";
        TracePattern pat = new TracePattern(false, true, 0);
        pat.condensedInputs.add(new StackEntry(new StackSpec(false, "i#0f3c", 166, null), "3", false));
        pat.condensedOutputs.add(new StackEntry(new StackSpec(false, "i#0f9a", 134, "n#5d2"), "1", false));
        f.bytecode.patterns.add(pat);

        f.plan = new TracePlan();
        f.plan.used.add(new StackEntry(new StackSpec(false, "i#0f3c", 166, null), "2500", false));
        f.plan.missing.add(new StackEntry(new StackSpec(false, "i#0f9a", 134, "n#5d2"), "1250", false));
        f.plan.patternTimes.add(new TracePlan.PatternTime(0, "1250"));
        f.plan.simulation = false;
        return f;
    }

    static TreeMap<String, String> map(String... kv) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
