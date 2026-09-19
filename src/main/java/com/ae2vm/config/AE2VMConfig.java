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
     * Feature gate for the ring family (ring folding + the out-of-ring
     * deficit expansion + faithful CPU startup billing). EXPERIMENTAL: the
     * folded plans are now billed so a real CPU can execute them, but the
     * family ships off by default until live validation (gaia / coupled /
     * tight-stock scenarios) passes.
     */
    @Config.Comment({"EXPERIMENTAL: net-amplifying recipe ring folding.",
            "The ring solver turns mutual recipe loops (e.g. 4 spirits -> 1 ingot,",
            "1 ingot -> 12 spirits) into a single CPU-executable plan and bills",
            "the startup inventory the CPU must withdraw. Off by default pending",
            "live validation."})
    @Config.RequiresMcRestart
    public static boolean ringSolverEnabled = false;

    // ---- diagnostics traces ----

    @Config.Comment({"Per-session event cap for armed trace recording.",
            "Oldest events are dropped first and the trace is marked truncated."})
    public static int traceSessionEventCap = 100000;

    @Config.Comment("Maximum number of trace files kept under <server dir>/aevm/traces (oldest deleted first).")
    public static int traceRetentionCount = 20;

    @Config.Comment("Maximum total size in bytes of trace files under <server dir>/aevm/traces.")
    public static int traceRetentionMaxBytes = 104857600;

    @Config.Comment({"Chat language for trace command feedback.", "en_us or zh_cn."})
    public static String language = "en_us";

    @Config.Comment({"Master switch for mclo.gs uploads (public by URL).",
            "Players may upload only their OWN traces; ops may upload any."})
    public static boolean traceUploadEnabled = true;

    @Config.Comment({"Stall watchdog: when a crafting CPU's state is unchanged",
            "for this many ticks with a non-empty waitingFor, its NBT is dumped",
            "to <server dir>/aevm/ for diagnostics. 0 = off (default)."})
    public static int stallWatchdogTicks = 0;

    @Config.Comment({"Machine sources kept on the NATIVE crafting tree. By default",
            "every auto-ordering device (stock keepers, interfaces, emitters) is",
            "planned by the VM — the native tree cannot carry net-gain rings or",
            "fluid patterns. Only add a device mod's class/package substring here",
            "if that mod misbehaves on VM plans (e.g. it walks the native tree",
            "structure and needs it intact). One INFO line per distinct excluded",
            "source confirms the exclusion is matching."})
    public static String[] nativeTreeSourceMarkers = new String[0];

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
