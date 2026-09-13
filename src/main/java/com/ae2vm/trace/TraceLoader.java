package com.ae2vm.trace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Hardened trace reader (design doc §3.1): verifies the per-event chain
 * and the payload hash; on the first bad event keeps the verified prefix
 * and flags the truncation point instead of failing the whole file
 * (SQLite WAL bad-frame semantics). Unknown event types / unknown members
 * pass through untouched — the format must outlive the code versions.
 */
public final class TraceLoader {

    private TraceLoader() {
    }

    public static final class Result {
        public final TraceFile file;
        /** Index (global, across segments) of the first event failing chain verification; -1 when intact. */
        public final int chainBrokenAt;
        /** True when the payload subtree failed its hash (content untrusted but preserved). */
        public final boolean payloadTruncated;

        Result(TraceFile file, int chainBrokenAt, boolean payloadTruncated) {
            this.file = file;
            this.chainBrokenAt = chainBrokenAt;
            this.payloadTruncated = payloadTruncated;
        }

        public boolean intact() {
            return chainBrokenAt < 0 && !payloadTruncated;
        }
    }

    public static Result load(Path path) throws IOException {
        String json;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (ZipException e) {
            throw new TraceFormatException("not a gzip trace: " + path, e);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new TraceFormatException("trace JSON unparsable: " + path, e);
        }
        TraceFile file = TraceFile.fromJson(root);

        // verify the event chain; on the first bad frame keep the prefix only
        String prev = TraceChain.genesis(file.traceId == null ? "?" : file.traceId);
        int index = 0;
        int brokenAt = -1;
        outer:
        for (TraceSegment s : file.segments) {
            for (TraceEvent e : s.events) {
                String expected = TraceChain.next(prev, TraceJson.canonical(e.canonical()));
                if (e.h == null || !expected.equals(e.h)) {
                    brokenAt = index;
                    break outer;
                }
                prev = e.h;
                index++;
            }
        }
        if (brokenAt >= 0) {
            int before = 0;
            boolean cutting = false;
            for (TraceSegment s : file.segments) {
                if (cutting) {
                    s.events.clear();
                    continue;
                }
                int size = s.events.size();
                if (before + size > brokenAt) {
                    int keep = brokenAt - before;
                    while (s.events.size() > keep) {
                        s.events.remove(s.events.size() - 1);
                    }
                    cutting = true;
                } else {
                    before += size;
                }
            }
        }

        boolean payloadBad = false;
        if (root.has("payloadHash") && root.has("payload") && root.get("payload").isJsonObject()) {
            String expected = TraceChain.sha256Hex(TraceJson.canonical(root.getAsJsonObject("payload")));
            payloadBad = !expected.equals(root.get("payloadHash").getAsString());
        }
        return new Result(file, brokenAt, payloadBad);
    }
}
