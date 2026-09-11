package com.ae2vm.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2vm.AE2VM;
import com.ae2vm.compiler.PatternCompiler;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Stack-based VM crafting calculator, ported from AE2-VM 1.21.1 (NeoForge).
 *
 * Execution model (identical to the original):
 * 1) A request runs its bytecode once per 1-craft and captures a "bundle"
 *    (the delta of every ledger) per output key — bundle[0] is the true
 *    per-craft subtree effect.
 * 2) Bundles are memoized in {@code bundleCache}; cts>1 calls apply
 *    {@code bundle[0].scale(cts)} in O(1), recursive chains collapse to
 *    O(patterns) demand propagation in applyAggregation.
 * 3) Recursion (A+B→2A), catalyst feedback loops, pure conversion rings,
 *    durability tools and fuzzy substitution groups receive closed-form
 *    corrections identical to the original v1.10.x semantics.
 *
 * 1.12 port notes: AEKey → IAEItemStack (type-equality keys), KeyCounter →
 * VMCounter, CraftingSimulationState → SimulationState, ICraftingPlan → VMPlan.
 */
public class CraftingVM {
    private static final int MAX_STACK = 512;
    private static final int MAX_CALL_DEPTH = 128;

    private static final BigInteger BIG_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    // Pre-allocated BigInteger cache for values 0–1023 (hot values in VM)
    private static final BigInteger[] BIG_CACHE = new BigInteger[1024];
    static {
        for (int i = 0; i < 1024; i++) BIG_CACHE[i] = BigInteger.valueOf(i);
    }

    private final Object networkKey;
    private Function<IAEItemStack, ICraftingPatternDetails> patternResolver;
    /** All patterns per output key, for the pure-conversion-ring analysis (optional). */
    private Function<IAEItemStack, List<ICraftingPatternDetails>> allPatternsResolver;

    private BigInteger[] stack;
    private int sp;
    private Deque<CallFrame> callStack;
    private byte[] code;
    private int pc;
    private IAEItemStack[] constantPool;
    private ICraftingPatternDetails[] patternPool;

    private VMCounter usedItems;
    private VMCounter missingItems;
    private VMCounter emittedItems;
    private VMCounter simInternal;
    private VMCounter catalystSeedItems;
    private Map<IAEItemStack, long[]> durabilityItems;
    private Map<ICraftingPatternDetails, Long> patternTimes;
    private SimulationState simulation;
    private IAEItemStack outputKey;
    private long nodeCount;
    private long rootCraftTimes;
    private BigInteger batchRemainder;
    private boolean aggregated;

    private final Set<IAEItemStack> resolvingKeys = new HashSet<>();
    private final Set<IAEItemStack> circularCache = new HashSet<>();
    /** GAP-4 phase 2: net-effect bundles produced by the ring solver, applied post-order. */
    private final List<Bundle> ringNetBundles = new ArrayList<>();
    private final Set<IAEItemStack> ringKeysHandled = new HashSet<>();
    private final Set<IAEItemStack> cyclicCraftKeys = new HashSet<>();
    private final Set<IAEItemStack> jitFailCache = new HashSet<>();
    /** Pattern-set version this VM's caches were built against (see invalidateCaches). */
    private volatile long patternVersion = com.ae2vm.compiler.PatternCompiler.patternSetVersion();
    /** Merged fuzzy families (substitution group ∪ NBT family), per key. */
    private final Map<IAEItemStack, List<IAEItemStack>> fuzzyFamilyCache = new HashMap<>();
    /** Lazily snapshotted live network stock (an IItemList supports findFuzzy). */
    private IItemList<IAEItemStack> realStockCache;
    private VMCounter executeStartStock;
    private BigInteger requestAmount;
    private Map<IAEItemStack, Map<IAEItemStack, long[]>> selfAdjacentKeys;
    private boolean extractIsClaim;
    private boolean currentSlotFuzzy;
    private Map<IAEItemStack, BigInteger> stockFromNetwork;

    // JIT: per-pattern power-of-2 bundles. Bundle[0]=1 run; linear effects only.
    private static final int MAX_BUNDLE_BITS = 64;
    /**
     * Captured 1-craft bundles, keyed by (resolved key, pattern bytecode).
     * The bytecode component (identity of the compiled code array) matters:
     * the multi-pattern repair loop re-resolves keys to different patterns
     * across replay passes, and a key-keyed cache would silently replay a
     * stale subtree effect captured from another pattern — replays would be
     * exact copies of the greedy pass. Entries for several patterns of the
     * same key coexist; only the currently-resolved one is ever read (see
     * {@link #activeBundles}).
     */
    private final Map<BundleKey, Bundle[]> bundleCache = new HashMap<>();

    /**
     * Bundle-cache identity: item key + identity of the compiled code array.
     * The hash uses the code LENGTH (stable across JVM runs) rather than
     * {@code identityHashCode}: an identity-based hash made the cache's bucket
     * layout — and with it the same-type fallback's pick — vary per run, which
     * surfaced as run-to-run shortfall fluctuations in the allocation solver.
     * Distinct arrays of equal length merely collide in the bucket; equals
     * still compares the array identity.
     */
    private static final class BundleKey {
        final IAEItemStack key;
        final byte[] code;

