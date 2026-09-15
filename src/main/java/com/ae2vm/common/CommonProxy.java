package com.ae2vm.common;

import com.ae2vm.AE2VM;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.config.AE2VMConfig;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {
    public void preInit(FMLPreInitializationEvent event) {
        // both sides register the trace channel so the S2C discriminator
        // tables match (client handlers are side-guarded, design doc §5.2)
        com.ae2vm.trace.TraceChannel.init();
        AE2VM.LOGGER.info("[AE2-VM] AE2 VM 1.12 loaded (stack-based VM crafting engine, ported from NeoForge 1.21)");
    }

    public void init() {
    }

    public void postInit() {
        // Pre-compile patterns of all loaded AE2 providers (parity with the
        // original's PatternProviderLogicMixin encode-time compilation).
        if (AE2VMConfig.proxyEnabled && Loader.isModLoaded("appliedenergistics2")) {
            int compiled = PatternCompiler.getCompiledCount();
            AE2VM.LOGGER.info("[AE2-VM] lazily compiles patterns on first request (pre-compiled at startup: {})", compiled);
        }
    }
}
