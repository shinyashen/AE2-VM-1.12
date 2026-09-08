package com.ae2vm.mixin;

import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.me.cache.CraftingGridCache;
import com.ae2vm.AE2VM;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.config.AE2VMConfig;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Pre-compiles every registered pattern to bytecode whenever the grid
 * recalculates its crafting methods — the 1.12 analogue of the original's
 * PatternProviderLogicMixin (encode-time compilation). Covers ALL pattern
 * sources (ME interfaces, third-party providers, AE2FC fluid interfaces),
 * because everything funnels through CraftingGridCache's pattern registry.
 * Compilation is idempotent and cached; this only removes the first-request
 * lazy-compile latency (AE2VMCrafting#calculate compiles on demand either
 * way, so neither hook is ever a functional requirement).
 *
 * <p>AE2UEL renamed the registry rebuild from {@code updatePatterns} to
 * {@code recalculateCraftingPatterns} in 2022-02; both hooks are declared so
 * every published AE2UEL build binds exactly one of them. Both are soft
 * (require/expect = 0): each is unbound by name on the other side of the
 * rename, and a future rename must not hard-crash the game.
 */
@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class CraftingGridCacheMixin {

    // Declared with AE2UEL's exact field type (fastutil). A shadow widened to
    // java.util.Map would emit GETFIELD with a descriptor that never matches
    // the target field.
    @Shadow(remap = false)
    private Object2ObjectMap<ICraftingPatternDetails, List<ICraftingMedium>> craftingMethods;

    @Inject(method = "recalculateCraftingPatterns", at = @At("TAIL"), remap = false,
            expect = 0, require = 0)
    private void ae2vm$precompilePatterns(CallbackInfo ci) {
        ae2vm$precompileAll();
    }

    @Inject(method = "updatePatterns", at = @At("TAIL"), remap = false,
            expect = 0, require = 0)
    private void ae2vm$precompilePatternsLegacy(CallbackInfo ci) {
        ae2vm$precompileAll();
    }

    private void ae2vm$precompileAll() {
        if (!AE2VMConfig.proxyEnabled) {
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
            AE2VM.LOGGER.debug("[AE2-VM] pre-compiled {} new pattern(s), {} cached",
                    compiled, PatternCompiler.getCompiledCount());
        }
    }
}
