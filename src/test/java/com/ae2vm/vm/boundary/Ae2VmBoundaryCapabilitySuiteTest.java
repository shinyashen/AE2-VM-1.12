package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.ae2vm.test.harness.Bench.pat;
import static com.ae2vm.test.fakes.BenchPatternDetails.withSlotSubstitute;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.12 port of the BOUNDARY capability suite: the NAST server report —
 * "合成一个物品或液体，1 个/1b 缺失无法合成，但 2 个/100b 正常" + "有样板却报缺失".
 * These are the quantity-boundary and fuzzy-replacement-stock scenarios the plain
 * 33-case suite (whose string-keyed translation has no live stock view) can never
 * exercise. Every case drives the ported VM over a real network stock so the
 * stock-aware aggregation and the fuzzy-group substitute-stock path are actually
 * validated.
 *
 * <p>Every case asserts {@code expectedFeasible} and prints one
 * {@code [reference-boundary]} row + a trailing summary, so the boundary surface
 * is pinned exactly like the main reference suite. (In the 1.12 port the VM takes
 * an opaque network key; {@code realStockOf} snapshots the simulation state at
 * {@code execute()} start, which observes the same live stock the original's
 * {@code FakeBenchGrid} provided.)
 */
class Ae2VmBoundaryCapabilitySuiteTest {

    /** One boundary case: id + target key + fixture builder + requested amount + expected feasibility. */
    private record BoundaryCase(
            String id, String target, java.util.function.Consumer<Fixture> build,
            long amount, boolean expectedFeasible) {
    }

    /** Outcome record for the summary. */
    private record Outcome(String id, boolean feasible, boolean ok, Map<String, Long> missing,
                           long elapsedMs) {
    }

    private static final ConcurrentLinkedQueue<Outcome> OUTCOMES = new ConcurrentLinkedQueue<>();

    @BeforeAll
    static void bootstrap() {
        // The 1.12 fakes touch the vanilla registry (createItemStack).
        net.minecraft.init.Bootstrap.register();
    }

    /** Per-case fixture: patterns by primary output + network stock. */
    private static final class Fixture {
        final Map<String, BenchPatternDetails> byOutput = new LinkedHashMap<>();
        final Map<String, Long> stock = new LinkedHashMap<>();

        Fixture fuzzyCraftablePrimary(boolean grayCraftable) {
            BenchPatternDetails product = withSlotSubstitute(
                    pat("product", 1, "gray_wool", 1L), new int[]{0}, "white_wool");
            byOutput.put("product", product);
            if (grayCraftable) {
                byOutput.put("gray_wool", pat("gray_wool", 1, "black_wool", 1L));
            }
            return this;
        }

        Fixture deepChainMidStock(int levels, String midId, long midStock) {
            for (int i = 1; i < levels; i++) {
                byOutput.put("N" + i, pat("N" + i, 1, "N" + (i - 1), 1L));
            }
            stock.put("N0", 1_000_000L);
            stock.put(midId, midStock);
            return this;
        }

        Fixture craftableFluidPartialStock() {
            byOutput.put("circuit_board", pat("circuit_board", 1, "raw", 1L));
            byOutput.put("fluid_x", pat("fluid_x", 1000, "water", 1L));
            byOutput.put("blank_pattern", pat("blank_pattern", 1,
                    "circuit_board", 1L, "fluid_x", 1000L));
            stock.put("raw", 1_000_000L);
            stock.put("water", 1_000_000L);
            stock.put("fluid_x", 500L); // partial: 500 of 1000 per craft
            return this;
        }
    }

    private static VMPlan runTarget(Fixture fx, String target, long amount) {
        PatternCompiler.clearCache(); // also resets the fuzzy groups
        // The VM resolver looks patterns up by OUTPUT STACK (type key), so index
        // the fixture's string-keyed patterns by their primary output here.
        Map<appeng.api.storage.data.IAEItemStack, appeng.api.networking.crafting.ICraftingPatternDetails> patterns =
                new LinkedHashMap<>();
        for (BenchPatternDetails p : fx.byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
            patterns.put(p.getOutputs()[0], p);
        }
        BenchPatternDetails top = fx.byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("boundary-bench", patterns::get);
        BenchSimulationState sim = new BenchSimulationState();
        for (Map.Entry<String, Long> e : fx.stock.entrySet()) {
            sim.seed(e.getKey(), e.getValue());
        }
        return vm.execute(req, sim);
    }

