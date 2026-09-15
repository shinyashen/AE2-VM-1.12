package com.ae2vm.trace;

import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.AE2VM;
import com.ae2vm.config.AE2VMConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import com.ae2vm.Log;

/**
 * Live stall watchdog (design doc §7.4, DEFAULT OFF): AE2UEL's {@code
 * waiting} flag resets every tick and nothing ever detects a stuck CPU.
 * The watchdog fingerprints each CPU per tick (task count + waitingFor
 * summary); when the fingerprint holds for {@code stallWatchdogTicks}
 * ticks with a non-empty waitingFor, the CPU's NBT is dumped once per
 * episode into {@code aevm/} under the server game dir — the evidence for stall shapes no
 * bench can reproduce.
 */
public final class StallWatchdog {

    private static final Map<Object, int[]> STATE = new IdentityHashMap<>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private StallWatchdog() {
    }

    /**
     * Called on every {@code updateCraftingLogic} return.
     *
     * @return true when the caller should dump this CPU's NBT now (once per
     *         stall episode); false when the watchdog is off, the CPU is
     *         progressing, complete, or already dumped for this episode.
     */
    public static boolean shouldDump(Object cluster, boolean complete,
                                     int tasksSize, Iterable<IAEItemStack> waitingFor) {
        int threshold = AE2VMConfig.stallWatchdogTicks;
        if (threshold <= 0 || complete) {
            STATE.remove(cluster);
            return false;
        }
        int wfCount = 0;
        int wfHash = 0;
        if (waitingFor != null) {
            for (IAEItemStack s : waitingFor) {
                if (s == null || s.getStackSize() <= 0) {
                    continue;
                }
                wfCount++;
                wfHash = wfHash * 31
                        + (s.getDefinition() == null ? 0 : s.getDefinition().hashCode())
                        + Long.hashCode(s.getStackSize());
            }
        }
        int fingerprint = (tasksSize * 31 + wfCount) * 31 + wfHash;
        int[] st = STATE.computeIfAbsent(cluster, k -> new int[3]);
        if (st[0] != fingerprint) {
            st[0] = fingerprint;
            st[1] = 1;
            st[2] = 0;
            return false;
        }
        st[1]++;
        if (wfCount > 0 && st[1] >= threshold && st[2] == 0) {
            st[2] = 1;
            Log.LOG.warn("[AE2-VM] stall watchdog: CPU {} unchanged for {} ticks "
                    + "(tasks={}, waitingFor={}) — dumping NBT", cluster, threshold, tasksSize, wfCount);
            return true;
        }
        return false;
    }

    /** Writes the dumped NBT next to the traces; failures never propagate. */
    public static void dump(Object cluster, net.minecraft.nbt.NBTTagCompound data) {
        try {
            Path root = TraceStore.serverRoot();
            if (root == null) {
                return;
            }
            Path dir = root.resolve("aevm");
            Files.createDirectories(dir);
            String name = "stall-" + STAMP.format(new Date())
                    + "-" + Integer.toHexString(System.identityHashCode(cluster)) + ".nbt";
            try (var out = Files.newOutputStream(dir.resolve(name))) {
                net.minecraft.nbt.CompressedStreamTools.writeCompressed(data, out);
            }
            Log.LOG.warn("[AE2-VM] stall dump written: {}", dir.resolve(name));
        } catch (IOException | RuntimeException e) {
            Log.LOG.warn("[AE2-VM] stall dump failed", e);
        }
    }
}
