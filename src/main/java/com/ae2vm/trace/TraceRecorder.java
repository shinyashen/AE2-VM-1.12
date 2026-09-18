package com.ae2vm.trace;

import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.NetworkCraftingSandbox;
import com.ae2vm.vm.VMPlan;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import com.ae2vm.Log;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import com.ae2vm.Tags;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.VMCounter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.minecraftforge.fml.common.Loader;

/**
 * One armed session: owns the in-memory trace document,
 * mints tokenized events at the engine's decision points, and writes the
 * file at phase boundaries. Events carry token ids only; the CALC-phase
 * recorder is reachable from engine internals via the thread-local
 * {@code current()} (the calculation runs single-threaded per job).
 *
 * <p>Write policy: the file lands as soon as the calculation ends (an
 * abandoned confirm screen must not lose the evidence) and is REWRITTEN
 * when START lands; {@code finish} stamps COMMIT and frees the session.
 * The engine's free-text rule: exception messages embed real item
 * names — record exception CLASS names, never messages.
 */
public final class TraceRecorder {

    private static final ThreadLocal<TraceRecorder> CURRENT = new ThreadLocal<>();

    private final TokenVault vault;
    private final StackCodec codec;
    private final TraceFile file;
    private final int cap;
    private final long startNanos = System.nanoTime();
    private final IdentityHashMap<Object, Integer> patternIndices = new IdentityHashMap<>();
    private CraftingBytecode rootBytecode;
    private final Path baseDir; // test injection; null = server trace dir
    private Path target;
    private boolean closed;
    private int eventCount;
    private int droppedCount;

    TraceRecorder(TokenVault vault, String traceId, int cap) {
        this(vault, traceId, cap, null);
    }

    TraceRecorder(TokenVault vault, String traceId, int cap, Path baseDir) {
        this.vault = vault;
        this.codec = new StackCodec(vault);
        this.file = new TraceFile();
        this.file.traceId = traceId;
        this.file.vaultId = vault.getVaultId();
        this.file.meta.put("aevm", Tags.VERSION);
        this.file.meta.put("mc", Loader.MC_VERSION);
        this.file.meta.put("side", "SERVER");
        this.baseDir = baseDir;
        this.cap = Math.max(1000, cap);
    }

    // ------------------------------------------------------------------
    // lifecycle

    /** The CALC-phase recorder for engine internals; null when unarmed. */
    public static TraceRecorder current() {
        return CURRENT.get();
    }

    public static void setCurrent(TraceRecorder rec) {
        if (rec == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(rec);
        }
    }

    /** Native-handoff / failure marker; the caller decides whether to finish. */
    public void fallback(String reason, String detail) {
        emit(TraceSegment.AUDIT, "FALLBACK", mapOf("reason", reason, "detail", detail));
    }

    public long elapsedUs() {
        return (System.nanoTime() - startNanos) / 1_000L;
    }

    public boolean isClosed() {
        return closed;
    }

    public String traceId() {
        return file.traceId;
    }

    /** Package-private: tests inspect the in-memory document. */
    TraceFile document() {
        return file;
    }

    public synchronized boolean emit(String phase, String type, Map<String, String> fields) {
        if (closed) {
            return false;
        }
        int live = eventCount - droppedCount;
        if (live >= cap) {
            // ring eviction: drop the OLDEST event, O(1) via the head marker
            for (TraceSegment s : file.segments) {
                if (s.liveCount() > 0) {
                    s.headSkip++;
                    droppedCount++;
                    file.truncatedEvents = true;
                    break;
                }
            }
        }
        file.segment(phase).events.add(new TraceEvent(
                elapsedUs(), type, fields == null ? Collections.<String, String>emptyMap() : fields));
        eventCount++;
        return true;
    }

    /** Writes the current document without closing the session. */
    public synchronized void writeNow() {
        Path p = target;
        if (p == null) {
            if (baseDir != null) {
                p = baseDir.resolve("trace-" + file.traceId + ".aevmtrace.json.gz");
            } else {
                p = TraceStore.allocatePath(file.traceId);
                if (p == null) {
                    return; // no server context (tests): keep in memory only
                }
            }
            target = p;
        }
        try {
            TraceWriter.write(file, p);
            TraceStore.enforceRetention();
        } catch (IOException e) {
            Log.LOG.warn("[AE2-VM] trace {} could not be written", file.traceId, e);
        } catch (Throwable e) {
            // Serialization defects (defensive: cyclic webs, pathological
            // payloads) must never fail the crafting order being recorded.
            // The partial file is kept — the hash chain marks it unverified.
            Log.LOG.error("[AE2-VM] trace {} serialization failed; partial file kept", file.traceId, e);
        }
    }

