package appeng.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2vm.AE2VM;
import com.ae2vm.api.AE2VMCrafting;
import com.ae2vm.api.AE2VMCraftingRegistry;
import com.ae2vm.config.AE2VMConfig;
import com.ae2vm.trace.TraceRecorder;
import com.ae2vm.trace.TraceSegment;
import com.ae2vm.trace.TraceSessions;
import com.ae2vm.vm.VMPlan;
import net.minecraft.world.World;



/**
 * Root CraftingTreeNode replacement that runs the AE2-VM stack machine and
 * feeds the resulting plan into AE2's native job lifecycle. Falls back to the
 * native recursive tree whenever the VM cannot handle the request.
 *
 * Must live in appeng.crafting: the tree lifecycle methods are package-private.
 */
public final class VMRootNode extends CraftingTreeNode {
    private final ICraftingGrid craftingGrid;
    private final CraftingJob craftingJob;
    private final IAEItemStack requestedOutput;
    private final World world;
    private final IGrid grid;
    private final String debugTag;
    private boolean nativeFallback;
    private VMPlan plan;
    /** Armed-session recorder for this order; null when not recording. */
    private TraceRecorder traceRecorder;

    public VMRootNode(ICraftingGrid craftingGrid,
                      CraftingJob craftingJob,
                      IAEItemStack requestedOutput,
                      World world,
                      IActionSource source,
                      IGrid grid) {
        super(craftingGrid, craftingJob, requestedOutput, null, -1, 0);
        this.craftingGrid = craftingGrid;
        this.craftingJob = craftingJob;
        this.requestedOutput = requestedOutput.copy();
        this.world = world;
        this.grid = grid;
        this.debugTag = "job@" + Integer.toHexString(System.identityHashCode(craftingJob));
    }

    @Override
    IAEItemStack request(MECraftingInventory inventory, long amount, IActionSource source)
            throws CraftBranchFailure, InterruptedException {
        if ((traceRecorder == null || traceRecorder.isClosed())
                && !nativeFallback && AE2VMConfig.proxyEnabled && !isThirdPartySource(source)) {
            traceRecorder = TraceSessions.openFor(source, grid, requestedOutput, amount,
                    craftingJob.isSimulation());
        }
        if (nativeFallback || !AE2VMConfig.proxyEnabled || isThirdPartySource(source)) {
            return super.request(inventory, amount, source);
        }
        craftingJob.handlePausing();
        try {
            long start = System.nanoTime();
            plan = calculate(amount);
            if (plan == null) {
                // No root pattern: the native tree queries the same index and
                // would not find one either. This is EXPECTED while the grid's
                // pattern index is still rebuilding (e.g. seconds after server
                // boot, before recalculateCraftingPatterns has run) and for
                // keys with no pattern at all — fall back quietly instead of
                // dressing a designed hand-off up as a failure.
                nativeFallback = true;
                if (traceRecorder != null && !traceRecorder.isClosed()) {
                    traceRecorder.fallback("no-root-pattern",
                            "expected: native tree handles pattern-less keys");
                    traceRecorder.finish("fallback");
                }
                AE2VM.LOGGER.debug("[AE2-VM] job {}: no root pattern for {}; native crafting handles it",
                        debugTag, requestedOutput.getDefinition());
                return super.request(inventory, amount, source);
            }
            long us = (System.nanoTime() - start) / 1_000L;
            AE2VM.LOGGER.info("[AE2-VM] job {}: plan for {}x{} in {} us (missing={} patterns={})",
                    debugTag, amount, requestedOutput.getDefinition(),
                    us, plan.getMissingItems().size(), plan.getPatternTimes().size());
            if (plan.isSimulation() && !craftingJob.isSimulation()) {
                throw new CraftBranchFailure(requestedOutput, amount);
            }
            return requestedOutput.copy().setStackSize(amount);
        } catch (CraftBranchFailure failure) {
            // simulate-retry re-enters request(): keep an open session alive
            throw failure;
        } catch (Throwable failure) {
            nativeFallback = true;
            plan = null;
            if (traceRecorder != null && !traceRecorder.isClosed()) {
                // free-text discipline: exception messages embed real item names
                traceRecorder.fallback("vm-exception", failure.getClass().getName());
                traceRecorder.finish("fallback");
            }
            AE2VM.LOGGER.warn("[AE2-VM] VM calculation failed for {}, falling back to native crafting",
                    requestedOutput.getDefinition(), failure);
            return super.request(inventory, amount, source);
        }
    }

    boolean isNativeFallback() {
        return nativeFallback;
    }

    VMPlan getVMPlan() {
        return plan;
    }

    /**
     * Third-party machine sources (programmatic job submissions) that never
     * registered with {@link AE2VMCraftingRegistry} keep their
     * native crafting behaviour; player-driven requests from AE2's own
     * terminals are always VM-eligible.
     */
    private boolean isThirdPartySource(IActionSource source) {
        if (source == null || source.player().isPresent()) {
            return false;
        }
        return source.machine()
                .map(host -> AE2VMCraftingRegistry
                        .isUnregisteredThirdParty(host.getClass().getName()))
                .orElse(false);
    }

