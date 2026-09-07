package com.ae2vm.bench;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.SimulationState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SimulationState fake: pre-seeded stock + sandbox inserts + crafting log. */
public final class BenchSimulationState implements SimulationState {
    private final Map<String, Long> stock = new LinkedHashMap<>();
    private final Map<String, Long> inserted = new LinkedHashMap<>();
    private final Map<ICraftingPatternDetails, Long> crafting = new LinkedHashMap<>();
    private final List<IAEItemStack> fuzzyFamily = new ArrayList<>();
    private double bytes = 0.0D;

    public BenchSimulationState seed(String id, long amount) {
        stock.merge(id, amount, Long::sum);
        return this;
    }

    public long craftingOf(ICraftingPatternDetails pattern) {
        return crafting.getOrDefault(pattern, 0L);
    }

    @Override
    public long extract(IAEItemStack key, long amount, boolean simulate) {
        BenchAEItemStack k = (BenchAEItemStack) key;
        long taken = 0L;
        long ins = inserted.getOrDefault(k.id, 0L);
        long fromIns = Math.min(ins, amount);
        if (fromIns > 0 && !simulate) {
            inserted.put(k.id, ins - fromIns);
        }
        taken += fromIns;
        long remaining = amount - fromIns;
        long have = stock.getOrDefault(k.id, 0L);
        long fromStock = Math.min(have, remaining);
        if (fromStock > 0 && !simulate) {
            stock.put(k.id, have - fromStock);
        }
        return taken + fromStock;
    }

    @Override
    public void insert(IAEItemStack key, long amount) {
        if (amount > 0) {
            inserted.merge(((BenchAEItemStack) key).id, amount, Long::sum);
        }
    }

    /** Same-id any-damage variants present in stock (processing default fuzzy). */
    @Override
    public List<IAEItemStack> findFuzzyFamily(IAEItemStack key) {
        return fuzzyFamily;
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
        stock.remove(((BenchAEItemStack) key).id);
    }
}
