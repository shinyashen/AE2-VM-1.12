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
