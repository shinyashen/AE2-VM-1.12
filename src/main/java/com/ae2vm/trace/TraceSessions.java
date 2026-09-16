package com.ae2vm.trace;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.config.AE2VMConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.ae2vm.Log;

/**
 * Armed-session state machine: traces exist ONLY when a
 * human armed the recorder. {@code NEXT} records the arming player's next
 * player-sourced order (consumed once); {@code WINDOW} (op) records
 * everything until disarmed. Unarmed = no recorder, no events, no vault
 * growth — the fast path is a couple of volatile reads.
 */
public final class TraceSessions {

    private enum Mode { NONE, NEXT, WINDOW }

    private static final Object LOCK = new Object();
    private static Mode mode = Mode.NONE;
    private static UUID armedPlayer;
    private static TokenVault vault;
    private static final Set<TraceRecorder> open = new HashSet<>();
    private static boolean hookInstalled;

    private TraceSessions() {
    }

    /** Arms "record my next order"; returns false when already armed. */
    public static boolean armNext(UUID player) {
        synchronized (LOCK) {
            if (mode != Mode.NONE) {
                return false;
            }
            mode = Mode.NEXT;
            armedPlayer = player;
            return true;
        }
    }

    /** Arms the global window (op): every order until disarm. */
    public static boolean armWindow() {
        synchronized (LOCK) {
            if (mode != Mode.NONE) {
                return false;
            }
            mode = Mode.WINDOW;
            armedPlayer = null;
            return true;
        }
    }

    /** Disarms any state; returns true when something was armed. */
    public static boolean disarm() {
        synchronized (LOCK) {
            boolean was = mode != Mode.NONE;
            mode = Mode.NONE;
            armedPlayer = null;
            return was;
        }
    }

    /** Human-readable arm status for the list command. */
    public static String status() {
        synchronized (LOCK) {
            switch (mode) {
                case NEXT:
                    return "next:" + armedPlayer;
                case WINDOW:
                    return "window";
                default:
                    return "off";
            }
        }
    }

    static boolean armed() {
        synchronized (LOCK) {
            return mode != Mode.NONE;
        }
    }

    /**
     * Arm-state decision shared by the open path: true when an order from
     * {@code player} (null = machine) should be recorded right now. In NEXT
     * mode the arming player's match CONSUMES the arm.
     */
    static boolean shouldRecord(UUID player) {
        synchronized (LOCK) {
            switch (mode) {
                case NEXT:
                    if (player == null || !player.equals(armedPlayer)) {
                        return false; // machine orders never consume another player's slot
                    }
                    mode = Mode.NONE;
                    armedPlayer = null;
                    return true;
                case WINDOW:
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * Opens a recorder for this order when the arm state matches;
     * null = not recording (the caller's zero-overhead path).
     */
    public static TraceRecorder openFor(IActionSource source, IGrid grid,
                                        IAEItemStack what, long amount, boolean simulate) {
        java.util.UUID player = null;
        if (source != null) {
            player = source.player().map(net.minecraft.entity.player.EntityPlayer::getUniqueID)
                    .orElse(null);
        }
        if (!shouldRecord(player)) {
            return null;
        }
        synchronized (LOCK) {
            TokenVault v = vault();
            TraceRecorder rec = newRecorder(v);
            open.add(rec);
            rec.stampRequest(source, player, what, amount, simulate);
            return rec;
        }
    }

    static synchronized TokenVault vault() {
        if (vault == null) {
            Path f = TraceStore.vaultFile();
            try {
                vault = f == null ? TokenVault.inMemory() : TokenVault.open(f);
            } catch (IOException e) {
                Log.LOG.warn("[AE2-VM] vault unavailable, falling back to in-memory tokens", e);
                vault = TokenVault.inMemory();
            }
            installHook();
        }
        return vault;
    }

    static synchronized void register(TraceRecorder rec) {
        open.add(rec);
        installHook();
    }

    static synchronized void unregister(TraceRecorder rec) {
        open.remove(rec);
    }

    public static synchronized void flushAll() {
        for (TraceRecorder r : new HashSet<>(open)) {
            r.finish("server-stop");
        }
        if (vault != null) {
            try {
                vault.flush();
            } catch (IOException e) {
                Log.LOG.warn("[AE2-VM] vault flush failed", e);
            }
        }
    }

    private static void installHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(TraceSessions::flushAll, "aevm-trace-flush"));
    }

    private static TraceRecorder newRecorder(TokenVault v) {
        long wall = System.currentTimeMillis();
        TraceRecorder rec = new TraceRecorder(v, TraceIds.newTraceId(wall),
                AE2VMConfig.traceSessionEventCap);
        register(rec);
        return rec;
    }
}
