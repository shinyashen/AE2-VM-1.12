package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Constructs the plan's task order instead of searching for one.
 *
 * <p>The faithful CPU consumes its per-tick budget strictly in task order
 * (each pass fires the tasks in plan order, each repeatedly while its inputs
 * last, returns landing next pass), so the plan's patternTimes sequence IS
 * the execution order. The order built here is correct by construction for
 * exact plans:
 *
 * <ul>
 *   <li><b>Why a topological order of the dependency condensation cannot
 *       deadlock.</b> A plan stalls only when some pass fires nothing while
 *       tasks remain: a set U of tasks each waiting for a key whose
 *       remaining production lies inside U. Follow the waits — every scarce
 *       key's producers either still have crafts (they would fire: progress,
 *       contradiction) or are themselves in U; an exhausted producer cannot
 *       be the cause in an exact plan, where initial inventory + production
 *       = consumption + delivery by construction (shortfalls there are the
 *       missing/simulation path and never execute). So each member of U
 *       waits on another member: the wait edges close a cycle, and a wait
 *       cycle is a cycle in the pattern dependency graph. The condensation
 *       is acyclic, hence no stuck set can span units and none can exist
 *       inside a unit ordered DAG-wise.</li>
 *   <li><b>Why the greedy draw needs no interleaving.</b> fire-while-canCraft
 *       lets an earlier consumer drain shared production within a pass —
 *       but with exact counts the drained units went to exactly their
 *       entitled tasks, so later consumers merely wait one more pass for the
 *       next production round. Delay, never deadlock: the fibonacci shape
 *       (deep chain, shared intermediates) completes under ANY order of its
 *       cycle-free graph. The demand-ratio interleaving once suspected is
 *       therefore unnecessary.</li>
 *   <li><b>Why SCC units carry the probed order.</b> Inside a dependency
 *       cycle the wait-cycle argument no longer protects: the bootstrap is
 *       order-sensitive and zero-slack shapes starve under some rotations.
 *       That is exactly what {@code RingSolver.startupFloors} probes — the
 *       returned firing order is validated together with the priming floor,
 *       so a folded ring keeps that order as its unit-internal sequence
 *       (the cycle is "cut" at the priming edges the probe forced).</li>
 * </ul>
 *
 * <p>Determinism: nodes, units and emission are all sequenced by insertion
 * order of {@code patternTimes} (a LinkedHashMap); adjacency is built as
 * sets, so hash iteration order of the per-pattern input/output maps cannot
 * leak into the result.
 */
final class TaskOrdering {

    private TaskOrdering() {
    }

