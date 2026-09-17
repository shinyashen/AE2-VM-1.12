package com.ae2vm.vm.boundary;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.test.fakes.BenchAEItemStack;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import com.ae2vm.replay.VirtualCPUCluster;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import net.minecraft.init.Bootstrap;

/**
 * Diagnostic probe for the fibonacci/minimum order stall: dumps the final
 * plan's task order, the initial inventory bill and both replica verdicts
 * (final order vs. plain reversal) so the divergence is visible in one run.
 */
class FibonacciOrderProbeTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void probeFibonacciMinimum() {
        for (String id : new String[]{"single-dag/fibonacci/minimum",
                "single-dag/fibonacci/missing", "single-dag/fibonacci/unbounded"}) {
            System.out.println("[probe] ===== " + id + " =====");
            probeOne(id);
        }
    }

    private void probeOne(String id) {
        ReferenceScenario scenario = ThunderboltReferenceScenarios.all().stream()
                .filter(s -> s.id().equals(id))
                .findFirst().orElseThrow();
        Ae2VmReferencePlanner planner = new Ae2VmReferencePlanner();
        long tPlan = System.nanoTime();
        planner.plan(scenario);
        long planMs = (System.nanoTime() - tPlan) / 1_000_000L;
        System.out.println("[probe] plan() wall = " + planMs + " ms"
                + " executable=" + planner.lastPlanExecutable);
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
        long totalCrafts = plan.getPatternTimes().values().stream().mapToLong(Long::longValue).sum();
        System.out.println("[probe] totalCrafts=" + totalCrafts
                + " patterns=" + plan.getPatternTimes().size());
        // re-run the replica on the final order, then on its reversal
        long tRep = System.nanoTime();
        var vFinal = new VirtualCPUCluster(plan, plan.getOutputKey(),
                plan.getDeliverAmount()).run(10_000, 0);
        long repMs = (System.nanoTime() - tRep) / 1_000_000L;
        System.out.println("[probe] replica(final order) = " + vFinal
                + "  [" + repMs + " ms]");
        LinkedHashMap<ICraftingPatternDetails, Long> reversed = new LinkedHashMap<>();
        Deque<Map.Entry<ICraftingPatternDetails, Long>> stack = new ArrayDeque<>();
        for (var e : plan.getPatternTimes().entrySet()) {
            stack.push(e);
        }
        for (var e : stack) {
            reversed.put(e.getKey(), e.getValue());
        }
    }
}
