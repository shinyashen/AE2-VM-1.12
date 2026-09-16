package com.ae2vm.replay;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.trace.HeadlessStackFactory;
import com.ae2vm.trace.StackEntry;
import com.ae2vm.trace.TraceBytecode;
import com.ae2vm.trace.TraceFile;
import com.ae2vm.trace.TracePattern;
import com.ae2vm.trace.TracePlan;
import com.ae2vm.trace.VirtualPatternDetails;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.NetworkCraftingSandbox;
import com.ae2vm.vm.VMPlan;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline replay core: rebuild the world from the
 * trace — tokenized stock, root bytecode, embedded sub-pattern bytecodes
 * — seed the compiler cache and execute with the CURRENT engine. The
 * recorded plan (token-keyed) and the replayed plan (factory-reverse
 * keyed) are then diffed token by token.
 *
 * <p>Everything here is deterministic by construction: no wall clock, no
 * randomness, no network — the same trace through the same jar produces
 * byte-identical output.
 */
public final class ReplayCore {

    private ReplayCore() {
    }

    public static final class Report {
        public final VMPlan replayedPlan;
        /** Per-key comparison lines; empty when the plans are identical. */
        public final List<String> differences;
        public final boolean identical;
        /** CPU lifecycle simulation verdict; null with --no-simulate. */
        public final VirtualCPUCluster.Verdict verdict;

        Report(VMPlan replayedPlan, List<String> differences, VirtualCPUCluster.Verdict verdict) {
            this.replayedPlan = replayedPlan;
            this.differences = differences;
            this.identical = differences.isEmpty();
            this.verdict = verdict;
        }
    }

    /** Rebuilds and re-executes the trace's order with the current engine. */
    public static Report replay(TraceFile trace) {
        return replay(trace, true);
    }

    /** Same, with the CPU-lifecycle simulation skippable. */
    public static Report replay(TraceFile trace, boolean simulate) {
        if (trace.bytecode == null) {
            throw new IllegalArgumentException("trace has no embedded bytecode (recorded before sub-pattern embedding?)");
        }
        HeadlessStackFactory factory = new HeadlessStackFactory();

        // world: the snapshot stock, materialized through the dummy registry
        HeadlessItemList stock = new HeadlessItemList();
        if (trace.snapshot != null) {
            for (StackEntry e : trace.snapshot.items) {
                stock.add(factory.stack(e.spec, Long.parseLong(e.count)));
            }
        }
        NetworkCraftingSandbox sandbox = NetworkCraftingSandbox.raw(stock);

        // bytecode: root + nested sub-patterns, seeded into the compiler cache
        Map<ICraftingPatternDetails, Integer> patternIndices = new IdentityHashMap<>();
        Map<ICraftingPatternDetails, CraftingBytecode> seed = new HashMap<>();
        CraftingBytecode root = rebuild(trace.bytecode, factory, seed, patternIndices);
        PatternCompiler.seedCompiled(seed);

        CraftingVM vm = new CraftingVM(new Object(), key -> null); // CALL_BY_KEY (fluid packets): no offline resolver yet
        VMPlan plan = vm.execute(root, sandbox);

        List<String> differences = diff(trace.plan, plan, factory, patternIndices);

        // CPU lifecycle simulation (instant tier): the
        // snapshot stock PRE-execution is the S3 reference
        VirtualCPUCluster.Verdict verdict = null;
        if (simulate && trace.plan != null && trace.snapshot != null) {
            HeadlessItemList snapshotStock = new HeadlessItemList();
            for (StackEntry e : trace.snapshot.items) {
                snapshotStock.add(factory.stack(e.spec, Long.parseLong(e.count)));
            }
            IAEItemStack what = requestIdentity(trace.bytecode, factory);
            long amount = requestAmount(trace.bytecode);
            if (what != null && amount > 0) {
                VirtualCPUCluster cluster = new VirtualCPUCluster(plan, what, amount);
                List<String> gaps = VirtualCPUCluster.extractionGap(plan,
                        toSnapshotStock(snapshotStock));
                verdict = cluster.run(10_000, 0);
                for (String gap : gaps) {
                    verdict.evidence.add(gap);
                }
            }
        }
        return new Report(plan, differences, verdict);
    }

    /** Recursive rebuild; records each rebuilt pattern's trace index for the diff. */
    private static CraftingBytecode rebuild(TraceBytecode t, HeadlessStackFactory factory,
                                            Map<ICraftingPatternDetails, CraftingBytecode> seed,
                                            Map<ICraftingPatternDetails, Integer> patternIndices) {
        IAEItemStack[] pool = new IAEItemStack[t.pool.size()];
        for (int i = 0; i < t.pool.size(); i++) {
            StackEntry e = t.pool.get(i);
            pool[i] = factory.stack(e.spec, Long.parseLong(e.count));
        }
        ICraftingPatternDetails[] patterns = new ICraftingPatternDetails[t.patterns.size()];
        for (int i = 0; i < t.patterns.size(); i++) {
            TracePattern tp = t.patterns.get(i);
            CraftingBytecode nested = tp.compiled == null ? null : rebuild(tp.compiled, factory, seed, patternIndices);
            VirtualPatternDetails details = new VirtualPatternDetails(
                    stacks(tp.condensedInputs, factory),
                    stacks(tp.condensedOutputs, factory),
                    tp.crafting,
                    tp.substitute);
            details.setPriority(tp.priority);
            patterns[i] = details;
            patternIndices.put(details, i);
            if (nested != null) {
                seed.put(details, nested);
            }
        }
        return new CraftingBytecode(
                pool,
                patterns,
                Base64.getDecoder().decode(t.code),
                t.outputIndex,
                Long.parseLong(t.perCraft));
    }

