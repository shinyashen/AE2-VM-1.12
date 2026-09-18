package appeng.crafting;

import appeng.api.networking.security.IActionSource;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.storage.data.IAEItemStack;

/**
 * Cycle guard over AE2UEL's native tree recursion — see
 * {@link AE2VMTreeDepth} for why this exists. Lives in appeng.crafting so
 * the injected handler can throw the package-private CraftBranchFailure,
 * which native callers already handle: the deep branch is reported as a
 * missing item and the confirm screen renders normally.
 *
 * VMRootNode overrides request(), so the VM's own root path is never
 * guarded (nor counted) — only the native expansion it can fall back to.
 */
@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class TreeDepthGuardMixin {

    @Shadow(remap = false)
    @Final
    private IAEItemStack what;

    @Inject(method = "request", at = @At("HEAD"), remap = false)
    private void ae2vm$depthEnter(final MECraftingInventory inv, long l, final IActionSource src,
                                  final CallbackInfo ci) throws CraftBranchFailure {
        if (AE2VMTreeDepth.enter() > AE2VMTreeDepth.LIMIT) {
            AE2VMTreeDepth.reset();
            throw new CraftBranchFailure(this.what, l);
        }
    }

    @Inject(method = "request", at = @At("RETURN"), remap = false)
    private void ae2vm$depthExit(final CallbackInfo ci) {
        AE2VMTreeDepth.exit();
    }
}
