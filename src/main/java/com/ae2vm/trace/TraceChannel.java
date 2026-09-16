package com.ae2vm.trace;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The mod's own network channel — used only by trace download (S2C).
 * Handlers live in the common package and are strictly side-guarded; the
 * client-side receiver is reached through lazy class resolution so a
 * dedicated server never loads client classes.
 */
public final class TraceChannel {

    public static SimpleNetworkWrapper WRAPPER;
    private static boolean registered;

    private TraceChannel() {
    }

    /** Call once from preInit on BOTH sides (discriminator tables must match). */
    public static synchronized void init() {
        if (registered) {
            return;
        }
        registered = true;
        WRAPPER = NetworkRegistry.INSTANCE.newSimpleChannel("aevm_trace");
        WRAPPER.registerMessage(TraceDownload.BeginHandler.class, TraceDownload.Begin.class, 0, Side.CLIENT);
        WRAPPER.registerMessage(TraceDownload.ChunkHandler.class, TraceDownload.Chunk.class, 1, Side.CLIENT);
    }
}
