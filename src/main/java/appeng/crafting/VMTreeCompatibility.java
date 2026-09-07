package appeng.crafting;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.AE2VM;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.vm.VMPlan;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds AE2CT's display tree directly from a completed VM plan. Used only by
 * the optional AE2CT packet mixin; never calls AE2's native recursive tree
 * construction. Ported from AE2-Quick-Calculation's proven implementation.
 */
public final class VMTreeCompatibility {
    private static final String NODE_CLASS =
            "github.kasuminova.ae2ctl.common.integration.ae2.data.LiteCraftTreeNode";
    private static final String PROC_CLASS =
            "github.kasuminova.ae2ctl.common.integration.ae2.data.LiteCraftTreeProc";
    private static final int MAX_DISPLAY_DEPTH = 1024;
    private static final int MAX_DISPLAY_NODES = 20000;

    private static volatile Constructor<?> nodeConstructor;
    private static volatile Constructor<?> procConstructor;
    private static volatile boolean constructorsResolved;

    private VMTreeCompatibility() {
    }

    /** Returns an AE2CT LiteCraftTreeNode as Object (null when AE2CT is absent). */
    public static Object createLiteTree(final CraftingTreeNode root) {
        if (!(root instanceof VMRootNode)) {
            return null;
        }
        final VMRootNode vmRoot = (VMRootNode) root;
        if (vmRoot.isNativeFallback()) {
            return null;
        }
        final VMPlan plan = vmRoot.getVMPlan();
        if (plan == null) {
            return null;
        }
        try {
            resolveConstructors();
            return new Builder(plan).build();
        } catch (final Throwable failure) {
            AE2VM.LOGGER.warn("[AE2-VM] Unable to build AE2CT tree from VM plan", failure);
            return null;
        }
    }

    private static void resolveConstructors() throws ReflectiveOperationException {
        if (constructorsResolved) {
            if (nodeConstructor == null || procConstructor == null) {
                throw new ClassNotFoundException("AE2CT tree model is unavailable");
            }
            return;
        }
        synchronized (VMTreeCompatibility.class) {
            if (!constructorsResolved) {
                try {
                    final Class<?> nodeClass = Class.forName(NODE_CLASS, true,
                            VMTreeCompatibility.class.getClassLoader());
                    final Class<?> procClass = Class.forName(PROC_CLASS, true,
                            VMTreeCompatibility.class.getClassLoader());
                    procConstructor = procClass.getConstructor(List.class);
                    nodeConstructor = nodeClass.getConstructor(
                            procClass, IAEItemStack.class, List.class, long.class);
                } finally {
                    constructorsResolved = true;
                }
            }
        }
        if (nodeConstructor == null || procConstructor == null) {
            throw new ClassNotFoundException("AE2CT tree model is unavailable");
        }
    }

    private static Object newProcess(final List<Object> inputs) throws ReflectiveOperationException {
        return procConstructor.newInstance(inputs);
    }

    private static Object newNode(final Object parent,
                                  final IAEItemStack output,
                                  final List<Object> inputs,
                                  final long missing) throws ReflectiveOperationException {
        return nodeConstructor.newInstance(parent, output, inputs, missing);
    }

    private static final class PatternUse {
        private final ICraftingPatternDetails pattern;
        private final IAEItemStack output;
        private long remainingCrafts;

        private PatternUse(final ICraftingPatternDetails pattern,
                           final IAEItemStack output,
                           final long remainingCrafts) {
            this.pattern = pattern;
            this.output = output;
            this.remainingCrafts = remainingCrafts;
        }

        private long outputAmount() {
            return Math.max(1L, output.getStackSize());
        }
    }

    private static final class Builder {
        private final VMPlan plan;
        private final IAEItemStack rootOutput;
        private final List<PatternUse> patterns = new ArrayList<PatternUse>();
        private final List<IAEItemStack> available = new ArrayList<IAEItemStack>();
        private final List<IAEItemStack> missing = new ArrayList<IAEItemStack>();
        private final Set<IAEItemStack> activeOutputs = new HashSet<IAEItemStack>();
        private int nodeCount;

        private Builder(final VMPlan plan) {
            this.plan = plan;
            this.rootOutput = plan.getOutputKey().copy().setStackSize(
                    Math.max(1L, plan.getDeliverAmount()));
            for (final Map.Entry<ICraftingPatternDetails, Long> entry
                    : plan.getPatternTimes().entrySet()) {
                final ICraftingPatternDetails pattern = entry.getKey();
                final Long craftCount = entry.getValue();
                final IAEItemStack output = primaryOutput(pattern);
                if (pattern == null || output == null || craftCount == null
                        || craftCount <= 0L || output.getStackSize() <= 0L) {
                    continue;
                }
                patterns.add(new PatternUse(pattern, output.copy(), craftCount));
            }
            for (final var item : plan.getUsedItems().entrySet()) {
                if (item.getValue() > 0L) {
                    available.add(sized(item.getKey(), item.getValue()));
                }
            }
            for (final var item : plan.getMissingItems().entrySet()) {
                if (item.getValue() > 0L) {
                    missing.add(sized(item.getKey(), item.getValue()));
                }
            }
        }

