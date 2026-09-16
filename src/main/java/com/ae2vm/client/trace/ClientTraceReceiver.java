package com.ae2vm.client.trace;

import com.ae2vm.trace.TraceLang;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * CLIENT-ONLY receiver for trace downloads . Loaded lazily — the
 * side-guarded common handlers are the only callers, so a dedicated
 * server never touches this class. Reassembles chunks on the client
 * thread and saves under {@code .minecraft/aevm/traces}.
 */
public final class ClientTraceReceiver {

    private static String name;
    private static int total;
    private static byte[][] parts;
    private static int received;

    private ClientTraceReceiver() {
    }

    public static void begin(String traceName, int chunks) {
        run(() -> {
            name = traceName;
            total = chunks;
            parts = new byte[chunks][];
            received = 0;
        });
    }

    public static void chunk(String traceName, int totalChunks, int index, byte[] data) {
        run(() -> {
            if (parts == null || !traceName.equals(name) || totalChunks != total
                    || index < 0 || index >= parts.length || parts[index] != null) {
                return;
            }
            parts[index] = Arrays.copyOf(data, data.length);
            received++;
            if (received == total) {
                save();
                parts = null;
            }
        });
    }

    private static void save() {
        int size = 0;
        for (byte[] p : parts) {
            size += p.length;
        }
        byte[] all = new byte[size];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        try {
            Path dir = Minecraft.getMinecraft().gameDir.toPath()
                    .resolve("aevm").resolve("traces");
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), all);
            message(TraceLang.format("aevm.trace.download.saved", name));
        } catch (IOException e) {
            message(TraceLang.format("aevm.trace.download.failed", e.toString()));
        }
    }

    private static void message(String line) {
        Minecraft.getMinecraft().player.sendMessage(new TextComponentString("[AE2-VM] " + line));
    }

    private interface VoidTask {
        void run();
    }

    private static void run(VoidTask task) {
        if (!FMLCommonHandler.instance().getSide().isClient()) {
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(task::run);
    }
}
