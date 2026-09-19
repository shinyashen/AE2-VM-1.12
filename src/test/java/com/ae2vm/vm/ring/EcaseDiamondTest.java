package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Live-server regression (2026-09-19, trace 20260919-123617-5663, the
 * 366-pattern draconic order): the E-case expansion cut EVERY re-arrival of
 * a shared ingredient with a flat seen-set — a diamond topology (two
 * ring-external consumers of one ingredient) sized the producer to the FIRST
 * consumer only and left the others uncovered. The live fingerprint: the
 * draconium producer's output equaled the ring chain's demand to the unit
 * while the other consumers' 63,804 shipped as false missing.
 *
 * <p>The mirror here: the gaia ring's recycler consumes BOTH g4 and fuel;
 * g4's producer eats 1 m0 per craft, fuel's producer eats 2 m0 per craft —
 * m0 is the shared diamond key. The producer of m0 must be sized for BOTH
 * consumers, disclosing only the true leaf shortfall (R).
 */
class EcaseDiamondTest {
    @Test
    void diamondRearrivalsSizeTheSharedProducer() {
        Bootstrap.register();
        Bench.reset();
        BenchPatternDetails p0 = Bench.patEx(new String[]{"g5", "dice"}, new long[]{12, 1}, "g14", 1L);
        // the recycler consumes g4 AND fuel — two ring-external ingredients
        BenchPatternDetails p1 = Bench.patEx(new String[]{"g14"}, new long[]{1},
                "g4", 1L, "fuel", 1L, "g5", 4L);
        BenchPatternDetails p2 = Bench.pat("g4", 4, "m0", 1L, "X", 500L);
        BenchPatternDetails p3 = Bench.pat("fuel", 3, "m0", 2L, "coal", 1L);
        BenchPatternDetails m = Bench.pat("m0", 2, "R", 1L);
        Bench.register(p0);
        Bench.register(p1);
        Bench.register(p2);
        Bench.register(p3);
        Bench.register(m);
        for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

        BenchSimulationState sim = new BenchSimulationState()
                .seed("g5", 5000)
                .seed("coal", 100)
                .seed("X", 223291314L);
        VMPlan plan = Bench.run(p0, 1000, sim);

        // the LEDGER sizes every producer — no E-case injection needed: the
        // least fixpoint rides 84 rounds (12x84 >= 1000) and sizes the
        // downstream chain g4 84 -> p2 21, fuel 84 -> p3 28, m0 21 + 56 = 77
        assertEquals(Long.valueOf(84L), plan.getPatternTimes().get(p0), "P0 loops");
        assertEquals(Long.valueOf(84L), plan.getPatternTimes().get(p1), "P1 loops");
        assertEquals(Long.valueOf(21L), plan.getPatternTimes().get(p2),
                "g4's producer: ceil(84/4)");
        assertEquals(Long.valueOf(28L), plan.getPatternTimes().get(p3),
                "fuel's producer: ceil(84/3)");
        assertEquals(Long.valueOf(39L), plan.getPatternTimes().get(m),
                "the shared m0 producer covers 21 + 56 = 77 units with 39 crafts");
        // only the true leaf is missing: R feeds all the m0 crafts
        Map<String, Long> missing = new LinkedHashMap<>();
        for (var e : plan.getMissingItems().entrySet()) {
            missing.put(((BenchAEItemStack) e.getKey()).id, e.getValue());
        }
        long rLeaf = 39L;
        assertTrue(missing.containsKey("R") && missing.get("R") == rLeaf,
                "the honest leaf disclosure is R x" + rLeaf + ", got " + missing);
        assertTrue(!missing.containsKey("m0"),
                "the shared key must never ship as missing: " + missing);
        // the plan honestly discloses the R shortfall; the faithful CPU stalls
        // on it (M has no R to fire, everything downstream starves) — S2
        CpuLifecycleAssert.stalls(plan, "S2");
    }


}
