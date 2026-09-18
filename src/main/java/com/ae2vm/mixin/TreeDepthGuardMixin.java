package com.ae2vm.mixin;

import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.AE2VMTreeDepth;
import appeng.crafting.TreeDepthGuard;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.MECraftingInventory;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cycle guard over AE2UEL's native tree recursion — see {@link AE2VMTreeDepth}
 * for why this exists. When the depth limit trips, the deep branch must fail
 * as an HONEST missing item: CraftBranchFailure is the exception native
 * callers already handle (they convert it into the confirm screen's missing
 * list), so the plan degrades gracefully instead of the thread dying.
 *
 * <p>CraftBranchFailure is package-private in appeng.crafting, so the
 * exception is built reflectively and sneaky-thrown; the bridge class lives
 * in appeng.crafting itself (com.ae2vm.mixin is a mixin package and refuses
 * to load non-mixin classes — MixinBooter 11.x hard-crashes the client on
 * it, regression 2026-09-18). the runtime performs no checked-exception
 * validation, and every frame above the target method already declares it.
 *
 * <p>Registered in {@code mixins.ae2_vm_112.json} (AE2 target → early loader
 * list in AE2VMEarlyMixinLoader — config files are NOT auto-discovered on
 * MixinBooter 10.x; an unregistered config file is dead weight, regression
 * 2026-09-18).
 */
@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class TreeDepthGuardMixin {

    @Shadow(remap = false)
    @Final
    private IAEItemStack what;

    @Inject(method = "request", at = @At("HEAD"), remap = false)
    private void ae2vm$depthEnter(final MECraftingInventory inv, long l,
                                  final IActionSource src, final CallbackInfo ci) {
        if (AE2VMTreeDepth.enter() > AE2VMTreeDepth.LIMIT) {
            AE2VMTreeDepth.reset();
            throw TreeDepthGuard.branchFailure(this.what, l);
        }
    }

    @Inject(method = "request", at = @At("RETURN"), remap = false)
    private void ae2vm$depthExit(final CallbackInfo ci) {
        AE2VMTreeDepth.exit();
    }
}
