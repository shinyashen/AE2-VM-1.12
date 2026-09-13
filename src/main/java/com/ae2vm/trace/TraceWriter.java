package com.ae2vm.trace;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes a trace: stamps the per-event chain, hashes the payload
 * subtree, writes gzip atomically (tmp + ATOMIC_MOVE so a crash never
 * leaves half a trace under the final name — design doc §5.5).
 */
public final class TraceWriter {

    private TraceWriter() {
    }

    /** Assigns every event its chained hash; idempotent (recomputes all). */
    public static void stampChain(TraceFile file) {
        String prev = TraceChain.genesis(file.traceId == null ? "?" : file.traceId);
        for (TraceSegment s : file.segments) {
            for (TraceEvent e : s.events) {
                e.h = TraceChain.next(prev, TraceJson.canonical(e.canonical()));
                prev = e.h;
            }
        }
    }

    public static String payloadHash(TraceFile file) {
        return TraceChain.sha256Hex(TraceJson.canonical(TraceFile.payloadOf(file)));
    }

    public static byte[] jsonBytes(TraceFile file) {
        stampChain(file);
        JsonObject root = file.toJson();
        root.addProperty("payloadHash", payloadHash(file));
        return TraceJson.canonical(root).getBytes(StandardCharsets.UTF_8);
    }

    public static void write(TraceFile file, Path out) throws IOException {
        byte[] json = jsonBytes(file);
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".tmp");
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            os.write(json);
        }
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
