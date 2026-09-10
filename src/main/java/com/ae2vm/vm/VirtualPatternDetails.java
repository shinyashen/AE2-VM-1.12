package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;

/**
 * A synthetic pattern encoding a SPLIT allocation: the demand of one output
 * key covered by several real patterns at once (e.g. 4 crafts of A + 1 craft
 * of B become one condensed line consuming 4*inputs(A) + 1*inputs(B) and
 * producing 4*out(A) + 1*out(B)).
 *
 * <p>The solver ({@code PatternChoiceRepair}) synthesizes these when a mixed
 * assignment beats every single-choice assignment; the resolver then hands the
 * virtual pattern out through the ordinary preference mechanism, so the
 * execution loop, bundle cache, aggregation and stock-aware semantics all see
 * just another pattern — no engine-side split machinery.
 *
 * <p>Processing semantics ({@code isCraftable() == false}), no substitution,
 * no priority: the VM consumes only the condensed input/output lines and the
 * strict-equality probes (catalyst/durability detection) which cannot fire —
 * the virtual inputs are ordinary consumption of constituent patterns.
 */
public final class VirtualPatternDetails implements ICraftingPatternDetails {
    private final IAEItemStack[] condensedInputs;
    private final IAEItemStack[] outputs;

    private VirtualPatternDetails(IAEItemStack[] condensedInputs, IAEItemStack[] outputs) {
        this.condensedInputs = condensedInputs;
        this.outputs = outputs;
    }

    /**
     * Builds the virtual pattern for one split key: {@code condensedInputs}
     * are the union of the constituent patterns' inputs (type-merged),
     * {@code amounts} the per-line per-batch consumption
     * (Σ crafts_i × amount_i), and the single output line produces the key
     * with the combined batch size (Σ crafts_i × opc_i).
     */
    public static VirtualPatternDetails of(IAEItemStack output, long totalOutput,
                                    IAEItemStack[] condensedInputs, long[] amounts) {
        IAEItemStack[] in = new IAEItemStack[condensedInputs.length];
        for (int i = 0; i < condensedInputs.length; i++) {
            IAEItemStack line = condensedInputs[i].copy();
            line.setStackSize(amounts[i]);
            in[i] = line;
        }
        IAEItemStack out = output.copy();
        out.setStackSize(totalOutput);
        return new VirtualPatternDetails(in, new IAEItemStack[]{out});
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
        return false;
    }

    @Override
    public List<IAEItemStack> getSubstituteInputs(int slot) {
        return Collections.emptyList();
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
}
