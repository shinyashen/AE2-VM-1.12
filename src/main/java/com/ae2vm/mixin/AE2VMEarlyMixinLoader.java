package com.ae2vm.mixin;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Early mixin hook. MixinBooter has NO filename-based config discovery on the
 * 10.x line (the {@code MixinConfigs} jar-manifest attribute is a MixinBooter
 * 11 feature), so the configs must be listed here explicitly — an empty list
 * silently disables every mixin in the jar (regression found on a live
 * server: the mod loaded, but CraftingJobMixin never applied, so the VM never
 * engaged and rings fell back to plain out-of-ring ingredients).
 */
public class AE2VMEarlyMixinLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList("mixins.ae2_vm_112.json", "mixins.ae2_vm_112.ae2ct.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(final Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
