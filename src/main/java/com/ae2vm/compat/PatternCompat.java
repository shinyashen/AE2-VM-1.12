package com.ae2vm.compat;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.lang.reflect.Method;

/**
 * Bridges small API differences between supported AE2UEL releases.
 *
 * <p>AE2UEL v0.56.7 added the default method
 * {@code ICraftingPatternDetails#getPrimaryOutput()} to its API; v0.56.4 and
 * earlier do not declare it, so code compiled against the newer API fails at
 * runtime with a {@link NoSuchMethodError}, and source that calls the method
 * directly cannot even compile against the old interface. The v0.56.7 default
 * implementation is exactly {@code getOutputs()[0]} and AE2UEL's own pattern
 * implementations do not override it, so this helper prefers the interface
 * method when the running AE2UEL declares it (preserving any third-party
 * overrides, e.g. AE2FC) and otherwise falls back to {@code getOutputs()[0]}.
 */
public final class PatternCompat {
    private static final Method GET_PRIMARY_OUTPUT = findGetPrimaryOutput();

    private PatternCompat() {
    }

    private static Method findGetPrimaryOutput() {
        try {
            return ICraftingPatternDetails.class.getMethod("getPrimaryOutput");
        } catch (final NoSuchMethodException ignored) {
            // AE2UEL 0.56.4 and earlier do not declare the method.
            return null;
        }
    }

    public static IAEItemStack getPrimaryOutput(final ICraftingPatternDetails pattern) {
        final Method accessor = GET_PRIMARY_OUTPUT;
        if (accessor != null) {
            try {
                return (IAEItemStack) accessor.invoke(pattern);
            } catch (final Exception ignored) {
                // Fall through to the default-method body below.
            }
        }
        final IAEItemStack[] outputs = pattern.getOutputs();
        return outputs == null || outputs.length == 0 ? null : outputs[0];
    }
}