    /**
     * Reorders {@code patternTimes} into the constructed task order. Counts
     * are preserved untouched. {@code ringTaskOrder} is the probed firing
     * order of folded rings (may be empty or partial — for unfolded cycles
     * the insertion order stands and the replica assertion adjudicates).
     */
    static LinkedHashMap<ICraftingPatternDetails, Long> construct(
            Map<ICraftingPatternDetails, Long> patternTimes,
            List<ICraftingPatternDetails> ringTaskOrder,
            Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> inputsOf,
            Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> outputsOf) {
        LinkedHashMap<ICraftingPatternDetails, Long> out = new LinkedHashMap<>();
        if (patternTimes.size() <= 1) {
            out.putAll(patternTimes);
            return out;
        }
        List<ICraftingPatternDetails> nodes = new ArrayList<>(patternTimes.keySet());
        int n = nodes.size();

        // producer -> consumer edges by shared keys, as SETS (a pattern
        // consuming several of a producer's keys yields ONE edge; self-loops
        // are dropped — a pattern re-consuming its own output circulates it
        // internally, the recursion/catalyst families, and imposes no
        // ordering constraint on others).
        Map<IAEItemStack, Set<Integer>> producersOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (IAEItemStack k : outputsOf.apply(nodes.get(i)).keySet()) {
                producersOf.computeIfAbsent(k, x -> new HashSet<>()).add(i);
            }
        }
        List<Set<Integer>> consumersOf = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            consumersOf.add(new HashSet<>());
        }
        for (int c = 0; c < n; c++) {
            for (IAEItemStack k : inputsOf.apply(nodes.get(c)).keySet()) {
                Set<Integer> ps = producersOf.get(k);
                if (ps == null) {
                    continue;
                }
                for (int p : ps) {
                    if (p != c) {
                        consumersOf.get(p).add(c);
                    }
                }
            }
        }

        // SCCs over the dependency graph (iterative Tarjan).
        int[] comp = stronglyConnectedComponents(consumersOf);

        // Condensation in-degrees, then emit units by repeatedly taking the
        // ready unit whose smallest member index is lowest — insertion order
        // breaks ties, so the result is fully deterministic.
        Map<Integer, Set<Integer>> unitDeps = new HashMap<>();
        for (int p = 0; p < n; p++) {
            for (int c : consumersOf.get(p)) {
                if (comp[p] != comp[c]) {
                    unitDeps.computeIfAbsent(comp[c], x -> new HashSet<>()).add(comp[p]);
                }
            }
        }
        Map<Integer, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            firstIndex.merge(comp[i], i, Math::min);
        }

        // unit-internal sequences: probed ring order where the fold supplied
        // one (probed patterns in probe rank, any stragglers after them in
        // insertion order), plain insertion order otherwise.
        Map<Integer, List<Integer>> unitMembers = new HashMap<>();
        for (int i = 0; i < n; i++) {
            unitMembers.computeIfAbsent(comp[i], x -> new ArrayList<>()).add(i);
        }
        Map<ICraftingPatternDetails, Integer> probeRank = new HashMap<>();
        for (int r = 0; r < ringTaskOrder.size(); r++) {
            probeRank.putIfAbsent(ringTaskOrder.get(r), r);
        }
        Map<Integer, List<Integer>> unitSequence = new HashMap<>();
        for (var e : unitMembers.entrySet()) {
            List<Integer> members = e.getValue();
            List<Integer> probed = new ArrayList<>();
            List<Integer> rest = new ArrayList<>();
            for (int m : members) {
                if (probeRank.containsKey(nodes.get(m))) {
                    probed.add(m);
                } else {
                    rest.add(m);
                }
            }
            probed.sort((a, b) -> Integer.compare(
                    probeRank.get(nodes.get(a)), probeRank.get(nodes.get(b))));
            probed.addAll(rest);
            unitSequence.put(e.getKey(), probed);
        }

        Set<Integer> emitted = new HashSet<>();
        List<Integer> unitIds = new ArrayList<>(unitMembers.keySet());
        while (emitted.size() < unitIds.size()) {
            int best = -1;
            for (int u : unitIds) {
                if (emitted.contains(u) || unitDeps.containsKey(u)
                        && !emitted.containsAll(unitDeps.get(u))) {
                    continue;
                }
                if (best < 0 || firstIndex.get(u) < firstIndex.get(best)) {
                    best = u;
                }
            }
            if (best < 0) {
                // Unreachable for a condensation (acyclic by construction);
                // an assertion guard against future edits breaking that.
                for (int u : unitIds) {
                    if (!emitted.contains(u)) {
                        best = u;
                        break;
                    }
                }
            }
            emitted.add(best);
            for (int m : unitSequence.get(best)) {
                ICraftingPatternDetails d = nodes.get(m);
                out.put(d, patternTimes.get(d));
            }
        }
        return out;
    }

    /**
     * Iterative Tarjan; returns the component id per node index. The DFS
     * frames carry their OWNING node (a frame's node stays on the SCC stack
     * after its frame exhausts, so the stack top is not the frame owner —
     * deriving v from the SCC stack re-judges exhausted frames and loses
     * every component).
     */
    private static int[] stronglyConnectedComponents(List<Set<Integer>> adj) {
        int n = adj.size();
        int[] index = new int[n];
        int[] low = new int[n];
        int[] comp = new int[n];
        boolean[] onStack = new boolean[n];
        java.util.Arrays.fill(index, -1);
        java.util.Arrays.fill(comp, -1);
        List<Integer> sccStack = new ArrayList<>();
        List<Integer> frameNode = new ArrayList<>();
        List<java.util.Iterator<Integer>> frameIter = new ArrayList<>();
        int counter = 0;
        int components = 0;
        for (int root = 0; root < n; root++) {
            if (index[root] >= 0) {
                continue;
            }
            index[root] = low[root] = counter++;
            sccStack.add(root);
            onStack[root] = true;
            frameNode.add(root);
            frameIter.add(adj.get(root).iterator());
            while (!frameIter.isEmpty()) {
                int v = frameNode.get(frameNode.size() - 1);
                java.util.Iterator<Integer> it = frameIter.get(frameIter.size() - 1);
                if (it.hasNext()) {
                    int w = it.next();
                    if (index[w] < 0) {
                        index[w] = low[w] = counter++;
                        sccStack.add(w);
                        onStack[w] = true;
                        frameNode.add(w);
                        frameIter.add(adj.get(w).iterator());
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    frameIter.remove(frameIter.size() - 1);
                    frameNode.remove(frameNode.size() - 1);
                    if (low[v] == index[v]) {
                        int c;
                        do {
                            c = sccStack.remove(sccStack.size() - 1);
                            onStack[c] = false;
                            comp[c] = components;
                        } while (c != v);
                        components++;
                    }
                    if (!frameIter.isEmpty()) {
                        int parent = frameNode.get(frameNode.size() - 1);
                        low[parent] = Math.min(low[parent], low[v]);
                    }
                }
            }
        }
        return comp;
    }
}
