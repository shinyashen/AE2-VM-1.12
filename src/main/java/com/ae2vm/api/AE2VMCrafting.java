package com.ae2vm.api;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2vm.compat.AE2FCCompat;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.DeadCycleGuard;
import com.ae2vm.vm.NetworkCraftingSandbox;
import com.ae2vm.vm.PatternChoiceRepair;
import com.ae2vm.vm.VMCounter;
import com.ae2vm.vm.VMPlan;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Public entry point of the VM engine — the 1.12 port of the original's
 * AE2VMCrafting.calculate.
 *
 * Faithful ports of the original semantics:
 * - pickBestPattern: when several patterns produce the requested key, prefer
 *   the SMALLEST per-craft output so a small need never triggers a mega/bulk
 *   pattern (the 1000 energy-tablet -> 625M alloy bug).
 * - ignore-fix: ignore(output) hides the requested item from the sandbox, but
 *   recursive sub-patterns needing the same key trigger cycle detection ->
 *   false missing. When the plan reports the requested key as missing, check
 *   the real network stock and shift the stocked amount from missing to used;
 *   a fully corrected plan becomes executable (simulation = false).
 * - resolver T1/T3: exact key first, then a registry-pure key (no NBT, damage 0)
 *   verified against the pattern's actual primary output. (The original's
 *   T2 "drop secondary" is a 1.21 AEKey concept; on 1.12 the canonical drop
 *   form already IS the normalized key.)
 */
