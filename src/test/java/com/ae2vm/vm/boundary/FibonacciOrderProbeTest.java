package com.ae2vm.vm.boundary;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceCapabilityRunner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic probe for the fibonacci/minimum order stall: dumps the final
 * plan's task order, the initial inventory bill and both replica verdicts
 * (final order vs. plain reversal) so the divergence is visible in one run.
 */
class FibonacciOrderProbeTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @Test
    void probeFibonacciMinimum() {
        ReferenceScenario scenario = ThunderboltReferenceScenarios.all().stream()
                .filter(s -> s.id().equals("single-dag/fibonacci/minimum"))
                .findFirst().orElseThrow();
        Ae2VmReferencePlanner planner = new Ae2VmReferencePlanner();
        ReferenceCapabilityRunner runner = new ReferenceCapabilityRunner(
                Duration.ofSeconds(30), Duration.ofMillis(100));
        var result = runner.run(planner, scenario);
        System.out.println("[probe] status=" + result.status()
                + " executable=" + planner.lastPlanExecutable
                + " verdict=" + planner.lastRuntimeVerdict);
        var plan = planner.lastPlan;
        if (plan == null) {
            System.out.println("[probe] plan=null");
            return;
        }
        Map<String, Long> used = new TreeMap<>();
        for (IAEItemStack k : plan.getUsedItems().keys()) {
            used.put(((BenchAEItemStack) k).id, plan.getUsedItems().get(k));
        }
        System.out.println("[probe] usedItems=" + used);
        System.out.println("[probe] emitted=" + plan.getEmittedItems().size()
                + " missing=" + plan.getMissingItems().size());
        StringBuilder sb = new StringBuilder("[probe] task order:");
        for (Map.Entry<ICraftingPatternDetails, Long> e : plan.getPatternTimes().entrySet()) {
            IAEItemStack out = e.getKey().getCondensedOutputs()[0];
            sb.append(' ').append(((BenchAEItemStack) out).id).append('x').append(e.getValue());
        }
        System.out.println(sb);
        // re-run the replica on the final order, then on its reversal
        var vFinal = new com.ae2vm.replay.VirtualCPUCluster(plan, plan.getOutputKey(),
                plan.getDeliverAmount()).run(10_000, 0);
        System.out.println("[probe] replica(final order) = " + vFinal);
        java.util.LinkedHashMap<ICraftingPatternDetails, Long> reversed = new java.util.LinkedHashMap<>();
        java.util.Deque<Map.Entry<ICraftingPatternDetails, Long>> stack = new java.util.ArrayDeque<>();
        for (var e : plan.getPatternTimes().entrySet()) {
            stack.push(e);
        }
        for (var e : stack) {
            reversed.put(e.getKey(), e.getValue());
        }
        var vRev = new com.ae2vm.replay.VirtualCPUCluster(
                new com.ae2vm.vm.VMPlan(plan.getOutputKey(), plan.getDeliverAmount(),
                        plan.getBytes(), false, plan.getUsedItems(), plan.getMissingItems(),
                        plan.getEmittedItems(), reversed),
                plan.getOutputKey(), plan.getDeliverAmount()).run(10_000, 0);
        System.out.println("[probe] replica(reversed)     = " + vRev);
    }
}
