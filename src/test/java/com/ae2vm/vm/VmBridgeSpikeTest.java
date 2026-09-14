package com.ae2vm.vm;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Port of the original VmBridgeSpikeTest — verifies the VM runs offline against a
 * {@link BenchSimulationState} (no IGrid / Minecraft world), mirroring the
 * production path with fake pattern details + in-memory stock.
 */
class VmBridgeSpikeTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    @Test
    void spikeDispersedDag() {
        // A <- B + C; B <- D + E; C <- F + G; stock D,E,F,G = 4 each; craft 4 A.
        BenchPatternDetails b = pat("B", 1, "D", 1L, "E", 1L);
        Bench.register(b);
        BenchPatternDetails c = pat("C", 1, "F", 1L, "G", 1L);
        Bench.register(c);
        BenchPatternDetails a = pat("A", 1, "B", 1L, "C", 1L);
        Bench.register(a);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("D", 4).seed("E", 4).seed("F", 4).seed("G", 4);

        for (appeng.api.networking.crafting.ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(a, 4);
        CraftingVM vm = new CraftingVM("spike", Bench.PATTERNS::get);
        VMPlan plan = vm.execute(req, sim);

        assertFalse(plan.isSimulation(), "dispersed DAG must be feasible");
        assertEquals(4L, plan.getUsedItems().get(k("D")));
        assertEquals(4L, plan.getUsedItems().get(k("E")));
        assertEquals(4L, plan.getUsedItems().get(k("F")));
        assertEquals(4L, plan.getUsedItems().get(k("G")));
    }
}
