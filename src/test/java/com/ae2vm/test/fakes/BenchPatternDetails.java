package com.ae2vm.test.fakes;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ICraftingPatternDetails fake backed by plain recipe lines: condensed inputs
 * and ordered outputs (first = primary, rest = byproducts). Deliberately
 * reports isCraftable() == false (processing semantics), matching the VM's
 * benchmark reference graphs.
 */
public final class BenchPatternDetails implements ICraftingPatternDetails {
    private final IAEItemStack[] condensedInputs;
    private final IAEItemStack[] outputs;
    /** Condensed slot index -> accepted substitute variants (replacement-enabled slots). */
    private final Map<Integer, List<IAEItemStack>> slotSubs;

    private BenchPatternDetails(IAEItemStack[] condensedInputs, IAEItemStack[] outputs) {
        this(condensedInputs, outputs, java.util.Collections.emptyMap());
    }

    private BenchPatternDetails(IAEItemStack[] condensedInputs, IAEItemStack[] outputs,
                                Map<Integer, List<IAEItemStack>> slotSubs) {
        this.condensedInputs = condensedInputs;
        this.outputs = outputs;
        this.slotSubs = slotSubs;
    }

    /** Pattern whose inputs accept one substitute variant (replacement enabled). */
    public static BenchPatternDetails withSubstitute(long[][] inputs, long[][] outputs, String subId) {
        return withSlotSubstitute(processing(inputs, outputs), new int[]{0}, subId);
    }

    /** Enables the substitute id on exactly the given condensed input slots. */
    public static BenchPatternDetails withSlotSubstitute(BenchPatternDetails base, int[] slots, String subId) {
        Map<Integer, List<IAEItemStack>> slotSubs = new java.util.HashMap<>();
        for (int slot : slots) {
            slotSubs.put(slot, java.util.Collections.singletonList(
                    (IAEItemStack) new BenchAEItemStack(subId, 1)));
        }
        return new BenchPatternDetails(base.condensedInputs, base.outputs, slotSubs);
    }

    /** Enables arbitrary pre-built variant keys on exactly the given condensed slots. */
    public static BenchPatternDetails withSlotVariants(BenchPatternDetails base,
                                                       Map<Integer, List<IAEItemStack>> slotSubs) {
        return new BenchPatternDetails(base.condensedInputs, base.outputs,
                new java.util.HashMap<>(slotSubs));
    }

    /** inputs: {id, amount} pairs; outputs: first is primary, rest are byproducts. */
    /** Custom recipe line with arbitrary fake keys (first output = primary). */
    public static BenchPatternDetails custom(IAEItemStack[] inputs, IAEItemStack[] outputs) {
        return new BenchPatternDetails(inputs, outputs);
    }

    public static BenchPatternDetails processing(long[][] inputs, long[][] outputs) {
        List<IAEItemStack> in = new ArrayList<>();
        for (long[] i : inputs) {
            in.add(new BenchAEItemStack(String.valueOf((char) ('A' + (int) i[0])), i[1]).setStackSize(i[1]));
        }
        List<IAEItemStack> out = new ArrayList<>();
        for (long[] o : outputs) {
            BenchAEItemStack s = new BenchAEItemStack(String.valueOf((char) ('A' + (int) o[0])), o[1]);
            s.setStackSize(o[1]);
            out.add(s);
        }
        return new BenchPatternDetails(in.toArray(new IAEItemStack[0]),
                out.toArray(new IAEItemStack[0]));
    }

    public static IAEItemStack key(int id) {
        return new BenchAEItemStack(String.valueOf((char) ('A' + id)), 1);
    }

    @Override
    public IAEItemStack[] getInputs() {
        return condensedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return condensedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return outputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return outputs;
    }

    @Override
    public boolean isCraftable() {
        return false;
    }

    @Override
    public boolean canSubstitute() {
        return !slotSubs.isEmpty();
    }

    @Override
    public List<IAEItemStack> getSubstituteInputs(int slot) {
        List<IAEItemStack> subs = slotSubs.get(slot);
        return subs == null ? java.util.Collections.<IAEItemStack>emptyList() : subs;
    }

    @Override
    public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
        return false;
    }

    @Override
    public ItemStack getPattern() {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void setPriority(int priority) {
    }
    /** Slot-substitute table for lifecycle-simulation hooks (slot → alternates). */
    public Map<Integer, java.util.List<IAEItemStack>> getSlotSubstitutes() {
        return slotSubs;
    }

}

