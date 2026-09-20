package com.ae2vm.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.AE2VMTreeDepth;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.VMRootNode;
import com.ae2vm.vm.NetworkCraftingSandbox;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the root calculation node with the VM-backed node; AE2 owns the
 * rest of the job lifecycle (simulation pass, plan building, CPU execution).
 * Same proven shell as AE2-Quick-Calculation's CraftingJobMixin.
 */
@Mixin(value = CraftingJob.class, remap = false)
public abstract class CraftingJobMixin {

    @Shadow
    private CraftingTreeNode tree;

    @Inject(method = "run", at = @At("HEAD"), remap = false)
    private void ae2vm$resetTreeDepth(CallbackInfo callbackInfo) {
        // One clean slate per job: the native-cycle guard (AE2VMTreeDepth)
        // leaks depth on exception exits, so every job starts from zero.
        AE2VMTreeDepth.reset();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ae2vm$replaceRoot(World world,
                                   IGrid grid,
                                   IActionSource source,
                                   IAEItemStack output,
                                   ICraftingCallback callback,
                                   CallbackInfo callbackInfo) {
        if (grid == null || output == null) {
            return;
        }
        // MAIN THREAD: capture the network stock snapshot for this job NOW —
        // the same thread grid ticks run on, so the read cannot race a
        // concurrent storage mutation (the pool-thread read could, and the
        // interrupted enumeration once shipped a 61-type poisoned stock view
        // that stalled every job planned on it).
        NetworkCraftingSandbox.captureJobStock((CraftingJob) (Object) this, grid);
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (craftingGrid != null) {
            this.tree = new VMRootNode(
                    craftingGrid,
                    (CraftingJob) (Object) this,
                    output,
                    world,
                    source,
                    grid);
        }
    }
}
