package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The VM's calculation result — the 1.12 analogue of AE2 1.21's CraftingPlan.
 * Consumed by the CraftingJob root adapter (setJob / populatePlan).
 */
public final class VMPlan {
    private final IAEItemStack outputKey;
    private final long deliverAmount;
    private final long bytes;
    private final boolean simulation;
    private final VMCounter usedItems;
    private final VMCounter missingItems;
    private final VMCounter emittedItems;
    private final Map<ICraftingPatternDetails, Long> patternTimes;

    public VMPlan(IAEItemStack outputKey, long deliverAmount, long bytes, boolean simulation,
                  VMCounter usedItems, VMCounter missingItems, VMCounter emittedItems,
                  Map<ICraftingPatternDetails, Long> patternTimes) {
        this.outputKey = outputKey;
        this.deliverAmount = deliverAmount;
        this.bytes = bytes;
        this.simulation = simulation;
        this.usedItems = usedItems;
        this.missingItems = missingItems;
        this.emittedItems = emittedItems;
        // LinkedHashMap: the plan's task order IS the execution order the
        // fold validated — a HashMap here re-shuffles it by identity hash
        // (different every JVM) and priority scheduling flips verdicts
        this.patternTimes = new LinkedHashMap<>(patternTimes);
    }

    public IAEItemStack getOutputKey() { return outputKey; }

    public long getDeliverAmount() { return deliverAmount; }

    public long getBytes() { return bytes; }

    public boolean isSimulation() { return simulation; }

    public VMCounter getUsedItems() { return usedItems; }

    public VMCounter getMissingItems() { return missingItems; }

    public VMCounter getEmittedItems() { return emittedItems; }

    public Map<ICraftingPatternDetails, Long> getPatternTimes() {
        return new LinkedHashMap<>(patternTimes);
    }

    /**
     * Stock re-validation for a memoized plan: it may be returned as-is only
     * while the network can still cover every planned consumption AND still
     * cannot cover any planned shortfall — a topped-up shortfall means the
     * world moved and the plan must be re-run. Pure function of the plan and
     * a stock lookup; no state, so it is unit-testable in isolation.
     */
    public boolean planMatchesStock(Function<IAEItemStack, Long> stock) {
        for (IAEItemStack key : usedItems.keys()) {
            Long have = stock.apply(key);
            if (have == null || have < usedItems.get(key)) {
                return false;
            }
        }
        for (IAEItemStack key : missingItems.keys()) {
            Long have = stock.apply(key);
            if (have != null && have >= missingItems.get(key)) {
                return false;
            }
        }
        return true;
    }
}
