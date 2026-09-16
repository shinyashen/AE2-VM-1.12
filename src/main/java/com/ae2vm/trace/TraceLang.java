package com.ae2vm.trace;

import com.ae2vm.config.AE2VMConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Server-side chat localization: ONE configured
 * language for the whole server ({@code language} config, default
 * en_us), rendered as literal strings — translation keys would show up
 * raw on vanilla clients without the mod, so the lang files are read
 * server-side from our own jar ({@code assets/ae2_vm_112/lang/}).
 * Logs stay English; token ids carry no language at all.
 */
public final class TraceLang {

    private static final String PREFIX = "/assets/ae2_vm_112/lang/";
    private static final Properties FALLBACK = load("en_us");
    private static volatile Properties current = load(currentCode());

    private TraceLang() {
    }

    private static String currentCode() {
        try {
            String cfg = AE2VMConfig.language;
            return cfg == null || cfg.isEmpty() ? "en_us" : cfg.toLowerCase();
        } catch (Throwable t) {
            return "en_us"; // config not loaded yet (early class init in tests)
        }
    }

    /** Reloads after a config change; falls back to en_us for unknown codes. */
    public static synchronized void reload() {
        current = load(currentCode());
    }

    private static Properties load(String code) {
        Properties p = new Properties(code.equals("en_us") ? new Properties() : FALLBACK);
        try (InputStream in = TraceLang.class.getResourceAsStream(PREFIX + code + ".lang")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (IOException ignored) {
            // missing file = fall through to the fallback chain
        }
        return p;
    }

    /** {0},{1},... positional substitution; unknown keys render as the key. */
    public static String format(String key, Object... args) {
        String tpl = current.getProperty(key, FALLBACK.getProperty(key, key));
        for (int i = 0; i < args.length; i++) {
            tpl = tpl.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return tpl;
    }
}
