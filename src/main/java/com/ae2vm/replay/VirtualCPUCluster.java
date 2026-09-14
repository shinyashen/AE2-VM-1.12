package com.ae2vm.replay;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.VMPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline replica of AE2UEL's crafting-CPU state machine (design doc
 * §7.1) — the four fields that define a live CPU: {@code tasks} (per
 * pattern remaining pushes), {@code inventory} (the stock setJob handed
 * over), {@code waitingFor} (expected returns + emitable), and
 * {@code finalOutput}. Patterns are pushed to VIRTUAL providers:
 * {@code instant} returns outputs within the same step, {@code lag=k}
 * returns them k steps later (IO-rhythm checking).
 *
 * <p>Verdict semantics mirror the live cluster: the job is COMPLETE when
 * the requested amount has been delivered — a CPU whose {@code waitingFor}
 * never drains stays busy FOREVER even with delivery done (AE2 has no
 * stall detection; {@code waiting} resets every tick). Stalls are
 * classified per design doc §7.2:
 *
 * <ul>
 *   <li><b>S1</b> — waitingFor holds emitable entries nothing will ever
 *       return (the gross-emitable bug shape: delivery done, CPU busy
 *       indefinitely);</li>
 *   <li><b>S2</b> — a scheduled pattern's input has no stock, no producer
 *       and no pending return (silent scheduling starvation);</li>
 *   <li><b>S4</b> — tasks exhausted / nothing pushable while delivery is
 *       short (expected returns that cannot exist).</li>
 * </ul>
 * (S3, the extraction gap, is a START-phase check — see
 * {@link #extractionGap(VMPlan, Stock)}.)
 */
public final class VirtualCPUCluster {

    /** Probe-limited stock: identity folding via isSameType, insertion order. */
    public static final class Stock {
        private final List<IAEItemStack> keys = new ArrayList<>();
        private final List<Long> amounts = new ArrayList<>();

        public void add(IAEItemStack k, long n) {
            if (n <= 0 || k == null) {
                return;
            }
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).isSameType(k)) {
                    amounts.set(i, satAdd(amounts.get(i), n));
                    return;
                }
            }
            keys.add(k);
            amounts.add(n);
        }

        public long amountOf(IAEItemStack k) {
            long s = 0;
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).isSameType(k)) {
                    s = satAdd(s, amounts.get(i));
                }
            }
            return s;
        }

        /** Returns how much was actually taken. */
        public long extract(IAEItemStack k, long n) {
            long taken = 0;
            for (int i = 0; i < keys.size() && taken < n; i++) {
                if (keys.get(i).isSameType(k) && amounts.get(i) > 0) {
                    long take = Math.min(amounts.get(i), n - taken);
                    amounts.set(i, amounts.get(i) - take);
                    taken += take;
                }
            }
            return taken;
        }

        public boolean isEmpty() {
            for (long a : amounts) {
                if (a > 0) {
                    return false;
                }
            }
            return true;
        }

        public List<IAEItemStack> keys() {
            return keys;
        }

        public long amountAt(int i) {
            return amounts.get(i);
        }

        private static long satAdd(long a, long b) {
            long r = a + b;
            return r < 0 ? Long.MAX_VALUE : r;
        }
    }

    /** Simulation verdict (design doc §7.2). */
    public static final class Verdict {
        public enum Status { COMPLETE, STALL }

        public final Status status;
        /** S1/S2/S4 for stalls; null when complete. */
        public final String stallClass;
        public final List<String> evidence;
        public final long delivered;
        public final int steps;

        Verdict(Status status, String stallClass, List<String> evidence, long delivered, int steps) {
            this.status = status;
            this.stallClass = stallClass;
            this.evidence = evidence;
            this.delivered = delivered;
            this.steps = steps;
        }

        public static Verdict complete(long delivered, int steps, List<String> notes) {
            return new Verdict(Status.COMPLETE, null, notes, delivered, steps);
        }

        public static Verdict stall(String cls, List<String> evidence, long delivered, int steps) {
            return new Verdict(Status.STALL, cls, evidence, delivered, steps);
        }

        @Override
        public String toString() {
            return status + (stallClass == null ? "" : " " + stallClass) + " delivered=" + delivered
                    + (evidence.isEmpty() ? "" : " " + evidence);
        }
    }

    private final Map<ICraftingPatternDetails, Long> tasks = new LinkedHashMap<>();
    private final Stock inventory = new Stock();
    private final Stock waitingFor = new Stock();
    private final Stock emitable = new Stock();
    private final List<IAEItemStack> pendingKeys = new ArrayList<>();
    private final List<Long> pendingAmounts = new ArrayList<>();
    private final List<Integer> pendingDue = new ArrayList<>();
    private final IAEItemStack finalOutputKey;
    private final long finalAmount;
    private long delivered;
    private long changeStamp;

    public VirtualCPUCluster(VMPlan plan, IAEItemStack what, long amount) {
        tasks.putAll(plan.getPatternTimes());
        for (Map.Entry<IAEItemStack, Long> e : plan.getUsedItems().entrySet()) {
            inventory.add(e.getKey(), e.getValue());
        }
        for (Map.Entry<IAEItemStack, Long> e : plan.getEmittedItems().entrySet()) {
            // addEmitable: the emitable sits in waitingFor and nothing will
            // ever inject it back
            emitable.add(e.getKey(), e.getValue());
            waitingFor.add(e.getKey(), e.getValue());
        }
        finalOutputKey = what;
        finalAmount = amount;
    }

    /** START-phase check (S3): every used item must exist in the snapshot stock. */
    public static List<String> extractionGap(VMPlan plan, Stock snapshot) {
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<IAEItemStack, Long> e : plan.getUsedItems().entrySet()) {
            long have = snapshot.amountOf(e.getKey());
            if (have < e.getValue()) {
                gaps.add("S3 extract-gap: " + e.getKey() + " used=" + e.getValue() + " stock=" + have);
            }
        }
        return gaps;
    }

    /** Runs the cluster to completion, stall, or the step budget. */
    public Verdict run(int maxSteps, int providerLag) {
        int stallAfter = 2;
        int noProgress = 0;
        int step = 0;
        for (; step < maxSteps; step++) {
            if (delivered >= finalAmount) {
                return classifyCompletion(step);
            }
            long before = changeStamp;
            stepOnce(providerLag, step);
            if (changeStamp != before) {
                noProgress = 0;
            } else {
                noProgress++;
            }
            if (noProgress >= stallAfter) {
                return classifyStall(step);
            }
        }
        return classifyStall(step);
    }

    private void stepOnce(int lag, int step) {
        // returns due this step (skip step 0: nothing pushed yet)
        List<IAEItemStack> dueKeys = new ArrayList<>();
        List<Long> dueAmounts = new ArrayList<>();
        for (int i = pendingDue.size() - 1; i >= 0; i--) {
            if (pendingDue.get(i) <= step) {
                dueKeys.add(pendingKeys.get(i));
                dueAmounts.add(pendingAmounts.get(i));
                pendingKeys.remove(i);
                pendingAmounts.remove(i);
                pendingDue.remove(i);
            }
        }
        for (int i = 0; i < dueKeys.size(); i++) {
            inject(dueKeys.get(i), dueAmounts.get(i));
        }

        // push loop: AE2 iterates tasks and pushes what it can
        boolean instantThisStep = lag == 0;
        for (Map.Entry<ICraftingPatternDetails, Long> e : new LinkedHashMap<>(tasks).entrySet()) {
            long remaining = e.getValue();
            if (remaining <= 0) {
                continue;
            }
            ICraftingPatternDetails d = e.getKey();
            if (!canCraft(d)) {
                continue;
            }
            for (IAEItemStack in : d.getCondensedInputs()) {
                if (in != null && in.getStackSize() > 0) {
                    inventory.extract(in, in.getStackSize());
                }
            }
            tasks.put(d, remaining - 1);
            changeStamp++;
            int due = instantThisStep ? step + 1 : step + Math.max(1, lag);
            for (IAEItemStack out : outputsOf(d)) {
                // AE2 accounting: a successful push records the EXPECTED
                // outputs in waitingFor before they return
                waitingFor.add(out, out.getStackSize());
                scheduleReturn(out, due);
            }
        }

        // account pending returns arriving later as "progress" only when they land
        if (delivered >= finalAmount) {
            changeStamp++;
        }
    }

    private boolean canCraft(ICraftingPatternDetails d) {
        for (IAEItemStack in : d.getCondensedInputs()) {
            if (in == null || in.getStackSize() <= 0) {
                continue;
            }
            if (inventory.amountOf(in) < in.getStackSize()) {
                return false;
            }
        }
        return true;
    }

    private List<IAEItemStack> outputsOf(ICraftingPatternDetails d) {
        List<IAEItemStack> outs = new ArrayList<>();
        try {
            for (IAEItemStack o : d.getCondensedOutputs()) {
                if (o != null && o.getStackSize() > 0) {
                    outs.add(o);
                }
            }
        } catch (Throwable ignored) {
            // virtual details always answer; defensive only
        }
        return outs;
    }

    private void scheduleReturn(IAEItemStack out, int due) {
        pendingKeys.add(out);
        pendingAmounts.add(out.getStackSize());
        pendingDue.add(due);
    }

    /** AE2 injectItems semantics: waitingFor first, then finalOutput, rest to inventory. */
    private void inject(IAEItemStack r, long n) {
        long matched = waitingFor.extract(r, n);
        if (matched < n) {
            // overproduction beyond anything awaited: lands in inventory anyway
            changeStamp++;
        }
        long incoming = matched;
        if (finalOutputKey != null && finalOutputKey.isSameType(r)) {
            long take = Math.min(incoming, Math.max(0, finalAmount - delivered));
            delivered += take;
            changeStamp++;
        }
        if (incoming > 0) {
            inventory.add(r, incoming); // intermediate products feed other patterns
        }
    }

    private Verdict classifyCompletion(int step) {
        List<String> notes = new ArrayList<>();
        if (!waitingFor.isEmpty()) {
            boolean allEmitable = true;
            for (int i = 0; i < waitingFor.keys().size(); i++) {
                if (waitingFor.amountAt(i) <= 0) {
                    continue; // zeroed entries are not residue
                }
                if (emitable.amountOf(waitingFor.keys().get(i)) <= 0) {
                    allEmitable = false;
                    break;
                }
            }
            if (allEmitable) {
                notes.add("S1 note: delivery complete but the CPU stays busy forever — "
                        + "waitingFor holds only emitable entries nothing will return");
            } else {
                notes.add("note: delivery complete with non-emitable waitingFor residue");
            }
        }
        return Verdict.complete(delivered, step, notes);
    }

    private Verdict classifyStall(int step) {
        List<String> evidence = new ArrayList<>();
        boolean tasksRemain = false;
        for (Map.Entry<ICraftingPatternDetails, Long> e : tasks.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                tasksRemain = true;
                for (IAEItemStack in : e.getKey().getCondensedInputs()) {
                    if (in == null || in.getStackSize() <= 0) {
                        continue;
                    }
                    long available = inventory.amountOf(in) + pendingAmountOf(in);
                    if (available < in.getStackSize()) {
                        evidence.add("S2 blocked input: " + in + " need=" + in.getStackSize()
                                + " available=" + available);
                    }
                }
            }
        }
        if (tasksRemain && !evidence.isEmpty()) {
            return Verdict.stall("S2", evidence, delivered, step);
        }
        // nothing pushable: what does waitingFor hold?
        boolean anyEmitable = false;
        boolean anyNonEmitable = false;
        for (int i = 0; i < waitingFor.keys().size(); i++) {
            IAEItemStack k = waitingFor.keys().get(i);
            if (waitingFor.amountAt(i) <= 0) {
                continue;
            }
            if (emitable.amountOf(k) > 0) {
                anyEmitable = true;
            } else {
                anyNonEmitable = true;
            }
        }
        if (anyEmitable && !anyNonEmitable) {
            evidence.add("waitingFor holds only emitable entries; nothing will inject them back");
            return Verdict.stall("S1", evidence, delivered, step);
        }
        evidence.add("tasks=" + (tasksRemain ? "blocked" : "empty")
                + " delivered=" + delivered + "/" + finalAmount);
        return Verdict.stall("S4", evidence, delivered, step);
    }

    private long pendingAmountOf(IAEItemStack k) {
        long s = 0;
        for (int i = 0; i < pendingKeys.size(); i++) {
            if (pendingKeys.get(i).isSameType(k)) {
                s += pendingAmounts.get(i);
            }
        }
        return s;
    }

    private static long satAdd(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }
}
