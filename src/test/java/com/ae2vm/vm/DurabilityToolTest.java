package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.VMPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ae2vm.test.harness.Bench.UNBOUNDED_STOCK;
import static com.ae2vm.test.harness.Bench.feasible;
import static com.ae2vm.test.harness.Bench.infeasibleMatches;
import static com.ae2vm.test.fakes.BenchPatternDetails.custom;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original DurabilityToolTest — scenario tests for the v1.10.x
 * DURABILITY (finite-use tool) support: a durability tool is a returned input
 * that degrades — one unit survives {@code uses} firings, so a batch of
 * {@code times} firings needs {@code ceil(times/uses)} tools (the reference's
 * "成环差分" closed form).
 *
 * <p>The reference {@code durability/finite-use-chain} scenario:
 * {@code product(1 raw + 1 finiteUse tool, 100 uses)} × 10,000. 10,000 firings need
 * {@code ceil(10000/100) = 100} tools — NOT 10,000 (the old "consumed per craft"
 * model) and NOT 1 (a catalyst seed). The tool demand is served from the NETWORK
 * stock: one tool short means exactly tool>=1 missing (the starved MINIMUM mode).
 *
 * <p><b>Planner vs runtime (M5 finding).</b> The closed form amortizes one tool
 * across {@code uses} firings, which requires the CPU to re-consume the worn
 * return damage-fuzzily. AE2UEL's processing extraction is exact
 * (CraftingCPUCluster :694) and the damage-fuzzy fallback is craftable-only
 * (:672), so on a processing pattern every firing burns a FRESH tool and the
 * worn returns pile up unusable — the faithful CPU delivers only
 * {@code ceil(amount/uses)} products before starving (S2). Charging the full
 * {@code amount} of fresh tools is the runtime-faithful form.
 */
class DurabilityToolTest {

    private static final int USES = 100;
    private static final long AMOUNT = 10_000L;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @BeforeEach
    void reset() {
        Bench.reset();
    }

    private static String dump(VMPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (var e : plan.getMissingItems().entrySet()) {
            sb.append(((BenchAEItemStack) e.getKey()).id).append('x').append(e.getValue()).append(' ');
        }
        return sb.toString();
    }

    /** product <- 1 raw + 1 finiteUse tool; the tool degrades T(0) -> T(1) per firing. */
    private static BenchPatternDetails durabilityPattern() {
        BenchAEItemStack toolIn = new BenchAEItemStack("tool", 0, USES, 1);
        BenchAEItemStack toolOut = new BenchAEItemStack("tool", 1, USES, 1);
        return custom(
                new IAEItemStack[]{toolIn, new BenchAEItemStack("raw", 1)},
                new IAEItemStack[]{new BenchAEItemStack("product", 1), toolOut});
    }

    private static BenchSimulationState stock(long tools, long raw) {
        BenchSimulationState sim = new BenchSimulationState();
        if (tools > 0) {
            sim.seedVariant("tool", 0, USES, tools);
        }
        return sim.seed("raw", raw);
    }

    @Test
    void durabilityMinimumFeasible() {
        // stock {tool: 100, raw: 10_000} — 100 tools × 100 uses = 10_000 firings → feasible.
        BenchPatternDetails p = durabilityPattern();
        Bench.register(p);
        VMPlan plan = Bench.run(p, AMOUNT, stock(USES, AMOUNT));
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        // Faithful runtime divergence — see the class note (fresh tool per firing)
        CpuLifecycleAssert.stalls(plan, "S2");
        assertTrue(feasible(plan),
                "durability with exactly ceil(amount/uses) tools must be feasible, got " + dump(plan));
    }

    @Test
    void durabilityUnboundedFeasible() {
        BenchPatternDetails p = durabilityPattern();
        Bench.register(p);
        VMPlan plan = Bench.run(p, AMOUNT, stock(UNBOUNDED_STOCK, UNBOUNDED_STOCK));
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        // Faithful runtime divergence — see the class note (fresh tool per firing)
        CpuLifecycleAssert.stalls(plan, "S2");
        assertTrue(feasible(plan),
                "durability with unbounded tools must be feasible, got " + dump(plan));
    }

    @Test
    void durabilityStarvedMissingOneTool() {
        // stock {tool: 99, raw: 10_000} — one tool short of the 100 needed.
        BenchPatternDetails p = durabilityPattern();
        Bench.register(p);
        VMPlan plan = Bench.run(p, AMOUNT, stock(USES - 1L, AMOUNT));
        com.ae2vm.replay.VirtualCPUCluster.TRACE = true;
        CpuLifecycleAssert.auto(plan);
        assertFalse(feasible(plan), "durability one tool short must be infeasible, got " + dump(plan));
        assertTrue(infeasibleMatches(plan, Map.of("tool", 1L)),
                "durability one tool short must report tool>=1 missing, got " + dump(plan));
    }
}
