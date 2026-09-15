package net.minecraft.init;

/**
 * Replay shim: the virtual registry needs no bootstrapping — this no-op
 * keeps the engine's {@code Bootstrap.register()} guard harmless offline.
 */
public final class Bootstrap {
    private static boolean registered;

    private Bootstrap() {
    }

    public static void register() {
        registered = true;
    }

    public static boolean isRegistered() {
        return registered;
    }
}
