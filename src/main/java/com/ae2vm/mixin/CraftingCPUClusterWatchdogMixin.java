package com.ae2vm.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2vm.trace.StallWatchdog;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Stall watchdog hook (default off): fingerprints the CPU
 * on every updateCraftingLogic return and asks {@link StallWatchdog} for a
 * one-shot NBT dump when the state is frozen with a non-empty waitingFor.
 * All AE2 members here are AE2-own names (no SRG), hence remap = false.
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterWatchdogMixin {

    @Shadow(remap = false)
    @Final
    private Map<ICraftingPatternDetails, ?> tasks;

    @Shadow(remap = false)
    private IItemList<IAEItemStack> waitingFor;

    @Shadow(remap = false)
    private boolean isComplete;

    @Shadow(remap = false)
    public abstract void writeToNBT(NBTTagCompound data);

    @Inject(method = "updateCraftingLogic", at = @At("RETURN"), remap = false, require = 1)
    private void aevm$stallWatchdog(IGrid grid, IEnergyGrid eg, CraftingGridCache cc,
                                    CallbackInfo ci) {
        try {
            if (StallWatchdog.shouldDump(this, isComplete,
                    tasks == null ? 0 : tasks.size(), waitingFor)) {
                NBTTagCompound data = new NBTTagCompound();
                this.writeToNBT(data);
                StallWatchdog.dump(this, data);
            }
        } catch (Throwable t) {
            // the watchdog must never break the CPU tick loop
        }
    }
}
