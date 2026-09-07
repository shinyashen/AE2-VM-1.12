package com.ae2vm.compiler;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.AE2VM;
import com.ae2vm.compat.AE2FCCompat;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.Opcode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pattern → bytecode compiler (1.12 port of AE2-VM's PatternCompiler).
 *
 * 1.12 adaptation notes (semantics preserved where the API allows):
 * - inputs: getCondensedInputs() (amount per craft carried by the stack)
 * - replacement groups: canSubstitute() + getSubstituteInputs(slot) → FUZZY_SLOT
 * - catalyst: a condensed output that returns the input (same key, amount ≥ the
 *   per-craft consumption) — the 1.12 analogue of IInput.getRemainingKey()==input
 * - durability: a same-item different-damage output transition (tool wear)
 * - processing-recipe default fuzzy: every input of a !isCraftable() pattern
 */
public final class PatternCompiler {
    private static final Map<ICraftingPatternDetails, CraftingBytecode> COMPILED_PATTERNS =
            new ConcurrentHashMap<>();

    /** Replacement (substitute) groups: every variant maps to the full accepted set. */
    private static final Map<IAEItemStack, Set<IAEItemStack>> FUZZY_GROUPS = new ConcurrentHashMap<>();

    /** Processing-recipe input keys (default fuzzy: same-item any-NBT family). */
    private static final Set<IAEItemStack> PROCESSING_INPUT_KEYS = ConcurrentHashMap.newKeySet();

    private PatternCompiler() {
    }

    /** True for patterns that are NOT molecular-assembler crafting patterns. */
    public static boolean isProcessingPattern(ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }
        try {
            return !pattern.isCraftable();
        } catch (Throwable t) {
            return true;
        }
    }

    /** True if {@code key} is an input of a processing recipe (default fuzzy). */
    public static boolean isProcessingInput(IAEItemStack key) {
        return key != null && contains(PROCESSING_INPUT_KEYS, key);
    }

    public static void clearProcessingInputKeys() {
        PROCESSING_INPUT_KEYS.clear();
    }

    public static Set<IAEItemStack> getFuzzyGroup(IAEItemStack key) {
        for (var e : FUZZY_GROUPS.entrySet()) {
            if (e.getKey().isSameType(key)) {
                return e.getValue();
            }
        }
        Set<IAEItemStack> single = new HashSet<>();
        if (key != null) {
            single.add(normalize(key));
        }
        return single;
    }

    private static boolean contains(Set<IAEItemStack> set, IAEItemStack key) {
        for (IAEItemStack s : set) {
            if (s.isSameType(key)) return true;
        }
        return false;
    }

    private static IAEItemStack normalize(IAEItemStack key) {
        IAEItemStack copy = key.copy();
        copy.reset();
        copy.setStackSize(1);
        return copy;
    }

    public static void compileIfAbsent(ICraftingPatternDetails pattern) {
        if (pattern != null && !COMPILED_PATTERNS.containsKey(pattern)) {
            COMPILED_PATTERNS.computeIfAbsent(pattern, PatternCompiler::compilePattern);
        }
    }

    public static CraftingBytecode getCompiled(ICraftingPatternDetails pattern) {
        return COMPILED_PATTERNS.get(pattern);
    }

    public static CraftingBytecode compileRequest(ICraftingPatternDetails pattern, long requestedAmount) {
        CraftingBytecode patternBytecode = COMPILED_PATTERNS.get(pattern);
        if (patternBytecode == null) {
            compileIfAbsent(pattern);
            patternBytecode = COMPILED_PATTERNS.get(pattern);
            if (patternBytecode == null) {
                throw new IllegalStateException("Failed to compile pattern: " + pattern);
            }
        }

        long outputPerCraft = patternBytecode.getOutputAmountPerCraft();
        long craftTimes = (requestedAmount + outputPerCraft - 1L) / outputPerCraft;
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        int outputIdx = builder.addConstant(patternBytecode.getOutput());
        builder.setOutput(outputIdx, requestedAmount);
        int patternIdx = builder.addPattern(pattern);
        builder.emitPushLong(craftTimes);
        builder.emit(Opcode.CALL);
        builder.emitShort(patternIdx);
        return builder.build();
    }

    private static void registerFuzzyGroups(ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return;
        }
        boolean processing = isProcessingPattern(pattern);
        IAEItemStack[] condensed = pattern.getCondensedInputs();
        if (condensed == null) {
            return;
        }
        boolean canSubstitute;
        try {
            canSubstitute = pattern.canSubstitute();
        } catch (Throwable t) {
            canSubstitute = false;
        }
        for (IAEItemStack input : condensed) {
            if (input == null || input.getStackSize() <= 0) {
                continue;
            }
            IAEItemStack primaryKey = normalize(input);
            if (processing) {
                PROCESSING_INPUT_KEYS.add(primaryKey);
            }
            if (!canSubstitute) {
                continue;
            }
            Set<IAEItemStack> group = new HashSet<>();
            group.add(primaryKey);
            int[] slots;
            try {
                slots = findInputSlots(pattern, input);
            } catch (Throwable t) {
                slots = new int[0];
            }
            for (int slot : slots) {
                List<IAEItemStack> subs;
                try {
                    subs = pattern.getSubstituteInputs(slot);
                } catch (Throwable t) {
                    subs = null;
                }
                if (subs == null) continue;
                for (IAEItemStack sub : subs) {
                    if (sub != null && sub.getStackSize() > 0) {
                        group.add(normalize(sub));
                    }
                }
            }
            if (group.size() > 1) {
                for (IAEItemStack k : group) {
                    Set<IAEItemStack> existing = null;
                    for (var e : FUZZY_GROUPS.entrySet()) {
                        if (e.getKey().isSameType(k)) { existing = e.getValue(); break; }
                    }
                    if (existing == null) {
                        FUZZY_GROUPS.put(k, group);
                    } else {
                        existing.addAll(group);
                    }
                }
            }
        }
    }

    /** Slots (in the slot-preserving input array) whose stack matches {@code input}. */
    private static int[] findInputSlots(ICraftingPatternDetails pattern, IAEItemStack input) {
        IAEItemStack[] slots = pattern.getInputs();
        if (slots == null) {
            return new int[0];
        }
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].isSameType(input)) {
                found.add(i);
            }
        }
        int[] result = new int[found.size()];
        for (int i = 0; i < found.size(); i++) {
            result[i] = found.get(i);
        }
        return result;
    }

    /**
     * Catalyst / durability detection for one condensed input.
     * Returns non-null when the pattern hands the input back: {@code [returnedKey, uses]}
     * with uses == Long.MAX_VALUE for an unchanged catalyst or the number of firings a
     * full unit survives for a degrading tool.
     */
    private static long[] detectReturnedInput(ICraftingPatternDetails pattern, IAEItemStack input) {
        IAEItemStack[] outputs = pattern.getOutputs();
        if (outputs == null) {
            return null;
        }
        long returnedAmount = 0;
        IAEItemStack returnedDamaged = null;
        for (IAEItemStack out : outputs) {
            if (out == null || out.getStackSize() <= 0) {
                continue;
            }
            if (out.isSameType(input)) {
                returnedAmount += out.getStackSize();
            } else if (sameItem(out, input) && input.getItem().isDamageable()) {
                int di = input.getItemDamage();
                int dof = out.getItemDamage();
                if (dof > di && out.getItem().getMaxDamage() >= dof) {
                    returnedDamaged = out;
                }
            }
        }
        if (returnedAmount >= input.getStackSize()) {
            // Handed back unchanged at least as much as consumed → catalyst seed.
            return new long[]{0L, Long.MAX_VALUE};
        }
        if (returnedDamaged != null) {
            // Degrading tool: one unit survives (maxDamage - damage) / step firings.
            int step = returnedDamaged.getItemDamage() - input.getItemDamage();
            if (step > 0) {
                long uses = (input.getItem().getMaxDamage() - input.getItemDamage()) / step;
                if (uses > 0) {
                    return new long[]{1L, uses};
                }
            }
        }
        return null;
    }

    private static boolean sameItem(IAEItemStack a, IAEItemStack b) {
        return a.getItem() == b.getItem();
    }

    private static CraftingBytecode compilePattern(ICraftingPatternDetails pattern) {
        registerFuzzyGroups(pattern);
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        IAEItemStack primaryOutput = PatternCompat.getPrimaryOutput(pattern);
        IAEItemStack outputKey = AE2FCCompat.normalizeFluidItem(primaryOutput);
        if (outputKey == null) {
            outputKey = primaryOutput;
        }
        long outputPerCraft = outputKey.getStackSize();
        int outputIdx = builder.addConstant(outputKey.copy().setStackSize(1));
        int patternIdx = builder.addPattern(pattern);
        builder.setOutput(outputIdx, outputPerCraft);
        builder.emit(Opcode.DUP);
        builder.emitRecordPattern(patternIdx);

        IAEItemStack[] condensedInputs = pattern.getCondensedInputs();
        if (condensedInputs != null) {
            for (IAEItemStack input : condensedInputs) {
                if (input == null || input.getStackSize() <= 0) {
                    continue;
                }
                long perCraft = input.getStackSize();
                IAEItemStack inputKey = normalize(input);

                // Replacement (substitute) slot variants → FUZZY_SLOT + per-variant EXTRACTs.
                List<IAEItemStack> variants = new ArrayList<>();
                variants.add(inputKey);
                boolean fuzzy = false;
                try {
                    if (pattern.canSubstitute()) {
                        for (int slot : findInputSlots(pattern, input)) {
                            List<IAEItemStack> subs = pattern.getSubstituteInputs(slot);
                            if (subs == null) continue;
                            for (IAEItemStack sub : subs) {
                                if (sub == null || sub.getStackSize() <= 0) continue;
                                IAEItemStack sk = normalize(sub);
                                boolean dup = false;
                                for (IAEItemStack v : variants) {
                                    if (v.isSameType(sk)) { dup = true; break; }
                                }
                                if (!dup) {
                                    variants.add(sk);
                                    fuzzy = true;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                // Catalyst / durability (returned input) — one-time seed or tool rate.
                long[] returned = detectReturnedInput(pattern, input);
                if (returned != null) {
                    int seedIdx = builder.addConstant(inputKey);
                    builder.emitPushLong(perCraft);
                    if (returned[1] == Long.MAX_VALUE) {
                        builder.emit(Opcode.CATALYST_SEED);
                    } else {
                        builder.emitPushLong(returned[1]);
                        builder.emit(Opcode.DURABILITY_TOOL);
                    }
                    builder.emitShort(seedIdx);
                    continue;
                }

                int inputKeyIdx = builder.addConstant(inputKey);
                builder.emit(Opcode.DUP);
                builder.emitPushLong(perCraft);
                builder.emit(Opcode.MUL);
                // Always schedule the sub-craft with the FULL per-craft need BEFORE
                // consuming stock (the v1.8.18 false-missing fix).
                builder.emit(Opcode.DUP);
                if (fuzzy) {
                    builder.emitFuzzySlot();
                }
                builder.emitCallByKey(inputKeyIdx);
                for (IAEItemStack variant : variants) {
                    int pIdx = builder.addConstant(variant);
                    builder.emitExtractIngredient(pIdx);
                }
                builder.emit(Opcode.POP);
            }
        }

        IAEItemStack[] outputs = pattern.getOutputs();
        if (outputs != null) {
            for (IAEItemStack output : outputs) {
                if (output == null || output.getStackSize() <= 0) {
                    continue;
                }
                IAEItemStack ok = AE2FCCompat.normalizeFluidItem(output);
                if (ok == null) {
                    ok = output;
                }
                int outIdx = builder.addConstant(ok.copy().setStackSize(1));
                builder.emit(Opcode.DUP);
                builder.emitPushLong(ok.getStackSize());
                builder.emit(Opcode.MUL);
                builder.emitInsertOutput(outIdx);
            }
        }

        builder.emit(Opcode.POP);
        builder.emit(Opcode.RETURN);
        return builder.build();
    }

    public static void invalidate(ICraftingPatternDetails pattern) {
        COMPILED_PATTERNS.remove(pattern);
    }

    public static void clearCache() {
        COMPILED_PATTERNS.clear();
        FUZZY_GROUPS.clear();
        PROCESSING_INPUT_KEYS.clear();
    }

    public static int getCompiledCount() {
        return COMPILED_PATTERNS.size();
    }
}
