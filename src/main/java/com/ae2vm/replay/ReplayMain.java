package com.ae2vm.replay;

import com.ae2vm.Tags;
import com.ae2vm.trace.TraceFile;
import com.ae2vm.trace.TraceLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The replay entry point. Two supported forms:
 * <ul>
 *   <li>standalone — classpath is just [mod jar, ae2vm-replay-shim jar]; the
 *       shim supplies the net.minecraft/appeng stubs the replay exercises;</li>
 *   <li>isolated — the legacy {@link ReplayLauncher} --deps form (real MC +
 *       AE2UEL jars).</li>
 * </ul>
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
        boolean diff = true;
        boolean simulate = true;
        for (String a : args) {
            if ("--no-diff".equals(a)) {
                diff = false;
            } else if ("--no-simulate".equals(a)) {
                simulate = false;
            } else if (a.startsWith("--")) {
                System.err.println("unknown flag: " + a);
            } else {
                tracePath = a;
            }
        }
        if (tracePath == null) {
            System.err.println("usage: java -cp ae2_vm_112.jar:ae2vm-replay-shim.jar "
                    + "com.ae2vm.replay.ReplayMain <trace.json.gz> [--no-diff] [--no-simulate]");
            return 2;
        }
        try {
            // vanilla registries must exist before any ItemStack is built
            // (no-op under the replay shim's virtual registry)
            net.minecraft.init.Bootstrap.register();
            Path p = Paths.get(tracePath);
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
                        + " — differences are evidence, not verdicts (design doc §6.4).");
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
}