    /** Stamps COMMIT, closes the session and writes the final document. */
    public synchronized void finish(String status) {
        if (closed) {
            return;
        }
        emit(TraceSegment.AUDIT, "COMMIT", Collections.singletonMap("status", status));
        closed = true;
        setCurrent(null);
        TraceSessions.unregister(this);
        writeNow();
        Path p = target;
        if (p != null) {
            Log.LOG.info("[AE2-VM] trace {} saved: {}", file.traceId, p);
        }
    }

    // ------------------------------------------------------------------
    // stamped decision points (CALC)

    public void stampRequest(IActionSource source, UUID player,
                             IAEItemStack what, long amount, boolean simulate) {
        Map<String, String> f = new TreeMap<>();
        f.put("what", codec.toSpec(McStackAdapter.identityOf(what)).token);
        f.put("count", Long.toString(amount));
        f.put("simulate", Boolean.toString(simulate));
        if (player != null) {
            EntityPlayer ep = source.player().get();
            f.put("source", "player");
            f.put("player", vault.tokenForPlayer(player, ep.getName()));
        } else {
            f.put("source", "machine");
            f.put("device", source.machine()
                    .map(this::deviceToken)
                    .orElse(vault.tokenForDevice("unknown", "unknown")));
        }
        emit(TraceSegment.CALC, "REQUEST", f);
    }

    private String deviceToken(Object host) {
        String cls = host.getClass().getName();
        String pos = cls;
        try {
            if (host instanceof TileEntity) {
                TileEntity te = (TileEntity) host;
                pos = te.getWorld().provider.getDimension() + ":" + te.getPos();
            }
        } catch (Throwable ignored) {
            // positions are best-effort attribution
        }
        return vault.tokenForDevice(cls, pos);
    }

    public void stampSnapshot(NetworkCraftingSandbox stockView) {
        if (file.snapshot != null) {
            return;
        }
        TraceSnapshot snap = new TraceSnapshot("full");
        try {
            for (IAEItemStack s : stockView.stockView()) {
                if (s == null || s.getStackSize() <= 0L) {
                    continue;
                }
                snap.items.add(new StackEntry(
                        codec.toSpec(McStackAdapter.identityOf(s)),
                        Long.toString(s.getStackSize()), false));
            }
        } catch (Throwable t) {
            Log.LOG.debug("[AE2-VM] snapshot capture skipped: {}", t.toString());
        }
        file.snapshot = snap;
        emit(TraceSegment.CALC, "SNAPSHOT_SEED", mapOf(
                "mode", "full", "items", Integer.toString(snap.items.size())));
    }

    public void stampBytecode(CraftingBytecode bc) {
        rootBytecode = bc;
        file.bytecode = BytecodeTraceCodec.toTrace(bc, codec);
        patternIndices.clear();
        Object[] pool = bc.getPatternPool();
        for (int i = 0; i < pool.length; i++) {
            patternIndices.put(pool[i], i);
        }
        emit(TraceSegment.CALC, "PATTERN_RESOLVED", mapOf(
                "scope", "root", "chosen", token(bc.getOutput()),
                "perCraft", Long.toString(bc.getOutputAmountPerCraft()),
                "patterns", Integer.toString(file.bytecode.patterns.size())));
    }

    public void passBegin(int pass) {
        emit(TraceSegment.CALC, "PASS_BEGIN", mapOf("pass", Integer.toString(pass)));
    }

    public void passEnd(int pass, VMPlan plan) {
        emit(TraceSegment.CALC, "PASS_END", mapOf(
                "pass", Integer.toString(pass),
                "simulation", Boolean.toString(plan.isSimulation()),
                "missing", Integer.toString(plan.getMissingItems().size()),
                "patterns", Integer.toString(plan.getPatternTimes().size())));
    }

    public void patternResolved(IAEItemStack key, String tier,
                                ICraftingPatternDetails chosen,
                                int candidates) {
        String chosenToken = "none";
        long perCraft = 0;
        if (chosen != null) {
            IAEItemStack out = PatternCompat.getPrimaryOutput(chosen);
            if (out != null) {
                chosenToken = token(out);
                perCraft = out.getStackSize();
            }
        }
        emit(TraceSegment.CALC, "PATTERN_RESOLVED", mapOf(
                "key", token(key), "tier", tier, "chosen", chosenToken,
                "perCraft", Long.toString(perCraft), "candidates", Integer.toString(candidates)));
    }

