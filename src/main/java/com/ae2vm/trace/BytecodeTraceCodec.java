package com.ae2vm.trace;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.CraftingBytecode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * {@link CraftingBytecode} ↔ {@link TraceBytecode}. The trace side is
 * fully tokenized (ids and NBT payloads are vault tokens) — a replay
 * environment materializes it through {@link HeadlessStackFactory} and
 * never needs the original registry, recipes or a World.
 */
public final class BytecodeTraceCodec {

    private BytecodeTraceCodec() {
    }

    /** Live bytecode → trace form (tokenized). */
    public static TraceBytecode toTrace(CraftingBytecode bc, StackCodec codec) {
        TraceBytecode t = new TraceBytecode();
        t.code = Base64.getEncoder().encodeToString(bc.getCode());
        for (IAEItemStack s : bc.getConstantPool()) {
            t.pool.add(entry(s, codec));
        }
        t.outputIndex = bc.getOutputIndex();
        t.perCraft = Long.toString(bc.getOutputAmountPerCraft());
        for (ICraftingPatternDetails d : bc.getPatternPool()) {
            TracePattern tp = new TracePattern(d.isCraftable(), d.canSubstitute(), d.getPriority());
            for (IAEItemStack s : d.getCondensedInputs()) {
                tp.condensedInputs.add(entry(s, codec));
            }
            for (IAEItemStack s : d.getCondensedOutputs()) {
                tp.condensedOutputs.add(entry(s, codec));
            }
            t.patterns.add(tp);
        }
        return t;
    }

    private static StackEntry entry(IAEItemStack s, StackCodec codec) {
        StackSpec spec = codec.toSpec(McStackAdapter.identityOf(s));
        return new StackEntry(spec, Long.toString(s.getStackSize()), false);
    }

    /** Trace form → executable bytecode in a headless environment. */
    public static CraftingBytecode fromTrace(TraceBytecode t, HeadlessStackFactory f) {
        IAEItemStack[] pool = new IAEItemStack[t.pool.size()];
        for (int i = 0; i < t.pool.size(); i++) {
            StackEntry e = t.pool.get(i);
            pool[i] = f.stack(e.spec, Long.parseLong(e.count));
        }
        List<ICraftingPatternDetails> patterns = new ArrayList<>();
        for (TracePattern tp : t.patterns) {
            VirtualPatternDetails d = new VirtualPatternDetails(
                    stacks(tp.condensedInputs, f),
                    stacks(tp.condensedOutputs, f),
                    tp.crafting,
                    tp.substitute);
            d.setPriority(tp.priority);
            patterns.add(d);
        }
        return new CraftingBytecode(
                pool,
                patterns.toArray(new ICraftingPatternDetails[0]),
                Base64.getDecoder().decode(t.code),
                t.outputIndex,
                Long.parseLong(t.perCraft));
    }

    private static IAEItemStack[] stacks(List<StackEntry> entries, HeadlessStackFactory f) {
        IAEItemStack[] out = new IAEItemStack[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            StackEntry e = entries.get(i);
            out[i] = f.stack(e.spec, Long.parseLong(e.count));
        }
        return out;
    }
}
