package com.ae2vm.mixin;

import appeng.crafting.CraftingTreeNode;
import appeng.crafting.VMTreeCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/** Optional direct adapter for AE2CT-Legacy's serialized tree model. */
@Pseudo
@Mixin(targets = "github.kasuminova.ae2ctl.common.network.PktCraftingTreeData", remap = false)
public abstract class PktCraftingTreeDataMixin {
    @Inject(method = "<init>(Lappeng/crafting/CraftingTreeNode;)V", at = @At("RETURN"), remap = false)
    private void ae2vm$replaceRoot(final CraftingTreeNode tree, final CallbackInfo callbackInfo) {
        final Object replacement = VMTreeCompatibility.createLiteTree(tree);
        if (replacement == null) {
            return;
        }
        try {
            final Field rootField = this.getClass().getDeclaredField("root");
            rootField.setAccessible(true);
            rootField.set(this, replacement);
        } catch (final Throwable ignored) {
            // Keep AE2CT's native tree if a future version changes its field.
        }
    }
}
