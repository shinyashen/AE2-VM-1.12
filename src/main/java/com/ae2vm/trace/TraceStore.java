package com.ae2vm.trace;

import com.ae2vm.AE2VM;
import com.ae2vm.config.AE2VMConfig;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Trace file home and retention (design doc §5.5): files live under
 * {@code logs/aevm/traces} of the running server; retention is LAZY —
 * enforced on every write and (M2) on server start — never by a background
 * task. {@code vault.json} lives one level up and is never touched by
 * retention.
 */
public final class TraceStore {

    private TraceStore() {
    }

    /** Server data directory, or null outside a running server (tests). */
    public static Path serverRoot() {
        try {
            net.minecraft.server.MinecraftServer server =
                    FMLCommonHandler.instance().getMinecraftServerInstance();
            return server == null ? null : server.getDataDirectory().toPath();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Path vaultFile() {
        Path root = serverRoot();
        return root == null ? null : root.resolve("logs").resolve("aevm").resolve("vault.json");
    }

    public static synchronized Path tracesDir() {
        Path root = serverRoot();
        if (root == null) {
            return null;
        }
        Path dir = root.resolve("logs").resolve("aevm").resolve("traces");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AE2VM.LOGGER.warn("[AE2-VM] cannot create trace dir {}", dir, e);
            return null;
        }
        return dir;
    }

    /** Assigns (and remembers) the trace's target file. */
    public static synchronized Path allocatePath(String traceId) {
        Path dir = tracesDir();
        return dir == null ? null : dir.resolve("trace-" + traceId + ".aevmtrace.json.gz");
    }

    /**
     * Lazy retention: oldest first by mtime until both the count and byte
     * caps hold. Missing dir / null config paths are no-ops.
     */
    public static synchronized void enforceRetention() {
        Path dir = tracesDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        int maxCount = Math.max(1, AE2VMConfig.traceRetentionCount);
        long maxBytes = Math.max(1024L, AE2VMConfig.traceRetentionMaxBytes);
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> traces = new java.util.ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".aevmtrace.json.gz"))
                    .forEach(traces::add);
            traces.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
            long total = 0;
            for (Path p : traces) {
                total += Files.size(p);
            }
            int i = 0;
            while (i < traces.size() && (traces.size() - i > maxCount || total > maxBytes)) {
                Path oldest = traces.get(i);
                long size = Files.size(oldest);
                if (Files.deleteIfExists(oldest)) {
                    total -= size;
                    AE2VM.LOGGER.debug("[AE2-VM] retention dropped old trace {}", oldest.getFileName());
                }
                i++;
            }
        } catch (IOException e) {
            AE2VM.LOGGER.warn("[AE2-VM] retention sweep failed", e);
        }
    }
}
