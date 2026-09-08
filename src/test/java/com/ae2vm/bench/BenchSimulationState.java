package com.ae2vm.bench;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.SimulationState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SimulationState fake: pre-seeded stock + sandbox inserts + crafting log. */
public final class BenchSimulationState implements SimulationState {
    private final Map<BenchAEItemStack, Long> stock = new LinkedHashMap<>();
    /** Type-exact insert cache, matching NetworkCraftingSandbox: a crafted
     *  T(damage 1) byproduct must NOT satisfy a T(damage 0) demand. */
    private final Map<BenchAEItemStack, Long> inserted = new LinkedHashMap<>();
    /** Consumption recorded privately, like AE2's sandbox inventory: the seeded
     *  stock stands for the live network and is never mutated by planning. */
    private final Map<BenchAEItemStack, Long> extracted = new LinkedHashMap<>();
    private final Set<BenchAEItemStack> ignored = new HashSet<>();
    private final Map<ICraftingPatternDetails, Long> crafting = new LinkedHashMap<>();
    private final List<IAEItemStack> fuzzyFamily = new ArrayList<>();

    /** Manually appended fuzzy-family members for tests that pre-seed them. */
    public BenchSimulationState addFuzzyVariant(IAEItemStack key) {
        fuzzyFamily.add(key);
        return this;
    }
    private double bytes = 0.0D;

    public BenchSimulationState seed(String id, long amount) {
        return seedKey(new BenchAEItemStack(id, 0, 0, 1), amount);
    }

    /** Seeds a damage-variant stock entry (same id, different damage). */
    public BenchSimulationState seedVariant(String id, int damage, int maxDamage, long amount) {
        return seedKey(new BenchAEItemStack(id, damage, maxDamage, 1), amount);
    }

    private BenchSimulationState seedKey(BenchAEItemStack key, long amount) {
        stock.merge(key, amount, Long::sum);
        return this;
    }

    public long craftingOf(ICraftingPatternDetails pattern) {
        return crafting.getOrDefault(pattern, 0L);
    }

    @Override
    public long extract(IAEItemStack key, long amount, boolean simulate) {
        BenchAEItemStack k = (BenchAEItemStack) key;
        long taken = 0L;
        long ins = inserted.getOrDefault(k, 0L);
        long fromIns = Math.min(ins, amount);
        if (fromIns > 0 && !simulate) {
            inserted.put(k, ins - fromIns);
        }
        taken += fromIns;
        long remaining = amount - fromIns;
        if (remaining > 0 && !ignored.contains(k)) {
            BenchAEItemStack probe = new BenchAEItemStack(k.id, k.damage, k.maxDamage, 1);
            Long have = stock.get(probe);
            long haveL = have == null ? 0L : have;
            long already = extracted.getOrDefault(probe, 0L);
            long fromStock = Math.min(haveL - already, remaining);
            if (fromStock > 0 && !simulate) {
                extracted.put(probe, already + fromStock);
            }
            taken += fromStock;
        }
        return taken;
    }

    @Override
    public void insert(IAEItemStack key, long amount) {
        if (amount > 0) {
            inserted.merge((BenchAEItemStack) key, amount, Long::sum);
        }
    }

    /** Same-id any-damage variants present in stock (processing default fuzzy). */
    @Override
    public List<IAEItemStack> findFuzzyFamily(IAEItemStack key) {
        List<IAEItemStack> family = new ArrayList<>();
        String id = ((BenchAEItemStack) key).id;
        for (BenchAEItemStack k : stock.keySet()) {
            if (k.id.equals(id)) {
                family.add(k);
            }
        }
        family.addAll(fuzzyFamily);
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
        // BenchAEItemStack equality is type-based, so the set matches any stack size.
        ignored.add((BenchAEItemStack) key);
    }
}
