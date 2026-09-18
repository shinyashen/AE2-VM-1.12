package com.ae2vm.replay;

import com.ae2vm.Tags;
import com.ae2vm.trace.TraceFile;
import com.ae2vm.trace.TraceLoader;
import com.ae2vm.trace.TraceLogText;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPOutputStream;
import net.minecraft.init.Bootstrap;

/**
 * The replay entry point. Two supported forms:
 * <ul>
 *   <li>standalone — classpath is just [mod jar, ae2vm-replay-shim jar]; the
 *       shim supplies the net.minecraft/appeng stubs the replay exercises;</li>
 *   <li>isolated — the legacy {@link ReplayLauncher} --deps form (real MC +
 *       AE2UEL jars).</li>
 * </ul>
 * The trace source is a local {@code .json.gz} file OR a pasted-trace URL:
 * {@code --from-mclogs https://mclo.gs/ID} fetches the raw paste and recovers
 * the {@link TraceLogText} DATA record (the reversible machine record the
 * uploader appends), so ONE shared link serves both human review and replay.
 * Output goes to stdout; exit code 0 = identical, 1 = differences,
 * 2 = failure, 3 = simulation verdict STALL.
 */
public final class ReplayMain {

    private ReplayMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Returns the process exit code instead of exiting (usable from tests). */
    public static int run(String[] args) {
        String tracePath = null;
        String mclogsUrl = null;
        boolean diff = true;
        boolean simulate = true;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--no-diff".equals(a)) {
                diff = false;
            } else if ("--no-simulate".equals(a)) {
                simulate = false;
            } else if ("--from-mclogs".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--from-mclogs needs a URL");
                    return 2;
                }
                mclogsUrl = args[++i];
            } else if (a.startsWith("--")) {
                System.err.println("unknown flag: " + a);
            } else {
                tracePath = a;
            }
        }
        if (tracePath == null && mclogsUrl == null) {
            System.err.println("usage: java -cp ae2_vm_112.jar:ae2vm-replay-shim.jar "
                    + "com.ae2vm.replay.ReplayMain <trace.json.gz | --from-mclogs https://mclo.gs/ID> "
                    + "[--no-diff] [--no-simulate]");
            return 2;
        }
        try {
            // vanilla registries must exist before any ItemStack is built
            // (no-op under the replay shim's virtual registry)
            Bootstrap.register();
            Path p;
            if (mclogsUrl != null) {
                p = fetchMclogs(mclogsUrl);
                System.out.println("trace source: " + mclogsUrl);
            } else {
                p = Paths.get(tracePath);
            }
            TraceLoader.Result r = TraceLoader.load(p);
            TraceFile f = r.file;
            if (!r.intact()) {
                System.err.println("WARNING: trace integrity: "
                        + (r.chainBrokenAt >= 0 ? "chain broken at event " + r.chainBrokenAt + "; " : "")
                        + (r.payloadTruncated ? "payload edited; " : "")
                        + "continuing with the verified prefix.");
            }
            String recordedVersion = f.meta.get("aevm");
            if (recordedVersion != null && !recordedVersion.equals(Tags.VERSION)) {
                System.out.println("note: recorded by AE2-VM " + recordedVersion
                        + ", replaying with " + Tags.VERSION
                        + " — differences are evidence, not verdicts.");
            }
            ReplayCore.Report report = ReplayCore.replay(f, simulate);
            System.out.println("replay: patterns=" + report.replayedPlan.getPatternTimes().size()
                    + " used=" + report.replayedPlan.getUsedItems().size()
                    + " missing=" + report.replayedPlan.getMissingItems().size()
                    + " emitted=" + report.replayedPlan.getEmittedItems().size()
                    + " simulation=" + report.replayedPlan.isSimulation());
            if (report.verdict != null) {
                System.out.println("simulate: " + report.verdict.status
                        + (report.verdict.stallClass == null ? "" : " " + report.verdict.stallClass)
                        + " delivered=" + report.verdict.delivered + "/" + "requested, steps="
                        + report.verdict.steps);
                for (String ev : report.verdict.evidence) {
                    System.out.println("  ! " + ev);
                }
                if (report.verdict.status == VirtualCPUCluster.Verdict.Status.STALL) {
                    return 3;
                }
            }
            if (!diff) {
                return 0;
            }
            if (report.identical) {
                System.out.println("diff: identical — the current engine reproduces the recorded plan exactly.");
                return 0;
            }
            System.out.println("diff: " + report.differences.size() + " difference(s):");
            for (String d : report.differences) {
                System.out.println("  - " + d);
            }
            return 1;
        } catch (IOException | RuntimeException e) {
            System.err.println("replay failed: " + e);
            return 2;
        }
    }

    /**
     * Fetches the paste's raw text and rebuilds the trace from its
     * {@link TraceLogText} DATA record into a temp gzip file the regular
     * loader consumes.
     */
    private static Path fetchMclogs(String url) throws IOException {
        String raw = rawUrlOf(url);
        String text = httpGet(raw);
        String data = TraceLogText.extractDataJson(text);
        if (data == null) {
            throw new IOException("no [TRACE/DATA] record in the paste — not an "
                    + "AE2-VM trace rendered by this engine (or a pre-DATA upload)");
        }
        Path tmp = Files.createTempFile("aevm-trace-", ".json.gz");
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            os.write(data.getBytes(StandardCharsets.UTF_8));
        }
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** {@code https://mclo.gs/ID} → raw endpoint; raw URLs pass through. */
    private static String rawUrlOf(String url) {
        if (url.startsWith("https://api.mclo.gs/1/raw/")) {
            return url;
        }
        int slash = url.lastIndexOf('/');
        String id = slash < 0 ? url : url.substring(slash + 1);
        return "https://api.mclo.gs/1/raw/" + id;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " for " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
