package appeng.crafting;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.inv.ItemListIgnoreCrafting;
import com.ae2vm.AE2VM;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.config.AE2VMConfig;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.NetworkCraftingSandbox;
import com.ae2vm.vm.VMPlan;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

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
        if (nativeFallback || !AE2VMConfig.proxyEnabled) {
            return super.request(inventory, amount, source);
        }
        craftingJob.handlePausing();
        try {
            long start = System.nanoTime();
            plan = calculate(amount);
            long us = (System.nanoTime() - start) / 1_000L;
            AE2VM.LOGGER.info("[AE2-VM] job {}: plan for {}x{} in {} us (missing={} patterns={})",
                    debugTag, amount, requestedOutput.getDefinition(),
                    us, plan.getMissingItems().size(), plan.getPatternTimes().size());
            if (plan.isSimulation() && !craftingJob.isSimulation()) {
                throw new CraftBranchFailure(requestedOutput, amount);
            }
            return requestedOutput.copy().setStackSize(amount);
        } catch (CraftBranchFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            nativeFallback = true;
            plan = null;
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

    private VMPlan calculate(long amount) {
        ICraftingPatternDetails selected = findPattern();
        if (selected == null) {
            throw new IllegalStateException("No compilable pattern for " + requestedOutput.getDefinition());
        }
        PatternCompiler.compileIfAbsent(selected);
        CraftingBytecode bytecode = PatternCompiler.compileRequest(selected, amount);
        if (bytecode == null) {
            throw new IllegalStateException("Pattern not compilable: " + selected);
        }

        CraftingVM vm = VmHolder.vmFor(grid, world);
        NetworkCraftingSandbox sandbox = NetworkCraftingSandbox.snapshot(grid);
        IAEItemStack normalizedOutput = com.ae2vm.compat.AE2FCCompat.normalizeFluidItem(requestedOutput);
        sandbox.ignore(normalizedOutput != null ? normalizedOutput : requestedOutput);
        return vm.execute(bytecode, sandbox);
    }

    private ICraftingPatternDetails findPattern() {
        Set<ICraftingPatternDetails> candidates = new LinkedHashSet<>();
        IAEItemStack key = requestedOutput.copy();
        long amountHint = key.getStackSize();
        key.reset();
        candidates.addAll(craftingGrid.getCraftingFor(key, null, -1, world));
        if (com.ae2vm.compat.AE2FCCompat.isFluidFakeItem(key)) {
            IAEItemStack packet = com.ae2vm.compat.AE2FCCompat.packFluidPacket(key, amountHint);
            if (packet != null && !packet.isSameType(key)) {
                candidates.addAll(craftingGrid.getCraftingFor(packet, null, -1, world));
            }
        }
        for (ICraftingPatternDetails candidate : candidates) {
            IAEItemStack primary = PatternCompat.getPrimaryOutput(candidate);
            if (primary == null) continue;
            IAEItemStack normalized = com.ae2vm.compat.AE2FCCompat.normalizeFluidItem(primary);
            IAEItemStack target = key;
            if (normalized != null && normalized.isSameType(target)) {
                return candidate;
            }
            if (normalized == null && primary.isSameType(target)) {
                return candidate;
            }
        }
        return null;
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
        IItemStorageChannel channel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        // Phase 1: validate the full extraction set.
        for (var e : plan.getUsedItems().entrySet()) {
            IAEItemStack request = e.getKey().copy();
            request.setStackSize(e.getValue());
            request.setCountRequestable(0L);
            request.setCraftable(false);
            IAEItemStack extracted = storage.extractItems(request, Actionable.SIMULATE, source);
            if (extracted == null || extracted.getStackSize() != request.getStackSize()) {
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
        IItemStorageChannel channel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        for (var e : plan.getMissingItems().entrySet()) {
            addPlanStorage(planList, e.getKey(), e.getValue());
        }
        for (var e : plan.getUsedItems().entrySet()) {
            addPlanStorage(planList, e.getKey(), e.getValue());
        }
        for (var e : plan.getEmittedItems().entrySet()) {
            IAEItemStack requestable = e.getKey().copy();
            requestable.setStackSize(e.getValue());
            requestable.setCountRequestable(e.getValue());
            planList.addRequestable(requestable);
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

    /** Per-grid VM cache: the JIT bundle cache persists across requests. */
    private static final class VmHolder {
        private static final java.util.concurrent.ConcurrentHashMap<IGrid, CraftingVM> VMS =
                new java.util.concurrent.ConcurrentHashMap<>();

        static CraftingVM vmFor(IGrid grid, net.minecraft.world.World world) {
            return VMS.computeIfAbsent(grid, g -> new CraftingVM(g, key -> {
                // Resolver: the grid indexes patterns by output key; the first
                // hit is a producer of the requested key.
                ICraftingGrid cg = g.getCache(ICraftingGrid.class);
                if (cg == null) return null;
                for (ICraftingPatternDetails candidate : cg.getCraftingFor(key, null, -1, world)) {
                    return candidate;
                }
                return null;
            }));
        }
    }
}
