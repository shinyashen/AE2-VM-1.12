package com.ae2vm.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge config (the 1.12 stand-in for the original's Cloth Config toggle).
 */
@Config(modid = "ae2_vm_112")
public class AE2VMConfig {
    @Config.Comment("Enable the AE2-VM proxy (replace recursive crafting calculation with the VM engine). Requires a restart.")
    @Config.RequiresMcRestart
    public static boolean proxyEnabled = true;

    /**
     * Feature gate for the incremental ring family (ring folding + the
     * out-of-ring deficit expansion + their net-bundle hand-off). Deliberately
     * NOT a registered config entry: shipped hard-off while the live-server
     * issues (downstream-item stalls) are triaged. Flip to true (by edit, or
     * via a future config entry) to re-enable the family; ring tests set it
     * themselves.
     */
    public static boolean ringSolverEnabled = false;

    // ---- diagnostics traces (design doc §4.2/§5.5) ----

    @Config.Comment({"Per-session event cap for armed trace recording.",
            "Oldest events are dropped first and the trace is marked truncated."})
    public static int traceSessionEventCap = 100000;

    @Config.Comment("Maximum number of trace files kept under logs/aevm/traces (oldest deleted first).")
    public static int traceRetentionCount = 20;

    @Config.Comment("Maximum total size in bytes of trace files under logs/aevm/traces.")
    public static long traceRetentionMaxBytes = 104857600L;

    @Config.Comment({"Chat language for trace command feedback.", "en_us or zh_cn."})
    public static String language = "en_us";

    @Mod.EventBusSubscriber(modid = "ae2_vm_112")
    private static class EventHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if ("ae2_vm_112".equals(event.getModID())) {
                ConfigManager.sync("ae2_vm_112", Config.Type.INSTANCE);
            }
        }
    }
}
