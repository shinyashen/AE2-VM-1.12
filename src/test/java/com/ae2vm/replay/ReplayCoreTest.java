package com.ae2vm.replay;

import com.ae2vm.trace.StackEntry;
import com.ae2vm.trace.StackSpec;
import com.ae2vm.trace.TraceBytecode;
import com.ae2vm.trace.TraceFile;
import com.ae2vm.trace.TracePattern;
import com.ae2vm.trace.TracePlan;
import com.ae2vm.trace.TraceSnapshot;
import com.ae2vm.vm.Opcode;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;

/**
 * M4 acceptance: a trace rebuilds offline (dummy registry, embedded
 * sub-bytecodes), executes with the CURRENT engine, reproduces the
 * recorded plan token-exactly, and is deterministic across runs; an
 * edited recorded plan produces a visible diff.
 */
class ReplayCoreTest {

    @BeforeAll
    static void bootstrap() {
        // ItemStack construction is guarded until the vanilla registries exist
        Bootstrap.register();
    }

    /** PUSH_ITEM stone x1000; INSERT_OUTPUT stone; HALT — one pattern slot with a HALT-only nested bytecode. */
    private static TraceFile trace(int recordedEmitted) {
        TraceFile f = new TraceFile();
        f.traceId = "replay-test-0001";
        f.vaultId = "ff00";
        f.meta.put("aevm", "test-1.0");

        // program built through the real Builder: PUSH_LONG 1000;
        // PUSH_ITEM stone x1 (pops the request, multiplies by per-craft);
        // INSERT_OUTPUT stone — mirrors the compiler's shape
        IAEItemStack stoneStack =
                AEItemStack.fromItemStack(new ItemStack(Items.COAL));
        CraftingBytecode.Builder b = new CraftingBytecode.Builder();
        int stoneIdx = b.addConstant(stoneStack);
        b.setOutput(stoneIdx, 1000); // deliver target = the requested amount (compileRequest semantics)
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

    @Test
    void replayReproducesTheRecordedPlanAndIsDeterministic() {
        TraceFile f = trace(1000);
        ReplayCore.Report r1 = ReplayCore.replay(f);
        assertTrue(r1.identical, "expected identical, got: " + r1.differences);
        assertEquals(1000, r1.replayedPlan.getDeliverAmount(),
                "the root output is DELIVERY, never emission (buildPlan strips it)");

        ReplayCore.Report r2 = ReplayCore.replay(f);
        assertTrue(r2.identical, "second run must also be identical: " + r2.differences);
        assertEquals(r1.differences, r2.differences, "determinism: same trace, same jar, same output");
        assertEquals(r1.replayedPlan.getDeliverAmount(), r2.replayedPlan.getDeliverAmount());
    }

    @Test
    void mutatedRecordedPlanProducesADiff() {
        ReplayCore.Report r = ReplayCore.replay(trace(999));
        assertFalse(r.identical);
        assertTrue(r.differences.stream().anyMatch(s -> s.startsWith("deliver")
                && s.contains("recorded=999") && s.contains("replayed=1000")),
                r.differences.toString());
    }

    @Test
    void traceWithoutBytecodeIsRejectedCleanly() {
        TraceFile f = new TraceFile();
        f.traceId = "pre-m4";
        IllegalArgumentException e =
                Assertions.assertThrows(
                        IllegalArgumentException.class, () -> ReplayCore.replay(f));
        assertTrue(e.getMessage().contains("bytecode"));
    }
}
