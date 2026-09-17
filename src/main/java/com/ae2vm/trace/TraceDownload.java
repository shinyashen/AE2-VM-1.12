package com.ae2vm.trace;

import io.netty.buffer.ByteBuf;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.ae2vm.client.trace.ClientTraceReceiver;
import java.nio.charset.StandardCharsets;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * Server → client trace delivery: gzip bytes chunked
 * into ~28 KiB packets, reassembled client-side into
 * {@code .minecraft/aevm/traces}. Requires the mod on the client — the
 * installed-player delivery channel.
 */
public final class TraceDownload {

    private static final int CHUNK = 28_000;

    private TraceDownload() {
    }

    public static void start(MinecraftServer server, ICommandSender sender, Path file) {
        if (!(sender instanceof EntityPlayerMP)) {
            sendLater(server, sender, TraceLang.format("aevm.trace.download.failed",
                    "download needs a player"));
            return;
        }
        byte[] gz;
        try {
            gz = Files.readAllBytes(file);
        } catch (IOException e) {
            sendLater(server, sender, TraceLang.format("aevm.trace.download.failed", e.toString()));
            return;
        }
        if (gz.length > 8 * 1024 * 1024) {
            sendLater(server, sender, TraceLang.format("aevm.trace.upload.too-big"));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        String name = file.getFileName().toString();
        sendLater(server, sender, TraceLang.format("aevm.trace.download.start",
                name, gz.length));
        int chunks = (gz.length + CHUNK - 1) / CHUNK;
        TraceChannel.WRAPPER.sendTo(new Begin(name, chunks), player);
        for (int i = 0; i < chunks; i++) {
            int from = i * CHUNK;
            int len = Math.min(CHUNK, gz.length - from);
            byte[] part = new byte[len];
            System.arraycopy(gz, from, part, 0, len);
            TraceChannel.WRAPPER.sendTo(new Chunk(i, chunks, name, part), player);
        }
    }

    private static void sendLater(MinecraftServer server, ICommandSender sender, String line) {
        server.addScheduledTask(() ->
                sender.sendMessage(new TextComponentString(line)));
    }

    // ------------------------------------------------------------------
    // wire messages

    public static final class Begin implements IMessage {
        String name;
        int chunks;

        public Begin() {
        }

        Begin(String name, int chunks) {
            this.name = name;
            this.chunks = chunks;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            writeString(buf, name);
            buf.writeInt(chunks);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            name = readString(buf);
            chunks = buf.readInt();
        }
    }

    public static final class Chunk implements IMessage {
        int index;
        int total;
        String name;
        byte[] data = new byte[0];

        public Chunk() {
        }

        Chunk(int index, int total, String name, byte[] data) {
            this.index = index;
            this.total = total;
            this.name = name;
            this.data = data;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(index);
            buf.writeInt(total);
            writeString(buf, name);
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            index = buf.readInt();
            total = buf.readInt();
            name = readString(buf);
            data = new byte[buf.readInt()];
            buf.readBytes(data);
        }
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(b.length);
        buf.writeBytes(b);
    }

    private static String readString(ByteBuf buf) {
        byte[] b = new byte[buf.readInt()];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // client-side handlers — STRICTLY side-guarded; the client receiver is
    // touched through a side check + lazy resolution so a dedicated server
    // never loads client classes.

    public static final class BeginHandler implements IMessageHandler<Begin, IMessage> {
        @Override
        public IMessage onMessage(Begin msg, MessageContext ctx) {
            if (!FMLCommonHandler.instance().getSide().isClient()) {
                return null;
            }
            ClientTraceReceiver.begin(msg.name, msg.chunks);
            return null;
        }
    }

    public static final class ChunkHandler implements IMessageHandler<Chunk, IMessage> {
        @Override
        public IMessage onMessage(Chunk msg, MessageContext ctx) {
            if (!FMLCommonHandler.instance().getSide().isClient()) {
                return null;
            }
            ClientTraceReceiver.chunk(msg.name, msg.total, msg.index, msg.data);
            return null;
        }
    }
}
