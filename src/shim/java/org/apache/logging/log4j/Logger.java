package org.apache.logging.log4j;

/** Replay shim: silent logger (real log4j Logger is an interface). */
public interface Logger {
    void info(String s);

    void info(String s, Object... a);

    void info(String s, Throwable t);

    void warn(String s);

    void warn(String s, Object... a);

    void warn(String s, Throwable t);

    void error(String s);

    void error(String s, Object... a);

    void error(String s, Throwable t);

    void debug(String s);

    void debug(String s, Object... a);

    void info(String s, Object a1);

    void info(String s, Object a1, Object a2);

    void info(String s, Object a1, Object a2, Object a3);

    void warn(String s, Object a1);

    void warn(String s, Object a1, Object a2);

    void warn(String s, Object a1, Object a2, Object a3);

    void error(String s, Object a1);

    void error(String s, Object a1, Object a2);

    void error(String s, Object a1, Object a2, Object a3);

    void debug(String s, Object a1);

    void debug(String s, Object a1, Object a2);

    void debug(String s, Object a1, Object a2, Object a3);

    void info(String s, Object a1, Object a2, Object a3, Object a4);


    /** No-op implementation. */
    Logger NOP = new Logger() {
        @Override public void info(String s) {
        }

        @Override public void info(String s, Object... a) {
        }

        @Override public void info(String s, Throwable t) {
        }

        @Override public void warn(String s) {
        }

        @Override public void warn(String s, Object... a) {
        }

        @Override public void warn(String s, Throwable t) {
        }

        @Override public void error(String s) {
        }

        @Override public void error(String s, Object... a) {
        }

        @Override public void error(String s, Throwable t) {
        }

        @Override public void debug(String s) {
        }

        @Override public void debug(String s, Object... a) {
        }

        @Override public void info(String s, Object a1) {
        }

        @Override public void info(String s, Object a1, Object a2) {
        }

        @Override public void info(String s, Object a1, Object a2, Object a3) {
        }

        @Override public void warn(String s, Object a1) {
        }

        @Override public void warn(String s, Object a1, Object a2) {
        }

        @Override public void warn(String s, Object a1, Object a2, Object a3) {
        }

        @Override public void error(String s, Object a1) {
        }

        @Override public void error(String s, Object a1, Object a2) {
        }

        @Override public void error(String s, Object a1, Object a2, Object a3) {
        }

        @Override public void debug(String s, Object a1) {
        }

        @Override public void debug(String s, Object a1, Object a2) {
        }

        @Override public void debug(String s, Object a1, Object a2, Object a3) {
        }

        @Override public void info(String s, Object a1, Object a2, Object a3, Object a4) {
        }

    };
}