    private static Map<String, Long> missing(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : p.getMissingItems().keys()) {
            out.put(((BenchAEItemStack) key).id, p.getMissingItems().get(key));
        }
        return out;
    }

    /**
     * Faithful runtime note (M5): AE2UEL processing patterns extract their exact
     * condensed inputs per push (CraftingCPUCluster :694) and slot substitution is
     * crafting-only (PatternHelper :85 {@code canSubstitute = isCrafting && ...}),
     * so a plan whose fuzzy slot was filled purely with substitute stock has NO
     * CPU-level support: the pattern cannot consume the substitute and the job
     * deadlocks at t=0 (S2). Those cases assert the faithful stall; every other
     * executable case must COMPLETE. The planner-level assertions above are
     * unaffected — the substitution math itself is pinned separately.
     */
    private static final java.util.Set<String> PROCESSING_SUBSTITUTE_CASES = java.util.Set.of(
            "quantity/craftable-primary-white-stock/",
            "quantity/craftable-primary-white-stock10/",
            "quantity/fuzzy-leaf-white-stock/");

    private static void runCase(BoundaryCase c) {
        long start = System.nanoTime();
        Fixture fx = new Fixture();
        c.build().accept(fx);
        VMPlan plan = runTarget(fx, c.target(), c.amount());
        if (plan != null) {
            if (!plan.isSimulation() && PROCESSING_SUBSTITUTE_CASES.stream().anyMatch(c.id()::startsWith)) {
                CpuLifecycleAssert.stalls(plan, "S2");
            } else {
                CpuLifecycleAssert.auto(plan); // feasible must complete; infeasible must stall
            }
        }
        Map<String, Long> miss = missing(plan);
        boolean feasible = miss.isEmpty();
        boolean ok = feasible == c.expectedFeasible();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        OUTCOMES.add(new Outcome(c.id(), feasible, ok, miss, elapsedMs));
        System.out.println("[reference-boundary] engine=ae2vm id=" + c.id()
                + " amount=" + c.amount()
                + " expectedFeasible=" + c.expectedFeasible()
                + " feasible=" + feasible
                + " ok=" + ok
                + " missing=" + miss
                + " elapsedMs=" + elapsedMs);
    }

    @TestFactory
    Stream<DynamicTest> boundaryCapabilities() {
        List<BoundaryCase> cases = new ArrayList<>();

        // --- Quantity boundary: gray craftable + WHITE stocked. Request 1 vs 2 vs 100.
        // Player: "1x missing but 2x/100x works" — the 1-craft boundary. With white=1
        // stock the old code crafted ALL gray AND demanded ALL white -> false missing.
        for (long amount : new long[]{1L, 2L, 100L}) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-white-stock/" + amount,
                    "product",
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put("white_wool", 1L);
                        fx.stock.put("black_wool", 1000L);
                    },
                    amt, true));
        }

        // --- Same, with MORE substitute stock (10 / 100) — must still never over-craft.
        for (long amount : new long[]{1L, 2L, 100L}) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-white-stock10/" + amount,
                    "product",
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put("white_wool", 10L);
                        fx.stock.put("black_wool", 1000L);
                    },
                    amt, true));
        }

        // --- Craftable primary with PARTIAL primary stock (gray=40 of 100 needed) +
        //     black stocked: the v1.9.12 regression — must still schedule gray's sub-craft.
        for (long amount : new long[]{1L, 2L, 100L}) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-partial-gray/" + amount,
                    "product",
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put("gray_wool", 40L);
                        fx.stock.put("black_wool", 1000L);
                    },
                    amt, true));
        }

        // --- Craftable primary, NO variant stock, black stocked: must craft gray fully.
        for (long amount : new long[]{1L, 2L, 100L}) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-no-variant-stock/" + amount,
                    "product",
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put("black_wool", 1000L);
                    },
                    amt, true));
        }

        // --- Fuzzy leaf primary + white stocked: replacement must satisfy the slot.
        for (long amount : new long[]{1L, 2L, 100L}) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/fuzzy-leaf-white-stock/" + amount,
                    "product",
                    fx -> {
                        fx.fuzzyCraftablePrimary(false);
                        fx.stock.put("white_wool", 1000L);
                    },
                    amt, true));
        }

        // --- Deep chain + mid partial stock: request 1 (the NAST 1m-item boundary).
        //     levels 10 & 20, mid stock 0 / 1 / 5.
        for (int levels : new int[]{10, 20}) {
            for (long midStock : new long[]{0L, 1L, 5L}) {
                for (long amount : new long[]{1L, 2L, 100L}) {
                    final int lv = levels;
                    final long ms = midStock;
                    final long amt = amount;
                    cases.add(new BoundaryCase(
                            "quantity/deep-chain-mid-stock-l" + levels + "-s" + midStock + "/" + amount,
                            "N" + (levels - 1),
                            fx -> fx.deepChainMidStock(lv, "N" + (lv - 2), ms),
                            amt, true));
                }
            }
        }

        // --- Craftable fluid + partial fluid stock: request 1 vs 2 (1b vs 2b boundary).
        for (long amount : new long[]{1L, 2L, 100L}) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-fluid-partial/" + amount,
                    "blank_pattern",
                    Fixture::craftableFluidPartialStock,
                    amt, true));
        }

        // --- Genuinely infeasible (sanity: the suite must not over-report feasibility):
        // gray NOT craftable, NO white stock -> request 1 is truly missing.
        cases.add(new BoundaryCase(
                "quantity/infeasible-no-variant-stock/1",
                "product",
                fx -> fx.fuzzyCraftablePrimary(false),
                1L, false));

        List<DynamicTest> tests = new ArrayList<>(cases.size() + 1);
        for (BoundaryCase c : cases) {
            tests.add(DynamicTest.dynamicTest(c.id(), () -> runCase(c)));
        }
        tests.add(DynamicTest.dynamicTest("boundary-summary", Ae2VmBoundaryCapabilitySuiteTest::printSummary));
        return tests.stream();
    }

    private static void printSummary() {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger feasible = new AtomicInteger();
        AtomicLong totalMs = new AtomicLong();
        for (Outcome o : OUTCOMES) {
            if (o.ok()) {
                ok.incrementAndGet();
            }
            if (o.feasible()) {
                feasible.incrementAndGet();
            }
            totalMs.addAndGet(o.elapsedMs());
        }
        System.out.println("[reference-boundary] engine=ae2vm SUMMARY cases=" + OUTCOMES.size()
                + " ok=" + ok.get()
                + " feasible=" + feasible.get()
                + " totalElapsedMs=" + totalMs.get());
        assertEquals(OUTCOMES.size(), ok.get(),
                "every boundary case must match its expected feasibility");
    }
}
