package com.ae2vm.api;

import com.ae2vm.config.AE2VMConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third-party source routing — 1.12 port of the original's
 * AE2VMCraftingRegistry.
 *
 * Machine-sourced job submissions (auto-ordering devices) are planned by the
 * VM BY DEFAULT — the native tree cannot carry net-gain rings or fluid
 * patterns, and a device that keeps stock needs honest plans. The
 * {@code nativeTreeSourceMarkers} config lists the rare foreign planners that
 * must keep the native tree (e.g. mods walking the tree structure); an
 * explicit {@link #register} call from a mod always wins back the VM.
 */
public final class AE2VMCraftingRegistry {
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private AE2VMCraftingRegistry() {
    }

    /** Registers a marker (substring matched against requester class names). */
    public static void register(String marker) {
        if (marker != null && !marker.trim().isEmpty()) {
            REGISTERED.add(marker);
        }
    }

    public static boolean isRegistered(String className) {
        if (className == null) {
            return false;
        }
        for (String marker : REGISTERED) {
            if (className.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the requester class is configured to keep the NATIVE tree.
     * An explicit {@link #register} call from the mod always wins back the
     * VM; AE2's own sources are never excluded.
     */
    public static boolean isNativeTreeSource(String className) {
        if (className == null) {
            return false;
        }
        if (className.startsWith("appeng.")) {
            return false;
        }
        if (isRegistered(className)) {
            return false;
        }
        try {
            for (String marker : AE2VMConfig.nativeTreeSourceMarkers) {
                if (marker != null && !marker.trim().isEmpty()
                        && className.contains(marker.trim())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // config not loaded yet (early class init)
        }
        return false;
    }

}
