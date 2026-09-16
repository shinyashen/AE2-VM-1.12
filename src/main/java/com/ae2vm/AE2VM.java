package com.ae2vm;

import com.ae2vm.common.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * AE2 VM 1.12 — stack-based VM crafting calculator for AE2UEL.
 * Port of Tao's AE2-VM (NeoForge 1.21.1) to Minecraft 1.12.2.
 *
 * Pure server-side: all computation happens on the (integrated or
 * dedicated) server; clients may omit the mod entirely
 * (acceptableRemoteVersions = "*", verified against FML's
 * NetworkModHolder$DefaultNetworkChecker semantics).
 */
@Mod(modid = AE2VM.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
    acceptableRemoteVersions = "*",
    dependencies = "required-after:mixinbooter@[8.0,);after:appliedenergistics2;after:ae2fc;")
public class AE2VM {
    public static final String MOD_ID = "ae2_vm_112";
    public static final String CLIENT_PROXY = "com.ae2vm.client.ClientProxy";
    public static final String COMMON_PROXY = "com.ae2vm.common.CommonProxy";

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @SidedProxy(clientSide = CLIENT_PROXY, serverSide = COMMON_PROXY)
    public static CommonProxy proxy;

    @Mod.Instance(MOD_ID)
    public static AE2VM instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // diagnostics Evidence layer: command entry points,
        // lazy-retention startup sweep, config-driven chat language
        com.ae2vm.trace.TraceChannel.init();
        event.registerServerCommand(new com.ae2vm.trace.TraceCommand());
        com.ae2vm.trace.TraceLang.reload();
        com.ae2vm.trace.TraceStore.enforceRetention();
    }
}