    /**
     * Attribution event for the multi-pattern repair loop (small ledger): the
     * adopted allocation per contended key — which pattern (or synthesized
     * split) the confirmed plan uses, and how many verified alternatives the
     * choice was made from.
     */
    public void repairChoice(IAEItemStack key,
                             ICraftingPatternDetails chosen,
                             int alternatives, boolean synthesized) {
        String chosenToken = "none";
        if (chosen != null) {
            IAEItemStack out =
                    PatternCompat.getPrimaryOutput(chosen);
            if (out != null) {
                chosenToken = token(out);
            }
        }
        emit(TraceSegment.CALC, "REPAIR_CHOICE", mapOf(
                "key", token(key), "chosen", chosenToken,
                "alternatives", Integer.toString(alternatives),
                "synthesized", Boolean.toString(synthesized)));
    }

    public void fuzzySubstitute(IAEItemStack key, IAEItemStack variant, long extracted, int familySize) {
        emit(TraceSegment.CALC, "FUZZY_SUBSTITUTE", mapOf(
                "key", token(key), "picked", token(variant),
                "got", Long.toString(extracted), "family", Integer.toString(familySize)));
    }

    public void planResult(VMPlan plan) {
        if (rootBytecode != null) {
            stampSubBytecodes(rootBytecode); // schema v2: embed every compiled sub-pattern
        }
        TracePlan p = new TracePlan();
        fill(p.used, plan.getUsedItems());
        fill(p.missing, plan.getMissingItems());
        fill(p.emitted, plan.getEmittedItems());
        for (Map.Entry<ICraftingPatternDetails, Long> e
                : plan.getPatternTimes().entrySet()) {
            Integer idx = e.getKey() == null || file.bytecode == null ? null
                    : patternIndices.get(e.getKey());
            if (idx == null && e.getKey() != null && file.bytecode != null) {
                // A fired pattern the root pool never listed (a repair-loop
                // preference, a synthesized split, a cache-hit
                // re-resolution): append it — with its compiled bytecode, so
                // offline replay can serve its CALLs — instead of writing a
                // dead -1 index.
                idx = registerRuntimePattern(e.getKey());
            }
            p.patternTimes.add(new TracePlan.PatternTime(idx,
                    Long.toString(e.getValue())));
        }
        p.simulation = plan.isSimulation();
        p.deliver = Long.toString(plan.getDeliverAmount());
        file.plan = p;
        emit(TraceSegment.CALC, "PLAN_RESULT", mapOf(
                "used", Integer.toString(p.used.size()),
                "missing", Integer.toString(p.missing.size()),
                "emitted", Integer.toString(p.emitted.size()),
                "patterns", Integer.toString(p.patternTimes.size()),
                "simulation", Boolean.toString(p.simulation)));
    }

