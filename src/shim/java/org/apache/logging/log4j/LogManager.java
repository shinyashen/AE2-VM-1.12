package org.apache.logging.log4j;

/** Replay shim: silent loggers — replay output stays on stdout. */
public final class LogManager {
    private LogManager() {
    }

    public static Logger getLogger(String name) {
        return Logger.NOP;
    }

    public static Logger getLogger(Class<?> c) {
        return Logger.NOP;
    }
}
