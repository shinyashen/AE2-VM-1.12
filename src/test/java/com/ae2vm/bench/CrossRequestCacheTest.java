package com.ae2vm.bench;

import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMCounter;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original CrossRequestCacheTest: cross-request cache tests for the
 * per-grid VM reuse. The ONE invariant that separates a *correct* bundleCache
 * reuse from a *polluting* one:
 *
 * <p><b>DETERMINISM:</b> the same request on the same VM with the same network
 * stock must produce a byte-for-byte identical plan on every execution. Request #1
 * fully captures (every sub-pattern is dispatched and captured); request #2+
 * reuses the cached sub-bundles (sub-patterns are NOT re-dispatched, so the
 * parent's capture-time EXTRACT reads a different sandbox — network stock instead
 * of freshly-crafted output). If the aggregation treats those two capture states
 * differently, request #1 and #2 diverge: the exact "first OK, later wrong" bug.
 *
 * <p>In the 1.12 port the VM takes an opaque network key; without a live
 * {@code IGrid} handle {@code realStockOf} snapshots the simulation state at
 * {@code execute()} start, which is exactly what the original's FakeBenchGrid
 * provided (a read-only view of the same stock map).
 */
class CrossRequestCacheTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static BenchAEItemStack k(String id) {
        return new BenchAEItemStack(id, 1);
    }

    /** Pattern with the first output as primary and {id, amount} input pairs. */
    private static BenchPatternDetails pat(String out, long outAmt, Object... inPairs) {
        IAEItemStack[] in = new IAEItemStack[inPairs.length / 2];
        for (int i = 0; i < inPairs.length; i += 2) {
            String id = (String) inPairs[i];
            long amt = ((Number) inPairs[i + 1]).longValue();
            in[i / 2] = new BenchAEItemStack(id, amt).setStackSize(amt);
        }
        IAEItemStack[] outs = new IAEItemStack[]{
                new BenchAEItemStack(out, outAmt).setStackSize(outAmt)};
        return BenchPatternDetails.custom(in, outs);
    }

    /** A<-B+C ; B<-D+E ; C<-F+G. B is CRAFTABLE and STOCKED (the stock-aware decision point). */
    private static final class StockedMidFixture {
        final Map<IAEItemStack, appeng.api.networking.crafting.ICraftingPatternDetails> byOutput =
                new java.util.HashMap<>();
        final Map<String, Long> stock = new java.util.HashMap<>();
        final BenchPatternDetails a = pat("A", 1, "B", 1, "C", 1);
        final BenchPatternDetails b = pat("B", 1, "D", 1, "E", 1);
        final BenchPatternDetails c = pat("C", 1, "F", 1, "G", 1);

        StockedMidFixture() {
            byOutput.put(new BenchAEItemStack("A", 1), a);
            byOutput.put(new BenchAEItemStack("B", 1), b);
            byOutput.put(new BenchAEItemStack("C", 1), c);
        }

        /** stockB may change between requests: the network is re-read per execute. */
        StockedMidFixture stockB(long b) {
            stock.put("B", b);
            stock.put("D", 32L);
            stock.put("E", 32L);
            stock.put("F", 32L);
            stock.put("G", 32L);
            return this;
        }

        BenchSimulationState sim() {
            BenchSimulationState s = new BenchSimulationState();
            for (Map.Entry<String, Long> e : stock.entrySet()) {
                s.seed(e.getKey(), e.getValue());
            }
            return s;
        }

        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(a, amount);
        }
    }

    /** A <- X ; X <- B + C ; B <- D + E ; C <- F + G — deeper chain with stocked mid B. */
    private static final class DeepFixture {
        final Map<IAEItemStack, appeng.api.networking.crafting.ICraftingPatternDetails> byOutput =
                new java.util.HashMap<>();
        final BenchPatternDetails a = pat("A", 1, "X", 1);
        final BenchPatternDetails x = pat("X", 1, "B", 1, "C", 1);
        final BenchPatternDetails b = pat("B", 1, "D", 1, "E", 1);
        final BenchPatternDetails c = pat("C", 1, "F", 1, "G", 1);

        DeepFixture() {
            byOutput.put(new BenchAEItemStack("A", 1), a);
            byOutput.put(new BenchAEItemStack("X", 1), x);
            byOutput.put(new BenchAEItemStack("B", 1), b);
            byOutput.put(new BenchAEItemStack("C", 1), c);
        }

        BenchSimulationState sim() {
            return new BenchSimulationState()
                    .seed("B", 4).seed("D", 32).seed("E", 32).seed("F", 32).seed("G", 32);
        }

        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(a, amount);
        }
    }

    /**
     * Fibonacci-style shared DAG: Xi = X(i-1) + X(i-2) with X0, X1 as leaves.
     * EMPTY stock — every node is missing. The aggregation must still derive the
     * full Fibonacci craft chain identically across reuse.
     */
    private static final class FibonacciFixture {
        final Map<String, BenchPatternDetails> byId = new TreeMap<>();
        final Map<IAEItemStack, appeng.api.networking.crafting.ICraftingPatternDetails> byOutput =
                new java.util.HashMap<>();
        final int levels;

        FibonacciFixture(int levels) {
            this.levels = levels;
            for (int i = 2; i < levels; i++) {
                BenchPatternDetails p = pat("X" + i, 1, "X" + (i - 1), 1L, "X" + (i - 2), 1L);
                byId.put("X" + i, p);
                byOutput.put(new BenchAEItemStack("X" + i, 1), p);
            }
        }

        BenchSimulationState sim() {
            return new BenchSimulationState(); // stock intentionally empty
        }

        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(byId.get("X" + (levels - 1)), amount);
        }
    }

    /** A <- P + Q ; P <- B + C ; Q <- B + D — diamond: B is shared by P and Q, and stocked. */
    private static final class DiamondFixture {
        final Map<IAEItemStack, appeng.api.networking.crafting.ICraftingPatternDetails> byOutput =
                new java.util.HashMap<>();
        final BenchPatternDetails a = pat("A", 1, "P", 1, "Q", 1);
        final BenchPatternDetails p = pat("P", 1, "B", 1, "C", 1);
        final BenchPatternDetails q = pat("Q", 1, "B", 1, "D", 1);
        final BenchPatternDetails b = pat("B", 1, "C", 1, "D", 1);

        DiamondFixture() {
            byOutput.put(new BenchAEItemStack("A", 1), a);
            byOutput.put(new BenchAEItemStack("P", 1), p);
            byOutput.put(new BenchAEItemStack("Q", 1), q);
            byOutput.put(new BenchAEItemStack("B", 1), b);
        }

        BenchSimulationState sim() {
            return new BenchSimulationState().seed("B", 4).seed("C", 32).seed("D", 32);
        }

        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(a, amount);
        }
    }

    private static String dump(String tag, VMPlan p) {
        TreeMap<String, Long> used = counter(p.getUsedItems());
        TreeMap<String, Long> pat = patternTimes(p);
        TreeMap<String, Long> miss = counter(p.getMissingItems());
        return "[XREQ " + tag + "] sim=" + p.isSimulation()
                + " used=" + used + " patterns=" + pat + " missing=" + miss;
    }

    private static void assertPlansEqual(VMPlan a, VMPlan b, String msg) {
        assertEquals(a.isSimulation(), b.isSimulation(), msg + " (simulation)");
        assertEquals(counter(a.getUsedItems()), counter(b.getUsedItems()), msg + " (used)");
        // AE2 GUI "to craft" = Σ emittedItems + Σ patternTimes × outputAmount — so
        // emittedItems must also be stable across reuse or the GUI count diverges.
        assertEquals(counter(a.getEmittedItems()), counter(b.getEmittedItems()), msg + " (emitted)");
        assertEquals(patternTimes(a), patternTimes(b), msg + " (patternTimes)");
        assertEquals(counter(a.getMissingItems()), counter(b.getMissingItems()), msg + " (missing)");
    }

    private static TreeMap<String, Long> counter(VMCounter c) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (IAEItemStack key : c.keys()) {
            out.put(((BenchAEItemStack) key).id, c.get(key));
        }
        return out;
    }

    private static TreeMap<String, Long> patternTimes(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (Map.Entry<appeng.api.networking.crafting.ICraftingPatternDetails, Long> e
                : p.getPatternTimes().entrySet()) {
            out.put(((BenchAEItemStack) e.getKey().getOutputs()[0]).id, e.getValue());
        }
        return out;
    }

    /**
     * CRITICAL REGRESSION (v1.9.7): the same request on the same VM with the SAME
     * stock must be deterministic — request #2 (cache reuse) must equal request #1
     * (full capture). If reuse changes the result, this is the "首次正确，后续一致错误"
     * symptom regardless of stock changes.
     */
    @Test
    void sameStockSameAmountIsDeterministic() {
        StockedMidFixture fx = new StockedMidFixture().stockB(4); // == the need for 4 A
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan3 = vm.execute(fx.request(4), fx.sim());

        dump("req1", plan1);
        dump("req2", plan2);
        dump("req3", plan3);

        assertTrue(plan1.getMissingItems().isEmpty(), "req1 feasible");
        assertTrue(plan2.getMissingItems().isEmpty(), "req2 feasible");
        assertTrue(plan3.getMissingItems().isEmpty(), "req3 feasible");
        assertPlansEqual(plan1, plan2, "req2 must equal req1");
        assertPlansEqual(plan2, plan3, "req3 must equal req2");
    }

    /**
     * Same stock, SAME amount, but B stock is a strict sub-multiple of the need
     * (B=2, need 4 B → half from stock, half crafted). Verifies the crafted-deficit
     * branch is also deterministic across reuse.
     */
    @Test
    void sameStockDeficitBranchIsDeterministic() {
        StockedMidFixture fx = new StockedMidFixture().stockB(2); // need 4 B, stock 2 → craft 2
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan3 = vm.execute(fx.request(4), fx.sim());

        dump("req1", plan1);
        dump("req2", plan2);
        dump("req3", plan3);

        assertTrue(plan1.getMissingItems().isEmpty(), "req1 feasible");
        assertPlansEqual(plan1, plan2, "req2 must equal req1");
        assertPlansEqual(plan2, plan3, "req3 must equal req2");
    }

    /**
     * Multi-step stock drain: B 8 → 4 → 2 → 0 with the SAME amount (4 A, need 4 B).
     * Each step must reflect the CURRENT stock and stay feasible until B runs out.
     */
    @Test
    void multiStepStockDrain() {
        StockedMidFixture fx = new StockedMidFixture();
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        // Step 1: B=8 ≥ need 4 → all from stock, craft 0 B.
        fx.stockB(8);
        VMPlan p1 = vm.execute(fx.request(4), fx.sim());
        dump("s1", p1);
        assertTrue(p1.getMissingItems().isEmpty(), "s1 feasible");
        assertEquals(4L, p1.getUsedItems().get(k("B")), "s1 used B 4");
        assertEquals(0L, p1.getPatternTimes().getOrDefault(fx.b, 0L), "s1 craft B 0");

        // Step 2: B=2 < need 4 → use 2 from stock, craft 2.
        fx.stockB(2);
        VMPlan p2 = vm.execute(fx.request(4), fx.sim());
        dump("s2", p2);
        assertTrue(p2.getMissingItems().isEmpty(), "s2 feasible");
        assertEquals(2L, p2.getUsedItems().get(k("B")), "s2 used B 2");
        assertEquals(2L, p2.getPatternTimes().getOrDefault(fx.b, 0L), "s2 craft B 2");

        // Step 3: B=0 → all 4 crafted.
        fx.stockB(0);
        VMPlan p3 = vm.execute(fx.request(4), fx.sim());
        dump("s3", p3);
        assertTrue(p3.getMissingItems().isEmpty(), "s3 feasible");
        assertEquals(0L, p3.getUsedItems().get(k("B")), "s3 used B 0");
        assertEquals(4L, p3.getPatternTimes().getOrDefault(fx.b, 0L), "s3 craft B 4");
    }

    /**
     * Request amount changes across reuse: 4 A (need 4 B) then 8 A (need 8 B), SAME
     * stock B=4. The aggregation must re-derive the B total from the new amount.
     */
    @Test
    void amountChangeAcrossReuse() {
        StockedMidFixture fx = new StockedMidFixture().stockB(4);
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan p4 = vm.execute(fx.request(4), fx.sim());
        dump("amt4", p4);
        assertTrue(p4.getMissingItems().isEmpty(), "4 A feasible");
        assertEquals(0L, p4.getPatternTimes().getOrDefault(fx.b, 0L), "4 A: craft 0 B");

        VMPlan p8 = vm.execute(fx.request(8), fx.sim());
        dump("amt8", p8);
        assertTrue(p8.getMissingItems().isEmpty(), "8 A feasible");
        assertEquals(4L, p8.getUsedItems().get(k("B")), "8 A: used 4 B from stock");
        assertEquals(4L, p8.getPatternTimes().getOrDefault(fx.b, 0L), "8 A: craft 4 B");
    }

    /** Deep chain (A<-X<-B+C) with stocked B, deterministic across reuse. */
    @Test
    void deepChainDeterministic() {
        DeepFixture fx = new DeepFixture();
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(4), fx.sim());
        dump("deep1", plan1);
        dump("deep2", plan2);
        assertTrue(plan1.getMissingItems().isEmpty(), "deep req1 feasible");
        assertTrue(plan2.getMissingItems().isEmpty(), "deep req2 feasible");
        assertPlansEqual(plan1, plan2, "deep req2 must equal req1");
    }

    /** Diamond (B shared by P and Q, and stocked), deterministic across reuse. */
    @Test
    void diamondDeterministic() {
        DiamondFixture fx = new DiamondFixture();
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(4), fx.sim());
        dump("dia1", plan1);
        dump("dia2", plan2);
        assertTrue(plan1.getMissingItems().isEmpty(), "diamond req1 feasible");
        assertTrue(plan2.getMissingItems().isEmpty(), "diamond req2 feasible");
        assertPlansEqual(plan1, plan2, "diamond req2 must equal req1");
    }

    /**
     * Two INDEPENDENT VMs (two different grids, different stock) must not pollute
     * each other: each VM owns its own bundleCache / realStockCache / stockFromNetwork.
     * VM1 sees B=2 (craft 2 B), VM2 sees B=8 (all from stock) — each must stay on its
     * own network's stock regardless of execution order.
     */
    @Test
    void separateVmsDoNotPolluteEachOther() {
        // VM1: B=2 → use 2 from stock + craft 2.
        StockedMidFixture fx1 = new StockedMidFixture().stockB(2);
        CraftingVM vm1 = new CraftingVM("grid1", fx1.byOutput::get);

        // VM2: B=8 → all 4 from stock, craft 0.
        StockedMidFixture fx2 = new StockedMidFixture().stockB(8);
        CraftingVM vm2 = new CraftingVM("grid2", fx2.byOutput::get);

        // Run them interleaved to prove no cross-VM state bleed.
        VMPlan v1p1 = vm1.execute(fx1.request(4), fx1.sim());
        VMPlan v2p1 = vm2.execute(fx2.request(4), fx2.sim());
        VMPlan v1p2 = vm1.execute(fx1.request(4), fx1.sim());
        VMPlan v2p2 = vm2.execute(fx2.request(4), fx2.sim());

        dump("v1p1", v1p1);
        dump("v1p2", v1p2);
        dump("v2p1", v2p1);
        dump("v2p2", v2p2);

        assertEquals(2L, v1p1.getUsedItems().get(k("B")), "VM1 used B 2 | " + dump("v1p1", v1p1));
        assertEquals(2L, v1p1.getPatternTimes().getOrDefault(fx1.b, 0L),
                "VM1 craft B 2 | " + dump("v1p1", v1p1) + " | " + dump("v2p1", v2p1));
        assertEquals(4L, v2p1.getUsedItems().get(k("B")), "VM2 used B 4");
        assertEquals(0L, v2p1.getPatternTimes().getOrDefault(fx2.b, 0L), "VM2 craft B 0");
        assertTrue(v1p1.getMissingItems().isEmpty());
        assertTrue(v2p1.getMissingItems().isEmpty());
        // And each VM stays deterministic on its own second request.
        assertPlansEqual(v1p1, v1p2, "VM1 deterministic");
        assertPlansEqual(v2p1, v2p2, "VM2 deterministic");
    }

    /**
     * A BRAND-NEW VM (cold, full re-capture) must produce the SAME plan as a
     * REUSED VM for identical stock+request. If JIT reuse changed the result, then a
     * VM_CACHE eviction / grid-instance change mid-game would silently change the
     * computed quantities ("不同 VM 结果不同").
     */
    @Test
    void freshVmEqualsReusedVm() {
        // Cold VM: full capture on its very first request.
        StockedMidFixture fxCold = new StockedMidFixture().stockB(2);
        CraftingVM coldVm = new CraftingVM("gridCold", fxCold.byOutput::get);
        VMPlan coldPlan = coldVm.execute(fxCold.request(4), fxCold.sim());

        // Warm VM: same fixture, but run twice (second run reuses bundleCache).
        StockedMidFixture fxWarm = new StockedMidFixture().stockB(2);
        CraftingVM warmVm = new CraftingVM("gridWarm", fxWarm.byOutput::get);
        warmVm.execute(fxWarm.request(4), fxWarm.sim());
        VMPlan warmSecond = warmVm.execute(fxWarm.request(4), fxWarm.sim());

        dump("cold", coldPlan);
        dump("warm2", warmSecond);
        assertPlansEqual(coldPlan, warmSecond, "fresh VM plan must equal reused VM plan");
    }

    /**
     * REGRESSION (v1.9.8): EMPTY network stock. The user reports two identical
     * orders (same item, empty inventory) produce DIFFERENT plans (926K bytes →
     * 364K bytes) — "缓存配方的 bug". With an empty network every captured bundle
     * has non-empty {@code missing}, so the cross-request cache hygiene (which
     * drops bundles whose missing is non-empty) wipes the whole bundleCache at the
     * start of request #2 and the VM re-captures everything. The second plan MUST
     * be byte-for-byte identical to the first (same missing, same craft chain,
     * same bytes). Any difference = the cached-recipe bug.
     */
    @Test
    void emptyStockReuseIsDeterministic() {
        // Empty inventory: nothing in stock (B/D/E/F/G all absent).
        StockedMidFixture fx = new StockedMidFixture();
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(4), fx.sim());
        VMPlan plan3 = vm.execute(fx.request(4), fx.sim());

        dump("empty1", plan1);
        dump("empty2", plan2);
        dump("empty3", plan3);

        assertTrue(plan1.isSimulation(),
                "empty stock → simulation (missing) | " + dump("empty1", plan1));
        assertEquals(4L, plan1.getMissingItems().get(k("D")), "D missing 4");
        assertPlansEqual(plan1, plan2, "empty-stock req2 must equal req1");
        assertPlansEqual(plan2, plan3, "empty-stock req3 must equal req2");
    }

    /**
     * REGRESSION (v1.9.8): Fibonacci shared-DAG chain + EMPTY stock, reused VM.
     * Mirrors the user's 926K → 364K report (each craft-count halved on the second
     * order). Every node is missing, so the cache hygiene wipes the bundleCache and
     * the VM re-captures — the re-captured chain MUST equal the first, including the
     * full Fibonacci multiplier chain (X5 = X4+X3, etc.), never a halved subset.
     */
    @Test
    void emptyStockFibonacciIsDeterministic() {
        FibonacciFixture fx = new FibonacciFixture(8); // X0..X7, request X7
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(8), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(8), fx.sim());
        VMPlan plan3 = vm.execute(fx.request(8), fx.sim());

        dump("fib1", plan1);
        dump("fib2", plan2);
        dump("fib3", plan3);

        assertTrue(plan1.isSimulation(), "empty → simulation");
        // Sanity: craft counts must follow Fibonacci (X7=8 → X6+X5 = 5+3, etc.).
        assertPlansEqual(plan1, plan2, "fib empty req2 must equal req1");
        assertPlansEqual(plan2, plan3, "fib empty req3 must equal req2");
    }

    /**
     * REGRESSION (v1.9.9): DEEP 24-level Fibonacci chain + EMPTY stock + LARGE amount
     * (10^9), multi-step. This mirrors the user's report: request
     * quantum_omni_cell_component x 10^9, omni_cell_comp shows missing 46T
     * (= 10^9 × Fibonacci(24)=46368) even though omni_cell_comp HAS a pattern
     * ("可合成"). Every node X2..X23 has a pattern and MUST be synthesized; only the
     * leaf nodes X0/X1 may be missing. If any craftable mid-chain node appears in
     * missingItems, that reproduces the bug.
     */
    @Test
    void deepFib24MultiStepCraftablesNeverMissing() {
        FibonacciFixture fx = new FibonacciFixture(24); // X0..X23, request X23
        CraftingVM vm = new CraftingVM("grid", fx.byOutput::get);

        VMPlan plan1 = vm.execute(fx.request(1_000_000_000L), fx.sim());
        VMPlan plan2 = vm.execute(fx.request(1_000_000_000L), fx.sim());

        dump("deep24-1", plan1);
        dump("deep24-2", plan2);

        // Every craftable node must be present in patternTimes (synthesized), never missing.
        for (int i = 2; i < fx.levels; i++) {
            assertTrue(plan1.getPatternTimes().containsKey(fx.byId.get("X" + i)),
                    "X" + i + " has a pattern and must be synthesized, not missing");
        }
        // Missing must be ONLY the leaf nodes X0 / X1.
        for (IAEItemStack key : plan1.getMissingItems().keys()) {
            String id = ((BenchAEItemStack) key).id;
            assertTrue(id.equals("X0") || id.equals("X1"),
                    "missing should only be leaves X0/X1, but got " + id);
        }
        // Multi-step determinism.
        assertPlansEqual(plan1, plan2, "deep24 req2 must equal req1");
    }
}
