package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.util.List;

/**
 * 1.12 replacement for AE2 1.21's CraftingSimulationState + ChildCraftingSimulationState.
 *
 * A sandbox inventory: {@link #extract} serves previously {@link #insert}ed items
 * first, then the network snapshot; {@link #ignore} zeroes the stored stock of the
 * requested output so a plan never consumes the target from the network (parity
 * with CraftingJob's craftingInventory.ignore(output)).
 */
public interface SimulationState {

    /**
     * Extraction from the sandbox. In simulate mode nothing is mutated; in
     * modulate mode sandbox inserts and the stock snapshot are debited.
     */
    long extract(IAEItemStack key, long amount, boolean simulate);

    /** Record produced items inside the sandbox (never touches the network). */
    void insert(IAEItemStack key, long amount);

    /**
     * Fuzzy family of {@code key} present in the network stock (same item, any
     * damage/NBT - FuzzyMode.IGNORE_ALL). May be empty.
     */
    List<IAEItemStack> findFuzzyFamily(IAEItemStack key);

    void addCrafting(ICraftingPatternDetails pattern, long times);

    void addBytes(double bytes);

    long getBytes();

    /** Zero the stored network stock of {@code key} (requested-output exclusion). */
    void ignore(IAEItemStack key);
}
