package com.ae2vm.common;

import com.ae2vm.AE2VM;
import com.ae2vm.compiler.PatternCompiler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;

public class CommonProxy {
    public void preInit(FMLPreInitializationEvent event) {
        AE2VM.LOGGER.info("[AE2-VM] AE2 VM 1.12 loaded (stack-based VM crafting engine, ported from NeoForge 1.21)");
    }

    public void init() {
    }

    public void postInit() {
        // Pre-compile patterns of all loaded AE2 providers (parity with the
        // original's PatternProviderLogicMixin encode-time compilation).
        if (com.ae2vm.config.AE2VMConfig.proxyEnabled && Loader.isModLoaded("appliedenergistics2")) {
            int compiled = PatternCompiler.getCompiledCount();
            AE2VM.LOGGER.info("[AE2-VM] lazily compiles patterns on first request (pre-compiled at startup: {})", compiled);
        }
    }
}
