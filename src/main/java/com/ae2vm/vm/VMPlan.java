package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.util.HashMap;
import java.util.Map;

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
        this.patternTimes = new HashMap<>(patternTimes);
    }

    public IAEItemStack getOutputKey() { return outputKey; }

    public long getDeliverAmount() { return deliverAmount; }

    public long getBytes() { return bytes; }

    public boolean isSimulation() { return simulation; }

    public VMCounter getUsedItems() { return usedItems; }

    public VMCounter getMissingItems() { return missingItems; }

    public VMCounter getEmittedItems() { return emittedItems; }

    public Map<ICraftingPatternDetails, Long> getPatternTimes() {
        return new HashMap<>(patternTimes);
    }
}
