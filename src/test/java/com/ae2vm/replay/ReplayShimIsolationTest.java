package com.ae2vm.replay;

import com.ae2vm.config.AE2VMConfig;
import com.ae2vm.trace.StackEntry;
import com.ae2vm.trace.StackSpec;
import com.ae2vm.trace.TraceBytecode;
import com.ae2vm.trace.TraceFile;
import com.ae2vm.trace.TracePattern;
import com.ae2vm.trace.TracePlan;
import com.ae2vm.trace.TraceSnapshot;
import com.ae2vm.trace.TraceWriter;
import com.ae2vm.vm.Opcode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;

/**
 * The shim-isolation gate (the dummy-registry shim): the offline
 * replay classpath is [mod classes, replay-shim classes, gson] with an EMPTY
 * parent loader — no real Minecraft, no AE2UEL. A green run proves the shim
 * surface is complete for the replay path; any engine change that starts
 * touching a real MC/AE2UEL class outside the shim fails here with a
 * NoClassDefFoundError naming the gap. The shim surface may only shrink.
 */
class ReplayShimIsolationTest {

    @BeforeAll
    static void bootstrapFixtureSide() {
        // the FIXTURE is built against the real (dev) registry — the child
        // loader that runs the replay gets the shim instead
        Bootstrap.register();
    }

    /** Same fixture shape as ReplayCoreTest.trace (PUSH_ITEM x1000; INSERT_OUTPUT; HALT). */
    private static TraceFile trace(int recordedEmitted) {
        TraceFile f = new TraceFile();
        f.traceId = "shim-gate-0001";
        f.vaultId = "ff00";
        f.meta.put("aevm", "test-1.0");

        IAEItemStack stoneStack =
                AEItemStack.fromItemStack(new ItemStack(Items.COAL));
        CraftingBytecode.Builder b = new CraftingBytecode.Builder();
        int stoneIdx = b.addConstant(stoneStack);
        b.setOutput(stoneIdx, 1000);
        b.emitPushLong(1000);
        b.emitPushItem(stoneIdx, 1);
        b.emitInsertOutput(stoneIdx);
        CraftingBytecode real = b.build();

        StackSpec stone = new StackSpec(false, "i#0001", 0, null);
        TraceBytecode tb = new TraceBytecode();
        tb.code = Base64.getEncoder().encodeToString(real.getCode());
        tb.pool.add(new StackEntry(stone, "1", false));
        tb.outputIndex = real.getOutputIndex();
        tb.perCraft = Long.toString(real.getOutputAmountPerCraft());
        TracePattern pat = new TracePattern(false, true, 0);
        pat.condensedOutputs.add(new StackEntry(stone, "1", false));
        pat.compiled = new TraceBytecode();
        pat.compiled.code = Base64.getEncoder().encodeToString(new byte[]{(byte) Opcode.HALT.code});
        pat.compiled.outputIndex = 0;
        pat.compiled.perCraft = "1";
        tb.patterns.add(pat);
        f.bytecode = tb;

        f.snapshot = new TraceSnapshot("relevant");
        f.snapshot.items.add(new StackEntry(stone, "1000", false));

        f.plan = new TracePlan();
        f.plan.deliver = Integer.toString(recordedEmitted);
        f.plan.simulation = false;
        return f;
    }

    private static List<URL> urls(String prop) throws Exception {
        List<URL> out = new ArrayList<>();
        for (String d : System.getProperty(prop).split(File.pathSeparator)) {
            if (!d.isEmpty()) {
                out.add(new File(d).toURI().toURL());
            }
        }
        return out;
    }

    @Test
    void replayRunsOnModClassesPlusShimAlone() throws Exception {
        // A2 (CLOSURE-DESIGN 5.6): same recorded-plan caveat as ReplayCoreTest.
        org.junit.jupiter.api.Assumptions.assumeTrue(!AE2VMConfig.closureEnabled);
        TraceFile f = trace(1000);
        TraceWriter.stampChain(f);
        Path file = Files.createTempFile("shim-gate", ".aevmtrace.json.gz");
        TraceWriter.write(f, file);
        file.toFile().deleteOnExit();

        List<URL> cp = new ArrayList<>(urls("ae2vm.gate.main"));
        cp.addAll(urls("ae2vm.gate.shim"));
        cp.add(new File(System.getProperty("ae2vm.gate.gson")).toURI().toURL());

        URLClassLoader child = new URLClassLoader(cp.toArray(new URL[0]), null);
        Class<?> main = child.loadClass("com.ae2vm.replay.ReplayMain");
        // --no-simulate: the fixture is the stock-covered degenerate plan (no
        // scheduled crafts), whose faithful verdict is an idle-plan S4 — the
        // gate pins CLASSPATH isolation (diff path), not verdict semantics.
        Object result = main.getMethod("run", String[].class)
                .invoke(null, (Object) new String[]{"--no-simulate", file.toString()});
        assertNotNull(result);
        assertEquals(0, ((Integer) result).intValue(),
                "mod classes + replay shim + gson must replay identically with no real MC/AE2UEL on the classpath");
    }
}