public final class AE2VMCrafting {
    private static final ConcurrentHashMap<IGrid, CraftingVM> VM_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Memoized plans per grid: request key → entry (amount + pattern-set
     * version + plan). A hit is returned as-is only after
     * {@link VMPlan#planMatchesStock} re-validates it against the LIVE
     * network stock — the "算缺 → 补料 → 重算" flow pays one stock probe
     * instead of a full engine re-run, and a topped-up shortfall falls
     * through to the slow path.
     */
    private static final ConcurrentHashMap<IGrid, ConcurrentHashMap<IAEItemStack, PlanEntry>> PLAN_CACHE =
            new ConcurrentHashMap<>();

    private static final class PlanEntry {
        final long amount;
        final long patternVersion;
        final VMPlan plan;

        PlanEntry(long amount, long patternVersion, VMPlan plan) {
            this.amount = amount;
            this.patternVersion = patternVersion;
            this.plan = plan;
        }
    }

    /**
     * Replay budget for the multi-pattern choice repair ({@link
     * PatternChoiceRepair}): only spent when the greedy first pass reports
     * missing, so successful requests never pay for it.
     */
    private static final int REPAIR_EXTRA_PASSES = 32;

    private AE2VMCrafting() {
    }

    /**
     * 1.12 parity of the original's {@code AE2VMCrafting.isLoaded()}: the class
     * is shipped inside the AE2 VM jar, so successfully referencing it means the
     * mod is installed. Third-party mods should still guard the class access
     * itself (e.g. {@code Loader.isModLoaded}) — see the original README.
     */
    public static boolean isLoaded() {
        return true;
    }

    /** Cached per-grid VM instance: the JIT bundle cache persists across requests. */
    public static CraftingVM vmFor(IGrid grid) {
        return VM_CACHE.computeIfAbsent(grid,
                g -> new CraftingVM(g, key -> null));
    }

    /**
     * Synchronous calculation for one request. Returns null when no pattern can
     * produce {@code what} (the caller should fall back to the native tree).
     * After execution the plan's remaining request overflow is available via
     * {@code vmFor(grid).getBatchRemainder()} (requests beyond Long.MAX_VALUE).
     */
    public static VMPlan calculate(IGrid grid, World world,
                                   IAEItemStack what, long amount) {
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (craftingGrid == null) {
            return null;
        }

        CraftingVM vm = vmFor(grid);
        if (vm.cachesStale()) {
            // the grid's pattern set changed since this VM's caches were built
            vm.invalidateCaches();
            PLAN_CACHE.remove(grid);
        }

        // Plan memoization hit: same request, same pattern set, and the live
        // stock still matches the plan (see VMPlan.planMatchesStock).
        ConcurrentHashMap<IAEItemStack, PlanEntry> plans = PLAN_CACHE.get(grid);
        PlanEntry entry = plans == null ? null : plans.get(what);
        if (entry != null && entry.amount == amount
                && entry.patternVersion == PatternCompiler.patternSetVersion()) {
            Function<IAEItemStack, Long> stock = liveStockLookup(grid);
            if (stock != null && entry.plan.planMatchesStock(stock)) {
                return entry.plan;
            }
            plans.remove(what, entry);
        }

        // Root pattern lookup mirrors the resolver: exact key first, then the
        // packet key for AE2FC fluids (the root request key arrives in canonical
        // drop form while the grid index may be amount-carrying).
        List<ICraftingPatternDetails> rootCandidates = new ArrayList<>();
        ICraftingPatternDetails topPattern =
                findRootPattern(craftingGrid, what, amount, world, rootCandidates);
        if (topPattern == null) {
            return null;
        }

        PatternCompiler.compileIfAbsent(topPattern);
        CraftingBytecode bytecode = PatternCompiler.compileRequest(topPattern, amount, what);
        if (bytecode == null) {
            return null;
        }

        // Multi-pattern choice repair: the greedy first pass is unchanged; only
        // when it reports missing are contended sub-pattern choices replayed
        // (see PatternChoiceRepair). Every pass gets a fresh resolver cache and
        // a fresh network snapshot so a replay sees the same network state. A
        // preference on the ROOT key re-compiles the request bytecode for that
        // pattern (its craft count derives from its own per-craft output).
        final IAEItemStack rootKey = bytecode.getOutput();
        // Pristine stock view for the repair model: never executed against, so
        // its stock reflects the network rather than a pass's consumption
        // (simulate extracts leave it untouched).
        final NetworkCraftingSandbox stockView = NetworkCraftingSandbox.snapshot(grid);
        // Resolver cache shared across ALL passes of this calculation: cached
        // entries are the no-preference resolutions (preference checks run
        // BEFORE the cache lookup in resolve()), and the dead-ring pruning
        // inside them is stock-stable within one request — re-running the
        // pruning per pass would only repeat identical work.
        final Map<IAEItemStack, ICraftingPatternDetails> resolverCache = new ConcurrentHashMap<>();
        final Map<VMPlan, BigInteger> remainders = new HashMap<>();
        PatternChoiceRepair.Pass pass = prefs -> {
            ICraftingPatternDetails passTop = prefs.get(rootKey);
            CraftingBytecode passBytecode = bytecode;
            if (passTop != null && passTop != topPattern) {
                passBytecode = PatternCompiler.compileRequest(passTop, amount, what);
                if (passBytecode == null) {
                    passTop = topPattern;
                    passBytecode = bytecode;
                }
            }
            PatternChoiceRepair.PassResult result = runPass(grid, world, vm,
                    passBytecode, passTop != null ? passTop : topPattern,
                    rootCandidates, what, stockView, prefs, resolverCache);
            remainders.put(result.plan, vm.getBatchRemainder());
            return result;
        };
        VMPlan plan = PatternChoiceRepair.repair(pass, REPAIR_EXTRA_PASSES);
        BigInteger remainder = remainders.get(plan);
        if (remainder != null) {
            // The winning plan is not necessarily the last replayed pass; keep
            // the exposed batch remainder consistent with the returned plan.
            vm.restoreBatchRemainder(remainder);
        }
        VMPlan fixed = applyIgnoreFix(grid, what, plan);
        if (fixed != null) {
            PLAN_CACHE.computeIfAbsent(grid, g -> new ConcurrentHashMap<>())
                    .put(what, new PlanEntry(amount, PatternCompiler.patternSetVersion(), fixed));
        }
        return fixed;
    }

    /**
     * Live network stock lookup for plan re-validation (one storage-list
     * reference per call site, precise per-key reads). Null when the grid has
     * no usable storage — memoization is skipped in that case.
     */
    private static Function<IAEItemStack, Long> liveStockLookup(IGrid grid) {
        try {
            IStorageGrid sg = grid.getCache(IStorageGrid.class);
            IItemStorageChannel channel =
                    AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
            IMEMonitor<IAEItemStack> inv = sg == null ? null : sg.getInventory(channel);
            IItemList<IAEItemStack> list = inv == null ? null : inv.getStorageList();
            if (list == null) {
                return null;
            }
            return key -> {
                try {
                    IAEItemStack stored = list.findPrecise(key);
                    return stored == null ? 0L : Math.max(0L, stored.getStackSize());
                } catch (Throwable t) {
                    return 0L;
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** One full engine pass under the given pattern-choice preferences. */
    private static PatternChoiceRepair.PassResult runPass(IGrid grid, World world,
                                                          CraftingVM vm,
                                                          CraftingBytecode bytecode,
                                                          ICraftingPatternDetails topPattern,
                                                          List<ICraftingPatternDetails> rootCandidates,
                                                          IAEItemStack what,
                                                          NetworkCraftingSandbox stockView,
                                                          Map<IAEItemStack, ICraftingPatternDetails> prefs,
                                                          Map<IAEItemStack, ICraftingPatternDetails> resolverCache) {
        PatternChoiceRepair.Choices choices = new PatternChoiceRepair.Choices();
        Function<IAEItemStack, Long> stockLookup = liveStockLookup(grid);
        vm.setPatternResolver(key -> resolve(grid, world, resolverCache, prefs, choices,
                stockLookup, key));

        NetworkCraftingSandbox sandbox = NetworkCraftingSandbox.snapshot(grid);
        IAEItemStack ignored = AE2FCCompat.normalizeFluidItem(what);
        sandbox.ignore(ignored != null ? ignored : what);

        // The root pattern is chosen by findRootPattern, not by the resolver —
        // record it (with its alternatives) so the repair model can propagate
        // the root demand and enumerate the root choice.
        choices.record(bytecode.getOutput(), topPattern,
                verifiedCandidates(rootCandidates, bytecode.getOutput()));

        VMPlan plan = vm.execute(bytecode, sandbox);
        return new PatternChoiceRepair.PassResult(plan, choices, stockView);
    }


    /**
     * API parity with the original's blocking entry point. The 1.12 job model is
     * synchronous end to end, so this is exactly {@link #calculate}; kept as a
     * named overload so third-party call sites port unchanged.
     */
    public static VMPlan calculateSync(IGrid grid, World world,
                                       IAEItemStack what, long amount) {
        return calculate(grid, world, what, amount);
    }

    /**
     * ignore-fix (v1.10.x parity): correct a simulated plan's requested-key
     * missing against the LIVE network stock.
     */
    private static VMPlan applyIgnoreFix(IGrid grid, IAEItemStack what, VMPlan rawPlan) {
        if (rawPlan == null || !rawPlan.isSimulation() || rawPlan.getMissingItems().isEmpty()) {
            return rawPlan;
        }
        long missingCount = rawPlan.getMissingItems().get(what);
        if (missingCount <= 0) {
            return rawPlan;
        }
        long avail;
        try {
            IStorageGrid sg = grid.getCache(IStorageGrid.class);
            IItemStorageChannel channel =
                    AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
            IMEMonitor<IAEItemStack> inv = sg.getInventory(channel);
            IAEItemStack stored = inv == null ? null : inv.getStorageList().findPrecise(what);
            avail = stored == null ? 0L : Math.max(0L, stored.getStackSize());
        } catch (Throwable t) {
            return rawPlan;
        }
        if (avail <= 0) {
            return rawPlan;
        }
        long usable = Math.min(avail, missingCount);
        VMCounter fixedUsed = rawPlan.getUsedItems();
        fixedUsed.add(what, usable);
        VMCounter fixedMissing = new VMCounter();
        for (var e : rawPlan.getMissingItems().entrySet()) {
            if (!e.getKey().isSameType(what)) {
                fixedMissing.add(e.getKey(), e.getValue());
            } else if (e.getValue() > usable) {
                fixedMissing.add(e.getKey(), e.getValue() - usable);
            }
        }
        return new VMPlan(rawPlan.getOutputKey(), rawPlan.getDeliverAmount(), rawPlan.getBytes(),
                !fixedMissing.isEmpty(), fixedUsed, fixedMissing,
                rawPlan.getEmittedItems(), rawPlan.getPatternTimes());
    }

    private static ICraftingPatternDetails findRootPattern(ICraftingGrid grid,
                                                           IAEItemStack what,
                                                           long amount,
                                                           World world,
                                                           List<ICraftingPatternDetails> outCandidates) {
        IAEItemStack key = what.copy();
        long amountHint = key.getStackSize();
        key.reset();
        Set<ICraftingPatternDetails> candidates = new LinkedHashSet<>();
        addAll(candidates, grid.getCraftingFor(key, null, -1, world));
        if (AE2FCCompat.isFluidFakeItem(key)) {
            IAEItemStack packet = AE2FCCompat.packFluidPacket(key, amountHint);
            if (packet != null && !packet.isSameType(key)) {
                addAll(candidates, grid.getCraftingFor(packet, null, -1, world));
            }
        }
        outCandidates.addAll(candidates);
        return pickBestPattern(candidates, key);
    }

    private static void addAll(Set<ICraftingPatternDetails> target,
                               Collection<ICraftingPatternDetails> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    /**
     * T1/T3 resolver. T1 exact key; T3 registry-pure key (no NBT, damage 0)
     * verified against the pattern's actual primary output — chain sub-patterns
     * with NBT variants (appflux cores, Fibonacci chains) resolve through T3.
     * Every result is verified to actually output the requested key.
     *
     * <p>Multi-pattern choice repair: a preference for the key (set by the
     * {@link PatternChoiceRepair} replay loop) overrides the greedy pick, and
     * every decision is recorded (chosen pattern + all verified alternatives)
     * so the repair loop can blame contended ancestors of missing keys.
     */
    private static ICraftingPatternDetails resolve(IGrid grid,
                                                   World world,
                                                   Map<IAEItemStack, ICraftingPatternDetails> cache,
                                                   Map<IAEItemStack, ICraftingPatternDetails> prefs,
                                                   PatternChoiceRepair.Choices record,
                                                   Function<IAEItemStack, Long> stockLookup,
                                                   IAEItemStack key) {
        if (key == null) {
            return null;
        }
        ICraftingPatternDetails cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (craftingGrid == null) {
            return null;
        }
        // Repair preference: a blamed key's forced pattern wins over the pick.
        ICraftingPatternDetails forced = prefs.get(key);
        if (forced != null) {
            PatternCompiler.compileIfAbsent(forced);
            cache.put(key, forced);
            record.record(key, forced, null);
            return forced;
        }
        // T1: exact match.
        Collection<ICraftingPatternDetails> subs = craftingGrid.getCraftingFor(key, null, -1, world);
        if (subs != null && !subs.isEmpty()) {
            // (CYCLE-AWARE) Drop candidates whose inputs would close a DEAD ring
            // (unseeded, externally-unfed SCC of the recipe graph, e.g.
            // dust<->ingot with neither in stock). Seeded / externally-fed rings
            // stay; when every candidate is ring-prone the originals are kept
            // and the VM's CALL-time resolvingKeys guard handles the cycle.
            // The pruned set also drives the recorded alternatives, so the
            // multi-pattern solver never treats a dead-ring pattern as a choice.
            List<ICraftingPatternDetails> viable = DeadCycleGuard.pruneDeadRings(
                    k -> craftingGrid.getCraftingFor(k, null, -1, world),
                    key, subs, stockLookup);
            ICraftingPatternDetails sub = pickBestPattern(viable, key);
            record.record(key, sub, verifiedCandidates(viable, key));
            PatternCompiler.compileIfAbsent(sub);
            cache.put(key, sub);
            return sub;
        }

        // T2.5: substitution-group variants. The slot may accept a
        // variant that is CRAFTABLE while the exact key itself is neither
        // stocked nor craftable — schedule the variant's craft and let the
        // fuzzy slot consume its output. (Upstream fixed the same gap in its
        // resolve(); gated on the slot's substitute group so the variant
        // space stays finite and explicit.)
        try {
            for (IAEItemStack variant : PatternCompiler.getFuzzyGroup(key)) {
                if (variant == null || variant.isSameType(key)) {
                    continue;
                }
                Collection<ICraftingPatternDetails> vsubs =
                        craftingGrid.getCraftingFor(variant, null, -1, world);
                if (vsubs == null || vsubs.isEmpty()) {
                    continue;
                }
                ICraftingPatternDetails sub = pickBestPattern(vsubs, variant);
                if (sub != null && patternOutputs(sub, variant)) {
                    record.record(key, sub, verifiedCandidates(vsubs, variant));
                    PatternCompiler.compileIfAbsent(sub);
                    cache.put(key, sub);
                    return sub;
                }
            }
        } catch (Throwable ignored) {
        }

        // Fluid fallback: packet-keyed index.
        if (AE2FCCompat.isFluidFakeItem(key)) {
            IAEItemStack packet = AE2FCCompat.packFluidPacket(key, key.getStackSize());
            if (packet != null && !packet.isSameType(key)) {
                subs = craftingGrid.getCraftingFor(packet, null, -1, world);
                if (subs != null && !subs.isEmpty()) {
                    ICraftingPatternDetails sub = pickBestPattern(subs, packet);
                    if (sub != null && patternOutputs(sub, key)) {
                        record.record(key, sub, verifiedCandidates(subs, key));
                        PatternCompiler.compileIfAbsent(sub);
                        cache.put(key, sub);
                        return sub;
                    }
                }
            }
        }

        // T3: registry-pure key (same item, no NBT, damage 0) — verified.
        try {
            Item item = key.getItem();
            if (item != null) {
                IAEItemStack pureKey = appeng.util.item.AEItemStack.fromItemStack(
                        new ItemStack(item, 1, 0));
                if (pureKey != null && !pureKey.isSameType(key)) {
                    subs = craftingGrid.getCraftingFor(pureKey, null, -1, world);
                    if (subs != null && !subs.isEmpty()) {
                        ICraftingPatternDetails sub = pickBestPattern(subs, key);
                        if (sub != null && patternOutputs(sub, key)) {
                            record.record(key, sub, verifiedCandidates(subs, key));
                            PatternCompiler.compileIfAbsent(sub);
                            cache.put(key, sub);
                            return sub;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Not found: no cross-network matching; the caller records missing.
        // NOTE: the byproduct-producer fallback is deliberately NOT
        // part of the capture resolver — resolving byproduct keys at capture
        // time re-shapes the catalyst/lossy reference scenarios (their
        // catalyst keys are themselves byproducts). The fallback lives ONLY
        // in the ring solver's recipeOf view, where the folded net bundle
        // covers the byproduct's production and consumption itself.
        return null;
    }

    /** All patterns that actually output {@code want}, in registration order. */
    private static List<ICraftingPatternDetails> verifiedCandidates(
            Collection<ICraftingPatternDetails> patterns, IAEItemStack want) {
        List<ICraftingPatternDetails> verified = new ArrayList<>(patterns.size());
        for (ICraftingPatternDetails p : patterns) {
            if (p != null && patternOutputs(p, want)) {
                verified.add(p);
            }
        }
        return verified;
    }

    /**
     * Pick the pattern producing {@code want} with the SMALLEST per-craft output
     * amount (mega/bulk pattern guard). Falls back to the first candidate.
     */
    private static ICraftingPatternDetails pickBestPattern(Collection<ICraftingPatternDetails> patterns,
                                                           IAEItemStack want) {
        ICraftingPatternDetails best = null;
        ICraftingPatternDetails fallback = null;
        long bestOut = Long.MAX_VALUE;
        for (ICraftingPatternDetails p : patterns) {
            if (p == null) continue;
            if (fallback == null) fallback = p;
            if (want != null && !patternOutputs(p, want)) continue;
            IAEItemStack out = PatternCompat.getPrimaryOutput(p);
            long amt = out == null ? Long.MAX_VALUE : Math.max(1L, out.getStackSize());
            if (amt < bestOut) {
                bestOut = amt;
                best = p;
            }
        }
        return best != null ? best : fallback;
    }

    /** True when the pattern's primary output is {@code want} (same type). */
    private static boolean patternOutputs(ICraftingPatternDetails pattern, IAEItemStack want) {
        IAEItemStack out = PatternCompat.getPrimaryOutput(pattern);
        return out != null && want != null && out.isSameType(want);
    }
}
