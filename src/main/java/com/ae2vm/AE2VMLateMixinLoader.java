package com.ae2vm;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Arrays;
import java.util.List;

/**
 * Late mixin hook for configs targeting OTHER MODS' classes. MixinBooter
 * instantiates this once every mod jar is on the classpath (FML discovery
 * phase / CleanMix's loadMixinBooterLateMixins), so the targets are
 * resolvable when the configs are queued — the early phase must never carry
 * these: a mod target that is not yet on the classpath is recorded as a
 * "virtual target" and its real class later fails to load (AE2CT-Legacy
 * crashed on exactly this).
 *
 * <p>This class MUST NOT live under {@code com.ae2vm.mixin}: that package is
 * owned by {@code mixins.ae2_vm_112.json} as a mixin package, and the mixin
 * transformer refuses to load non-mixin classes from it
 * ("cannot be referenced directly" — crashed the client under MixinBooter
 * 11.x where the check is enforced on every load).
 */
public class AE2VMLateMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList("mixins.ae2_vm_112.ae2ct.json");
    }
}
