package com.ae2vm.vm.boundary;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.compiler.PatternCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static com.ae2vm.test.fakes.BenchPatternDetails.withSlotSubstitute;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Port of the original FuzzyDiagTest — diagnoses the fuzzy / craftable scenarios the
 * user hit, printing the produced plan for eyeballing:
 *
 * <ol>
 *   <li><b>fuzzy gray wool</b> — pattern input returns [gray_wool, white_wool]
 *       (item-replacement encoded), network has ONLY white wool. The original bug
 *       was "missing gray wool".</li>
 *   <li><b>craftable primary with partial stock</b> — primary has a crafting
 *       pattern and the network holds a small amount of it (enough for the 1-craft
 *       capture, not the full batch). The v1.9.12 bug was "has a pattern but
 *       reported missing".</li>
 * </ol>
 * Like the original, these are diagnostics: they print the plan and assert the
 * boundary quantities that must hold once the fix is in.
 *
 * <p>The substitute-slot products are CRAFTABLE fakes: slot substitution is a
 * crafting-pattern feature (PatternHelper :87), so a
 * processing pattern with a substitute table compiles exact slots and none of
 * the fuzzy-fill math below would apply to it.
 */
class FuzzyDiagTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static VMPlan run(BenchPatternDetails target, long amount, BenchSimulationState sim) {
        for (appeng.api.networking.crafting.ICraftingPatternDetails p : Bench.PATTERNS.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(target, amount);
        CraftingVM vm = new CraftingVM("diag", Bench.PATTERNS::get);
        return vm.execute(req, sim);
    }

    private static Map<String, Long> used(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : p.getUsedItems().keys()) {
            out.put(((BenchAEItemStack) key).id, p.getUsedItems().get(key));
        }
        return out;
    }

    private static Map<String, Long> missing(VMPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var key : p.getMissingItems().keys()) {
            out.put(((BenchAEItemStack) key).id, p.getMissingItems().get(key));
        }
        return out;
    }

    @Test
    void diagFuzzyGrayWool() {
        BenchPatternDetails product = withSlotSubstitute(
                pat("product", 1, "gray_wool", 1L).asCraftable(), new int[]{0}, "white_wool");
        Bench.register(product);
        BenchSimulationState sim = new BenchSimulationState().seed("white_wool", 1000L);

        VMPlan plan = run(product, 100, sim);
        System.out.println("[DIAG fuzzy-gray] used=" + used(plan) + " missing=" + missing(plan));
        // the replacement-enabled slot must be satisfied by the stocked substitute
        assertEquals(100L, plan.getUsedItems().get(k("white_wool")));
        assertEquals(0, plan.getMissingItems().size());
    }

    @Test
    void diagCraftablePrimaryPartialStock() {
        // X is CRAFTABLE (X <- raw); product <- { X (fuzzy: X, X') }; network has X=1 (partial).
        BenchPatternDetails x = pat("X", 1, "raw", 1L);
        Bench.register(x);
        BenchPatternDetails product = withSlotSubstitute(
                pat("product", 1, "X", 1L).asCraftable(), new int[]{0}, "X_prime");
        Bench.register(product);
        BenchSimulationState sim = new BenchSimulationState()
                .seed("X", 1L)
                .seed("raw", 1000L);

        VMPlan plan = run(product, 100, sim);
        System.out.println("[DIAG craftable-partial] used=" + used(plan)
                + " missing=" + missing(plan)
                + " patterns=" + plan.getPatternTimes().size());
        // partial stock + crafted deficit -> feasible, never "has a pattern but missing"
        assertEquals(0, plan.getMissingItems().size(), "missing=" + missing(plan));
        assertEquals(99L, plan.getPatternTimes().getOrDefault(x, 0L));
    }

    @Test
    void diagCraftablePrimaryNoStock() {
        BenchPatternDetails x = pat("X", 1, "raw", 1L);
        Bench.register(x);
        BenchPatternDetails product = withSlotSubstitute(
                pat("product", 1, "X", 1L).asCraftable(), new int[]{0}, "X_prime");
        Bench.register(product);
        BenchSimulationState sim = new BenchSimulationState().seed("raw", 1000L);

        VMPlan plan = run(product, 100, sim);
        System.out.println("[DIAG craftable-nostock] used=" + used(plan)
                + " missing=" + missing(plan)
                + " patterns=" + plan.getPatternTimes().size());
        // no stock at all: the full 100 must be crafted, nothing missing
        assertEquals(0, plan.getMissingItems().size(), "missing=" + missing(plan));
        assertEquals(100L, plan.getPatternTimes().getOrDefault(x, 0L));
    }
}
