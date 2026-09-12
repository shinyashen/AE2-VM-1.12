package com.ae2vm.compiler;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
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

    /**
     * Monotonic version of the network's pattern set. Bumped by the grid
     * recalculation hook whenever the registered pattern set changes
     * (add / remove / replace); consumers (per-grid VM bundle caches, plan
     * memoization) latch the version they were built with and drop their
     * state when it moves.
     */
    private static final java.util.concurrent.atomic.AtomicLong PATTERN_SET_VERSION =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** Current pattern-set version (latch this alongside cached state). */
    public static long patternSetVersion() {
        return PATTERN_SET_VERSION.get();
    }

    /** Signals a pattern-set change; invalidates every latched consumer. */
    public static void bumpPatternSetVersion() {
        PATTERN_SET_VERSION.incrementAndGet();
    }

    private static final Map<ICraftingPatternDetails, CraftingBytecode> COMPILED_PATTERNS =
            new ConcurrentHashMap<>();

    /**
     * T4 byproduct fallback index: output key → the patterns producing it in
     * ANY output slot. A key with no PRIMARY producer resolves
     * through here while exactly one known pattern produces it — a byproduct
     * intermediate of a ring. Two or more producers is the multi-pattern
     * choice domain: the entry resolves to nothing (the key keeps its legacy
     * leaf/missing semantics). Maintained additively by
     * {@link #indexAnyOutput} and rebuilt wholesale from the live pattern set
     * by the grid recalculation hook (removed patterns must stop resolving).
     */
    private static final Map<IAEItemStack, Set<ICraftingPatternDetails>> ANY_OUTPUT_PRODUCERS =
            new ConcurrentHashMap<>();

    /** Index every output slot of the pattern (idempotent, additive). */
    private static void indexAnyOutput(ICraftingPatternDetails pattern) {
        IAEItemStack[] outs = pattern.getOutputs();
        if (outs == null) return;
        for (IAEItemStack out : outs) {
            if (out == null || out.getStackSize() <= 0) continue;
            ANY_OUTPUT_PRODUCERS.computeIfAbsent(normalize(out),
                    k -> ConcurrentHashMap.newKeySet()).add(pattern);
        }
    }

    /** Wholesale rebuild from the LIVE pattern set (grid recalculation hook). */
    public static void rebuildAnyOutputIndex(Iterable<ICraftingPatternDetails> current) {
        ANY_OUTPUT_PRODUCERS.clear();
        for (ICraftingPatternDetails pattern : current) {
            if (pattern != null) indexAnyOutput(pattern);
        }
    }

    /**
     * The unique pattern producing {@code key} in any output slot, or null
     * when no / more than one producer is known (ambiguous keys keep their
     * legacy resolution).
     */
    public static ICraftingPatternDetails resolveAnyOutputProducer(IAEItemStack key) {
        if (key == null) return null;
        Set<ICraftingPatternDetails> producers = ANY_OUTPUT_PRODUCERS.get(normalize(key));
        if (producers == null || producers.size() != 1) return null;
        return producers.iterator().next();
    }

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

    /**
     * Defensive wrapper unwrap (port of the upstream UselessMod fix): some
     * addons hand the VM a runtime WRAPPER around a real pattern (e.g. a
     * "ScaledProcessingPattern" that multiplies inputs/outputs). Compiling the
     * wrapper directly bakes the multiplier into the bytecode and makes the
     * wrapper the plan's patternTimes key — which the CPU / providers do not
     * recognize. Unwrapping to the innermost real pattern (naming convention
     * "Scaled*" + reflective {@code getOriginal()}) keeps the compiled form
     * and every plan key anchored to the pattern everyone knows. Unknown
     * wrappers are left untouched: no Scaled* class, no getOriginal(), or a
     * broken reflective call all return the input unchanged.
     */
    public static ICraftingPatternDetails unwrapScaled(ICraftingPatternDetails pattern) {
        ICraftingPatternDetails cur = pattern;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (!cur.getClass().getSimpleName().startsWith("Scaled")) {
                break;
            }
            try {
                Object orig = cur.getClass().getMethod("getOriginal").invoke(cur);
                if (orig instanceof ICraftingPatternDetails) {
                    cur = (ICraftingPatternDetails) orig;
                    continue;
                }
            } catch (Throwable ignored) {
            }
            break;
        }
        return cur;
    }

    public static void compileIfAbsent(ICraftingPatternDetails pattern) {
        pattern = unwrapScaled(pattern);
        if (pattern != null && !COMPILED_PATTERNS.containsKey(pattern)) {
            COMPILED_PATTERNS.computeIfAbsent(pattern, PatternCompiler::compilePattern);
        }
        if (pattern != null) {
            indexAnyOutput(pattern); // T4 byproduct fallback index (idempotent)
        }
    }

    public static CraftingBytecode getCompiled(ICraftingPatternDetails pattern) {
        pattern = unwrapScaled(pattern);
        return COMPILED_PATTERNS.get(pattern);
    }

    public static CraftingBytecode compileRequest(ICraftingPatternDetails pattern, long requestedAmount) {
        return compileRequest(pattern, requestedAmount, null);
    }

    /**
     * Compiles a request bytecode rooted at {@code requestedKey}. AE2UEL indexes
     * patterns by EVERY output slot, so a request may be rooted at a BYPRODUCT
     * (the pattern's primary output is something else) — craft count and output
     * key then derive from the requested output, not the primary one. A null or
     * primary-matching {@code requestedKey} keeps the primary-rooted behavior;
     * a key the pattern does not output at all also falls back to the primary.
     */
    public static CraftingBytecode compileRequest(ICraftingPatternDetails pattern, long requestedAmount,
                                                  IAEItemStack requestedKey) {
        pattern = unwrapScaled(pattern);
        CraftingBytecode patternBytecode = COMPILED_PATTERNS.get(pattern);
        if (patternBytecode == null) {
            compileIfAbsent(pattern);
            patternBytecode = COMPILED_PATTERNS.get(pattern);
            if (patternBytecode == null) {
                throw new IllegalStateException("Failed to compile pattern: " + pattern);
            }
        }

        long outputPerCraft = patternBytecode.getOutputAmountPerCraft();
        IAEItemStack outputKey = patternBytecode.getOutput();
        if (requestedKey != null && !requestedKey.isSameType(outputKey)) {
            IAEItemStack[] outs = pattern.getOutputs();
            if (outs != null) {
                for (IAEItemStack out : outs) {
                    if (out == null || !out.isSameType(requestedKey) || out.getStackSize() <= 0) {
                        continue;
                    }
                    outputPerCraft = (long) out.getStackSize();
                    outputKey = out;
                    break;
                }
            }
        }
        long craftTimes = (requestedAmount + outputPerCraft - 1L) / outputPerCraft;
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        int outputIdx = builder.addConstant(outputKey);
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
     * Catalyst / durability detection for one condensed input. Public: the VM's
     * cycle analyses must exclude returned inputs exactly like the original
     * excludes getRemainingKey(ik) == ik inputs (a catalyst is a seed, not a
     * per-craft consumption).
     *
     * Returns non-null when the pattern hands the input back: {@code [kind, uses]}
     * with uses == Long.MAX_VALUE for an unchanged catalyst or the number of
     * firings a full unit survives for a degrading tool.
     */
    public static long[] detectReturnedInput(ICraftingPatternDetails pattern, IAEItemStack input) {
        // 1) Crafting-pattern container-item semantics: the crafting executor
        //    hands the container back after every firing (native
        //    CraftingTreeProcess isPartContainer / Item#getContainerItem),
        //    so a same-key container is a catalyst and a degrading same-item
        //    container is a finite-use tool. Recipe remainders never appear in
        //    the pattern outputs, hence the dedicated probe. A different-item
        //    container stays a plain consumed input (same as the original VM).
        if (!isProcessingPattern(pattern)) {
            try {
                net.minecraft.item.ItemStack src = input.copy().setStackSize(1L).createItemStack();
                if (src != null && !src.isEmpty() && src.getItem().hasContainerItem(src)) {
                    net.minecraft.item.ItemStack cont = src.getItem().getContainerItem(src);
                    IAEItemStack contAE = cont == null || cont.isEmpty()
                            ? null : appeng.util.item.AEItemStack.fromItemStack(cont);
                    if (contAE != null) {
                        if (contAE.isSameType(input)) {
                            return new long[]{0L, Long.MAX_VALUE};
                        }
                        if (contAE.getItem() == input.getItem() && input.getItem().isDamageable()) {
                            int step = contAE.getItemDamage() - input.getItemDamage();
                            if (step > 0) {
                                long uses = (input.getItem().getMaxDamage()
                                        - input.getItemDamage()) / step;
                                if (uses > 0) {
                                    return new long[]{1L, uses};
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to the pattern-output probe.
            }
        }
        // 2) Explicit pattern-output return (processing catalysts / tools).
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
        if (returnedAmount == input.getStackSize()) {
            // Handed back unchanged, exactly as much as consumed → catalyst
            // seed. STRICT equality: an output exceeding the consumption is an
            // AMPLIFIER (A + B -> 2A) whose input is genuinely consumed every
            // craft and is handled by the aggregation's self-adjacent
            // correction - treating it as a catalyst would create items from
            // nothing. (The 1.21 original tells the two apart via
            // IInput#getRemainingKey, which 1.12 patterns do not expose.)
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
                // AE2FC fluid patterns may encode inputs in the packet form
                // while the network monitor exposes the canonical drop form —
                // normalize so EXTRACT_INGREDIENT can actually find the stock.
                IAEItemStack normalizedInput = AE2FCCompat.normalizeFluidItem(input);
                if (normalizedInput == null) {
                    normalizedInput = input;
                }
                long perCraft = normalizedInput.getStackSize();
                // Keep the encoded fluid amount on the key (drop AE size = mB):
                // CALL_BY_KEY resolution queries the grid with a packet built
                // from this amount, and the pattern-provider index is keyed by
                // the amount-carrying packet. Ordinary items stay size-normalized.
                IAEItemStack inputKey;
                if (AE2FCCompat.isFluidFakeItem(normalizedInput)) {
                    inputKey = normalizedInput.copy();
                    inputKey.reset();
                    inputKey.setStackSize(normalizedInput.getStackSize());
                } else {
                    inputKey = normalize(normalizedInput);
                }

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
                long[] returned = detectReturnedInput(pattern, normalizedInput);
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

    /** Clears the replacement-group registry (the compile caches stay warm). */
    public static void clearFuzzyGroups() {
        FUZZY_GROUPS.clear();
        PROCESSING_INPUT_KEYS.clear();
    }

    public static void clearCache() {
        COMPILED_PATTERNS.clear();
        ANY_OUTPUT_PRODUCERS.clear();
        clearFuzzyGroups();
    }

    public static int getCompiledCount() {
        return COMPILED_PATTERNS.size();
    }
}
