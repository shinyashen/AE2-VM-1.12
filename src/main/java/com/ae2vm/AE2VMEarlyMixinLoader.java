package com.ae2vm;

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
 *
 * <p>Only vanilla/forge/AE2 targets belong here; configs targeting other
 * mods' classes go to {@link AE2VMLateMixinLoader}. Both loaders MUST stay
 * out of {@code com.ae2vm.mixin}: that package is owned by
 * {@code mixins.ae2_vm_112.json} as a mixin package, and the mixin
 * transformer refuses to load non-mixin classes from it
 * ("cannot be referenced directly" — crashed the client under MixinBooter
 * 11.x where the check is enforced on every load).
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class AE2VMEarlyMixinLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList("mixins.ae2_vm_112.json");
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
