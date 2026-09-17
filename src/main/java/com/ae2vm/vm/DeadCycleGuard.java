package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

/**
 * Cycle-aware pattern pruning (1.12 port of the upstream GTL CYCLE-AWARE +
 * SEEDED-RING + DEFINITION-GRAPH work): tells whether resolving a candidate
 * pattern for {@code target} would close a DEAD ring — a strongly connected
 * component (size &gt; 1) of the recipe-definition exchange graph in which no
 * member holds network stock (a seed) and no pattern outside the component
 * produces a member.
 *
 * <p>A dead ring can never be entered from nothing: firing its patterns would
 * consume inputs the network can never obtain, so the resolver must not prefer
 * it over a healthy alternative. Seeded rings (any member in stock — a
 * dust↔ingot pair with some dust seeded is a legitimate production cycle that
 * terminates when the seed is consumed), externally-fed rings (the candidate
 * consumes something outside the ring, or an outside pattern produces into it)
 * and self-fed re-flow rings stay craftable; only genuinely dead rings are
 * pruned. The VM's CALL-time {@code resolvingKeys} guard remains as the
 * runtime backstop either way.
 *
 * <p>The graph spans the transitive dependency closure of {@code target} plus
 * the candidate's own edges — resolve() filters candidates one at a time, so
 * the lookup's producer set for {@code target} may already be filtered and the
 * candidate edge must be injected explicitly. Nodes are type-normalized
 * (isSameType-merged) key instances; edges go from each condensed input to
 * every output of a pattern. Pruning is best-effort and conservative: on any
 * malformed pattern, missing lookup, or an oversized closure the guard answers
 * "no dead ring" and defers to the runtime guard.
 */
public final class DeadCycleGuard {

    /**
     * Dependency-closure size above which the SCC analysis is skipped (the
     * runtime guard handles the cycle instead of risking an oversized graph —
     * and the recursion depth of Tarjan stays bounded by this).
     */
    private static final int MAX_DEPENDENCY_KEYS = 512;

    private DeadCycleGuard() {
    }

