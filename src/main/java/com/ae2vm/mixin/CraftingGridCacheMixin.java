package com.ae2vm.mixin;

import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.me.cache.CraftingGridCache;
import com.ae2vm.compiler.PatternCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Pre-compiles every registered pattern to bytecode whenever the grid
 * recalculates its crafting methods — the 1.12 analogue of the original's
 * PatternProviderLogicMixin (encode-time compilation). Covers ALL pattern
 * sources (ME interfaces, third-party providers, AE2FC fluid interfaces),
 * because everything funnels through CraftingGridCache#provideCrafting.
 * Compilation is idempotent and cached; this only removes the first-request
 * lazy-compile latency.
 */
@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class CraftingGridCacheMixin {

    @Shadow(remap = false)
    private Map<ICraftingPatternDetails, List<ICraftingMedium>> craftingMethods;

    @Inject(method = "recalculateCraftingPatterns", at = @At("TAIL"), remap = false)
    private void ae2vm$precompilePatterns(CallbackInfo ci) {
        if (!com.ae2vm.config.AE2VMConfig.proxyEnabled) {
            return;
        }
        int compiled = 0;
        for (ICraftingPatternDetails details : this.craftingMethods.keySet()) {
            if (PatternCompiler.getCompiled(details) == null) {
                PatternCompiler.compileIfAbsent(details);
                compiled++;
            }
        }
        if (compiled > 0) {
            com.ae2vm.AE2VM.LOGGER.debug("[AE2-VM] pre-compiled {} new pattern(s), {} cached",
                    compiled, PatternCompiler.getCompiledCount());
        }
    }
}
