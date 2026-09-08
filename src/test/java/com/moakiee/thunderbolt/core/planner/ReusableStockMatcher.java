package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Long-capacity bipartite matcher for overlapping reusable/fuzzy seed variants. */
public final class ReusableStockMatcher {
    public record Result<K>(boolean feasible, Map<ReusableStockAllocationKey<K>, Long> allocation) {
        public Result {
            allocation = Map.copyOf(allocation);
        }
    }

    public static <K> Result<K> allocate(
            Map<ReusableStockKey<K>, Long> available,
            Map<ReusableStockRouteKey<K>, Long> demand,
            Function<ReusableStockRouteKey<K>, ? extends Iterable<K>> candidates) {
        var actual = positiveEntries(available);
        var routes = positiveEntries(demand);
        if (routes.isEmpty()) return new Result<>(true, Map.of());
        if (actual.isEmpty()) return new Result<>(false, Map.of());

        int source = 0;
        int routeOffset = 1;
        int actualOffset = routeOffset + routes.size();
        int sink = actualOffset + actual.size();
        var flow = new LongCapacityFlow(sink + 1);
        var acceptedByRoute = new LinkedHashMap<ReusableStockRouteKey<K>, Set<K>>();
        for (int i = 0; i < routes.size(); i++) {
            var route = routes.get(i).getKey();
            var accepted = new LinkedHashSet<K>();
            var routeCandidates = candidates.apply(route);
            if (routeCandidates != null) {
                for (var candidate : routeCandidates) {
                    if (candidate != null) {
                        accepted.add(candidate);
                    }
                }
            }
            acceptedByRoute.put(route, accepted);
        }

        var assignmentEdges = new ArrayList<AssignmentEdge<K>>();
        for (int i = 0; i < actual.size(); i++) {
            flow.addEdge(actualOffset + i, sink, actual.get(i).getValue());
        }

        var demandEdges = new ArrayList<LongCapacityFlow.Edge>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            var route = routes.get(i).getKey();
            demandEdges.add(flow.addEdge(source, routeOffset + i, routes.get(i).getValue()));

            // Prefer the physical planned key. Besides minimizing needless component re-keying,
            // this lets an exact catalyst become a stable shared credit immediately. Residual
            // reverse edges still reassign a flexible earlier route when a later constrained route
            // needs that exact variant, so feasibility remains order-independent.
            for (int exactPass = 0; exactPass < 2; exactPass++) {
                for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++) {
                    var actualEntry = actual.get(actualIndex);
                    if (!route.source().storageScope().equals(actualEntry.getKey().scope())) continue;
                    var actualKey = actualEntry.getKey().key();
                    if (!acceptedByRoute.get(route).contains(actualKey)) continue;
                    boolean exact = route.plannedKey().equals(actualKey);
                    if ((exactPass == 0) != exact) continue;
                    var edge = flow.addEdge(
                            routeOffset + i, actualOffset + actualIndex, Long.MAX_VALUE);
                    assignmentEdges.add(new AssignmentEdge<>(route, actualKey, edge));
                }
            }
        }
        flow.maximize(source, sink);

        boolean feasible = demandEdges.stream().allMatch(edge -> edge.remaining == 0L);
        var allocation = new LinkedHashMap<ReusableStockAllocationKey<K>, Long>();
        for (var assignment : assignmentEdges) {
            long used = Long.MAX_VALUE - assignment.edge.remaining;
            if (used > 0) {
                allocation.put(
                        new ReusableStockAllocationKey<>(assignment.route, assignment.actualKey), used);
            }
        }
        return new Result<>(feasible, allocation);
    }

    private static <K> List<Map.Entry<K, Long>> positiveEntries(Map<K, Long> input) {
        var result = new ArrayList<Map.Entry<K, Long>>();
        for (var entry : input.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                result.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private record AssignmentEdge<K>(
            ReusableStockRouteKey<K> route, K actualKey, LongCapacityFlow.Edge edge) {
    }

    private static final class LongCapacityFlow {
        private final List<List<Edge>> graph;
        private int[] level;
        private int[] next;

        private LongCapacityFlow(int nodes) {
            graph = new ArrayList<>(nodes);
            for (int i = 0; i < nodes; i++) graph.add(new ArrayList<>());
        }

        private Edge addEdge(int from, int to, long capacity) {
            var forward = new Edge(to, graph.get(to).size(), capacity);
            var reverse = new Edge(from, graph.get(from).size(), 0L);
            graph.get(from).add(forward);
            graph.get(to).add(reverse);
            return forward;
        }

        private void maximize(int source, int sink) {
            while (buildLevels(source, sink)) {
                next = new int[graph.size()];
                while (send(source, sink, Long.MAX_VALUE) > 0) {
                    // One augmentation moves a long-capacity bottleneck, never one item.
                }
            }
        }

        private boolean buildLevels(int source, int sink) {
            level = new int[graph.size()];
            java.util.Arrays.fill(level, -1);
            level[source] = 0;
            var queue = new ArrayDeque<Integer>();
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.removeFirst();
                for (var edge : graph.get(node)) {
                    if (edge.remaining > 0 && level[edge.to] < 0) {
                        level[edge.to] = level[node] + 1;
                        queue.addLast(edge.to);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private long send(int node, int sink, long limit) {
            if (node == sink) return limit;
            var edges = graph.get(node);
            while (next[node] < edges.size()) {
                var edge = edges.get(next[node]);
                if (edge.remaining > 0 && level[edge.to] == level[node] + 1) {
                    long moved = send(edge.to, sink, Math.min(limit, edge.remaining));
                    if (moved > 0) {
                        edge.remaining -= moved;
                        var reverse = graph.get(edge.to).get(edge.reverseIndex);
                        reverse.remaining = saturatingAdd(reverse.remaining, moved);
                        return moved;
                    }
                }
                next[node]++;
            }
            return 0L;
        }

        private static long saturatingAdd(long left, long right) {
            return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }

        private static final class Edge {
            private final int to;
            private final int reverseIndex;
            private long remaining;

            private Edge(int to, int reverseIndex, long remaining) {
                this.to = to;
                this.reverseIndex = reverseIndex;
                this.remaining = remaining;
            }
        }
    }

    private ReusableStockMatcher() {
    }
}
