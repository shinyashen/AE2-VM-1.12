package com.ae2vm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Engine-side logger holder. Offline replay executes the vm/compiler/trace
 * packages without the mod's @Mod main class (whose class graph drags in
 * Forge/MC command types a bare JVM must never need) — these packages log
 * through here instead of {@code AE2VM.LOGGER}. Same logger name, identical
 * output.
 */
public final class Log {

    public static final Logger LOG = LogManager.getLogger(Tags.MOD_NAME);

    private Log() {
    }
}
