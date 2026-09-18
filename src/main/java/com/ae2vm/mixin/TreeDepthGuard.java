package com.ae2vm.mixin;

import appeng.api.storage.data.IAEItemStack;

import java.lang.reflect.Constructor;

/**
 * Reflective factory + sneaky-throw bridge for appeng.crafting's
 * package-private CraftBranchFailure, used by {@link TreeDepthGuardMixin}
 * (which cannot live in appeng.crafting — mixin packages hold mixins only).
 *
 * <p>Sneaky-throw is safe here: the JVM performs no checked-exception
 * validation at runtime, and the target method ({@code
 * CraftingTreeNode.request}) plus every frame above it already declare
 * {@code throws CraftBranchFailure}, so native callers handle it through
 * their normal missing-item paths.
 */
final class TreeDepthGuard {

    private static volatile Constructor<?> failureCtor;

    private TreeDepthGuard() {
    }

    /** Always throws; the return type only appeases the compiler. */
    static RuntimeException branchFailure(IAEItemStack what, long howMany) {
        throw sneaky(make(what, howMany));
    }

    private static Throwable make(IAEItemStack what, long howMany) {
        try {
            Constructor<?> ctor = failureCtor;
            if (ctor == null) {
                Class<?> type = Class.forName("appeng.crafting.CraftBranchFailure");
                ctor = type.getConstructor(IAEItemStack.class, long.class);
                ctor.setAccessible(true);
                failureCtor = ctor;
            }
            return (Throwable) ctor.newInstance(what, howMany);
        } catch (ReflectiveOperationException failure) {
            // The guard exists to convert a fatal SOE into a graceful
            // failure; if the reflection breaks, fail loudly rather than
            // silently returning a useless plan.
            throw new IllegalStateException("CraftBranchFailure unavailable", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
