package com.ae2vm.test.harness;

import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.replay.VirtualCPUCluster;
import com.ae2vm.vm.VMPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bridges the plan-level tests to the M5 CPU-lifecycle simulator (design
 * doc §7): a plan is no longer "correct" merely because its schedule and
 * balances are right — it must also SURVIVE the virtual CPU.
 *
 * <p>Semantics: {@code complete} runs the instant provider and asserts the
 * requested amount is delivered with no stall. {@code stalls} asserts the
 * recorded stall class — S1 (emitable residue), S2 (starved input, the
 * missing-list shape) or S4 (bookkeeping leak). Sim plans with intentional
 * shortfalls stall by design: assert S2, never COMPLETE.
 */
public final class CpuLifecycleAssert {

    private static final int MAX_STEPS = 10_000;

    private CpuLifecycleAssert() {
    }

    public static VirtualCPUCluster.Verdict complete(VMPlan plan, IAEItemStack what, long amount) {
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan, what, amount).run(MAX_STEPS, 0);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
        return v;
    }

    /** COMPLETE with craftable-branch slot filling (substitute-enabled slots). */
    public static VirtualCPUCluster.Verdict complete(VMPlan plan, IAEItemStack what, long amount,
                                                     VirtualCPUCluster.SlotAlternates alternates) {
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan, what, amount, alternates).run(MAX_STEPS, 0);
        assertEquals(VirtualCPUCluster.Verdict.Status.COMPLETE, v.status, v.toString());
        return v;
    }

    /**
     * Zero-config bridge for Bench.run call sites. An executable plan
     * (job would be accepted) must faithfully COMPLETE on the virtual CPU —
     * a stall is a real engine defect, not a test expectation. Simulation
     * plans are rejected by the terminal before any CPU exists, so their
     * forced-run verdict is informational only (no assertion): the engine's
     * missing margin may be conservative, and faithful execution may or may
     * not deliver. Scenarios whose true executable expectation differs use
     * the explicit {@code complete}/{@code stalls} forms with a comment
     * citing the AE2UEL semantics that force the divergence.
     */
    public static VirtualCPUCluster.Verdict auto(VMPlan plan) {
        // the plan itself carries the request: outputKey = requested identity,
        // deliverAmount = requested amount (buildPlan semantics)
        IAEItemStack what = plan.getOutputKey();
        long amount = plan.getDeliverAmount();
        if (plan.isSimulation()) {
            return new VirtualCPUCluster(plan, what, amount).run(MAX_STEPS, 0);
        }
        return complete(plan, what, amount);
    }

    /** {@link #auto} with craftable-branch slot filling (substitute-enabled slots). */
    public static VirtualCPUCluster.Verdict auto(VMPlan plan,
                                                 VirtualCPUCluster.SlotAlternates alternates) {
        IAEItemStack what = plan.getOutputKey();
        long amount = plan.getDeliverAmount();
        if (plan.isSimulation()) {
            return new VirtualCPUCluster(plan, what, amount, alternates).run(MAX_STEPS, 0);
        }
        return complete(plan, what, amount, alternates);
    }

    public static VirtualCPUCluster.Verdict stalls(VMPlan plan, IAEItemStack what, long amount,
                                                   String stallClass) {
        VirtualCPUCluster.Verdict v = new VirtualCPUCluster(plan, what, amount).run(MAX_STEPS, 0);
        assertEquals(VirtualCPUCluster.Verdict.Status.STALL, v.status, v.toString());
        assertEquals(stallClass, v.stallClass, v.toString());
        return v;
    }

    /**
     * Explicit stall bridge reading the request from the plan itself — for
     * scenarios where the faithful CPU verdict diverges from the planner's
     * feasibility (the call site carries a comment citing the AE2UEL
     * semantics that force the divergence).
     */
    public static VirtualCPUCluster.Verdict stalls(VMPlan plan, String stallClass) {
        return stalls(plan, plan.getOutputKey(), plan.getDeliverAmount(), stallClass);
    }
}