    /** Null when no root pattern exists — the caller falls back natively. */
    private VMPlan calculate(long amount) {
        TraceRecorder live = traceRecorder != null && !traceRecorder.isClosed() ? traceRecorder : null;
        return AE2VMCrafting.calculate(grid, world, requestedOutput, amount, live);
    }

    @Override
    void dive(CraftingJob job) {
        if (nativeFallback) {
            super.dive(job);
            return;
        }
        if (plan != null) {
            job.addBytes(plan.getBytes());
        }
    }

    @Override
    void setSimulate() {
        if (nativeFallback) {
            super.setSimulate();
            return;
        }
        plan = null;
    }

    @Override
    public void setJob(MECraftingInventory storage,
                       CraftingCPUCluster craftingCPUCluster,
                       IActionSource source) throws CraftBranchFailure {
        if (nativeFallback) {
            super.setJob(storage, craftingCPUCluster, source);
            return;
        }
        if (plan == null || plan.isSimulation()) {
            throw new CraftBranchFailure(requestedOutput, requestedOutput.getStackSize());
        }
        // Phase 1: validate the full extraction set.
        for (var e : plan.getUsedItems().entrySet()) {
            IAEItemStack request = e.getKey().copy();
            request.setStackSize(e.getValue());
            request.setCountRequestable(0L);
            request.setCraftable(false);
            IAEItemStack extracted = storage.extractItems(request, Actionable.SIMULATE, source);
            if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
                if (traceRecorder != null && !traceRecorder.isClosed()) {
                    traceRecorder.startExtract("simulate", false, e.getKey(), e.getValue());
                    traceRecorder.finish("extract-failed");
                }
                throw new CraftBranchFailure(request, request.getStackSize());
            }
        }
        // Phase 2: extract and hand to the CPU.
        for (var e : plan.getUsedItems().entrySet()) {
            IAEItemStack request = e.getKey().copy();
            request.setStackSize(e.getValue());
            request.setCountRequestable(0L);
            request.setCraftable(false);
            IAEItemStack extracted = storage.extractItems(request, Actionable.MODULATE, source);
            if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
                throw new CraftBranchFailure(request, request.getStackSize());
            }
            craftingCPUCluster.addStorage(extracted);
        }
        for (var e : plan.getEmittedItems().entrySet()) {
            IAEItemStack emitable = e.getKey().copy();
            emitable.setStackSize(e.getValue());
            emitable.setCountRequestable(0L);
            emitable.setCraftable(false);
            craftingCPUCluster.addEmitable(emitable);
        }
        for (var e : plan.getPatternTimes().entrySet()) {
            craftingCPUCluster.addCrafting(e.getKey(), e.getValue());
        }
        if (traceRecorder != null && !traceRecorder.isClosed()) {
            traceRecorder.startSummary(plan);
            traceRecorder.finish("started");
        }
        AE2VM.LOGGER.info("[AE2-VM DIAG-SETJOB] job {}: cpu got used={} emitted={} patterns={}",
                debugTag, plan.getUsedItems().size(), plan.getEmittedItems().size(),
                plan.getPatternTimes().size());
    }

    @Override
    void getPlan(IItemList<IAEItemStack> planList) {
        if (nativeFallback) {
            super.getPlan(planList);
            return;
        }
        if (plan == null) {
            return;
        }
        for (var e : plan.getMissingItems().entrySet()) {
            addPlanStorage(planList, e.getKey(), e.getValue());
        }
        for (var e : plan.getUsedItems().entrySet()) {
            addPlanStorage(planList, e.getKey(), e.getValue());
        }
        // Emitted surplus uses plan.add (native CraftingTreeNode.getPlan spawns
        // emitted the same way): addRequestable ACCUMULATES, so reporting the
        // ring's output alongside the scheduled patterns' outputs doubled the
        // craft counts (the live 2500-ingot report).
        for (var e : plan.getEmittedItems().entrySet()) {
            addPlanStorage(planList, e.getKey(), e.getValue());
        }
        for (var entry : plan.getPatternTimes().entrySet()) {
            long crafts = entry.getValue();
            IAEItemStack[] outputs;
            try {
                outputs = entry.getKey().getOutputs();
            } catch (Throwable t) {
                continue;
            }
            if (outputs == null) continue;
            for (IAEItemStack output : outputs) {
                if (output == null || output.getStackSize() <= 0L) continue;
                IAEItemStack requestable = output.copy();
                requestable.setCountRequestable(output.getStackSize() * crafts);
                planList.addRequestable(requestable);
            }
        }
    }

    private static void addPlanStorage(IItemList<IAEItemStack> plan, IAEItemStack key, long amount) {
        if (key == null || amount <= 0L) return;
        IAEItemStack stored = key.copy();
        stored.setStackSize(amount);
        stored.setCountRequestable(0L);
        stored.setCraftable(false);
        plan.add(stored);
    }
}
