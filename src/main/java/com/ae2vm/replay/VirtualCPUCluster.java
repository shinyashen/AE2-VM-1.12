package com.ae2vm.replay;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.VMPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ae2vm.Log;
import java.util.Collection;
import java.util.Collections;

/**
 * Offline replica of AE2UEL's crafting-CPU state machine,
 * built operation-by-operation against the live source
 * ({@code CraftingCPUCluster}):
 *
 * <ul>
 *   <li><b>Inputs are charged per push, exactly.</b> There is no input-class
 *       ledger: {@code executeCrafting} :614 calls {@code canCraft} and then
 *       :694 extracts each condensed input from {@code inventory} with
 *       {@code extractItems} — full per-craft amount, exact key. The fuzzy
 *       paths (:656 canSubstitute / :672 damageable fallback) live in the
 *       <em>craftable</em> branch only; processing patterns never substitute
 *       at the CPU.</li>
 *   <li><b>Stock matching axes.</b> {@code isSameType} on real
 *       {@code AEItemStack}s compares SharedStack equality =
 *       {@code ItemStack.areItemStacksEqual} — the FULL identity — so the
 *       Stock's exact-key reads are findPrecise-faithful (:445-453). The
 *       craftable slot pool is matched at ITEM level instead
 *       ({@code findFuzzy(IGNORE_ALL)} :466/:499 matches every damage/NBT
 *       variant of an accepted item) — a per-identity sum here once
 *       UNDER-counted variant stock and made the gate falsely reject plans
 *       the real CPU completes (the false-fallback source, §5.10).</li>
 *   <li><b>injectItems is waitingFor-gated.</b> :218 finds the entry
 *       precisely; an item the CPU is not waiting for is REFUSED whole
 *       (:253 falls through to {@code return input}). Items matching
 *       {@code finalOutput} are delivered (:265 dec finalOutput) and never
 *       enter {@code inventory}; everything else awaited goes to inventory
 *       (:284/:314). Amounts beyond the waitingFor entry are rejected.</li>
 *   <li><b>Consequence (a faithful-simulation finding):</b> a plan whose final output is
 *       also an intermediate its own schedule re-consumes cannot execute —
 *       the produced units are delivered, not circulated, so the consumer
 *       starves on inventory once the pre-extracted seed runs dry. The same
 *       holds for plans whose ring keys were net-stripped without a startup
 *       seed in usedItems (t=0 deadlock) and for idle-ring plans (nothing
 *       scheduled, nothing arrives — S4). These are faithful predictions of
 *       live behaviour, not simulator bugs.</li>
 * </ul>
 *
 * <p>Patterns are pushed to VIRTUAL providers: {@code instant} returns
 * outputs the step after the push (real providers never answer within the
 * same tick), {@code lag=k} k steps later (IO-rhythm checking).
 *
 * <p>Verdict semantics: COMPLETE when the requested
 * amount has been delivered (a CPU whose waitingFor never drains stays busy
 * FOREVER — AE2 has no stall detection, {@code waiting} resets every tick);
 * stalls classified:
 *
 * <ul>
 *   <li><b>S1</b> — waitingFor holds emitable entries nothing will ever
 *       return (delivery done or impossible, CPU busy indefinitely);</li>
 *   <li><b>S2</b> — a scheduled pattern's input lacks inventory and no
 *       pending return will cover it (silent scheduling starvation);</li>
 *   <li><b>S4</b> — no task can run and no awaited return is outstanding
 *       while delivery is short.</li>
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

        /** Returns how much was actually taken (exact key only). */
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

        /**
         * {@code findFuzzy(key, IGNORE_ALL)} pool: the total stock of every
         * entry sharing the key's own ITEM or any alternate's ITEM (AE2UEL
         * canCraft :466/:499 matches by Item, any damage/NBT; the slot list
         * always carries the original input — PatternHelper :289). Entries
         * count once even when several alternates share an Item.
         */
        public long amountOfItemFamily(IAEItemStack k, Collection<IAEItemStack> alternates) {
            long s = 0;
            for (int i = 0; i < keys.size(); i++) {
                if (amounts.get(i) > 0 && acceptsItem(keys.get(i), k, alternates)) {
                    s = satAdd(s, amounts.get(i));
                }
            }
            return s;
        }

        /**
         * Drains up to {@code n} from the IGNORE_ALL pool: the exact identity
         * first (canCraft's findPrecise precedes its findFuzzy loop), then
         * every other accepted-item entry in insertion order.
         */
        public long extractItemFamily(IAEItemStack k, Collection<IAEItemStack> alternates, long n) {
            long taken = extract(k, n);
            for (int i = 0; i < keys.size() && taken < n; i++) {
                if (amounts.get(i) <= 0 || keys.get(i).isSameType(k)) {
                    continue; // the exact tier is already drained
                }
                if (!acceptsItem(keys.get(i), k, alternates)) {
                    continue;
                }
                long take = Math.min(amounts.get(i), n - taken);
                amounts.set(i, amounts.get(i) - take);
                taken += take;
            }
            return taken;
        }

        /** Item-level acceptance for the IGNORE_ALL pool (key's own item included). */
        private static boolean acceptsItem(IAEItemStack entry, IAEItemStack k,
                                           Collection<IAEItemStack> alternates) {
            if (entry.getItem() == k.getItem()) {
                return true;
            }
            for (IAEItemStack alt : alternates) {
                if (alt != null && entry.getItem() == alt.getItem()) {
                    return true;
                }
            }
            return false;
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

    /** Simulation verdict. */
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

    /**
     * Slot-alternate hook — the offline counterpart of AE2's craftable-branch
     * slot filling (:656 {@code getSubstituteInputs} + :664 {@code findFuzzy}
     * + :681 {@code isValidItemForSlot}). Only consulted for
     * {@code details.isCraftable()} patterns; processing patterns extract
     * their exact condensed keys (:694) and never substitute. Null hook =
     * exact keys only.
     */
    public interface SlotAlternates {
        Collection<IAEItemStack> alternates(ICraftingPatternDetails d, int slot);
    }

    private final Map<ICraftingPatternDetails, Long> tasks = new LinkedHashMap<>();
    private final Stock inventory = new Stock();
    private final Stock waitingFor = new Stock();
    private final Stock emitable = new Stock();
    private final List<IAEItemStack> pendingKeys = new ArrayList<>();
    private final List<Long> pendingAmounts = new ArrayList<>();
    private final List<Integer> pendingDue = new ArrayList<>();
    private final SlotAlternates alternates;
    private final IAEItemStack finalOutputKey;
    private final long finalAmount;
    private long delivered;
    /** Returns refused because waitingFor held no entry (or the job completed). */
    private long refused;
    private long changeStamp;
    private boolean jobComplete;

    public VirtualCPUCluster(VMPlan plan, IAEItemStack what, long amount) {
        this(plan, what, amount, null);
    }

    public VirtualCPUCluster(VMPlan plan, IAEItemStack what, long amount, SlotAlternates alternates) {
        this.alternates = alternates;
        for (Map.Entry<ICraftingPatternDetails, Long> e : plan.getPatternTimes().entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                tasks.put(e.getKey(), e.getValue()); // addCrafting
            }
        }
        for (Map.Entry<IAEItemStack, Long> e : plan.getUsedItems().entrySet()) {
            inventory.add(e.getKey(), e.getValue()); // setJob phase-2: extract + addStorage
        }
        // plan.getEmittedItems() is NOT seeded into waitingFor/emitable: the
        // real CPU's waitingFor is populated per dispatch (:730-734) and the
        // VM no longer bridges emitted items to addEmitable (they are all
        // task-derived — pre-registering doubled them and left a phantom
        // residue the real returns could never satisfy).
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

    /** When true, run() prints a per-step state snapshot (diagnostics only). */
    public static boolean TRACE;
    private int traceLines;

    private void trace(int step, String tag) {
        if (!TRACE || traceLines > 40) {
            return;
        }
        traceLines++;
        StringBuilder sb = new StringBuilder("  step ").append(step).append(' ').append(tag)
                .append(" delivered=").append(delivered).append(" tasks={");
        for (Map.Entry<ICraftingPatternDetails, Long> e : tasks.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                sb.append(outputsOf(e.getKey())).append('x').append(e.getValue()).append(',');
            }
        }
        sb.append("} inv={");
        for (int i = 0; i < inventory.keys().size(); i++) {
            if (inventory.amountAt(i) > 0) {
                sb.append(inventory.keys().get(i)).append('x').append(inventory.amountAt(i)).append(',');
            }
        }
        sb.append("} waiting={");
        for (int i = 0; i < waitingFor.keys().size(); i++) {
            if (waitingFor.amountAt(i) > 0) {
                sb.append(waitingFor.keys().get(i)).append('x').append(waitingFor.amountAt(i)).append(',');
            }
        }
        sb.append("} pending=").append(pendingKeys.size()).append(" refused=").append(refused);
        Log.LOG.debug("{}", sb);
    }

    public Verdict run(int maxSteps, int providerLag) {
        // idleness within the returns' flight time is not a stall: the CPU
        // waits for in-flight provider returns before judging
        int stallAfter = Math.max(2, providerLag + 1);
        int noProgress = 0;
        int step = 0;
        for (; step < maxSteps; step++) {
            if (delivered >= finalAmount) {
                trace(step, "complete?");
                return classifyCompletion(step);
            }
            long before = changeStamp;
            trace(step, "pre");
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

        // push loop: executeCrafting :602 iterates tasks; canCraft :614;
        // extraction :694 (processing, exact, full per-craft amount);
        // pushPattern :726; waitingFor registration :730-734.
        // Scheduling shape: AE2UEL consumes its per-tick operation budget
        // strictly IN TASK ORDER (the first craftable task absorbs the
        // budget; later tasks only see leftovers), so the faithful limit is
        // priority scheduling — each pass fires the tasks in map order, each
        // repeatedly until its inputs run dry, with returns landing next
        // pass. WITHIN one pass the inventory only shrinks (returns land
        // next pass), so each task's stopping point is computable in one
        // shot — fires = min(remaining, min over slots floor(avail / need))
        // — and firing that batch is VERDICT-IDENTICAL to the per-craft
        // loop: same extraction preference, same waitingFor/pending totals
        // (addition is associative mod 2^64), same injection gating. It
        // turns O(total crafts) replays into O(tasks x passes): a
        // 3.5M-craft fibonacci plan replays in microseconds instead of ~1s.
        for (Map.Entry<ICraftingPatternDetails, Long> e : new LinkedHashMap<>(tasks).entrySet()) {
            long remaining = e.getValue();
            ICraftingPatternDetails d = e.getKey();
            long fires = firesBeforeInputsRunDry(d, remaining);
            if (fires <= 0) {
                continue;
            }
            extractInputs(d, fires);
            remaining -= fires;
            changeStamp++;
            int due = step + Math.max(1, lag); // pushPattern :726 — the provider returns next tick
            for (IAEItemStack out : outputsOf(d)) {
                // :730 — a successful push records EXPECTED outputs in waitingFor
                long amount = saturatedMultiply(fires, out.getStackSize());
                waitingFor.add(out, amount);
                scheduleReturn(out, amount, due);
            }
            tasks.put(d, remaining);
        }

        if (delivered >= finalAmount) {
            changeStamp++;
        }
    }

    /**
     * The craftable-branch slot-fill view. The injected hook wins; otherwise
     * the pattern's own {@code getSubstituteInputs} — the same source the
     * real CPU reads (PatternHelper :289: the user's grid stack first, then
     * the recipe ingredient's matching stacks). Processing patterns never
     * reach this: both call sites gate on {@code craftable} (slot
     * substitution is a crafting-pattern feature, PatternHelper :87).
     */
    private Collection<IAEItemStack> substitutesOf(ICraftingPatternDetails d, int slot) {
        if (alternates != null) {
            return alternates.alternates(d, slot);
        }
        try {
            final List<IAEItemStack> subs = d.getSubstituteInputs(slot);
            return subs == null ? Collections.emptyList() : subs;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * canCraft :444, generalized from a boolean to HOW MANY crafts fit.
     * Processing branch: exact SIMULATE extract of every condensed input —
     * a craft fits when every slot's available pool covers its per-craft
     * need, and within a pass nothing refills the inventory, so the per-
     * craft loop's stopping point is the min over slots of floor(available
     * / needed), capped by the remaining count. Stock matching is the FULL
     * identity (real {@code AEItemStack.isSameType} compares SharedStack
     * equality = {@code areItemStacksEqual}) — a findPrecise-faithful
     * reading of :445-453; no NBT/damage variant serves a processing slot.
     * Craftable branch (:454-516): a slot's pool is the IGNORE_ALL item
     * family — the input's own item plus every alternate's item, any
     * damage/NBT ({@code findFuzzy(substitute, IGNORE_ALL)}), the slot
     * list carrying the original (PatternHelper :289).
     * Inputs-less patterns answer "all of them".
     */
    private long firesBeforeInputsRunDry(ICraftingPatternDetails d, long remaining) {
        IAEItemStack[] inputs = d.getCondensedInputs();
        if (inputs == null) {
            return remaining;
        }
        boolean craftable;
        try {
            craftable = d.isCraftable();
        } catch (Throwable t) {
            craftable = false;
        }
        long fires = remaining;
        for (int slot = 0; slot < inputs.length && fires > 0; slot++) {
            IAEItemStack in = inputs[slot];
            if (in == null || in.getStackSize() <= 0) {
                continue;
            }
            long available = craftable
                    ? inventory.amountOfItemFamily(in, substitutesOf(d, slot))
                    : inventory.amountOf(in);
            long bySlot = available / in.getStackSize();
            if (bySlot < fires) {
                fires = bySlot;
            }
        }
        return fires;
    }

    /**
     * Extracts {@code fires} crafts' inputs: processing slots drain the
     * exact identity (:445-453); craftable slots drain the IGNORE_ALL item
     * family (:454-516) — exact identity first, then every other accepted
     * variant, the same preference the per-craft loop produced. fires x
     * need cannot overflow: {@link #firesBeforeInputsRunDry} bounded fires
     * by every slot's availability.
     */
    private void extractInputs(ICraftingPatternDetails d, long fires) {
        IAEItemStack[] inputs = d.getCondensedInputs();
        if (inputs == null) {
            return;
        }
        boolean craftable;
        try {
            craftable = d.isCraftable();
        } catch (Throwable t) {
            craftable = false;
        }
        for (int slot = 0; slot < inputs.length; slot++) {
            IAEItemStack in = inputs[slot];
            if (in == null || in.getStackSize() <= 0) {
                continue;
            }
            long left = fires * in.getStackSize();
            if (craftable) {
                left -= inventory.extractItemFamily(in, substitutesOf(d, slot), left);
            } else {
                left -= inventory.extract(in, left);
            }
        }
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

    /** Schedules a return of {@code amount} units to arrive at {@code due}. */
    private void scheduleReturn(IAEItemStack out, long amount, int due) {
        pendingKeys.add(out);
        pendingAmounts.add(amount);
        pendingDue.add(due);
    }

    /** fires x perCraft, saturated — amounts beyond Long.MAX_VALUE cap there. */
    private static long saturatedMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        long r = a * b;
        return r / b == a ? r : Long.MAX_VALUE;
    }

    /**
     * injectItems :210. waitingFor-gated: an entry is found precisely
     * (:218); nothing awaited is refused whole (:253). The awaited part is
     * delivered when it matches finalOutput (:265 — final output NEVER
     * enters inventory), otherwise it circulates in inventory (:314).
     * Amounts beyond the waitingFor entry are rejected (:287-317 return
     * the excess to the injector).
     */
    private void inject(IAEItemStack r, long n) {
        if (jobComplete) {
            refused += n; // :213 — completed CPUs refuse re-insertions
            return;
        }
        long awaited = waitingFor.amountOf(r);
        if (awaited <= 0) {
            refused += n;
            return;
        }
        long take = Math.min(n, awaited);
        waitingFor.extract(r, take);
        if (take < n) {
            refused += n - take;
        }
        if (finalOutputKey != null && finalOutputKey.isSameType(r)) {
            delivered += Math.min(take, Math.max(0, finalAmount - delivered));
            if (delivered >= finalAmount) {
                jobComplete = true; // completeJob :275
            }
        } else {
            inventory.add(r, take); // :314 — intermediates circulate
        }
        changeStamp++;
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
        if (refused > 0) {
            notes.add("refused=" + refused + " (returns the CPU was not waiting for)");
        }
        return Verdict.complete(delivered, step, notes);
    }

    private Verdict classifyStall(int step) {
        List<String> evidence = new ArrayList<>();
        boolean tasksRemain = false;
        for (Map.Entry<ICraftingPatternDetails, Long> e : tasks.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                tasksRemain = true;
                IAEItemStack[] inputs = e.getKey().getCondensedInputs();
                if (inputs == null) {
                    continue;
                }
                for (IAEItemStack in : inputs) {
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
        if (refused > 0) {
            evidence.add("refused=" + refused);
        }
        if (tasksRemain && !evidence.isEmpty() && anyBlocked(evidence)) {
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

    /** True when the evidence list actually contains a blocked-input line. */
    private static boolean anyBlocked(List<String> evidence) {
        for (String s : evidence) {
            if (s.startsWith("S2 blocked input:")) {
                return true;
            }
        }
        return false;
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
}
