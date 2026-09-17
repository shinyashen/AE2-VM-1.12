package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;

import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Port of the original JitReuseTest — cross-request JIT bundleCache reuse: the SAME
 * CraftingVM instance is executed repeatedly (as in AE2VMCrafting's per-grid VM
 * cache). The first request captures every sub-pattern (mostly JIT misses);
 * subsequent requests reuse the captured bundles (high hit-rate) and still produce
 * identical, correct plans.
 */
class JitReuseTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    /** A<-B+C ; B<-D+E ; C<-F+G with stock D,E,F,G=4 → craft 4 A. */
    private static final class Fixture {
        final BenchPatternDetails a = pat("A", 1, "B", 1L, "C", 1L);
        final BenchPatternDetails b = pat("B", 1, "D", 1L, "E", 1L);
        final BenchPatternDetails c = pat("C", 1, "F", 1L, "G", 1L);
        /** Mutable stock shared by every sim() — mirrors FakeBenchGrid's live view. */
        final Map<String, Long> stock = new LinkedHashMap<>();

        Fixture() {
            Bench.register(a);
            Bench.register(b);
            Bench.register(c);
            stockAll(4);
        }

        void stockAll(long n) {
            stock.put("D", n);
            stock.put("E", n);
            stock.put("F", n);
            stock.put("G", n);
        }

        BenchSimulationState sim() {
            BenchSimulationState s = new BenchSimulationState();
            for (Map.Entry<String, Long> e : stock.entrySet()) {
                s.seed(e.getKey(), e.getValue());
            }
            return s;
        }

        BenchSimulationState simWith(String id, long amount) {
            BenchSimulationState s = sim();
            return s.seed(id, amount);
        }

        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(a, amount);
        }
    }

    @Test
    void reuseVmAcrossRequests() {
        Fixture fx = new Fixture();
        // Same VM reused across many requests — mirrors AE2VMCrafting's per-grid cache.
        CraftingVM vm = new CraftingVM("jit-reuse-bench", Bench.PATTERNS::get);

        for (int i = 0; i < 5; i++) {
            VMPlan plan = vm.execute(fx.request(4), fx.sim());
        CpuLifecycleAssert.auto(plan);
            assertTrue(plan.getMissingItems().isEmpty(), "no missing on request " + i);
            assertEquals(4L, plan.getUsedItems().get(k("D")));
            assertEquals(4L, plan.getUsedItems().get(k("E")));
            assertEquals(4L, plan.getUsedItems().get(k("F")));
            assertEquals(4L, plan.getUsedItems().get(k("G")));
        }
    }

    /**
     * Correctness under reuse when the network is later short on an ingredient: the
     * reused bundle claims a per-craft need, but the deficit-aware apply must still
     * report the shortfall as missing (never invent stock from a stale bundle).
     */
    @Test
    void reuseVmWithLaterShortfall() {
        Fixture fx = new Fixture();
        CraftingVM vm = new CraftingVM("jit-reuse-bench", Bench.PATTERNS::get);

        // First request: full stock → clean capture of every sub-pattern.
        VMPlan ok = vm.execute(fx.request(4), fx.sim());
        CpuLifecycleAssert.auto(ok);
        assertTrue(ok.getMissingItems().isEmpty(), "first request should be feasible");

        // Second request: stock now only covers 2 A worth (D,E=2 ; F,G=2).
        fx.stockAll(2);
        VMPlan shortPlan = vm.execute(fx.request(4), fx.sim());
        CpuLifecycleAssert.auto(shortPlan);
        // With reused bundles, the deficit-aware apply must report the shortfall:
        // need 4 D, only 2 in stock → used 2, missing 2.
        assertTrue(shortPlan.isSimulation(), "short request should be simulation (missing)");
        assertEquals(2L, shortPlan.getUsedItems().get(k("D")),
                "D used should be the 2 actually extractable");
        assertEquals(2L, shortPlan.getMissingItems().get(k("D")),
                "D missing should be the 2 shortfall");
    }

    /**
     * REGRESSION (v1.9.5): a reused VM must NOT carry realStockCache across requests.
     * realStockOf() lazily snapshots the network inventory once per execute; with the
     * per-grid VM reuse the snapshot from request #1 was reused by request #2, so a
     * stock-aware aggregation read the OLD stock and reported wrong quantities
     * ("数量出错了"). The stock map shared by grid and simulation drops to 2 between
     * the two requests so the second request genuinely sees less inventory.
     */
    @Test
    void reuseVmMustRefreshStockSnapshot() {
        Fixture fx = new Fixture();
        CraftingVM vm = new CraftingVM("jit-reuse-bench", Bench.PATTERNS::get);

        // Request 1: full stock → clean capture; realStockCache snapshot = 4.
        VMPlan ok = vm.execute(fx.request(4), fx.sim());
        CpuLifecycleAssert.auto(ok);
        assertTrue(ok.getMissingItems().isEmpty(), "first request should be feasible");
        assertEquals(4L, ok.getUsedItems().get(k("D")));

        // Simulate the first craft consuming the network: only 2 D left now.
        fx.stockAll(2);

        // Request 2 on the SAME VM: must re-snapshot (realStockOf returns 2, not 4).
        VMPlan plan2 = vm.execute(fx.request(4), fx.sim());
        CpuLifecycleAssert.auto(plan2);
        assertEquals(2L, plan2.getUsedItems().get(k("D")),
                "used[D] must reflect the refreshed stock (2), not the stale 4");
        assertTrue(plan2.isSimulation(), "req2 must be a simulation (missing)");
        assertEquals(2L, plan2.getMissingItems().get(k("D")),
                "missing[D] must be the 2 shortfall after stock dropped to 2");
    }

    /**
     * REGRESSION (v1.9.5 realStockCache): a reused VM must RE-READ real network stock
     * of a CRAFTABLE sub-item on every request. This is the exact production symptom
     * ("首次下单正常，后续数量一致错误"): request #1 captures bundleCache AND lazily
     * snapshots realStockCache; if request #2 reuses that stale snapshot,
     * realStockOf(B) still returns request #1's stock and the stock-aware aggregation
     * under-crafts B (takes the stale amount from stock, never crafts the deficit).
     *
     * <p>Only a sub-item that HAS a pattern exercises realStockOf() — so this fixture
     * seeds B in stock AND gives B a recipe: B is the stock-aware decision point.
     */
    @Test
    void reuseVmMustReReadStockForCraftableSubItem() {
        // A <- B + C ; B <- D + E ; C <- F + G
        // Network stock: B=100 (craftable but plentiful), D/E/F/G=8, C=0 (must craft).
        Fixture fx = new Fixture();
        fx.stockAll(8);
        CraftingVM vm = new CraftingVM("jit-reuse-bench", Bench.PATTERNS::get);

        // Request #1: 4 A. B is fully in stock → take B from stock (usedB=4), craft 0 B.
        VMPlan plan1 = vm.execute(fx.request(4), fx.simWith("B", 100));
        CpuLifecycleAssert.auto(plan1);
        assertTrue(plan1.getMissingItems().isEmpty(), "req1 feasible");
        assertEquals(4L, plan1.getUsedItems().get(k("B")), "req1: B taken from stock");
        assertEquals(0L, plan1.getPatternTimes().getOrDefault(fx.b, 0L),
                "req1: no B crafted (stock covers it)");

        // Request #2 on the SAME VM, network B now down to 2: realStockOf(B) must
        // return 2, not the stale 100. Correct: take 2 from stock + craft 2 B.
        VMPlan plan2 = vm.execute(fx.request(4), fx.simWith("B", 2));
        CpuLifecycleAssert.auto(plan2);
        assertEquals(2L, plan2.getUsedItems().get(k("B")),
                "req2: used[B] must be the refreshed stock (2), not the stale 100");
        assertEquals(2L, plan2.getPatternTimes().getOrDefault(fx.b, 0L),
                "req2: must craft 2 B to cover the 2-craft shortfall");
        assertTrue(plan2.getMissingItems().isEmpty(), "req2 feasible");
    }
}
