package com.ae2vm.trace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import com.ae2vm.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Optional mclo.gs upload: async POST of the
 * (already pseudonymous) trace text, chat-delivered short URL. The
 * upload is OFF by default (op-gated); the primary channel remains the
 * trace FILE itself.
 */
public final class TraceUpload {

    /** mclo.gs public-instance comfort limit (10 MiB hard; stay well under). */
    private static final int MAX_TEXT = 1_500_000;

    private TraceUpload() {
    }

    public static void start(MinecraftServer server, ICommandSender sender, Path file) {
        String name = file.getFileName().toString();
        deliver(server, sender, TraceLang.format("aevm.trace.upload.start", name));
        Thread t = new Thread(() -> {
            try {
                String json = gunzip(Files.readAllBytes(file));
                if (json.length() > MAX_TEXT) {
                    deliver(server, sender, TraceLang.format("aevm.trace.upload.too-big"));
                    return;
                }
                String url = post(json);
                deliver(server, sender, url == null
                        ? TraceLang.format("aevm.trace.upload.failed", "no url in response")
                        : TraceLang.format("aevm.trace.upload.done", url));
            } catch (Exception e) {
                Log.LOG.debug("[AE2-VM] trace upload failed", e);
                deliver(server, sender, TraceLang.format("aevm.trace.upload.failed",
                        e.getClass().getSimpleName()));
            }
        }, "aevm-trace-upload");
        t.setDaemon(true);
        t.start();
    }

    private static String post(String content) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.mclo.gs/1/log")
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("content=" + URLEncoder.encode(content, "UTF-8"))
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code);
        }
        String resp = readAll(conn.getInputStream());
        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
        if (!o.has("success") || !o.get("success").getAsBoolean()) {
            throw new IOException("mclo.gs rejected the paste");
        }
        return o.has("url") ? o.get("url").getAsString() : null;
    }

    private static String gunzip(byte[] gz) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deliver(MinecraftServer server, ICommandSender sender, String line) {
        server.addScheduledTask(() ->
                sender.sendMessage(withClickableUrls(line)));
    }

    /**
     * Split plain chat text on http(s) URLs; each URL becomes an OPEN_URL
     * clickable (1.12 vanilla chat does NOT auto-detect URLs in plain
     * components — the mclo.gs link rendered as dead text on live clients).
     * Trailing punctuation is kept out of the target so a sentence-final URL
     * still opens cleanly.
     */
    private static ITextComponent withClickableUrls(String line) {
        TextComponentString root = new TextComponentString("");
        Matcher m = URL_PATTERN.matcher(line);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                root.appendSibling(new TextComponentString(line.substring(last, m.start())));
            }
            String url = m.group();
            TextComponentString link = new TextComponentString(url);
            Style st = link.getStyle();
            st.setColor(TextFormatting.LIGHT_PURPLE);
            st.setUnderlined(true);
            st.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            st.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new TextComponentString(url)));
            root.appendSibling(link);
            last = m.end();
        }
        if (last < line.length()) {
            root.appendSibling(new TextComponentString(line.substring(last)));
        }
        return root.getSiblings().isEmpty()
                ? new TextComponentString(line) : root;
    }

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");
}