    /**
     * Schema v2: walk the root pattern pool and embed each
     * pattern's compiled bytecode (harvested from the compiler cache — every
     * CALLed pattern is compiled by execution time), recursively. Uncalled
     * patterns have no bytecode and stay null: the VM never CALLs them, so
     * replay never needs them either.
     */
    private void stampSubBytecodes(CraftingBytecode bc) {
        stampOne(file.bytecode, bc,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Appends a runtime-fired pattern to the trace's pattern table and indexes
     * it. Appending keeps every existing pool index valid.
     */
    private int registerRuntimePattern(
            ICraftingPatternDetails d) {
        TracePattern tp = new TracePattern(d.isCraftable(), d.canSubstitute(),
                d.getPriority());
        for (IAEItemStack s : d.getCondensedInputs()) {
            if (s != null && s.getStackSize() > 0) {
                tp.condensedInputs.add(BytecodeTraceCodec.entryOf(s, codec));
            }
        }
        for (IAEItemStack s : d.getCondensedOutputs()) {
            if (s != null && s.getStackSize() > 0) {
                tp.condensedOutputs.add(BytecodeTraceCodec.entryOf(s, codec));
            }
        }
        CraftingBytecode sub = PatternCompiler.getCompiled(d);
        if (sub != null) {
            tp.compiled = buildNested(sub);
        }
        int idx = file.bytecode.patterns.size();
        file.bytecode.patterns.add(tp);
        patternIndices.put(d, idx);
        return idx;
    }

    /** The replay-servable compiled form of one sub-pattern bytecode. */
    private TraceBytecode buildNested(CraftingBytecode sub) {
        TraceBytecode nested = new TraceBytecode();
        nested.code = Base64.getEncoder().encodeToString(sub.getCode());
        for (IAEItemStack s : sub.getConstantPool()) {
            nested.pool.add(BytecodeTraceCodec.entryOf(s, codec));
        }
        nested.outputIndex = sub.getOutputIndex();
        nested.perCraft = Long.toString(sub.getOutputAmountPerCraft());
        for (ICraftingPatternDetails d : sub.getPatternPool()) {
            TracePattern ntp = new TracePattern(d.isCraftable(), d.canSubstitute(), d.getPriority());
            for (IAEItemStack s : d.getCondensedInputs()) {
                ntp.condensedInputs.add(BytecodeTraceCodec.entryOf(s, codec));
            }
            for (IAEItemStack s : d.getCondensedOutputs()) {
                ntp.condensedOutputs.add(BytecodeTraceCodec.entryOf(s, codec));
            }
            nested.patterns.add(ntp);
        }
        return nested;
    }

    /**
     * @param stamped identity set of already-stamped bytecodes. CALL webs
     *        are cyclic in the wild (a net-gain self-loop's compiled
     *        bytecode contains its own pattern; mutual webs nest each
     *        other) — without this set the embedding recurses until the
     *        calculator thread dies with StackOverflowError and the whole
     *        order fails (live regression 2026-09-18, gaia ring).
     */
    private void stampOne(TraceBytecode t, CraftingBytecode bc,
                          Set<CraftingBytecode> stamped) {
        if (!stamped.add(bc)) {
            return; // cycle: this bytecode's nesting is already built
        }
        Object[] pool = bc.getPatternPool();
        for (int i = 0; i < pool.length && i < t.patterns.size(); i++) {
            CraftingBytecode sub = PatternCompiler.getCompiled(
                    (ICraftingPatternDetails) pool[i]);
            if (sub == null) {
                continue;
            }
            TracePattern tp = t.patterns.get(i);
            TraceBytecode nested = new TraceBytecode();
            nested.code = Base64.getEncoder().encodeToString(sub.getCode());
            for (IAEItemStack s : sub.getConstantPool()) {
                nested.pool.add(BytecodeTraceCodec.entryOf(s, codec));
            }
            nested.outputIndex = sub.getOutputIndex();
            nested.perCraft = Long.toString(sub.getOutputAmountPerCraft());
            for (ICraftingPatternDetails d : sub.getPatternPool()) {
                TracePattern ntp = new TracePattern(d.isCraftable(), d.canSubstitute(), d.getPriority());
                for (IAEItemStack s : d.getCondensedInputs()) {
                    ntp.condensedInputs.add(BytecodeTraceCodec.entryOf(s, codec));
                }
                for (IAEItemStack s : d.getCondensedOutputs()) {
                    ntp.condensedOutputs.add(BytecodeTraceCodec.entryOf(s, codec));
                }
                nested.patterns.add(ntp);
            }
            tp.compiled = nested;
            stampOne(nested, sub, stamped);
        }
    }

    private void fill(List<StackEntry> into, VMCounter counter) {
        for (Map.Entry<IAEItemStack, Long> e : counter.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0L) {
                continue;
            }
            into.add(new StackEntry(codec.toSpec(McStackAdapter.identityOf(e.getKey())),
                    Long.toString(e.getValue()), false));
        }
    }

    private String token(IAEItemStack s) {
        try {
            return codec.toSpec(McStackAdapter.identityOf(s)).token;
        } catch (Throwable t) {
            return "i#err0";
        }
    }

    /** START-phase extraction record (setJob phase 1/2). */
    public void startExtract(String phase, boolean ok, IAEItemStack item, long expected) {
        TreeMap<String, String> f = new TreeMap<>();
        f.put("phase", phase);
        f.put("result", ok ? "ok" : "fail");
        if (item != null) {
            f.put("item", token(item));
        }
        if (expected > 0L) {
            f.put("expected", Long.toString(expected));
        }
        emit(TraceSegment.START, "START_EXTRACT", f);
    }

    /** START-phase summary (mirrors the DIAG-SETJOB log line). */
    public void startSummary(VMPlan plan) {
        emit(TraceSegment.START, "START_EXTRACT", mapOf(
                "phase", "commit", "result", "ok",
                "used", Integer.toString(plan.getUsedItems().size()),
                "emitted", Integer.toString(plan.getEmittedItems().size()),
                "patterns", Integer.toString(plan.getPatternTimes().size())));
    }

    /** Invariant checker verdicts, one event per violation. */
    public void invariantViolations(List<String> violations) {
        for (String v : violations) {
            emit(TraceSegment.AUDIT, "INVARIANT_VIOLATION", Collections.singletonMap("rule", v));
        }
    }

    private static TreeMap<String, String> mapOf(String... kv) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
