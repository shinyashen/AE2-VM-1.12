package com.ae2vm.mixin;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Arrays;
import java.util.List;

/**
 * Late mixin hook for configs targeting OTHER MODS' classes. MixinBooter
 * instantiates this once every mod jar is on the classpath (FML discovery
 * phase), so the targets are resolvable when the configs are queued — the
 * early phase must never carry these: a mod target that is not yet on the
 * classpath is recorded as a "virtual target" and its real class later fails
 * to load (AE2CT-Legacy crashed on exactly this). Structure mirrors
 * AE2CTLLateMixinLoader (plain class, no-arg constructor).
 */
public class AE2VMLateMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList("mixins.ae2_vm_112.ae2ct.json");
    }
}
