package com.ae2vm.vm;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.util.inv.ItemListIgnoreCrafting;
import com.ae2vm.compat.AE2FCCompat;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.IMEMonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live-network sandbox: an immutable snapshot of the network stock plus a
 * mutable insert cache. Parity with AE2-VM's RealtimeNetworkCraftingSimulationState
 * (always reads the LIVE inventory so the plan matches what the CPU can extract)
 * and CraftingJob's craftingInventory.ignore(output).
 */
public final class NetworkCraftingSandbox implements SimulationState {
    private final IItemList<IAEItemStack> stock;
    private final VMCounter inserted = new VMCounter();
    private final Map<ICraftingPatternDetails, Long> crafting = new HashMap<>();
    private double bytes = 0.0D;
    /**
     * Memoized fuzzy families. The stock KEY SET is stable for the sandbox's
     * lifetime (extract only decrements, inserts live in {@code inserted}), so
     * a family's member list never changes — only the per-record stack sizes
     * do, and those stay live through the retained record references. Without
     * this cache the variant-heavy fuzzy scenarios re-run stock.findFuzzy and
     * re-copy the family list on every short extraction.
     */
    private final Map<IAEItemStack, List<IAEItemStack>> fuzzyFamilyCache = new HashMap<>();

    /** Package-private: tests in this package construct a sandbox from a bare stock list. */
    NetworkCraftingSandbox(IItemList<IAEItemStack> stock) {
        this.stock = stock;
    }

    /** Snapshots the live network inventory (items + AE2FC fluids as drops). */
    public static NetworkCraftingSandbox snapshot(IGrid grid) {
        IItemStorageChannel itemChannel =
                AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        IItemList<IAEItemStack> bridge = itemChannel.createList();
        IItemList<IAEItemStack> stock = itemChannel.createList();
        if (grid != null) {
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid != null) {
                IMEMonitor<IAEItemStack> itemInventory = storageGrid.getInventory(itemChannel);
                if (itemInventory != null) {
                    itemInventory.getAvailableItems(new ItemListIgnoreCrafting<>(bridge));
                }
                boolean fluidAuthoritative = false;
                if (AE2FCCompat.isAvailable()) {
                    // The native fluid channel is authoritative: merge it in as
                    // canonical fake drops and keep AE2FC's own item-channel fake
                    // entries OUT of the stock to avoid double counting.
                    IFluidStorageChannel fluidChannel =
                            AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
                    IMEMonitor<IAEFluidStack> fluidInventory = storageGrid.getInventory(fluidChannel);
                    if (fluidInventory != null) {
                        IItemList<IAEFluidStack> fluids = fluidChannel.createList();
                        fluidInventory.getAvailableItems(new ItemListIgnoreCrafting<>(fluids));
                        for (IAEFluidStack fluid : fluids) {
                            if (fluid == null || fluid.getStackSize() <= 0L) continue;
                            IAEItemStack drop = AE2FCCompat.packFluid(fluid);
                            if (drop != null) addStock(stock, drop, false);
                        }
                        fluidAuthoritative = true;
                    }
                }
                for (IAEItemStack item : bridge) {
                    if (fluidAuthoritative && AE2FCCompat.isFluidFakeItem(item)) {
                        continue;
                    }
                    addStock(stock, item, false);
                }
            }
        }
        return new NetworkCraftingSandbox(stock);
    }

    private static void addStock(IItemList<IAEItemStack> list, IAEItemStack item, boolean replace) {
        IAEItemStack normalized = AE2FCCompat.normalizeFluidItem(item);
        if (normalized == null || normalized.getStackSize() <= 0L) {
            return;
        }
        normalized.setCraftable(false);
        normalized.setCountRequestable(0L);
        IAEItemStack existing = list.findPrecise(normalized);
        if (existing == null) {
            list.add(normalized);
        } else if (replace) {
            existing.setStackSize(normalized.getStackSize());
        }
    }

    @Override
    public long extract(IAEItemStack key, long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        long taken = 0L;
        // Sandbox inserts first.
        long internal = inserted.get(key);
        long fromInternal = Math.min(internal, amount);
        if (fromInternal > 0L && !simulate) {
            inserted.add(key, -fromInternal);
        }
        taken += fromInternal;
        long remaining = amount - fromInternal;
        if (remaining > 0L) {
            IAEItemStack current = stock.findPrecise(key);
            if (current != null) {
                long available = Math.max(0L, current.getStackSize());
                long fromStock = Math.min(remaining, available);
                if (fromStock > 0L && !simulate) {
                    current.setStackSize(available - fromStock);
                }
                taken += fromStock;
            }
        }
        return taken;
    }

    @Override
    public void insert(IAEItemStack key, long amount) {
        if (amount > 0L) {
            inserted.add(key, amount);
        }
    }

    @Override
    public List<IAEItemStack> findFuzzyFamily(IAEItemStack key) {
        List<IAEItemStack> cached = fuzzyFamilyCache.get(key);
        if (cached != null) {
            return cached;
        }
        // AE2FC fluid drops/packets: the NBT IS the identity (FluidName) —
        // every fluid shares the same Item and damage, so an NBT-tolerant
        // family would let ANY fluid substitute ANY other (water covering a
        // molten-platinum demand). Fluids resolve through exact keys and the
        // packet-key index; they have no family.
        if (AE2FCCompat.isFluidFakeItem(key)) {
            List<IAEItemStack> empty = new ArrayList<>(0);
            fuzzyFamilyCache.put(key, empty);
            return empty;
        }
        List<IAEItemStack> family = new ArrayList<>();
        for (IAEItemStack item : stock.findFuzzy(key, FuzzyMode.IGNORE_ALL)) {
            if (item == null) {
                continue;
            }
            // In 1.12 the damage value is item IDENTITY (Thermal materials,
            // dyes, ...): a lumium ingot (damage 166) must never be satisfied
            // by a platinum ingot (damage 134) of the same Item. The upstream
            // 1.21 NBT-tolerance maps to NBT-only variation — same item, same
            // damage, different NBT.
            if (item.getItemDamage() != key.getItemDamage()) {
                continue;
            }
            family.add(item);
        }
        fuzzyFamilyCache.put(key, family);
        return family;
    }

    @Override
    public void addCrafting(ICraftingPatternDetails pattern, long times) {
        crafting.merge(pattern, times, Long::sum);
    }

    @Override
    public void addBytes(double amount) {
        bytes += amount;
    }

    @Override
    public long getBytes() {
        return (long) Math.ceil(bytes);
    }

    @Override
    public void ignore(IAEItemStack key) {
        IAEItemStack current = stock.findPrecise(key);
        if (current != null) {
            current.setStackSize(0L);
        }
    }
}
