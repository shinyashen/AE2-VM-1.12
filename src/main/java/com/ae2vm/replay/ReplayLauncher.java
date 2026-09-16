package com.ae2vm.replay;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * System-classloader stub for offline replay: builds an
 * isolated classloader over {@code --deps} jars PLUS this jar itself, then
 * reflectively enters {@link ReplayMain} inside it. Nothing here loads
 * game classes; Forge never touches this class.
 *
 * <pre>
 * java -cp ae2_vm_112-x.y.z.jar com.ae2vm.replay.ReplayLauncher \
 *     --deps gson.jar --deps deobf-mc-1.12.2.jar trace-xxx.aevmtrace.json.gz
 * </pre>
 *
 * {@code --deps} may repeat; commas also separate jars. The deobf MC jar
 * is generated locally with the same gradle toolchain (never distributed —
 * Mojang EULA); SRG-reobf release jars do NOT bind and cannot be used.
 */
public final class ReplayLauncher {

    private ReplayLauncher() {
    }

    public static void main(String[] args) {
        List<URL> urls = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                if ("--deps".equals(args[i]) && i + 1 < args.length) {
                    for (String part : args[++i].split(",")) {
                        if (!part.isEmpty()) {
                            urls.add(new File(part).toURI().toURL());
                        }
                    }
                } else {
                    rest.add(args[i]);
                }
            }
            urls.add(ReplayLauncher.class.getProtectionDomain().getCodeSource().getLocation());
            URLClassLoader child = new URLClassLoader(urls.toArray(new URL[0]), null);
            Class<?> main = Class.forName("com.ae2vm.replay.ReplayMain", true, child);
            Method m = main.getMethod("main", String[].class);
            m.invoke(null, (Object) rest.toArray(new String[0]));
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }
}
