package com.ae2vm.api;

import com.ae2vm.config.AE2VMConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third-party opt-in registry — 1.12 port of the original's
 * AE2VMCraftingRegistry.
 *
 * Registered markers are substrings matched against the requester's class
 * name. Only relevant for programmatic (machine-sourced) job submissions:
 * player-driven requests from AE2's own terminals are always handled by the
 * VM. Unregistered third-party machine sources fall back to the native
 * crafting tree so foreign planners keep their native behaviour.
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
        // user-configured markers (the config route for stock-keeper style
        // devices whose mods never call the registry API)
        try {
            for (String marker : AE2VMConfig.thirdPartySourceMarkers) {
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

    /** AE2's own sources are always VM-eligible; others require registration. */
    public static boolean isUnregisteredThirdParty(String className) {
        if (className == null) {
            return false;
        }
        if (className.startsWith("appeng.")) {
            return false;
        }
        return !isRegistered(className);
    }

    public static boolean hasRegistrations() {
        return !REGISTERED.isEmpty();
    }
}