    private static VirtualCPUCluster.Stock toSnapshotStock(HeadlessItemList list) {
        VirtualCPUCluster.Stock s = new VirtualCPUCluster.Stock();
        for (IAEItemStack st : list) {
            s.add(st, st.getStackSize());
        }
        return s;
    }

    /**
     * The requested identity, materialized (size 1): compileRequest semantics
     * make the ROOT bytecode's output entry + perCraft the request itself.
     */
    static IAEItemStack requestIdentity(TraceBytecode tb, HeadlessStackFactory factory) {
        if (tb.outputIndex < 0 || tb.outputIndex >= tb.pool.size()) {
            return null;
        }
        StackEntry out = tb.pool.get(tb.outputIndex);
        return factory.stack(out.spec, 1);
    }

    static long requestAmount(TraceBytecode tb) {
        return Long.parseLong(tb.perCraft);
    }

    private static IAEItemStack[] stacks(List<StackEntry> entries, HeadlessStackFactory factory) {
        IAEItemStack[] out = new IAEItemStack[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            StackEntry e = entries.get(i);
            out[i] = factory.stack(e.spec, Long.parseLong(e.count));
        }
        return out;
    }

    /**
     * Token-keyed plan diff. The recorded plan's entries carry tokens; the
     * replayed plan's stacks map back through the factory to the very same
     * specs, so comparison is exact — no names involved anywhere.
     */
    static List<String> diff(TracePlan recorded, VMPlan replayed,
                             HeadlessStackFactory factory,
                             Map<ICraftingPatternDetails, Integer> patternIndices) {
        List<String> diffs = new ArrayList<>();
        if (recorded == null) {
            diffs.add("recorded plan absent (trace without an embedded plan)");
            return diffs;
        }
        diffMaps("used", toTokenMap(recorded.used), toSpecMap(replayed.getUsedItems(), factory), diffs);
        diffMaps("missing", toTokenMap(recorded.missing), toSpecMap(replayed.getMissingItems(), factory), diffs);
        diffMaps("emitted", toTokenMap(recorded.emitted), toSpecMap(replayed.getEmittedItems(), factory), diffs);

        Map<Integer, Long> recTimes = new LinkedHashMap<>();
        for (TracePlan.PatternTime pt : recorded.patternTimes) {
            recTimes.put(pt.patternIndex, Long.parseLong(pt.times));
        }
        Map<Integer, Long> newTimes = new LinkedHashMap<>();
        for (Map.Entry<ICraftingPatternDetails, Long> e : replayed.getPatternTimes().entrySet()) {
            Integer idx = patternIndices.get(e.getKey());
            newTimes.merge(idx == null ? -1 : idx, e.getValue(), Long::sum);
        }
        diffTimes(recTimes, newTimes, diffs);
        if (recorded.simulation != replayed.isSimulation()) {
            diffs.add("simulation: recorded=" + recorded.simulation + " replayed=" + replayed.isSimulation());
        }
        if (recorded.deliver != null
                && !recorded.deliver.equals(Long.toString(replayed.getDeliverAmount()))) {
            diffs.add("deliver recorded=" + recorded.deliver
                    + " replayed=" + replayed.getDeliverAmount());
        }
        return diffs;
    }

    private static Map<String, String> toTokenMap(List<StackEntry> entries) {
        Map<String, String> m = new LinkedHashMap<>();
        for (StackEntry e : entries) {
            m.merge(specKey(e.spec.token, e.spec.damage, e.spec.nbtToken), e.count, ReplayCore::sum);
        }
        return m;
    }

    private static Map<String, String> toSpecMap(com.ae2vm.vm.VMCounter counter, HeadlessStackFactory factory) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<IAEItemStack, Long> e : counter.entrySet()) {
            m.merge(specKey(e.getKey(), factory), Long.toString(e.getValue()), ReplayCore::sum);
        }
        return m;
    }

    private static String specKey(String token, int damage, String nbtToken) {
        return token + "@" + damage + (nbtToken == null ? "" : "+" + nbtToken);
    }

    private static String specKey(IAEItemStack stack, HeadlessStackFactory factory) {
        com.ae2vm.trace.StackSpec spec = factory.specOf(stack);
        if (spec != null) {
            return specKey(spec.token, spec.damage, spec.nbtToken);
        }
        return "unknown:" + stack;
    }

    private static String sum(String a, String b) {
        return Long.toString(Long.parseLong(a) + Long.parseLong(b));
    }

    private static void diffMaps(String label, Map<String, String> recorded, Map<String, String> replayed,
                                 List<String> out) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        keys.addAll(recorded.keySet());
        keys.addAll(replayed.keySet());
        for (String k : keys) {
            String r = recorded.get(k);
            String n = replayed.get(k);
            if (r == null) {
                out.add(label + " replayed-only " + k + " x" + n);
            } else if (n == null) {
                out.add(label + " recorded-only " + k + " x" + r);
            } else if (!r.equals(n)) {
                out.add(label + " " + k + " recorded=" + r + " replayed=" + n);
            }
        }
    }

    private static void diffTimes(Map<Integer, Long> recorded, Map<Integer, Long> replayed, List<String> out) {
        java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
        keys.addAll(recorded.keySet());
        keys.addAll(replayed.keySet());
        for (Integer k : keys) {
            Long r = recorded.get(k);
            Long n = replayed.get(k);
            if (r == null) {
                out.add("patterns replayed-only #" + k + " x" + n);
            } else if (n == null) {
                out.add("patterns recorded-only #" + k + " x" + r);
            } else if (!r.equals(n)) {
                out.add("patterns #" + k + " recorded=" + r + " replayed=" + n);
            }
        }
    }
}