        BundleKey(IAEItemStack key, byte[] code) {
            this.key = key;
            this.code = code;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BundleKey)) return false;
            BundleKey other = (BundleKey) o;
            return key.equals(other.key) && code == other.code;
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + code.length;
        }
    }

    /**
     * The bundle array for {@code key} under the CURRENTLY resolved pattern,
     * falling back to any captured bundle of the same key type (the pre-repair
     * lookup semantics — e.g. the request root's output key need not resolve
     * through the pattern resolver). Repair replays always hit the active
     * entry first, so a flipped key never reads a previous pattern's bundle.
     */
    private Bundle[] activeBundles(IAEItemStack key) {
        ICraftingPatternDetails details =
                patternResolver != null ? patternResolver.apply(key) : null;
        CraftingBytecode sbc = details != null ? PatternCompiler.getCompiled(details) : null;
        if (sbc != null) {
            Bundle[] arr = bundleCache.get(new BundleKey(key, sbc.getCode()));
            if (arr != null) {
                return arr;
            }
        }
        for (Map.Entry<BundleKey, Bundle[]> e : bundleCache.entrySet()) {
            if (e.getKey().key.isSameType(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static final class CallFrame {
        final int returnPc;
        final byte[] code;
        final IAEItemStack[] constantPool;
        final ICraftingPatternDetails[] patternPool;
        final IAEItemStack resolvingKey;
        final IAEItemStack bundleKey;
        final Bundle bundleBefore;
        final long savedReq;
        final Map<IAEItemStack, Long> subCalls;
        final Map<IAEItemStack, Long> fuzzySubCalls;

        CallFrame(int returnPc, byte[] code, IAEItemStack[] constantPool,
                  ICraftingPatternDetails[] patternPool, IAEItemStack resolvingKey) {
            this(returnPc, code, constantPool, patternPool, resolvingKey, null, null, 0, null, null);
        }

        CallFrame(int returnPc, byte[] code, IAEItemStack[] constantPool,
                  ICraftingPatternDetails[] patternPool, IAEItemStack resolvingKey,
                  IAEItemStack bundleKey, Bundle bundleBefore, long savedReq,
                  Map<IAEItemStack, Long> subCalls, Map<IAEItemStack, Long> fuzzySubCalls) {
            this.returnPc = returnPc;
            this.code = code;
            this.constantPool = constantPool;
            this.patternPool = patternPool;
            this.resolvingKey = resolvingKey;
            this.bundleKey = bundleKey;
            this.bundleBefore = bundleBefore;
            this.savedReq = savedReq;
            this.subCalls = subCalls;
            this.fuzzySubCalls = fuzzySubCalls;
        }

        CallFrame withBundle(IAEItemStack key, Bundle before, long req) {
            return new CallFrame(returnPc, code, constantPool, patternPool, resolvingKey, key, before, req,
                    new HashMap<>(), new HashMap<>());
        }

        void recordSubCall(IAEItemStack k, long r) {
            if (subCalls != null) subCalls.merge(k, r, Long::sum);
        }

        void recordFuzzySubCall(IAEItemStack k, long r) {
            if (fuzzySubCalls != null) fuzzySubCalls.merge(k, r, Long::sum);
        }
    }

    private static final class Bundle {
        BigInteger bytes = BigInteger.ZERO;
        final Map<IAEItemStack, BigInteger> used = new ConcurrentHashMap<>();
        final Map<IAEItemStack, BigInteger> emitted = new ConcurrentHashMap<>();
        final Map<IAEItemStack, BigInteger> missing = new ConcurrentHashMap<>();
        final Map<IAEItemStack, BigInteger> internal = new ConcurrentHashMap<>();
        final Map<ICraftingPatternDetails, BigInteger> patterns = new ConcurrentHashMap<>();
        // DIRECT sub-pattern needs (crafts / item amounts) — subtree effects are NOT
        // folded in; they are applied via these needs so scaling never double-counts.
        final Map<IAEItemStack, BigInteger> needs = new ConcurrentHashMap<>();
        final Map<IAEItemStack, BigInteger> itemNeeds = new ConcurrentHashMap<>();
        final Map<IAEItemStack, BigInteger> fuzzyItemNeeds = new ConcurrentHashMap<>();
        // One-time catalyst seeds (NOT scaled by craft count).
        final Map<IAEItemStack, BigInteger> seeds = new ConcurrentHashMap<>();
        // Finite-use tool rates (key → [amount, uses]) — NOT scaled.
        final Map<IAEItemStack, long[]> durability = new ConcurrentHashMap<>();
        // The pattern each direct sub-call was resolved to at capture time.
        // A replay may only reuse the bundle while the CURRENT resolver picks
        // the same pattern for every sub-call (the multi-pattern repair loop
        // re-resolves keys across passes); otherwise the subtree is stale.
        final Map<IAEItemStack, ICraftingPatternDetails> subChoices = new ConcurrentHashMap<>();
        // Direct sub-calls (incl. transitively merged ones) that resolved to NO
        // pattern at capture time. If any of them resolves NOW, the bundle is
        // stale: the subtree was captured with that branch reported missing, and
        // replaying it would keep hiding the newly registered pattern (the
        // upstream PatternRefreshReuse bug — "new pattern not recognized until
        // restart").
        final Set<IAEItemStack> missingSubKeys = ConcurrentHashMap.newKeySet();

        Bundle scale(long factor) { return scale(BigInteger.valueOf(factor)); }

        Bundle scale(BigInteger factor) {
            Bundle b = new Bundle();
            b.bytes = bytes.multiply(factor);
            used.forEach((k, v) -> b.used.put(k, v.multiply(factor)));
            emitted.forEach((k, v) -> b.emitted.put(k, v.multiply(factor)));
            missing.forEach((k, v) -> b.missing.put(k, v.multiply(factor)));
            b.missingSubKeys.addAll(missingSubKeys);
            internal.forEach((k, v) -> b.internal.put(k, v.multiply(factor)));
            patterns.forEach((k, v) -> b.patterns.put(k, v.multiply(factor)));
            needs.forEach((k, v) -> b.needs.put(k, v.multiply(factor)));
            itemNeeds.forEach((k, v) -> b.itemNeeds.put(k, v.multiply(factor)));
            fuzzyItemNeeds.forEach((k, v) -> b.fuzzyItemNeeds.put(k, v.multiply(factor)));
            seeds.forEach((k, v) -> b.seeds.put(k, v));
            durability.forEach(b.durability::put);
            return b;
        }

        boolean isEmpty() {
            return bytes.signum() == 0 && used.isEmpty() && emitted.isEmpty() && missing.isEmpty()
                && internal.isEmpty() && patterns.isEmpty() && needs.isEmpty() && itemNeeds.isEmpty()
                && fuzzyItemNeeds.isEmpty() && seeds.isEmpty() && durability.isEmpty();
        }
    }

    /** Safe BigInteger→long conversion; caps at Long.MAX_VALUE. */
    private static long toLongSafe(BigInteger v, String ctx) {
        if (v.compareTo(BIG_MAX_LONG) > 0) {
            return Long.MAX_VALUE;
        }
        if (v.signum() < 0) return 0;
        return v.longValue();
    }

    private static double toBytesDouble(BigInteger v) {
        return v.doubleValue();
    }

    public CraftingVM(Object networkKey, Function<IAEItemStack, ICraftingPatternDetails> patternResolver) {
        this.networkKey = networkKey;
        this.patternResolver = patternResolver;
    }

    public void setPatternResolver(Function<IAEItemStack, ICraftingPatternDetails> patternResolver) {
        this.patternResolver = patternResolver;
    }

    public void setAllPatternsResolver(Function<IAEItemStack, List<ICraftingPatternDetails>> resolver) {
        this.allPatternsResolver = resolver;
    }

    public BigInteger getBatchRemainder() { return batchRemainder; }

    /**
     * Re-arms the batch remainder to match a previously executed plan. Used by
     * the multi-pattern repair loop ({@code PatternChoiceRepair}), whose
     * returned best plan is not necessarily the last replayed pass — without
     * this, the exposed remainder would belong to a discarded trial.
     */
    public void restoreBatchRemainder(BigInteger remainder) {
        this.batchRemainder = remainder;
    }

    /**
     * Drops every cached state that depends on the network's pattern set
     * (JIT bundles, negative/fail caches, cycle bookkeeping). Called when the
     * grid's pattern-set version moves — a removed sub-pattern must not keep
     * serving plans replayed from bundles captured while it existed.
     */
    public void invalidateCaches() {
        bundleCache.clear();
        jitFailCache.clear();
        circularCache.clear();
        cyclicCraftKeys.clear();
        fuzzyFamilyCache.clear();
        realStockCache = null;
        patternVersion = com.ae2vm.compiler.PatternCompiler.patternSetVersion();
    }

    /** True when this VM's caches pre-date the current pattern set. */
    public boolean cachesStale() {
        return patternVersion != com.ae2vm.compiler.PatternCompiler.patternSetVersion();
    }

    public VMPlan execute(CraftingBytecode requestBytecode, SimulationState simulation) {
        synchronized (this) {
            return execute(requestBytecode, simulation,
                BigInteger.valueOf(requestBytecode.getOutputAmountPerCraft()));
        }
    }

    /**
     * True when every direct sub-call of the captured bundle still resolves to
     * the pattern it was captured with. A stale bundle — the repair loop (or a
     * different request) re-resolved a sub-call to another pattern — must not
     * be replayed: its subtree effect belongs to a different pattern mix.
     */
        private boolean bundleChoicesCurrent(Bundle bundle) {
            for (Map.Entry<IAEItemStack, ICraftingPatternDetails> e
                    : bundle.subChoices.entrySet()) {
                ICraftingPatternDetails current = patternResolver != null
                        ? patternResolver.apply(e.getKey()) : null;
                if (current != e.getValue()) {
                    return false;
                }
            }
            // A sub-call that had NO pattern at capture time but resolves now:
            // the captured subtree reported that branch missing — replaying it
            // would hide the pattern registered in the meantime.
            for (IAEItemStack sk : bundle.missingSubKeys) {
                if (patternResolver != null && patternResolver.apply(sk) != null) {
                    return false;
                }
            }
            return true;
        }

    private VMPlan execute(CraftingBytecode requestBytecode, SimulationState simulation,
                           BigInteger requestedAmount) {
        this.stack = new BigInteger[MAX_STACK];
        this.sp = 0;
        this.callStack = new ArrayDeque<>(MAX_CALL_DEPTH);
        resolvingKeys.clear();
        this.usedItems = new VMCounter();
        this.missingItems = new VMCounter();
        this.emittedItems = new VMCounter();
        this.simInternal = new VMCounter();
        this.catalystSeedItems = new VMCounter();
        this.durabilityItems = new HashMap<>();
        this.patternTimes = new HashMap<>();
        this.simulation = simulation;
        this.nodeCount = 1;
        this.rootCraftTimes = 0;
        this.batchRemainder = null;
        this.aggregated = false;
        this.outputKey = requestBytecode.getOutput();
        this.extractIsClaim = false;
        this.requestAmount = requestedAmount;
        this.realStockCache = null;
        this.stockFromNetwork = new HashMap<>();
        circularCache.clear();
        cyclicCraftKeys.clear();
        jitFailCache.clear();
        ringNetBundles.clear();
        ringKeysHandled.clear();
        this.executeStartStock = snapshotExecuteStartStock();

        long vmStartNs = System.nanoTime();

        loadBytecode(requestBytecode);

        while (pc < code.length) {
            int op = code[pc++] & 0xFF;
            switch (op) {
                case 0 -> { int idx = readShort(); long cnt = readLong(); pushL(popL() * cnt); } // PUSH_ITEM
                case 1 -> pushL(readLong()); // PUSH_LONG
                case 2 -> { // ADD with overflow detection
                    long b = popL(), a = popL(), r = a + b;
                    if (((a ^ r) & (b ^ r)) < 0) { push(BigInteger.valueOf(a).add(BigInteger.valueOf(b))); }
                    else pushL(r);
                }
                case 3 -> { // SUB with overflow detection
                    long b = popL(), a = popL(), r = a - b;
                    if (((a ^ b) & (a ^ r)) < 0) { push(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b))); }
                    else pushL(r);
                }
                case 4 -> { // MUL with overflow detection
                    long b = popL(), a = popL();
                    if ((b & (b - 1)) == 0) { pushL(a << Long.numberOfTrailingZeros(b)); break; }
                    long r = a * b;
                    if (b != 0 && r / b != a) { push(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))); }
                    else pushL(r);
                }
                case 5 -> { // DIV_ROUNDUP — bitwise fast path for powers of 2
                    long pc2 = popL(), rq = popL();
                    if (pc2 <= 0) { pushL(0); break; }
                    if ((pc2 & (pc2 - 1)) == 0) {
                        pushL((rq + pc2 - 1) >>> Long.numberOfTrailingZeros(pc2));
                    } else {
                        pushL((rq + pc2 - 1) / pc2);
                    }
                }
                case 6 -> { // EXTRACT_INGREDIENT
                    int idx = readShort(); IAEItemStack key = constantPool[idx]; long needed = popL();
                    if (needed <= 0) { pushL(0); break; }
                    simulation.addBytes(needed); nodeCount++;
                    long got = simulation.extract(key, needed, false);
                    if (got > 0) {
                        long internal = simInternal.get(key);
                        long fromInternal = Math.min(got, internal);
                        if (fromInternal > 0) simInternal.add(key, -fromInternal);
                        long fromNetwork = got - fromInternal;
                        if (!extractIsClaim && fromNetwork > 0) {
                            usedItems.add(key, fromNetwork);
                        }
                    }
                    // Processing-recipe default fuzzy: same-item NBT variants satisfy the slot.
                    if (got < needed && PatternCompiler.isProcessingInput(key)) {
                        long remaining = needed - got;
                        for (IAEItemStack variant : nbtFamilyOf(key)) {
                            if (variant.isSameType(key)) continue;
                            long vgot = simulation.extract(variant, remaining, false);
                            if (vgot <= 0) continue;
                            long vint = simInternal.get(variant);
                            long vfromInt = Math.min(vgot, vint);
                            if (vfromInt > 0) simInternal.add(variant, -vfromInt);
                            long vfromNet = vgot - vfromInt;
                            if (!extractIsClaim && vfromNet > 0) usedItems.add(variant, vfromNet);
                            got += vgot;
                            remaining -= vgot;
                            if (remaining <= 0) break;
                        }
                    }
                    extractIsClaim = false;
                    pushL(Math.max(0, needed - got));
                }
                case 7 -> { readShort(); popL(); } // RECORD_OUTPUT
                case 8 -> { readShort(); popL(); } // RECORD_INGREDIENT (legacy)
                case 9 -> { int idx = readShort(); long cnt = popL(); if (cnt > 0) missingItems.add(constantPool[idx], cnt); } // RECORD_MISSING
                case 10 -> push(peek()); // DUP
                case 11 -> popL(); // POP
                case 12 -> { long b = popL(), a = popL(); pushL(b); pushL(a); } // SWAP
                case 13 -> { // RECORD_PATTERN
                    int idx = readShort(); ICraftingPatternDetails pat = patternPool[idx]; long times = popL();
                    if (times > 0) {
                        patternTimes.merge(pat, times, Long::sum);
                        simulation.addCrafting(pat, times);
                        simulation.addBytes(times);
                    }
                }
                case 14 -> { // CALL
                    int pidx = readShort(); ICraftingPatternDetails pat = patternPool[pidx]; long ct = popL();
                    if (ct <= 0) break;
                    boolean isRoot = callStack.isEmpty();
                    if (isRoot) rootCraftTimes = ct;
                    // SELF-GROWTH LOOP CUT: A->2A with no external seed never fires.
                    if (isRoot && isUnseededSelfLoop(pat)) {
                        rootCraftTimes = 0;
                        long itemReq = toLongSafe(requestedAmount, "selfloop");
                        simulation.addBytes(itemReq); nodeCount++;
                        long got = simulation.extract(outputKey, itemReq, false);
                        long internal = simInternal.get(outputKey);
                        long fromInternal = Math.min(got, internal);
                        if (fromInternal > 0) simInternal.add(outputKey, -fromInternal);
                        long fromNetwork = got - fromInternal;
                        if (fromNetwork > 0) usedItems.add(outputKey, fromNetwork);
                        long shortfall = itemReq - got;
                        if (shortfall > 0) missingItems.add(outputKey, shortfall);
                        break;
                    }
                    CraftingBytecode sbc = PatternCompiler.getCompiled(pat);
                    if (sbc == null) { PatternCompiler.compileIfAbsent(pat); sbc = PatternCompiler.getCompiled(pat); }
                    if (sbc == null || callStack.size() >= MAX_CALL_DEPTH) break;
                    // Pattern-set gating (GAP-3): CALL slots bind the pattern
                    // directly, bypassing the resolver — a pattern REMOVED from
                    // the network since this bytecode was compiled would keep
                    // running on the stale slot reference. If the resolver no
                    // longer knows the key, treat the craft like any
                    // un-patterned key: consume stock, report the shortfall.
                    if (!isRoot && patternResolver != null && pat != null) {
                        IAEItemStack outKey = com.ae2vm.compat.PatternCompat.getPrimaryOutput(pat);
                        if (outKey != null && patternResolver.apply(outKey) == null) {
                            long got = simulation.extract(outKey, ct, false);
                            if (got > 0) usedItems.add(outKey, got);
                            long shortfall = ct - got;
                            if (shortfall > 0) missingItems.add(outKey, shortfall);
                            break;
                        }
                    }
                    if (isRoot) {
                        // Root frame: per-1-craft bundle (never pushL(ct) — the ×ct² bug).
                        Bundle snap = captureDelta();
                        resolvingKeys.add(outputKey);
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, outputKey)
                            .withBundle(outputKey, snap, ct));
                        loadBytecode(sbc); pushL(1);
                    } else {
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                        loadBytecode(sbc); pushL(ct);
                    }
                }
                case 15 -> { // RETURN
                    if (callStack.isEmpty()) { pc = code.length; break; }
                    // The callee's code array qualifies the bundle store below —
                    // save it before the frame restore overwrites this.code with
                    // the caller's saved code (CallFrame.code is the caller's).
                    byte[] calleeCode = code;
                    CallFrame f = callStack.pop(); code = f.code; constantPool = f.constantPool;
                    patternPool = f.patternPool; pc = f.returnPc;
                    extractIsClaim = true;
                    if (f.resolvingKey != null) {
                        if (f.bundleKey != null && f.bundleKey.equals(f.resolvingKey)) {
                            Bundle after = captureDelta();
                            Bundle delta = diffBundle(after, f.bundleBefore);
                            if (f.subCalls != null && !f.subCalls.isEmpty()) {
                                for (var sc : f.subCalls.entrySet()) {
                                    IAEItemStack sk = sc.getKey();
                                    long sreq = sc.getValue();
                                    if (cyclicCraftKeys.contains(sk)) continue;
                                    long sopc = 1;
                                    var subPat = patternResolver != null ? patternResolver.apply(sk) : null;
                                    var ssbc = subPat != null ? PatternCompiler.getCompiled(subPat) : null;
                                    if (ssbc != null) sopc = ssbc.getOutputAmountPerCraft();
                                    if (subPat != null) {
                                        delta.subChoices.put(sk, subPat);
                                        // Transitive choice record: the sub-call's
                                        // own bundle carries its whole subtree's
                                        // choices, so a deep change (X3 under X5
                                        // under X7) invalidates the outer bundle.
                                        if (ssbc != null) {
                                            Bundle[] subArr = bundleCache.get(
                                                    new BundleKey(sk, ssbc.getCode()));
                                            if (subArr != null && subArr[0] != null) {
                                                delta.subChoices.putAll(subArr[0].subChoices);
                                                delta.missingSubKeys.addAll(subArr[0].missingSubKeys);
                                            }
                                        }
                                    } else {
                                        delta.missingSubKeys.add(sk);
                                    }
                                    if (sreq > 0) {
                                        delta.itemNeeds.merge(sk, BigInteger.valueOf(sreq), BigInteger::add);
                                        if (sopc > 0) {
                                            long scts = (sreq + sopc - 1) / sopc;
                                            if (scts > 0) delta.needs.merge(sk, BigInteger.valueOf(scts), BigInteger::add);
                                        }
                                    }
                                }
                            }
                            if (f.fuzzySubCalls != null && !f.fuzzySubCalls.isEmpty()) {
                                for (var sc : f.fuzzySubCalls.entrySet()) {
                                    IAEItemStack sk = sc.getKey();
                                    long sreq = sc.getValue();
                                    if (cyclicCraftKeys.contains(sk)) continue;
                                    if (sreq > 0) {
                                        delta.fuzzyItemNeeds.merge(sk, BigInteger.valueOf(sreq), BigInteger::add);
                                    }
                                }
                            }
                            Bundle[] bundles = bundleCache.computeIfAbsent(
                                    new BundleKey(f.resolvingKey, calleeCode),
                                    k -> new Bundle[MAX_BUNDLE_BITS]);
                            bundles[0] = delta;
                            resolvingKeys.remove(f.resolvingKey);
                            boolean enclosingCapture = !callStack.isEmpty() && callStack.peek().bundleKey != null;
                            if (callStack.isEmpty()) {
                                revertBundle(delta);
                                extractIsClaim = true;
                            } else if (enclosingCapture) {
                                revertBundle(delta);
                                extractIsClaim = true;
                            } else if (f.savedReq > 1) {
                                revertBundle(delta);
                                pushL(f.savedReq);
                                pc = f.returnPc - 3;
                            } else {
                                revertBundle(delta);
                                applyBundle(delta);
                                extractIsClaim = true;
                            }
                        } else {
                            resolvingKeys.remove(f.resolvingKey);
                            extractIsClaim = true;
                        }
                    }
                }
                case 16 -> { // CALL_BY_KEY with JIT
                    int kidx = readShort(); IAEItemStack tk = constantPool[kidx]; long req = popL();
                    if (req <= 0) break;
                    if (tk == null) break;
                    boolean slotFuzzy = currentSlotFuzzy;
                    currentSlotFuzzy = false;
                    ICraftingPatternDetails sub = patternResolver != null ? patternResolver.apply(tk) : null;
                    if (sub == null) {
                        // No sub-pattern: pre-mark residual shortfall missing via SIMULATE.
                        simulation.addBytes(req); nodeCount++;
                        long availSim = simulation.extract(tk, req, true);
                        if (slotFuzzy) {
                            for (IAEItemStack variant : fuzzyFamilyOf(tk)) {
                                if (variant.isSameType(tk)) continue;
                                availSim += simulation.extract(variant, req, true);
                            }
                        } else if (PatternCompiler.isProcessingInput(tk)) {
                            // Processing exact slot: same-item NBT variants count —
                            // NEVER the cross-item replacement group (v1.10.5).
                            for (IAEItemStack v : nbtFamilyOf(tk)) {
                                if (v.isSameType(tk)) continue;
                                availSim += simulation.extract(v, req, true);
                            }
                        }
                        long shortfall = req - availSim;
                        if (shortfall > 0) missingItems.add(tk, shortfall);
                        break;
                    }
                    if (isUnseededSelfLoop(sub)) {
                        simulation.addBytes(req); nodeCount++;
                        long gotx = simulation.extract(tk, req, false);
                        if (gotx > 0) {
                            long internal = simInternal.get(tk);
                            long fromInternal = Math.min(gotx, internal);
                            if (fromInternal > 0) simInternal.add(tk, -fromInternal);
                            long fromNetwork = gotx - fromInternal;
                            if (fromNetwork > 0) usedItems.add(tk, fromNetwork);
                        } else {
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    PatternCompiler.compileIfAbsent(sub);
                    CraftingBytecode sbc = PatternCompiler.getCompiled(sub);
                    if (sbc == null) { missingItems.add(tk, req); break; }
                    if (callStack.size() >= MAX_CALL_DEPTH) { missingItems.add(tk, req); break; }
                    if (circularCache.contains(tk)) { missingItems.add(tk, req); break; }
                    if (!resolvingKeys.add(tk)) {
                        // Cycle: consume available stock, record shortfall as missing.
                        circularCache.add(tk);
                        CallFrame capFrame = callStack.peek();
                        if (capFrame != null && capFrame.bundleKey != null && !capFrame.bundleKey.isSameType(tk)) {
                            cyclicCraftKeys.add(capFrame.bundleKey);
                        }
                        simulation.addBytes(req); nodeCount++;
                        long gotx = simulation.extract(tk, req, false);
                        if (gotx > 0) {
                            long internal = simInternal.get(tk);
                            long fromInternal = Math.min(gotx, internal);
                            if (fromInternal > 0) simInternal.add(tk, -fromInternal);
                            long fromNetwork = gotx - fromInternal;
                            if (fromNetwork > 0) usedItems.add(tk, fromNetwork);
                        } else {
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    long opc = sbc.getOutputAmountPerCraft();
                    long cts = opc <= 0 ? 0 : (req + opc - 1) / opc;
                    if (cts <= 0) { resolvingKeys.remove(tk); break; }

                    boolean capturing = !callStack.isEmpty() && callStack.peek().bundleKey != null;
                    if (!callStack.isEmpty()) callStack.peek().recordSubCall(tk, req);
                    if (slotFuzzy && !callStack.isEmpty()) {
                        callStack.peek().recordFuzzySubCall(tk, req);
                    }

                    Bundle[] bundles = bundleCache.computeIfAbsent(
                            new BundleKey(tk, sbc.getCode()), k -> new Bundle[MAX_BUNDLE_BITS]);

                    if (capturing) {
                        if (bundles[0] == null || !bundleChoicesCurrent(bundles[0])) {
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                        } else if (!subBundlesComplete(bundles[0])
                                || shortfallRetryable(bundles[0])) {
                            resolvingKeys.remove(tk);
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                        } else {
                            resolvingKeys.remove(tk);
                        }
                        break;
                    }

                    if (cts == 1) {
                        if (bundles[0] == null || !bundleChoicesCurrent(bundles[0])) {
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, 1));
                            loadBytecode(sbc); pushL(cts);
                            break;
                        }
                        if (jitFailCache.contains(tk)) {
                            resolvingKeys.remove(tk);
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                            loadBytecode(sbc); pushL(1);
                            break;
                        }
                        Bundle b0 = bundles[0];
                        if (shortfallRetryable(b0)) {
                            // the shortfall capture pre-dates available stock:
                            // re-execute for real instead of replaying it
                            jitFailCache.add(tk);
                            resolvingKeys.remove(tk);
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                            loadBytecode(sbc); pushL(1);
                            break;
                        }
                        boolean sat1ok = true;
                        for (var e : b0.used.entrySet()) {
                            long usedPerCall = toLongSafe(e.getValue(), "sat");
                            long internalPerCall = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            long netDrain = Math.max(0, usedPerCall - internalPerCall);
                            if (netDrain == 0) continue;
                            long totalAvail = simulation.extract(e.getKey(), netDrain, true);
                            long vmInternal = simInternal.get(e.getKey());
                            long realAvail = Math.max(0, totalAvail - vmInternal);
                            if (realAvail < netDrain) { sat1ok = false; break; }
                        }
                        if (sat1ok) {
                            applyBundle(b0);
                            extractIsClaim = true;
                            resolvingKeys.remove(tk);
                            break;
                        }
                        jitFailCache.add(tk);
                        resolvingKeys.remove(tk);
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                        loadBytecode(sbc); pushL(1);
                        break;
                    }

                    if (bundles[0] != null && bundleChoicesCurrent(bundles[0])
                            && !shortfallRetryable(bundles[0])) {
                        Bundle b0 = bundles[0];
                        boolean selfSufficient = true;
                        for (var e : b0.used.entrySet()) {
                            long internal = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            if (toLongSafe(e.getValue(), "jit") > internal) { selfSufficient = false; break; }
                        }
                        if (selfSufficient) {
                            applyBundle(b0.scale(cts));
                            resolvingKeys.remove(tk);
                            break;
                        }
                        applyBundleDeficit(b0.scale(cts));
                        resolvingKeys.remove(tk);
                        break;
                    }

                    // No bundle yet: dispatch a single 1-craft to capture bundle[0];
                    // RETURN (savedReq>1) reverts and rewinds to re-run with cts.
                    Bundle snap = captureDelta();
                    callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                        .withBundle(tk, snap, req));
                    loadBytecode(sbc); pushL(1);
                }
                case 20 -> currentSlotFuzzy = true; // FUZZY_SLOT
                case 17 -> { int idx = readShort(); long amt = popL(); // INSERT_OUTPUT
                    if (amt > 0) {
                        simulation.insert(constantPool[idx], amt);
                        simInternal.add(constantPool[idx], amt);
                        emittedItems.add(constantPool[idx], amt);
                    }
                }
                case 18 -> { // CATALYST_SEED
                    int idx = readShort(); long amt = popL();
                    if (amt > 0 && constantPool[idx] != null) {
                        catalystSeedItems.add(constantPool[idx], amt);
                    }
                }
                case 19 -> { // DURABILITY_TOOL
                    int idx = readShort(); long uses = popL(); long amt = popL();
                    if (amt > 0 && uses > 0 && constantPool[idx] != null) {
                        durabilityItems.put(constantPool[idx], new long[]{amt, uses});
                    }
                }
                case 255 -> { // HALT
                    simulation.addBytes(nodeCount * 8.0);
                    if (rootCraftTimes > 0 && outputKey != null) simulation.addBytes(rootCraftTimes);
                    VMPlan plan = buildPlan(requestedAmount);
                    logPerfLine(vmStartNs);
                    return plan;
                }
                default -> { } // unknown opcode, skip
            }
        }
        VMPlan plan = buildPlan(requestedAmount);
        logPerfLine(vmStartNs);
        return plan;
    }

    private void logPerfLine(long vmStartNs) {
        long calcUs = (System.nanoTime() - vmStartNs) / 1_000;
        AE2VM.LOGGER.info("[AE2-VM] calc time: {} us ({} ms)", calcUs, String.format("%.2f", calcUs / 1000.0D));
    }

    private void applyBundleDirect(Bundle b) {
        simulation.addBytes(toBytesDouble(b.bytes));
        // Catalyst seeds are STARTUP capital: extract them BEFORE this bundle's own
        // outputs flood the sandbox, so a self-returned catalyst (A + B -> A + C)
        // can never satisfy its seed with its own circulating byproduct. Outputs of
        // OTHER patterns applied earlier (post-order) may legitimately seed it.
        for (var e : b.seeds.entrySet()) {
            long val = toLongSafe(e.getValue(), "seed");
            if (val <= 0) continue;
            simulation.addBytes(val); nodeCount++;
            long got = simulation.extract(e.getKey(), val, false);
            if (got > 0) {
                long internal = simInternal.get(e.getKey());
                long fromInternal = Math.min(got, internal);
                if (fromInternal > 0) simInternal.add(e.getKey(), -fromInternal);
                long fromNetwork = got - fromInternal;
                if (fromNetwork > 0) usedItems.add(e.getKey(), fromNetwork);
            }
            if (got < val) {
                long remaining = val - got;
                for (IAEItemStack variant : fuzzyFamilyOf(e.getKey())) {
                    if (variant.isSameType(e.getKey())) continue;
                    long vgot = simulation.extract(variant, remaining, false);
                    if (vgot <= 0) continue;
                    long vint = simInternal.get(variant);
                    long vfromInt = Math.min(vgot, vint);
                    if (vfromInt > 0) simInternal.add(variant, -vfromInt);
                    long vfromNet = vgot - vfromInt;
                    if (vfromNet > 0) usedItems.add(variant, vfromNet);
                    got += vgot;
                    remaining -= vgot;
                    if (remaining <= 0) break;
                }
            }
            long shortfall = val - got;
            if (shortfall > 0) missingItems.add(e.getKey(), shortfall);
        }
        for (var e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit");
            simulation.insert(e.getKey(), val);
            simInternal.add(e.getKey(), val);
        }
        for (var e : b.used.entrySet()) {
            long val = toLongSafe(e.getValue(), "used");
            long got = simulation.extract(e.getKey(), val, false);
            long internal = simInternal.get(e.getKey());
            long fromInternal = Math.min(got, internal);
            if (fromInternal > 0) simInternal.add(e.getKey(), -fromInternal);
            long fromNetwork = got - fromInternal;
            if (fromNetwork > 0) usedItems.add(e.getKey(), fromNetwork);
            long shortfall = val - got;
            if (shortfall > 0) missingItems.add(e.getKey(), shortfall);
        }
        for (var e : b.missing.entrySet()) {
            long val = toLongSafe(e.getValue(), "miss");
            if (val <= 0) continue;
            // Realtime-verify capture-time missing against current stock (v1.9.11).
            long got = simulation.extract(e.getKey(), val, false);
            if (got > 0) {
                long internal = simInternal.get(e.getKey());
                long fromInternal = Math.min(got, internal);
                if (fromInternal > 0) simInternal.add(e.getKey(), -fromInternal);
                long fromNetwork = got - fromInternal;
                if (fromNetwork > 0) usedItems.add(e.getKey(), fromNetwork);
            }
            long shortfall = val - got;
            if (shortfall > 0) missingItems.add(e.getKey(), shortfall);
        }
        for (var e : b.patterns.entrySet()) {
            long val = toLongSafe(e.getValue(), "pat");
            if (val != 0) {
                patternTimes.merge(e.getKey(), val, Long::sum);
                simulation.addCrafting(e.getKey(), val);
            }
        }
    }

    private void applyBundle(Bundle b) { applyBundleDirect(b); }
    private void applyBundleDeficit(Bundle b) { applyBundleDirect(b); }

    /**
     * Final aggregation over the captured bundle DAG: demand-propagation worklist
     * (O(patterns+edges)), stock-aware sub-craft with exact-vs-fuzzy slot split,
     * post-order single application of each bundle scaled by its total demand.
     */
    private void applyAggregation() {
        if (aggregated) return;
        aggregated = true;
        VMCounter initialStock = executeStartStock;
        Map<IAEItemStack, BigInteger> total = new HashMap<>();
        Map<IAEItemStack, Set<IAEItemStack>> children = new HashMap<>();
        Map<IAEItemStack, Integer> parentCount = new HashMap<>();
        {
            Deque<IAEItemStack> stack = new ArrayDeque<>();
            Set<IAEItemStack> seen = new HashSet<>();
            stack.push(outputKey);
            seen.add(outputKey);
            while (!stack.isEmpty()) {
                IAEItemStack k = stack.pop();
                Bundle[] arr = activeBundles(k);
                if (arr == null || arr[0] == null) continue;
                Set<IAEItemStack> subs = children.computeIfAbsent(k, x -> new HashSet<>());
                for (var e : arr[0].itemNeeds.entrySet()) {
                    IAEItemStack sub = e.getKey();
                    if (sub.isSameType(k)) continue;
                    if (subs.add(sub)) {
                        parentCount.merge(sub, 1, Integer::sum);
                        if (seen.add(sub)) stack.push(sub);
                    }
                }
            }
        }
        Map<IAEItemStack, BigInteger> itemDemand = new HashMap<>();
        Map<IAEItemStack, BigInteger> fuzzyItemDemand = new HashMap<>();
        Deque<IAEItemStack> queue = new ArrayDeque<>();
        total.put(outputKey, BigInteger.valueOf(rootCraftTimes));
        queue.add(outputKey);
        while (!queue.isEmpty()) {
            IAEItemStack p = queue.poll();
            BigInteger pCrafts = total.getOrDefault(p, BigInteger.ZERO);
            Bundle[] pArr = activeBundles(p);
            if (pArr == null || pArr[0] == null) continue;
            for (var e : pArr[0].itemNeeds.entrySet()) {
                IAEItemStack c = e.getKey();
                if (c.isSameType(p)) continue;
                if (total.containsKey(c)) continue; // cycle back-edge: stock-only
                BigInteger add = pCrafts.multiply(e.getValue());
                if (add.signum() != 0) itemDemand.merge(c, add, BigInteger::add);
                BigInteger fuzzyPerCraft = pArr[0].fuzzyItemNeeds.getOrDefault(c, BigInteger.ZERO);
                if (fuzzyPerCraft.signum() != 0) {
                    BigInteger fadd = pCrafts.multiply(fuzzyPerCraft);
                    if (fadd.signum() != 0) fuzzyItemDemand.merge(c, fadd, BigInteger::add);
                }
                int rem = parentCount.merge(c, 0, Integer::sum) - 1;
                parentCount.put(c, rem);
                if (rem == 0) {
                    BigInteger demand = itemDemand.getOrDefault(c, BigInteger.ZERO);
                    Bundle[] cArr = activeBundles(c);
                    if (cArr == null || cArr[0] == null) {
                        missingItems.add(c, toLongSafe(demand, "agg-miss"));
                    } else {
                        long opc = outputPerCraftOf(c, cArr[0]);
                        // STOCK-AWARE SUB-CRAFT with EXACT-vs-FUZZY slot split (v1.10.x).
                        Set<IAEItemStack> replacementGroup = PatternCompiler.getFuzzyGroup(c);
                        boolean hasReplacement = replacementGroup.size() > 1;
                        long primaryStock = realStockOf(c);
                        Set<IAEItemStack> nbtVariants = new HashSet<>();
                        if (PatternCompiler.isProcessingInput(c)) {
                            ensureRealStockSnapshot();
                            if (realStockCache != null) {
                                for (IAEItemStack v : realStockCache.findFuzzy(c, FuzzyMode.IGNORE_ALL)) {
                                    if (v.isSameType(c) || containsKey(replacementGroup, v)) continue;
                                    nbtVariants.add(v);
                                    primaryStock += realStockOf(v);
                                }
                            }
                        }
                        long substituteStock = 0;
                        if (hasReplacement) {
                            for (IAEItemStack v : replacementGroup) {
                                if (v.isSameType(c)) continue;
                                substituteStock += realStockOf(v);
                            }
                        }
                        BigInteger fuzzyDemand = fuzzyItemDemand.getOrDefault(c, BigInteger.ZERO);
                        if (fuzzyDemand.signum() < 0) fuzzyDemand = BigInteger.ZERO;
                        if (fuzzyDemand.compareTo(demand) > 0) fuzzyDemand = demand;
                        BigInteger exactDemand = demand.subtract(fuzzyDemand);
                        long fromStock = 0;
                        long primaryForExact = 0, primaryForFuzzy = 0;
                        if (primaryStock > 0 && demand.signum() > 0) {
                            primaryForExact = Math.min(primaryStock, toLongSafe(exactDemand, "prim-exact"));
                            primaryForFuzzy = Math.min(primaryStock - primaryForExact,
                                    toLongSafe(fuzzyDemand, "prim-fuzzy"));
                            long consumedPrimary = primaryForExact + primaryForFuzzy;
                            if (consumedPrimary > 0) {
                                long remaining = consumedPrimary;
                                List<IAEItemStack> primaries = new ArrayList<>(nbtVariants.size() + 1);
                                primaries.add(c);
                                primaries.addAll(nbtVariants);
                                for (IAEItemStack v : primaries) {
                                    long s = realStockOf(v);
                                    if (s <= 0) continue;
                                    long take = Math.min(remaining, s);
                                    if (take <= 0) continue;
                                    usedItems.add(v, take);
                                    simulation.extract(v, take, false);
                                    stockFromNetwork.merge(v, BigInteger.valueOf(take), BigInteger::add);
                                    remaining -= take;
                                }
                                fromStock += consumedPrimary;
                            }
                        }
                        long remainingFuzzy = Math.max(0L,
                                toLongSafe(fuzzyDemand.subtract(BigInteger.valueOf(primaryForFuzzy)), "rem-fuzzy"));
                        long substituteForFuzzy = 0;
                        if (remainingFuzzy > 0 && substituteStock > 0) {
                            substituteForFuzzy = Math.min(remainingFuzzy, substituteStock);
                            long remaining = substituteForFuzzy;
                            for (IAEItemStack v : replacementGroup) {
                                if (v.isSameType(c)) continue;
                                long s = realStockOf(v);
                                if (s <= 0) continue;
                                long take = Math.min(remaining, s);
                                if (take <= 0) continue;
                                usedItems.add(v, take);
                                simulation.extract(v, take, false);
                                remaining -= take;
                            }
                            fromStock += substituteForFuzzy;
                        }
                        if (hasReplacement) {
                            long poolAdd = toLongSafe(fuzzyDemand, "pool");
                            if (poolAdd > 0) {
                                for (IAEItemStack v : replacementGroup) {
                                    if (v.isSameType(c)) continue;
                                    stockFromNetwork.merge(v, BigInteger.valueOf(poolAdd), BigInteger::add);
                                }
                            }
                        }
                        BigInteger netDeficit = demand.subtract(BigInteger.valueOf(fromStock));
                        if (netDeficit.signum() < 0) netDeficit = BigInteger.ZERO;
                        BigInteger crafts = netDeficit.add(BigInteger.valueOf(opc - 1)).divide(BigInteger.valueOf(opc));
                        total.put(c, crafts);
                        queue.add(c);
                    }
                }
            }
        }
        this.selfAdjacentKeys = computeSelfKeys(total);
        if (selfAdjacentKeys != null && !selfAdjacentKeys.isEmpty()) {
            correctRecursion(total, initialStock);
        }
        solveRings(total);
        for (Bundle net : ringNetBundles) applyBundleDirect(net);
        Set<IAEItemStack> applied = new HashSet<>();
        for (IAEItemStack k : total.keySet()) applyOrdered(k, applied, total);
        Map<IAEItemStack, Long> loopMissing = computeFeedbackLoopMissing(total, initialStock);
        if (!loopMissing.isEmpty()) {
            for (var e : loopMissing.entrySet()) {
                missingItems.remove(e.getKey());
                if (e.getValue() > 0) missingItems.add(e.getKey(), e.getValue());
            }
        }
        Map<IAEItemStack, Long> ringMissing = computeConversionRingMissing(total, initialStock);
        if (!ringMissing.isEmpty()) {
            for (var e : ringMissing.entrySet()) {
                missingItems.add(e.getKey(), e.getValue());
            }
        }
    }

    private static boolean containsKey(Set<IAEItemStack> set, IAEItemStack key) {
        for (IAEItemStack s : set) {
            if (s.isSameType(key)) return true;
        }
        return false;
    }

    /** Self-adjacent patterns: own output key also a NON-returned consumed input. */
    private Map<IAEItemStack, Map<IAEItemStack, long[]>> computeSelfKeys(Map<IAEItemStack, BigInteger> total) {
        Map<IAEItemStack, Map<IAEItemStack, long[]>> result = new HashMap<>();
        if (patternResolver == null) return result;
        for (var en : total.entrySet()) {
            IAEItemStack key = en.getKey();
            if (key == null || en.getValue().signum() <= 0) continue;
            Bundle[] arr = activeBundles(key);
            if (arr == null || arr[0] == null) continue;
            ICraftingPatternDetails details = patternResolver.apply(key);
            if (details == null || isUnseededSelfLoop(details)) continue;
            Map<IAEItemStack, Long> inputs = new HashMap<>();
            IAEItemStack[] condensed = safeCondensedInputs(details);
            if (condensed != null) {
                for (IAEItemStack in : condensed) {
                    if (in == null || in.getStackSize() <= 0) continue;
                    // A returned/catalyst input is a seed, not a per-craft
                    // consumption — excluding it keeps marker patterns
                    // (X + A -> X + B) out of the self-adjacent set, exactly
                    // like the original's getRemainingKey(ik) == ik check.
                    if (PatternCompiler.detectReturnedInput(details, in) != null) continue;
                    IAEItemStack ik = in.copy();
                    long amt = ik.getStackSize();
                    ik.reset();
                    inputs.merge(ik, amt, Long::sum);
                }
            }
            if (inputs.isEmpty()) continue;
            Map<IAEItemStack, Long> outputs = new HashMap<>();
            IAEItemStack[] outs = safeOutputs(details);
            if (outs != null) {
                for (IAEItemStack gs : outs) {
                    if (gs == null || gs.getStackSize() <= 0) continue;
                    IAEItemStack ok = gs.copy();
                    long amt = ok.getStackSize();
                    ok.reset();
                    outputs.merge(ok, amt, Long::sum);
                }
            }
            if (outputs.isEmpty()) continue;
            Map<IAEItemStack, long[]> self = new HashMap<>();
            for (var e : inputs.entrySet()) {
                Long out = getOutputAmount(outputs, e.getKey());
                if (out != null && out > 0) {
                    self.put(e.getKey(), new long[]{e.getValue(), out});
                }
            }
            if (!self.isEmpty()) result.put(key, self);
        }
        return result;
    }

    private static IAEItemStack[] safeCondensedInputs(ICraftingPatternDetails details) {
        try {
            return details.getCondensedInputs();
        } catch (Throwable t) {
            return null;
        }
    }

    private static IAEItemStack[] safeOutputs(ICraftingPatternDetails details) {
        try {
            return details.getOutputs();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Total per-craft output amount of {@code key} in a type-keyed amount map. */
    private static Long getOutputAmount(Map<IAEItemStack, Long> outputs, IAEItemStack key) {
        long sum = 0;
        boolean found = false;
        for (var e : outputs.entrySet()) {
            if (e.getKey().isSameType(key)) {
                sum += e.getValue();
                found = true;
            }
        }
        return found ? sum : null;
    }

    /** Seed requirement + amplifier craft-count correction for self-adjacent patterns. */
    private void correctRecursion(Map<IAEItemStack, BigInteger> total, VMCounter initialStock) {
        for (var en : selfAdjacentKeys.entrySet()) {
            IAEItemStack k = en.getKey();
            Map<IAEItemStack, long[]> self = en.getValue();
            BigInteger cur = total.getOrDefault(k, BigInteger.ZERO);
            if (cur.signum() <= 0) continue;
            long t = toLongSafe(cur, "rec-total");
            if (t <= 0) continue;
            for (var se : self.entrySet()) {
                long in = se.getValue()[0];
                if (in <= 0) continue;
                long s = stockOf(initialStock, se.getKey());
                if (s < in) {
                    missingItems.add(se.getKey(), in - s);
                    t = 0;
                }
            }
            if (t <= 0) {
                total.put(k, BigInteger.ZERO);
                continue;
            }
            for (var se : self.entrySet()) {
                if (!se.getKey().isSameType(k)) continue;
                long in = se.getValue()[0];
                long out = se.getValue()[1];
                long net = out - in;
                if (net <= 0) continue;
                if (!k.isSameType(outputKey) || requestAmount == null) continue;
                long s = stockOf(initialStock, k);
                long req = toLongSafe(requestAmount, "rec-req");
                t = (req > s) ? (req - s + net - 1) / net : 0;
                break;
            }
            total.put(k, BigInteger.valueOf(t));
        }
    }

    private static long stockOf(VMCounter stock, IAEItemStack key) {
        return stock == null ? 0L : stock.get(key);
    }

    /** Snapshot the sandbox's INITIAL network stock, for every key the plan touches. */
    private VMCounter snapshotExecuteStartStock() {
        VMCounter snap = new VMCounter();
        Set<IAEItemStack> keys = new HashSet<>();
        Set<IAEItemStack> visited = new HashSet<>();
        collectPlanKeys(outputKey, keys, visited);
        // Replacement-group members and same-item stock variants must be probed
        // too: the stock-aware aggregation reads realStockOf() for them, and
        // without a live grid handle that reads this snapshot.
        List<IAEItemStack> expand = new ArrayList<>(keys);
        while (!expand.isEmpty()) {
            IAEItemStack k = expand.remove(expand.size() - 1);
            for (IAEItemStack v : PatternCompiler.getFuzzyGroup(k)) {
                if (v != null && keys.add(v)) expand.add(v);
            }
            if (simulation != null) {
                for (IAEItemStack v : simulation.findFuzzyFamily(k)) {
                    if (v != null && keys.add(v)) expand.add(v);
                }
            }
        }
        for (IAEItemStack k : keys) {
            if (k == null) continue;
            long amt = simulation.extract(k, Long.MAX_VALUE, true);
            if (amt > 0) snap.add(k, amt);
        }
        return snap;
    }

    /** Collect every key the plan touches via the recipe graph (leaves + byproducts). */
    private void collectPlanKeys(IAEItemStack key, Set<IAEItemStack> keys, Set<IAEItemStack> visited) {
        if (key == null || !visited.add(key)) return;
        keys.add(key);
        ICraftingPatternDetails p = patternResolver != null ? patternResolver.apply(key) : null;
        if (p == null) return;
        IAEItemStack[] condensed = safeCondensedInputs(p);
        if (condensed != null) {
            for (IAEItemStack in : condensed) {
                if (in == null || in.getStackSize() <= 0) continue;
                IAEItemStack ik = in.copy().setStackSize(1);
                ik.reset();
                keys.add(ik);
                collectPlanKeys(ik, keys, visited);
            }
        }
        IAEItemStack[] outs = safeOutputs(p);
        if (outs != null) {
            for (IAEItemStack gs : outs) {
                if (gs != null && gs.getStackSize() > 0) {
                    IAEItemStack ok = gs.copy().setStackSize(1);
                    ok.reset();
                    keys.add(ok);
                }
            }
        }
    }

    /** Structural per-craft recipe line of an in-plan pattern (for the loop analysis). */
    private static final class LoopPattern {
        final Map<IAEItemStack, Long> inputs;
        final Map<IAEItemStack, Long> outputs;
        final Set<IAEItemStack> byproducts;

        LoopPattern(Map<IAEItemStack, Long> inputs, Map<IAEItemStack, Long> outputs, Set<IAEItemStack> byproducts) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.byproducts = byproducts;
        }
    }

    /**
     * Catalyst feedback loops: working-capital (minimum seed) computation via a
     * forward simulation with deadlock-injection. Overrides loop items' missing.
     */
    private Map<IAEItemStack, Long> computeFeedbackLoopMissing(Map<IAEItemStack, BigInteger> total,
            VMCounter initialStock) {
        Map<IAEItemStack, LoopPattern> pats = new HashMap<>();
        Set<IAEItemStack> primaryOutputs = new HashSet<>();
        for (var en : total.entrySet()) {
            IAEItemStack key = en.getKey();
            if (key == null || en.getValue().signum() <= 0) continue;
            Bundle[] arr = activeBundles(key);
            if (arr == null || arr[0] == null) continue;
            ICraftingPatternDetails details = patternResolver != null ? patternResolver.apply(key) : null;
            if (details == null) continue;
            Map<IAEItemStack, Long> inputs = new HashMap<>();
            IAEItemStack[] condensed = safeCondensedInputs(details);
            if (condensed != null) {
                for (IAEItemStack in : condensed) {
                    if (in == null || in.getStackSize() <= 0) continue;
                    // A returned/catalyst input is a seed, not a consumption.
                    if (PatternCompiler.detectReturnedInput(details, in) != null) continue;
                    IAEItemStack ik = in.copy().setStackSize(1);
                    ik.reset();
                    inputs.merge(ik, in.getStackSize(), Long::sum);
                }
            }
            Map<IAEItemStack, Long> outputs = new HashMap<>();
            Set<IAEItemStack> byproducts = new HashSet<>();
            IAEItemStack[] outs = safeOutputs(details);
            if (outs != null) {
                for (int i = 0; i < outs.length; i++) {
                    IAEItemStack gs = outs[i];
                    if (gs == null || gs.getStackSize() <= 0) continue;
                    IAEItemStack ok = gs.copy().setStackSize(1);
                    ok.reset();
                    outputs.merge(ok, gs.getStackSize(), Long::sum);
                    if (i > 0) byproducts.add(ok);
                }
            }
            primaryOutputs.add(key);
            pats.put(key, new LoopPattern(inputs, outputs, byproducts));
        }
        if (pats.isEmpty()) return new HashMap<>();

        // 2) SCCs of the item graph containing a byproduct edge = feedback loops.
        Map<IAEItemStack, Set<IAEItemStack>> graph = new HashMap<>();
        for (LoopPattern p : pats.values()) {
            for (IAEItemStack i : p.inputs.keySet()) {
                graph.computeIfAbsent(i, x -> new HashSet<>()).addAll(p.outputs.keySet());
            }
        }
        Set<IAEItemStack> loopItems = new HashSet<>();
        for (Set<IAEItemStack> scc : tarjanScc(graph)) {
            if (scc.size() <= 1) continue;
            boolean hasByproduct = false;
            outer:
            for (LoopPattern p : pats.values()) {
                for (IAEItemStack i : p.inputs.keySet()) {
                    if (!containsKey(scc, i)) continue;
                    for (IAEItemStack j : p.outputs.keySet()) {
                        if (containsKey(scc, j) && containsKey(p.byproducts, j)) {
                            hasByproduct = true;
                            break outer;
                        }
                    }
                }
            }
            if (hasByproduct) loopItems.addAll(scc);
        }
        if (loopItems.isEmpty()) return new HashMap<>();

        // 3) Working-capital simulation with deadlock injection.
        Map<IAEItemStack, Long> available = new HashMap<>();
        if (initialStock != null) {
            for (var e : initialStock.entrySet()) {
                if (e.getValue() > 0) available.put(e.getKey(), e.getValue());
            }
        }
        Map<IAEItemStack, Long> remaining = new HashMap<>();
        for (var en : total.entrySet()) {
            if (en.getValue().signum() <= 0 || !pats.containsKey(en.getKey())) continue;
            remaining.put(en.getKey(), toLongSafe(en.getValue(), "loop-fire"));
        }
        Map<IAEItemStack, Long> injected = new HashMap<>();
        long totalFires = 0;
        for (Long v : remaining.values()) totalFires += v;
        final long FIRE_CAP = 500_000L;
        long guard = 0;
        while (!remaining.isEmpty() && totalFires <= FIRE_CAP && guard++ < 2 * FIRE_CAP + 100) {
            IAEItemStack fireable = null;
            for (var en : remaining.entrySet()) {
                if (en.getValue() <= 0) continue;
                LoopPattern p = pats.get(en.getKey());
                boolean ok = true;
                for (var e : p.inputs.entrySet()) {
                    Long av = available.get(e.getKey());
                    long avl = av == null ? 0L : av;
                    if (avl < e.getValue()) { ok = false; break; }
                }
                if (ok) { fireable = en.getKey(); break; }
            }
            if (fireable != null) {
                LoopPattern p = pats.get(fireable);
                remaining.merge(fireable, -1L, Long::sum);
                totalFires--;
                for (var e : p.inputs.entrySet())
                    available.merge(e.getKey(), -e.getValue(), Long::sum);
                for (var e : p.outputs.entrySet())
                    available.merge(e.getKey(), e.getValue(), Long::sum);
                continue;
            }
            IAEItemStack best = null;
            long bestDeficit = Long.MAX_VALUE;
            long bestPrimary = -1;
            for (var en : remaining.entrySet()) {
                if (en.getValue() <= 0) continue;
                LoopPattern p = pats.get(en.getKey());
                long deficit = 0;
                long primaryScore = 0;
                for (var e : p.inputs.entrySet()) {
                    Long av = available.get(e.getKey());
                    long avl = av == null ? 0L : av;
                    long gap = Math.max(0L, e.getValue() - avl);
                    deficit += gap;
                    if (gap > 0 && containsKey(primaryOutputs, e.getKey())) primaryScore++;
                }
                if (deficit == 0) { best = en.getKey(); bestDeficit = 0; break; }
                if (deficit < bestDeficit
                        || (deficit == bestDeficit && primaryScore > bestPrimary)) {
                    best = en.getKey(); bestDeficit = deficit; bestPrimary = primaryScore;
                }
            }
            if (best == null || bestDeficit <= 0) break;
            LoopPattern bp = pats.get(best);
            boolean injectedAny = false;
            for (var e : bp.inputs.entrySet()) {
                Long av = available.get(e.getKey());
                long avl = av == null ? 0L : av;
                long gap = Math.max(0L, e.getValue() - avl);
                if (gap > 0) {
                    injected.merge(e.getKey(), gap, Long::sum);
                    available.merge(e.getKey(), gap, Long::sum);
                    injectedAny = true;
                }
            }
            if (!injectedAny) break;
        }
        if (totalFires > FIRE_CAP) return new HashMap<>();

        Map<IAEItemStack, Long> result = new HashMap<>();
        for (IAEItemStack x : loopItems) {
            Long inj = injected.get(x);
            result.put(x, inj == null ? 0L : inj);
        }
        return result;
    }

    /** Directed pure-conversion edge {@code from -> to} exchanging in for out. */
    private static final class ConvEdge {
        final IAEItemStack to;
        final long in;
        final long out;

        ConvEdge(IAEItemStack to, long in, long out) {
            this.to = to;
            this.in = in;
            this.out = out;
        }

        IAEItemStack to() { return to; }

        long in() { return in; }

        long out() { return out; }
    }

    /** Pure-conversion-ring value conservation guard (adds missing, never removes). */
    private Map<IAEItemStack, Long> computeConversionRingMissing(Map<IAEItemStack, BigInteger> total,
            VMCounter initialStock) {
        Set<IAEItemStack> reachableKeys = new HashSet<>();
        collectPlanKeys(outputKey, reachableKeys, new HashSet<>());
        Map<IAEItemStack, List<LoopPattern>> recipesByKey = new HashMap<>();
        for (IAEItemStack key : reachableKeys) {
            if (key == null) continue;
            List<ICraftingPatternDetails> patterns = (allPatternsResolver != null)
                    ? allPatternsResolver.apply(key) : Collections.<ICraftingPatternDetails>emptyList();
            if (patterns.isEmpty()) {
                ICraftingPatternDetails chosen = patternResolver != null ? patternResolver.apply(key) : null;
                if (chosen != null) patterns = Collections.singletonList(chosen);
            }
            for (ICraftingPatternDetails details : patterns) {
                if (details == null) continue;
                Map<IAEItemStack, Long> in = new HashMap<>();
                IAEItemStack[] condensed = safeCondensedInputs(details);
                if (condensed != null) {
                    for (IAEItemStack entry : condensed) {
                        if (entry == null || entry.getStackSize() <= 0) continue;
                        // A returned/catalyst input is a seed, not a consumption.
                        if (PatternCompiler.detectReturnedInput(details, entry) != null) continue;
                        IAEItemStack ik = entry.copy().setStackSize(1);
                        ik.reset();
                        in.merge(ik, entry.getStackSize(), Long::sum);
                    }
                }
                if (in.isEmpty()) continue;
                Map<IAEItemStack, Long> out = new HashMap<>();
                Set<IAEItemStack> bp = new HashSet<>();
                int idx = 0;
                IAEItemStack[] outs = safeOutputs(details);
                if (outs != null) {
                    for (IAEItemStack gs : outs) {
                        if (gs == null || gs.getStackSize() <= 0) continue;
                        IAEItemStack ok = gs.copy().setStackSize(1);
                        ok.reset();
                        out.merge(ok, gs.getStackSize(), Long::sum);
                        if (idx > 0) bp.add(ok);
                        idx++;
                    }
                }
                if (out.isEmpty()) continue;
                recipesByKey.computeIfAbsent(key, x -> new ArrayList<>())
                        .add(new LoopPattern(in, out, bp));
            }
        }
        if (recipesByKey.isEmpty()) return new HashMap<>();

        Map<IAEItemStack, Set<IAEItemStack>> graph = new HashMap<>();
        for (List<LoopPattern> recs : recipesByKey.values()) {
            for (LoopPattern p : recs) {
                for (IAEItemStack i : p.inputs.keySet()) {
                    graph.computeIfAbsent(i, x -> new HashSet<>())
                            .addAll(p.outputs.keySet());
                }
            }
        }
        Map<IAEItemStack, Long> result = new HashMap<>();
        for (Set<IAEItemStack> scc : tarjanScc(graph)) {
            if (scc.size() <= 1) continue;
            // Pure-conversion check: 1-in/1-out internal exchanges, no byproducts.
            boolean pure = true;
            for (IAEItemStack member : scc) {
                List<LoopPattern> recs = null;
                for (var re : recipesByKey.entrySet()) {
                    if (re.getKey().isSameType(member)) { recs = re.getValue(); break; }
                }
                if (recs == null) { pure = false; break; }
                for (LoopPattern p : recs) {
                    if (!p.byproducts.isEmpty() || p.inputs.size() != 1 || p.outputs.size() != 1) {
                        pure = false;
                        break;
                    }
                    if (!containsKey(scc, p.inputs.keySet().iterator().next())
                            || !containsKey(scc, p.outputs.keySet().iterator().next())) {
                        pure = false;
                        break;
                    }
                }
                if (!pure) break;
            }
            if (!pure) continue;
            // Exchange values via edge BFS.
            Map<IAEItemStack, List<ConvEdge>> adj = new HashMap<>();
            for (IAEItemStack member : scc) {
                for (var re : recipesByKey.entrySet()) {
                    if (!re.getKey().isSameType(member)) continue;
                    for (LoopPattern p : re.getValue()) {
                        IAEItemStack from = p.inputs.keySet().iterator().next();
                        IAEItemStack to = p.outputs.keySet().iterator().next();
                        long a = p.inputs.get(from);
                        long b = p.outputs.get(to);
                        adj.computeIfAbsent(from, x -> new ArrayList<>())
                                .add(new ConvEdge(to, a, b));
                    }
                }
            }
            IAEItemStack base = scc.iterator().next();
            Map<IAEItemStack, BigInteger[]> value = new HashMap<>();
            value.put(base, new BigInteger[]{BigInteger.ONE, BigInteger.ONE});
            Deque<IAEItemStack> queue = new ArrayDeque<>();
            queue.add(base);
            boolean consistent = true;
            while (!queue.isEmpty() && consistent) {
                IAEItemStack cur = queue.poll();
                BigInteger[] cv = value.get(cur);
                List<ConvEdge> edges = adj.get(cur);
                if (edges == null) continue;
                for (ConvEdge e : edges) {
                    BigInteger num = cv[0].multiply(BigInteger.valueOf(e.in()));
                    BigInteger den = cv[1].multiply(BigInteger.valueOf(e.out()));
                    BigInteger g = num.gcd(den);
                    if (g.signum() > 0 && !g.equals(BigInteger.ONE)) {
                        num = num.divide(g);
                        den = den.divide(g);
                    }
                    BigInteger[] existing = value.get(e.to());
                    if (existing == null) {
                        value.put(e.to(), new BigInteger[]{num, den});
                        queue.add(e.to());
                    } else if (!existing[0].equals(num) || !existing[1].equals(den)) {
                        consistent = false;
                        break;
                    }
                }
            }
            if (!consistent) continue;
            // External demand on the ring from non-ring consumers.
            Map<IAEItemStack, BigInteger> demand = new HashMap<>();
            for (var en : total.entrySet()) {
                IAEItemStack k = en.getKey();
                if (containsKey(scc, k)) continue;
                List<LoopPattern> recs = null;
                for (var re : recipesByKey.entrySet()) {
                    if (re.getKey().isSameType(k)) { recs = re.getValue(); break; }
                }
                if (recs == null) continue;
                BigInteger t = en.getValue();
                if (t == null || t.signum() <= 0) continue;
                for (LoopPattern p : recs) {
                    for (var e : p.inputs.entrySet()) {
                        if (containsKey(scc, e.getKey())) {
                            demand.merge(e.getKey(), t.multiply(BigInteger.valueOf(e.getValue())),
                                    BigInteger::add);
                        }
                    }
                }
            }
            if (outputKey != null && containsKey(scc, outputKey) && requestAmount != null) {
                demand.merge(outputKey, requestAmount, BigInteger::add);
            }
            if (demand.isEmpty()) continue;
            // Ring-value comparison (exact fractions).
            BigInteger dNum = BigInteger.ZERO;
            BigInteger dDen = BigInteger.ONE;
            for (var e : demand.entrySet()) {
                BigInteger[] v = value.get(e.getKey());
                if (v == null) continue;
                BigInteger termNum = e.getValue().multiply(v[0]);
                BigInteger termDen = v[1];
                dNum = dNum.multiply(termDen).add(termNum.multiply(dDen));
                dDen = dDen.multiply(termDen);
            }
            BigInteger sNum = BigInteger.ZERO;
            BigInteger sDen = BigInteger.ONE;
            for (IAEItemStack member : scc) {
                BigInteger[] v = value.get(member);
                if (v == null) continue;
                long st = stockOf(initialStock, member);
                if (st <= 0) continue;
                BigInteger termNum = BigInteger.valueOf(st).multiply(v[0]);
                BigInteger termDen = v[1];
                sNum = sNum.multiply(termDen).add(termNum.multiply(sDen));
                sDen = sDen.multiply(termDen);
            }
            if (sNum.multiply(dDen).compareTo(dNum.multiply(sDen)) >= 0) {
                continue;
            }
            BigInteger deficitNum = dNum.multiply(sDen).subtract(sNum.multiply(dDen));
            BigInteger deficitDen = dDen.multiply(sDen);
            IAEItemStack best = null;
            BigInteger[] bestVal = null;
            for (IAEItemStack member : scc) {
                if (!containsKey(demand.keySet(), member)) continue;
                BigInteger[] v = value.get(member);
                if (v == null || v[0].signum() <= 0) continue;
                if (best == null || v[0].multiply(bestVal[1]).compareTo(bestVal[0].multiply(v[1])) < 0) {
                    best = member;
                    bestVal = v;
                }
            }
            if (best != null) {
                BigInteger need = deficitNum.multiply(bestVal[1])
                        .add(deficitDen.multiply(bestVal[0]).subtract(BigInteger.ONE))
                        .divide(deficitDen.multiply(bestVal[0]));
                long amount = need.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : need.longValue();
                Long prev = result.get(best);
                long prevL = prev == null ? 0L : prev;
                if (amount > 0) result.put(best, Math.max(prevL, amount));
            }
        }
        return result;
    }

    /** Iterative Tarjan SCC over a small item graph. */
    private static List<Set<IAEItemStack>> tarjanScc(Map<IAEItemStack, Set<IAEItemStack>> graph) {
        List<Set<IAEItemStack>> sccs = new ArrayList<>();
        if (graph.isEmpty()) return sccs;
        // 1.12 note: IAEItemStack has identity hashCode semantics that vary by
        // wrapper, so SCC bookkeeping uses a stable wrapper for map lookups.
        Map<Wrapper, Integer> index = new HashMap<>();
        Map<Wrapper, Integer> low = new HashMap<>();
        Map<Wrapper, IAEItemStack> unwrap = new HashMap<>();
        Deque<Wrapper> stack = new ArrayDeque<>();
        Set<Wrapper> onStack = new HashSet<>();
        Map<IAEItemStack, List<IAEItemStack>> adj = new HashMap<>();
        for (var e : graph.entrySet()) {
            adj.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        int[] counter = {0};
        for (IAEItemStack start : graph.keySet()) {
            Wrapper sw = Wrapper.of(start);
            if (index.containsKey(sw)) continue;
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[]{start, null});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                IAEItemStack node = (IAEItemStack) frame[0];
                Wrapper nw = Wrapper.of(node);
                if (!index.containsKey(nw)) {
                    index.put(nw, counter[0]);
                    low.put(nw, counter[0]);
                    unwrap.put(nw, node);
                    counter[0]++;
                    stack.push(nw);
                    onStack.add(nw);
                }
                @SuppressWarnings("unchecked")
                Iterator<IAEItemStack> it = (Iterator<IAEItemStack>) frame[1];
                if (it == null) {
                    List<IAEItemStack> next = adj.get(node);
                    it = (next == null ? Collections.<IAEItemStack>emptyList() : next).iterator();
                }
                boolean advanced = false;
                while (it.hasNext()) {
                    IAEItemStack w = it.next();
                    Wrapper ww = Wrapper.of(w);
                    if (!index.containsKey(ww)) {
                        frame[1] = it;
                        work.push(new Object[]{w, null});
                        advanced = true;
                        break;
                    } else if (onStack.contains(ww)) {
                        low.put(nw, Math.min(low.get(nw), index.get(ww)));
                    }
                }
                if (advanced) continue;
                work.pop();
                if (low.get(nw).equals(index.get(nw))) {
                    Set<IAEItemStack> scc = new HashSet<>();
                    Wrapper w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(unwrap.get(w));
                    } while (!w.equals(nw));
                    sccs.add(scc);
                }
                if (!work.isEmpty()) {
                    IAEItemStack parent = (IAEItemStack) work.peek()[0];
                    Wrapper pw = Wrapper.of(parent);
                    low.put(pw, Math.min(low.get(pw), low.get(nw)));
                }
            }
        }
        return sccs;
    }

    /** isSameType-hashed wrapper so graph maps behave per type equality. */
    private static final class Wrapper {
        final IAEItemStack key;

        private Wrapper(IAEItemStack key) {
            this.key = key;
        }

        static Wrapper of(IAEItemStack key) {
            return new Wrapper(key.copy().setStackSize(1));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Wrapper w && key.isSameType(w.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    private static long outputPerCraftOf(IAEItemStack key, Bundle b) {
        BigInteger out = null;
        for (var e : b.emitted.entrySet()) {
            if (e.getKey().isSameType(key)) { out = e.getValue(); break; }
        }
        if (out != null && out.signum() > 0) {
            long v = out.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : out.longValue();
            return v > 0 ? v : 1;
        }
        return 1;
    }

    /** Real network stock of a key, O(1) cached. */
    private long realStockOf(IAEItemStack key) {
        ensureRealStockSnapshot();
        if (realStockCache != null) {
            IAEItemStack found = realStockCache.findPrecise(key);
            return found == null ? 0L : Math.max(0L, found.getStackSize());
        }
        // No grid handle (tests / detached use): the sandbox was snapshotted
        // from the live network at execute() start, so its initial stock IS
        // the live stock for this calculation.
        return stockOf(executeStartStock, key);
    }

    /** Lazily snapshot the live network inventory (reset every execute()). */
    private void ensureRealStockSnapshot() {
        if (realStockCache == null) {
            IItemList<IAEItemStack> snap = null;
            try {
                if (networkKey instanceof IGrid g) {
                    appeng.api.networking.storage.IStorageGrid sg =
                            g.getCache(appeng.api.networking.storage.IStorageGrid.class);
                    if (sg != null) {
                        IItemStorageChannel channel = appeng.api.AEApi.instance().storage()
                                .getStorageChannel(IItemStorageChannel.class);
                        appeng.api.storage.IMEMonitor<IAEItemStack> inv = sg.getInventory(channel);
                        if (inv != null) {
                            snap = channel.createList();
                            inv.getAvailableItems(snap);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            realStockCache = snap;
        }
    }

    /**
     * Same-item NBT/damage variants present in the network stock — the
     * PROCESSING default fuzzy family (v1.10.x: usable by ANY processing slot,
     * unlike the compile-time replacement group which only applies to
     * replacement-enabled slots).
     */
    private List<IAEItemStack> nbtFamilyOf(IAEItemStack key) {
        return simulation.findFuzzyFamily(key);
    }

    /**
     * Effective fuzzy family for {@code key}: the compile-time substitution
     * group plus, for processing inputs, the same-item NBT variants present
     * in the network stock (delegated to the simulation state so tests and
     * grid-detached runs behave identically to the live network).
     * ONLY for replacement-enabled (FUZZY_SLOT) demand — exact slots use
     * {@link #nbtFamilyOf}.
     */
    /**
     * Memoized composition of the substitution group and the stock's NBT
     * family. Both inputs are stable for the VM's lifetime (the substitution
     * registry is version-gated and cleared in {@link #invalidateCaches()};
     * the stock key set is stable per sandbox), so the merged family is cached
     * per key instead of being rebuilt (two lists + a HashSet) on every call.
     */
    private List<IAEItemStack> fuzzyFamilyOf(IAEItemStack key) {
        List<IAEItemStack> cached = fuzzyFamilyCache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<IAEItemStack> family = new HashSet<>(PatternCompiler.getFuzzyGroup(key));
        family.addAll(simulation.findFuzzyFamily(key));
        List<IAEItemStack> list = new ArrayList<>(family);
        fuzzyFamilyCache.put(key, list);
        return list;
    }

    /**
     * True if {@code pattern} is an unseeded self-growth loop: its primary output
     * key is the key of EVERY one of its own inputs (A -> 2A) — an AE2 exploit
     * pattern that must never fire.
     */
    private boolean isUnseededSelfLoop(ICraftingPatternDetails pattern) {
        if (pattern == null) return false;
        IAEItemStack primary = com.ae2vm.compat.PatternCompat.getPrimaryOutput(pattern);
        if (primary == null) return false;
        IAEItemStack out = primary.copy().setStackSize(1);
        out.reset();
        IAEItemStack[] inputs = safeCondensedInputs(pattern);
        if (inputs == null || inputs.length == 0) return false;
        for (IAEItemStack input : inputs) {
            if (input == null) return false;
            if (!input.isSameType(out)) return false; // external seed → not cut
        }
        return true;
    }

    /** Read-only DFS that applies each bundle exactly once, children before parents. */
    private void applyOrdered(IAEItemStack k, Set<IAEItemStack> applied, Map<IAEItemStack, BigInteger> total) {
        if (!applied.add(k)) return;
        Bundle[] arr = activeBundles(k);
        if (arr != null && arr[0] != null) {
            for (var e : arr[0].itemNeeds.entrySet()) applyOrdered(e.getKey(), applied, total);
        }
        BigInteger t = total.getOrDefault(k, BigInteger.ZERO);
        if (t.signum() == 0) return;
        if (arr == null || arr[0] == null) {
            missingItems.add(k, toLongSafe(t, "agg-miss"));
            return;
        }
        Bundle scaled = arr[0].scale(t);
        Map<IAEItemStack, long[]> self = null;
        if (selfAdjacentKeys != null) {
            for (var se : selfAdjacentKeys.entrySet()) {
                if (se.getKey().isSameType(k)) { self = se.getValue(); break; }
            }
        }
        if (self != null && !self.isEmpty()) {
            for (var se : self.entrySet()) {
                long seed = se.getValue()[0];
                if (seed > 0) {
                    // collapse used demand to the one-time seed
                    BigInteger removed = scaled.used.remove(se.getKey());
                    scaled.used.put(se.getKey(), BigInteger.valueOf(seed));
                }
            }
        }
        subtractStockFromNetwork(scaled);
        applyBundleDirect(scaled);
        // Finite-use (durability) tool demand: amount × ceil(t/uses) tools.
        for (var d : arr[0].durability.entrySet()) {
            IAEItemStack toolKey = d.getKey();
            long amount = d.getValue()[0];
            long uses = d.getValue()[1];
            if (amount <= 0 || uses <= 0) continue;
            BigInteger units = t.add(BigInteger.valueOf(uses - 1)).divide(BigInteger.valueOf(uses));
            long demand = toLongSafe(units.multiply(BigInteger.valueOf(amount)), "dur");
            if (demand <= 0) continue;
            simulation.addBytes(demand); nodeCount++;
            long got = simulation.extract(toolKey, demand, false);
            if (got > 0) {
                long internal = simInternal.get(toolKey);
                long fromInternal = Math.min(got, internal);
                if (fromInternal > 0) simInternal.add(toolKey, -fromInternal);
                long fromNetwork = got - fromInternal;
                if (fromNetwork > 0) usedItems.add(toolKey, fromNetwork);
            }
            long shortfall = demand - got;
            if (shortfall > 0) missingItems.add(toolKey, shortfall);
        }
    }

    /** Remove the already-consumed network-stock pool from a bundle's used demand. */
    private void subtractStockFromNetwork(Bundle b) {
        if (stockFromNetwork == null || stockFromNetwork.isEmpty()) return;
        var it = b.used.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BigInteger pool = getPool(stockFromNetwork, e.getKey());
            if (pool == null || pool.signum() <= 0) continue;
            BigInteger used = e.getValue();
            BigInteger sub = used.min(pool);
            BigInteger rem = used.subtract(sub);
            if (rem.signum() <= 0) it.remove();
            else e.setValue(rem);
            BigInteger poolRem = pool.subtract(sub);
            setPool(stockFromNetwork, e.getKey(), poolRem);
        }
    }

    private static BigInteger getPool(Map<IAEItemStack, BigInteger> map, IAEItemStack key) {
        for (var e : map.entrySet()) {
            if (e.getKey().isSameType(key)) return e.getValue();
        }
        return null;
    }

    private static void setPool(Map<IAEItemStack, BigInteger> map, IAEItemStack key, BigInteger value) {
        IAEItemStack found = null;
        for (IAEItemStack k : map.keySet()) {
            if (k.isSameType(key)) { found = k; break; }
        }
        if (found == null && value.signum() > 0) {
            map.put(key.copy().setStackSize(1), value);
        } else if (found != null) {
            if (value.signum() <= 0) map.remove(found);
            else map.put(found, value);
        }
    }

    private boolean subBundlesComplete(Bundle b0) {
        if (b0.itemNeeds.isEmpty()) return true;
        for (IAEItemStack key : b0.itemNeeds.keySet()) {
            Bundle[] sub = getBundles(key);
            if (sub == null || sub[0] == null) return false;
        }
        return true;
    }

    private Bundle[] getBundles(IAEItemStack key) {
        return activeBundles(key);
    }

    /**
     * True when a shortfall capture should be RETRIED instead of replayed: one
     * of its missing keys is available from stock NOW, meaning the capture
     * pre-dates that stock ("算缺 → 补料 → 重算同一请求"). Replaying it would
     * keep reporting a shortfall that no longer exists — or worse, an empty
     * "feasible" plan whose crafts the CPU can never push. SIMULATE-only, so
     * the probe costs nothing.
     */
    private boolean shortfallRetryable(Bundle b0) {
        if (b0.missing.isEmpty()) {
            return false;
        }
        for (var e : b0.missing.entrySet()) {
            try {
                if (e.getValue().signum() > 0
                        && simulation.extract(e.getKey(), 1, true) > 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Undo a bundle's effects — reverse order of apply. */
    private void revertBundle(Bundle b) {
        simulation.addBytes(-toBytesDouble(b.bytes));
        for (var e : b.patterns.entrySet()) {
            long val = toLongSafe(e.getValue(), "pat-revert");
            long newVal = patternTimes.merge(e.getKey(), -val, Long::sum);
            if (newVal == 0) patternTimes.remove(e.getKey());
            simulation.addCrafting(e.getKey(), -val);
        }
        for (var e : b.missing.entrySet()) {
            long val = toLongSafe(e.getValue(), "miss-revert");
            missingItems.add(e.getKey(), -val);
            if (missingItems.get(e.getKey()) == 0) missingItems.remove(e.getKey());
        }
        for (var e : b.used.entrySet()) {
            long val = toLongSafe(e.getValue(), "used-revert");
            simulation.insert(e.getKey(), val);
            long internal = simInternal.get(e.getKey());
            long fromInternal = Math.min(val, internal);
            long fromNetwork = val - fromInternal;
            if (fromNetwork > 0) usedItems.add(e.getKey(), -fromNetwork);
            if (usedItems.get(e.getKey()) == 0) usedItems.remove(e.getKey());
        }
        for (var e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit-revert");
            simulation.extract(e.getKey(), val, false);
            emittedItems.add(e.getKey(), -val);
            if (emittedItems.get(e.getKey()) == 0) emittedItems.remove(e.getKey());
            simInternal.add(e.getKey(), -val);
            if (simInternal.get(e.getKey()) == 0) simInternal.remove(e.getKey());
        }
        for (var e : b.seeds.entrySet()) {
            long val = toLongSafe(e.getValue(), "seed-revert");
            catalystSeedItems.add(e.getKey(), -val);
            if (catalystSeedItems.get(e.getKey()) == 0) catalystSeedItems.remove(e.getKey());
        }
        for (var e : b.durability.entrySet()) {
            durabilityItems.remove(e.getKey());
        }
    }

    private Bundle captureDelta() {
        Bundle b = new Bundle();
        b.bytes = BigInteger.valueOf(simulation.getBytes());
        for (var e : usedItems.entrySet()) { if (e.getValue() != 0) b.used.put(e.getKey(), BigInteger.valueOf(e.getValue())); }
        for (var e : emittedItems.entrySet()) { if (e.getValue() != 0) b.emitted.put(e.getKey(), BigInteger.valueOf(e.getValue())); }
        for (var e : missingItems.entrySet()) { if (e.getValue() != 0) b.missing.put(e.getKey(), BigInteger.valueOf(e.getValue())); }
        for (var e : simInternal.entrySet()) { if (e.getValue() != 0) b.internal.put(e.getKey(), BigInteger.valueOf(e.getValue())); }
        for (var e : catalystSeedItems.entrySet()) { if (e.getValue() != 0) b.seeds.put(e.getKey(), BigInteger.valueOf(e.getValue())); }
        for (var en : durabilityItems.entrySet()) b.durability.put(en.getKey(), en.getValue());
        for (var k : patternTimes.keySet()) { long v = patternTimes.get(k); if (v != 0) b.patterns.put(k, BigInteger.valueOf(v)); }
        return b;
    }

    private Bundle diffBundle(Bundle after, Bundle before) {
        Bundle b = new Bundle();
        b.bytes = after.bytes.subtract(before.bytes);
        for (var e : after.used.entrySet()) {
            BigInteger bv = before.used.get(e.getKey());
            if (bv == null) bv = BigInteger.ZERO;
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.used.put(e.getKey(), d);
        }
        for (var e : after.emitted.entrySet()) {
            BigInteger bv = before.emitted.get(e.getKey());
            if (bv == null) bv = BigInteger.ZERO;
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.emitted.put(e.getKey(), d);
        }
        for (var e : after.missing.entrySet()) {
            BigInteger bv = before.missing.get(e.getKey());
            if (bv == null) bv = BigInteger.ZERO;
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.missing.put(e.getKey(), d);
        }
        for (var e : after.internal.entrySet()) {
            BigInteger bv = before.internal.get(e.getKey());
            if (bv == null) bv = BigInteger.ZERO;
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.internal.put(e.getKey(), d);
        }
        for (var e : after.seeds.entrySet()) {
            BigInteger bv = before.seeds.get(e.getKey());
            if (bv == null) bv = BigInteger.ZERO;
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.seeds.put(e.getKey(), d);
        }
        for (var e : after.durability.entrySet()) {
            boolean had = false;
            for (var be : before.durability.keySet()) {
                if (be.isSameType(e.getKey())) { had = true; break; }
            }
            if (!had) b.durability.put(e.getKey(), e.getValue());
        }
        for (var e : after.patterns.entrySet()) {
            BigInteger bv = before.patterns.get(e.getKey());
            if (bv == null) bv = BigInteger.ZERO;
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.patterns.put(e.getKey(), d);
        }
        return b;
    }

    private static java.io.PrintStream LEDGER_LOG;

    private static void ledgerLog(String s) {
        try {
            if (LEDGER_LOG == null) {
                LEDGER_LOG = new java.io.PrintStream(new java.io.FileOutputStream("/tmp/aevm-ledger.txt", true),
                        true, java.nio.charset.StandardCharsets.UTF_8.name());
            }
            LEDGER_LOG.println(s);
        } catch (Exception ignored) {
        }
    }

    private IAEItemStack familyRep(IAEItemStack k) {
        try {
            var g = PatternCompiler.getFuzzyGroup(k);
            if (g != null && !g.isEmpty()) {
                for (var m : g) {
                    if (m != null && m.isSameType(k)) return m;
                }
                return g.iterator().next();
            }
        } catch (Throwable ignored) {
        }
        return k;
    }

    /**
     * GAP-4 phase 2: solve the pure mutual rings whose back-edge demand the
     * propagation loop dropped, fold each into a net-effect bundle, and remove
     * the ring keys from the aggregation total (the net bundle takes over their
     * scheduling — including the root direction when the root key is a ring
     * member).
     */

    private void solveRings(Map<IAEItemStack, BigInteger> total) {
        List<RingSolver.RingPlan> plans;
        try {
        plans = RingSolver.solve(total, k -> {
            ICraftingPatternDetails d = patternResolver != null ? patternResolver.apply(k) : null;
            if (d == null) return null;
            Map<IAEItemStack, BigInteger> in = new HashMap<>();
            IAEItemStack[] ins = safeCondensedInputs(d);
            if (ins != null) {
                for (IAEItemStack i : ins) {
                    if (i == null || i.getStackSize() <= 0) continue;
                    if (PatternCompiler.detectReturnedInput(d, i) != null) continue;
                    IAEItemStack ik = i.copy().setStackSize(1);
                    ik.reset();
                    in.merge(ik, BigInteger.valueOf(i.getStackSize()), BigInteger::add);
                }
            }
            Map<IAEItemStack, BigInteger> out = new HashMap<>();
            IAEItemStack[] outs = safeOutputs(d);
            if (outs != null) {
                for (IAEItemStack o : outs) {
                    if (o == null || o.getStackSize() <= 0) continue;
                    IAEItemStack ok = o.copy().setStackSize(1);
                    ok.reset();
                    out.merge(ok, BigInteger.valueOf(o.getStackSize()), BigInteger::add);
                }
            }
            return new RingSolver.RecipeView() {
                @Override
                public ICraftingPatternDetails pattern() {
                    return d;
                }

                @Override
                public Map<IAEItemStack, BigInteger> inputs() {
                    return in;
                }

                @Override
                public Map<IAEItemStack, BigInteger> outputs() {
                    return out;
                }
            };
        }, k -> BigInteger.valueOf(executeStartStock.get(k)), outputKey, this.requestAmount);
        for (RingSolver.RingPlan plan : plans) {
            Bundle net = new Bundle();
            for (var e : plan.patterns.entrySet()) {
                long val = toLongSafe(e.getValue(), "ring-pat");
                if (val <= 0) continue;
                net.patterns.put(e.getKey(), BigInteger.valueOf(val));
            }
            for (var e : plan.emitted.entrySet()) {
                long val = toLongSafe(e.getValue(), "ring-emit");
                if (val > 0) net.emitted.put(e.getKey(), BigInteger.valueOf(val));
            }
            for (var e : plan.used.entrySet()) {
                long val = toLongSafe(e.getValue(), "ring-use");
                if (val > 0) net.used.put(e.getKey(), BigInteger.valueOf(val));
            }
            // startup seed shortfall: timing capital the network must hold
            // before the ring's first output lands — honest missing; the
            // ring's own production pays it back within the first round
            for (var e : plan.seedShortfall.entrySet()) {
                long val = toLongSafe(e.getValue(), "ring-seed");
                if (val > 0) missingItems.add(e.getKey(), val);
            }
            ringNetBundles.add(net);
        }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private VMPlan buildPlan(BigInteger requestedAmount) {
        applyAggregation();
        // final output is delivered separately — must not duplicate in emitted
        emittedItems.remove(outputKey);
        if (!missingItems.isEmpty()) {
            StringBuilder sb = new StringBuilder("[AE2-VM DIAG-MISS] rootCraftTimes=").append(rootCraftTimes).append(" missing:");
            for (var e : missingItems.entrySet()) {
                boolean hasPattern = patternResolver != null && patternResolver.apply(e.getKey()) != null;
                sb.append(" ").append(e.getValue()).append("x").append(e.getKey().getDefinition())
                        .append(hasPattern ? "(PATTERN)" : "(leaf)");
            }
            AE2VM.LOGGER.info(sb.toString());
        }
        long bytes = simulation.getBytes();
        long deliver;
        if (requestedAmount.compareTo(BIG_MAX_LONG) > 0) {
            deliver = Long.MAX_VALUE;
            batchRemainder = requestedAmount.subtract(BIG_MAX_LONG);
        } else {
            deliver = requestedAmount.longValue();
            batchRemainder = null;
        }
        boolean sim = !missingItems.isEmpty();
        return new VMPlan(outputKey, deliver, bytes, sim,
                usedItems, missingItems, emittedItems, new HashMap<>(patternTimes));
    }

    private void push(BigInteger v) { stack[sp++] = v; }

    private void pushL(long v) {
        stack[sp++] = v >= 0 && v < 1024 ? BIG_CACHE[(int) v] : BigInteger.valueOf(v);
    }

    private BigInteger pop() { return stack[--sp]; }

    private long popL() { return stack[--sp].longValue(); }

    private BigInteger peek() { return stack[sp - 1]; }

    private void loadBytecode(CraftingBytecode bc) {
        this.code = bc.getCode();
        this.constantPool = bc.getConstantPool();
        this.patternPool = bc.getPatternPool();
        this.pc = 0;
    }

    private int readShort() {
        int hi = code[pc++] & 0xFF;
        int lo = code[pc++] & 0xFF;
        return (hi << 8) | lo;
    }

    private long readLong() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (code[pc++] & 0xFF);
        }
        return v;
    }
}
