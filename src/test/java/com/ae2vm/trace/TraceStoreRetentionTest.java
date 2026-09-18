package com.ae2vm.trace;

import com.ae2vm.config.AE2VMConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2: lazy retention — count/byte caps evict oldest first, vault never touched. */
class TraceStoreRetentionTest {

    @TempDir
    Path dir;

    private Path writeTrace(String name) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, new byte[]{1, 2, 3});
        return p;
    }

    @Test
    void countCapEvictsOldestFirstAndKeepsVault() throws Exception {
        Path oldest = writeTrace("trace-a.aevmtrace.json.gz");
        Path middle = writeTrace("trace-b.aevmtrace.json.gz");
        Path newest = writeTrace("trace-c.aevmtrace.json.gz");
        // the real vault lives in aevm/ (outside traces/); a stray file
        // here stands in for "anything that is not a trace must survive"
        Path vault = dir.resolve("vault.json");
        Files.write(vault, new byte[]{9});
        oldest.toFile().setLastModified(1_000_000L);
        middle.toFile().setLastModified(2_000_000L);
        newest.toFile().setLastModified(3_000_000L);

        int savedCount = AE2VMConfig.traceRetentionCount;
        try {
            AE2VMConfig.traceRetentionCount = 2;
            TraceStore.enforceRetention(dir);
        } finally {
            AE2VMConfig.traceRetentionCount = savedCount;
        }
        assertTrue(Files.exists(middle) && Files.exists(newest));
        assertFalse(Files.exists(oldest), "the oldest trace must be evicted");
        assertTrue(Files.exists(vault), "non-trace files must never be deleted");

        List<Path> traces = list().stream()
                .filter(pp -> pp.getFileName().toString().endsWith(".aevmtrace.json.gz"))
                .collect(Collectors.toList());
        assertEquals(2, traces.size());
    }

    @Test
    void byteCapApplies() throws Exception {
        Path big1 = writeTrace("trace-big1.aevmtrace.json.gz");
        Path big2 = writeTrace("trace-big2.aevmtrace.json.gz");
        Files.write(big1, new byte[600]);
        Files.write(big2, new byte[600]);
        big1.toFile().setLastModified(1_000_000L);
        big2.toFile().setLastModified(2_000_000L);

        int savedCount = AE2VMConfig.traceRetentionCount;
        int savedBytes = AE2VMConfig.traceRetentionMaxBytes;
        try {
            AE2VMConfig.traceRetentionCount = 10;
            AE2VMConfig.traceRetentionMaxBytes = 1024; // one file max
            TraceStore.enforceRetention(dir);
        } finally {
            AE2VMConfig.traceRetentionCount = savedCount;
            AE2VMConfig.traceRetentionMaxBytes = savedBytes;
        }
        assertFalse(Files.exists(big1), "byte cap must evict the oldest first");
        assertTrue(Files.exists(big2));
    }

    private List<Path> list() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.collect(Collectors.toList());
        }
    }
}
