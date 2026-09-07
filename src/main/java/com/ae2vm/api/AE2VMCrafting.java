package com.ae2vm.api;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.AE2VM;
import com.ae2vm.compat.AE2FCCompat;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.NetworkCraftingSandbox;
import com.ae2vm.vm.VMPlan;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, CraftingVM> VM_CACHE =
            new ConcurrentHashMap<>();

    private AE2VMCrafting() {
    }

    /** Cached per-grid VM instance: the JIT bundle cache persists across requests. */
    public static CraftingVM vmFor(IGrid grid) {
        return VM_CACHE.computeIfAbsent(grid,
                g -> new CraftingVM(g, key -> resolve(g, new ConcurrentHashMap<>(), key)));
    }

    /**
     * Synchronous calculation for one request. Returns null when no pattern can
     * produce {@code what} (the caller should fall back to the native tree).
     * After execution the plan's remaining request overflow is available via
     * {@code vmFor(grid).getBatchRemainder()} (requests beyond Long.MAX_VALUE).
     */
    public static VMPlan calculate(IGrid grid, net.minecraft.world.World world,
                                   IAEItemStack what, long amount) {
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (craftingGrid == null) {
            return null;
        }

        // Root pattern lookup mirrors the resolver: exact key first, then the
        // packet key for AE2FC fluids (the root request key arrives in canonical
        // drop form while the grid index may be amount-carrying).
        ICraftingPatternDetails topPattern = findRootPattern(craftingGrid, what, amount, world);
        if (topPattern == null) {
            return null;
        }

        PatternCompiler.compileIfAbsent(topPattern);
        CraftingBytecode bytecode = PatternCompiler.compileRequest(topPattern, amount);
        if (bytecode == null) {
            return null;
        }

        CraftingVM vm = vmFor(grid);
        // Per-request resolver cache: repeated keys resolve once per calculation.
        Map<IAEItemStack, ICraftingPatternDetails> resolverCache = new ConcurrentHashMap<>();
        vm.setPatternResolver(key -> resolve(grid, world, resolverCache, key));

        NetworkCraftingSandbox sandbox = NetworkCraftingSandbox.snapshot(grid);
        IAEItemStack ignored = AE2FCCompat.normalizeFluidItem(what);
        sandbox.ignore(ignored != null ? ignored : what);

        VMPlan plan = vm.execute(bytecode, sandbox);
        return applyIgnoreFix(grid, what, plan);
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
            appeng.api.storage.channels.IItemStorageChannel channel =
                    appeng.api.AEApi.instance().storage()
                            .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class);
            appeng.api.storage.IMEMonitor<IAEItemStack> inv = sg.getInventory(channel);
            IAEItemStack stored = inv == null ? null : inv.getStorageList().findPrecise(what);
            avail = stored == null ? 0L : Math.max(0L, stored.getStackSize());
        } catch (Throwable t) {
            return rawPlan;
        }
        if (avail <= 0) {
            return rawPlan;
        }
        long usable = Math.min(avail, missingCount);
        com.ae2vm.vm.VMCounter fixedUsed = rawPlan.getUsedItems();
        fixedUsed.add(what, usable);
        com.ae2vm.vm.VMCounter fixedMissing = new com.ae2vm.vm.VMCounter();
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
                                                           net.minecraft.world.World world) {
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
     */
    private static ICraftingPatternDetails resolve(IGrid grid,
                                                   net.minecraft.world.World world,
                                                   Map<IAEItemStack, ICraftingPatternDetails> cache,
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
        // T1: exact match.
        Collection<ICraftingPatternDetails> subs = craftingGrid.getCraftingFor(key, null, -1, world);
        if (subs != null && !subs.isEmpty()) {
            ICraftingPatternDetails sub = pickBestPattern(subs, key);
            PatternCompiler.compileIfAbsent(sub);
            cache.put(key, sub);
            return sub;
        }

        // Fluid fallback: packet-keyed index.
        if (AE2FCCompat.isFluidFakeItem(key)) {
            IAEItemStack packet = AE2FCCompat.packFluidPacket(key, key.getStackSize());
            if (packet != null && !packet.isSameType(key)) {
                subs = craftingGrid.getCraftingFor(packet, null, -1, world);
                if (subs != null && !subs.isEmpty()) {
                    ICraftingPatternDetails sub = pickBestPattern(subs, packet);
                    if (sub != null && patternOutputs(sub, key)) {
                        PatternCompiler.compileIfAbsent(sub);
                        cache.put(key, sub);
                        return sub;
                    }
                }
            }
        }

        // T3: registry-pure key (same item, no NBT, damage 0) — verified.
        try {
            net.minecraft.item.Item item = key.getItem();
            if (item != null) {
                IAEItemStack pureKey = appeng.util.item.AEItemStack.fromItemStack(
                        new net.minecraft.item.ItemStack(item, 1, 0));
                if (pureKey != null && !pureKey.isSameType(key)) {
                    subs = craftingGrid.getCraftingFor(pureKey, null, -1, world);
                    if (subs != null && !subs.isEmpty()) {
                        ICraftingPatternDetails sub = pickBestPattern(subs, key);
                        if (sub != null && patternOutputs(sub, key)) {
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
        return null;
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