        private Object build() throws ReflectiveOperationException {
            return buildNode(rootOutput, null, 0);
        }

        private Object buildNode(final IAEItemStack requested,
                                 final Object parent,
                                 final int depth) throws ReflectiveOperationException {
            final long requestedAmount = Math.max(1L, requested.getStackSize());
            final IAEItemStack output = sized(requested, requestedAmount);
            final List<Object> processes = new ArrayList<Object>();
            long remaining = requestedAmount;

            remaining -= takeAvailable(output, remaining);
            while (remaining > 0L && depth <= MAX_DISPLAY_DEPTH && nodeCount < MAX_DISPLAY_NODES) {
                final PatternUse pattern = findPattern(output);
                if (pattern == null) {
                    break;
                }
                final long wantedCrafts = divideCeil(remaining, pattern.outputAmount());
                final long crafts = Math.min(pattern.remainingCrafts, wantedCrafts);
                if (crafts <= 0L) {
                    break;
                }
                pattern.remainingCrafts -= crafts;
                nodeCount++;

                final IAEItemStack activeKey = typeKey(pattern.output);
                activeOutputs.add(activeKey);
                try {
                    final List<Object> inputs = new ArrayList<Object>();
                    final Object process = newProcess(inputs);
                    addInputs(inputs, process, pattern.pattern, crafts, depth + 1);
                    processes.add(process);
                } finally {
                    activeOutputs.remove(activeKey);
                }

                final long produced = multiply(pattern.outputAmount(), crafts);
                remaining = produced >= remaining ? 0L : remaining - produced;
            }

            final long missingAmount = takeMissing(output, remaining);
            return newNode(parent, output, processes, missingAmount);
        }

        private void addInputs(final List<Object> destination,
                               final Object parent,
                               final ICraftingPatternDetails pattern,
                               final long crafts,
                               final int depth) throws ReflectiveOperationException {
            final IAEItemStack[] inputs = pattern.getCondensedInputs();
            if (inputs == null) {
                return;
            }
            for (final IAEItemStack input : inputs) {
                if (input == null || input.getStackSize() <= 0L) {
                    continue;
                }
                final long amount = multiply(input.getStackSize(), crafts);
                destination.add(buildNode(sized(input, amount), parent, depth));
            }
        }

        private PatternUse findPattern(final IAEItemStack requested) {
            for (final PatternUse pattern : patterns) {
                if (pattern.remainingCrafts > 0L
                        && sameType(pattern.output, requested)
                        && !containsKey(activeOutputs, typeKey(pattern.output))) {
                    return pattern;
                }
            }
            return null;
        }

        private long takeAvailable(final IAEItemStack requested, final long amount) {
            if (amount <= 0L) return 0L;
            long remaining = amount;
            for (final IAEItemStack item : available) {
                if (item == null || item.getStackSize() <= 0L || !sameType(item, requested)) continue;
                final long taken = Math.min(remaining, item.getStackSize());
                item.setStackSize(item.getStackSize() - taken);
                remaining -= taken;
                if (remaining == 0L) break;
            }
            return amount - remaining;
        }

        private long takeMissing(final IAEItemStack requested, final long amount) {
            if (amount <= 0L) return 0L;
            long remaining = amount;
            for (final IAEItemStack item : missing) {
                if (item == null || item.getStackSize() <= 0L || !sameType(item, requested)) continue;
                final long taken = Math.min(remaining, item.getStackSize());
                item.setStackSize(item.getStackSize() - taken);
                remaining -= taken;
                if (remaining == 0L) break;
            }
            return amount - remaining;
        }
    }

    private static boolean containsKey(Set<IAEItemStack> set, IAEItemStack key) {
        for (IAEItemStack s : set) {
            if (s.isSameType(key)) return true;
        }
        return false;
    }

    private static IAEItemStack primaryOutput(final ICraftingPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        try {
            final IAEItemStack output = PatternCompat.getPrimaryOutput(pattern);
            if (output != null && output.getStackSize() > 0L) {
                return output;
            }
        } catch (final Throwable ignored) {
        }
        try {
            final IAEItemStack[] outputs = pattern.getCondensedOutputs();
            return outputs == null || outputs.length == 0 ? null : outputs[0];
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static boolean sameType(final IAEItemStack left, final IAEItemStack right) {
        return left != null && right != null && left.isSameType(right);
    }

    private static IAEItemStack typeKey(final IAEItemStack source) {
        final IAEItemStack key = source.copy();
        key.reset();
        return key;
    }

    private static long divideCeil(final long value, final long divisor) {
        if (value <= 0L) return 0L;
        if (divisor <= 1L) return value;
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    private static long multiply(final long left, final long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static IAEItemStack sized(final IAEItemStack source, final long amount) {
        final IAEItemStack copy = source.copy();
        copy.setStackSize(Math.max(1L, amount));
        return copy;
    }
}
