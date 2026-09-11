package com.ae2vm.bench;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.PrintStream;

/** Temporary debug: trace the seeded (2303) gaia execution. */
class GaiaDebug {
    @Test
    void dump() throws Exception {
        net.minecraft.init.Bootstrap.register();
        Bench.reset();
        BenchPatternDetails makeIngot = Bench.pat("I", 1, "S", 4L);
        BenchPatternDetails makeSpirit = Bench.pat("S", 12, "I", 1L);
        Bench.register(makeIngot);
        Bench.register(makeSpirit);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode request = PatternCompiler.compileRequest(makeSpirit, 10000);
        PrintStream log = new PrintStream(new FileOutputStream("/tmp/gaia-trace.txt"), true, "UTF-8");
        BenchSimulationState base = new BenchSimulationState().seed("S", 2303);
        CraftingVM vm = new CraftingVM("bench", Bench.PATTERNS::get);
        VMPlan plan = vm.execute(request, new TraceSimulationState(base, log, "g"));
        log.close();
        StringBuilder sb = new StringBuilder("[gaia] sim=" + plan.isSimulation() + " times:");
        for (var e : plan.getPatternTimes().entrySet()) {
            sb.append(" in-S=").append(e.getValue());
        }
        System.out.println(sb);
        System.out.println("[gaia] done");
    }
}