    /**
     * True when {@code candidate} (a producer of {@code target}) closes a dead
     * ring: the candidate directly consumes its own output (can never fire
     * without inventing items), or every one of its inputs lies inside an
     * unseeded, externally-unfed SCC that also contains {@code target}.
     */
    public static boolean wouldCloseDeadRing(
            Function<IAEItemStack, Collection<ICraftingPatternDetails>> patternLookup,
            ICraftingPatternDetails candidate,
            IAEItemStack target,
            Function<IAEItemStack, Long> stockLookup) {
        try {
            if (patternLookup == null || candidate == null || target == null) {
                return false;
            }
            List<IAEItemStack> inputs = safeLines(candidate.getCondensedInputs());
            // Direct self-edge: consume-own-output can never fire from nothing.
            for (IAEItemStack input : inputs) {
                if (input != null && input.isSameType(target)) {
                    return true;
                }
            }
            // Transitive dependency closure of target, seeded with the
            // candidate's own inputs (candidate-edge injection).
            List<IAEItemStack> keys = new ArrayList<>();
            if (!addTypeKey(keys, target)) {
                return false;
            }
            Deque<IAEItemStack> queue = new ArrayDeque<>(keys);
            for (IAEItemStack input : inputs) {
                if (addTypeKey(keys, input)) {
                    queue.add(input);
                }
            }
            while (!queue.isEmpty()) {
                IAEItemStack k = queue.poll();
                Collection<ICraftingPatternDetails> producers = patternLookup.apply(k);
                if (producers == null) {
                    continue;
                }
                for (ICraftingPatternDetails p : producers) {
                    if (p == null) {
                        continue;
                    }
                    for (IAEItemStack input : safeLines(p.getCondensedInputs())) {
                        if (input != null && addTypeKey(keys, input)) {
                            queue.add(input);
                        }
                    }
                }
                if (keys.size() > MAX_DEPENDENCY_KEYS) {
                    return false; // oversized closure: defer to the runtime guard
                }
            }

            Map<Integer, Set<Integer>> graph = buildGraph(keys, patternLookup, inputs, outputs(candidate));
            List<Set<Integer>> dead = deadComponents(graph, keys.size(), stockLookup, keys,
                    patternLookup);
            int targetNode = typeIndexOf(keys, target);
            if (targetNode < 0) {
                return false;
            }
            boolean inDead = false;
            for (Set<Integer> scc : dead) {
                if (scc.contains(targetNode)) {
                    inDead = true;
                    break;
                }
            }
            if (!inDead) {
                return false;
            }
            // Ring-internal production only when EVERY input is inside the dead
            // ring; an external input means the ring is fed from outside and
            // must stay craftable. (An input-free producer enters the ring from
            // nothing, so it too is kept.)
            if (inputs.isEmpty()) {
                return false;
            }
            for (IAEItemStack input : inputs) {
                if (!inAnyDeadComponent(dead, typeIndexOf(keys, input))) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when {@code node} is a member of a dead component. */
    private static boolean inAnyDeadComponent(List<Set<Integer>> dead, int node) {
        if (node < 0) {
            return false;
        }
        for (Set<Integer> scc : dead) {
            if (scc.contains(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filters {@code candidates} down to those that would NOT close a dead ring,
     * preserving order. Returns the candidates unchanged when nothing is pruned
     * — including when EVERY candidate is ring-prone (the runtime guard handles
     * the cycle instead of leaving no choice at all).
     */
    @SuppressWarnings("unchecked")
    public static List<ICraftingPatternDetails> pruneDeadRings(
            Function<IAEItemStack, Collection<ICraftingPatternDetails>> patternLookup,
            IAEItemStack target,
            Collection<ICraftingPatternDetails> candidates,
            Function<IAEItemStack, Long> stockLookup) {
        List<ICraftingPatternDetails> list = candidates instanceof List
                ? (List<ICraftingPatternDetails>) candidates
                : new ArrayList<>(candidates);
        if (list.size() <= 1) {
            // A single candidate has no alternative to prefer: pruning it could
            // only remove the last choice, and the runtime guard handles the
            // cycle. Skip the whole graph analysis (the common no-contention
            // case — one pattern per key — pays nothing).
            return list;
        }
        List<ICraftingPatternDetails> filtered = null;
        for (int i = 0; i < list.size(); i++) {
            ICraftingPatternDetails p = list.get(i);
            boolean prune = p != null
                    && wouldCloseDeadRing(patternLookup, p, target, stockLookup);
            if (prune) {
                if (filtered == null) {
                    filtered = new ArrayList<>(list.subList(0, i));
                }
            } else if (filtered != null) {
                filtered.add(p);
            }
        }
        return filtered != null && !filtered.isEmpty() ? filtered : list;
    }

    // ------------------------------------------------------------------
    // Definition graph + dead-component analysis
    // ------------------------------------------------------------------

    private static List<IAEItemStack> outputs(ICraftingPatternDetails p) {
        return safeLines(p.getOutputs());
    }

    /**
     * Exchange graph over the normalized key list: one edge per (condensed
     * input, output) pair of every pattern reachable from {@code keys}, plus
     * the candidate's own edges (its inputs → its outputs, including when the
     * lookup no longer lists it as a producer of the target).
     */
    private static Map<Integer, Set<Integer>> buildGraph(List<IAEItemStack> keys,
                                                         Function<IAEItemStack, Collection<ICraftingPatternDetails>> patternLookup,
                                                         List<IAEItemStack> candidateInputs,
                                                         List<IAEItemStack> candidateOutputs) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        addEdges(graph, keys, candidateInputs, candidateOutputs);
        for (IAEItemStack k : keys) {
            Collection<ICraftingPatternDetails> producers;
            try {
                producers = patternLookup.apply(k);
            } catch (Throwable ignored) {
                continue;
            }
            if (producers == null) {
                continue;
            }
            for (ICraftingPatternDetails p : producers) {
                if (p == null) {
                    continue;
                }
                addEdges(graph, keys, safeLines(p.getCondensedInputs()), safeLines(p.getOutputs()));
            }
        }
        return graph;
    }

    private static void addEdges(Map<Integer, Set<Integer>> graph, List<IAEItemStack> keys,
                                 List<IAEItemStack> inputs, List<IAEItemStack> outputs) {
        for (IAEItemStack in : inputs) {
            if (in == null) {
                continue;
            }
            int from = typeIndexOf(keys, in);
            if (from < 0) {
                continue;
            }
            for (IAEItemStack out : outputs) {
                if (out == null) {
                    continue;
                }
                int to = typeIndexOf(keys, out);
                if (to >= 0) {
                    graph.computeIfAbsent(from, x -> new HashSet<>()).add(to);
                }
            }
        }
    }

    /**
     * Tarjan SCC (recursive — depth bounded by {@link #MAX_DEPENDENCY_KEYS})
     * followed by the deadness filter: a component is DEAD when it has more
     * than one member, no member holds stock, and no OUTSIDE KEY's own producer
     * patterns output a member. A target sibling candidate producing into the
     * ring does NOT count as feeding — the siblings are exactly what pruning
     * is choosing between, and counting them would keep every ring alive.
     */
    private static List<Set<Integer>> deadComponents(Map<Integer, Set<Integer>> graph,
                                                     int nodeCount,
                                                     Function<IAEItemStack, Long> stockLookup,
                                                     List<IAEItemStack> nodes,
                                                     Function<IAEItemStack, Collection<ICraftingPatternDetails>> patternLookup) {
        List<Set<Integer>> dead = new ArrayList<>();
        if (graph.isEmpty()) {
            return dead;
        }
        int[] index = new int[nodeCount];
        int[] low = new int[nodeCount];
        Arrays.fill(index, -1);
        int[] counter = {0};
        List<Integer> stack = new ArrayList<>();
        boolean[] onStack = new boolean[nodeCount];
        for (int root = 0; root < nodeCount; root++) {
            if (index[root] < 0 && graph.containsKey(root)) {
                tarjan(root, graph, index, low, counter, stack, onStack, dead);
            }
        }
        // Deadness filter: keep only components that are multi-member, unseeded
        // and not fed from outside.
        Iterator<Set<Integer>> it = dead.iterator();
        while (it.hasNext()) {
            Set<Integer> scc = it.next();
            if (scc.size() <= 1) {
                it.remove(); // no multi-node ring; self-loops handled up front
                continue;
            }
            boolean seeded = false;
            for (int m : scc) {
                Long stock = safeStock(stockLookup, nodes.get(m));
                if (stock != null && stock > 0) {
                    seeded = true;
                    break;
                }
            }
            if (seeded) {
                it.remove();
                continue;
            }
            boolean fed = false;
            for (int k = 0; k < nodes.size(); k++) {
                if (scc.contains(k)) {
                    continue;
                }
                Collection<ICraftingPatternDetails> producers;
                try {
                    producers = patternLookup.apply(nodes.get(k));
                } catch (Throwable ignored) {
                    continue;
                }
                if (producers == null) {
                    continue;
                }
                for (ICraftingPatternDetails p : producers) {
                    if (p == null) {
                        continue;
                    }
                    boolean hits = false;
                    for (IAEItemStack out : safeLines(p.getOutputs())) {
                        if (out != null && typeIndexOf(nodes, out) >= 0
                                && scc.contains(typeIndexOf(nodes, out))) {
                            hits = true;
                            break;
                        }
                    }
                    if (hits) {
                        fed = true; // an outside key's own pattern produces into the ring
                        break;
                    }
                }
            }
            if (fed) {
                it.remove();
            }
        }
        return dead;
    }

    private static void tarjan(int node, Map<Integer, Set<Integer>> graph, int[] index,
                               int[] low, int[] counter, List<Integer> stack,
                               boolean[] onStack, List<Set<Integer>> out) {
        index[node] = counter[0];
        low[node] = counter[0];
        counter[0]++;
        stack.add(node);
        onStack[node] = true;
        for (int w : graph.getOrDefault(node, Collections.emptySet())) {
            if (index[w] < 0) {
                tarjan(w, graph, index, low, counter, stack, onStack, out);
                low[node] = Math.min(low[node], low[w]);
            } else if (onStack[w]) {
                low[node] = Math.min(low[node], index[w]);
            }
        }
        if (low[node] == index[node]) {
            Set<Integer> scc = new HashSet<>();
            int w;
            do {
                w = stack.remove(stack.size() - 1);
                onStack[w] = false;
                scc.add(w);
            } while (w != node);
            out.add(scc);
        }
    }

    private static Long safeStock(Function<IAEItemStack, Long> stockLookup, IAEItemStack k) {
        try {
            return stockLookup == null ? null : stockLookup.apply(k);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<IAEItemStack> safeLines(IAEItemStack[] lines) {
        try {
            return lines == null ? Collections.<IAEItemStack>emptyList()
                    : new ArrayList<>(Arrays.asList(lines));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------
    // Type-normalized node registry (isSameType-merged key instances)
    // ------------------------------------------------------------------

    /** Registers {@code k} under its type; false when an equivalent node exists. */
    private static boolean addTypeKey(List<IAEItemStack> nodes, IAEItemStack k) {
        if (k == null) {
            return false;
        }
        return typeIndexOf(nodes, k) < 0 && nodes.add(k);
    }

    private static int typeIndexOf(List<IAEItemStack> nodes, IAEItemStack k) {
        for (int i = 0; i < nodes.size(); i++) {
            try {
                if (nodes.get(i).isSameType(k)) {
                    return i;
                }
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }
}
