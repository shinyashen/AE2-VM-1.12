package com.ae2vm.bench;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.vm.SimulationState;

import java.io.PrintStream;
import java.util.List;

/**
 * Diagnostic tool: logging delegate for {@link SimulationState}. Records every
 * state mutation (crafting / extraction / insertion / fuzzy lookup) with stable
 * item ids to a print stream, one line per event prefixed by a scenario tag —
 * the extraction-level view that rooted the former
 * {@code multi-dag/fibonacci/minimum} residual shortfall.
 *
 * <p>Not wired into any test by default (suite runs stay silent). To trace one
 * scenario, wrap the pass's simulation in {@code Ae2VmReferencePlanner}'s pass
 * lambda (see the comment at its {@code vm.execute} call site).
 */
public final class TraceSimulationState implements SimulationState {
    private final SimulationState delegate;
    private final PrintStream log;
    private final String tag;
    private int seq;

    public TraceSimulationState(SimulationState delegate, PrintStream log, String tag) {
        this.delegate = delegate;
        this.log = log;
        this.tag = tag;
    }

    private static String idOf(IAEItemStack key) {
        if (key instanceof BenchAEItemStack b) {
            return b.id + (b.damage != 0 ? "d" + b.damage : "");
        }
        return String.valueOf(key);
    }

    @Override
    public long extract(IAEItemStack key, long amount, boolean simulate) {
        long got = delegate.extract(key, amount, simulate);
        if (!simulate) {
            log.println((seq++) + " " + tag + " EXTRACT " + idOf(key)
                    + " req=" + amount + " got=" + got);
        }
        return got;
    }

    @Override
    public void insert(IAEItemStack key, long amount) {
        log.println((seq++) + " " + tag + " INSERT " + idOf(key) + " amount=" + amount);
        delegate.insert(key, amount);
    }

    @Override
    public List<IAEItemStack> findFuzzyFamily(IAEItemStack key) {
        List<IAEItemStack> out = delegate.findFuzzyFamily(key);
        log.println((seq++) + " " + tag + " FUZZYFAM " + idOf(key) + " -> " + out.size());
        return out;
    }

    @Override
    public void addCrafting(ICraftingPatternDetails pattern, long times) {
        String ins = "";
        try {
            for (IAEItemStack line : pattern.getCondensedInputs()) {
                ins += idOf(line) + ":" + line.getStackSize() + " ";
            }
        } catch (Throwable t) {
            ins = "?";
        }
        log.println((seq++) + " " + tag + " CRAFT in[" + ins.trim() + "] x" + times);
        delegate.addCrafting(pattern, times);
    }

    @Override
    public void addBytes(double amount) {
        delegate.addBytes(amount);
    }

    @Override
    public long getBytes() {
        return delegate.getBytes();
    }

    @Override
    public void ignore(IAEItemStack key) {
        delegate.ignore(key);
    }
}
