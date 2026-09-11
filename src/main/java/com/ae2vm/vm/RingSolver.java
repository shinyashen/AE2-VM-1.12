package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * GAP-4 phase 2: solver for net-amplifying <b>simple</b> mutual recipe rings
 * ("gaia loops": 4 spirits → 1 ingot, 1 ingot → 12 spirits). The propagation
 * loop drops ring back-edge demand (stock-only assumption), so a pure mutual
 * ring ends up scheduled only along the root direction — the CPU then stalls
 * on missing intermediates (834 missing ingots for the gaia report).
 *
 * <p>The solver folds a detected simple ring into a <b>gross-flow super
 * bundle</b>: {@code patterns} = each ring recipe × its solved turn count,
 * {@code emitted} = gross production per key, {@code used} = gross
 * consumption per key (including the ring's internal traffic). One
 * {@code applyBundleDirect} applies the whole ring: emitted is inserted
 * before used is extracted, so the ring's own production provides the
 * working capital for its consumption — no order-of-application overdraft.
 *
 * <p>Math: the turn count comes from a Jacobian fixed-point iteration over
 * the ring keys. For each key {@code needed = Σ consumer crafts × per-craft
 * input + delivery (root key)} and {@code cover = start stock + own craft ×
 * per-craft output}; a shortfall bumps the key's own craft count. A
 * net-amplifying ring converges geometrically; monotonically growing
 * increments beyond the cap (a net-draining ring) abandon the solve and fall
 * back to the previous behavior.
 *
 * <p>The startup timing constraint — the first round's inputs must be on hand
 * before the first output lands — is reported separately as a seed shortfall
 * (timing capital, not net consumption; the ring pays it back within the
 * first round). Both ring firing directions are probed and the smaller seed
 * is kept.
 *
 * <p>Scope: <b>simple rings</b> only — a strongly-connected set where every
 * key has exactly one pattern, every pattern consumes exactly one in-ring
 * key, and every in-ring key is consumed by exactly one in-ring pattern (a
 * pure single cycle, no shared intermediates). Rings with shared
 * intermediates (e.g. 4A→B, B+C→D, D+B→12A where B feeds two consumers) need
 * a general linear solve and are left to the caller's previous behavior.
 */
final class RingSolver {

    /** Result of a successful ring solve. */
    static final class RingPlan {
        /** Ring keys (the caller removes them from the aggregation total). */
        final List<IAEItemStack> ringKeys = new ArrayList<>();
        /** Ring recipe → turn count. */
        final Map<ICraftingPatternDetails, BigInteger> patterns = new LinkedHashMap<>();
        /** Gross production per key (own craft × per-craft output). */
        final Map<IAEItemStack, BigInteger> emitted = new LinkedHashMap<>();
        /** Gross consumption per key (Σ consumer crafts × per-craft input). */
        final Map<IAEItemStack, BigInteger> used = new LinkedHashMap<>();
        /** Startup seed shortfall per key (timing: first round's input vs stock). */
        final Map<IAEItemStack, BigInteger> seedShortfall = new LinkedHashMap<>();
    }

    /** Per-craft typed inputs/outputs of one ring recipe (returned inputs excluded). */
    interface RecipeView {
        ICraftingPatternDetails pattern();

        Map<IAEItemStack, BigInteger> inputs();

        Map<IAEItemStack, BigInteger> outputs();
    }

    private static final int MAX_RING_WALK = 4096;
    private static final int MAX_ITERATIONS = 64;
    private static final BigInteger CRAFT_CAP = BigInteger.valueOf(1_000_000_000_000L);

    private RingSolver() {
    }

    /**
     * Solves the simple mutual rings among the scheduled keys. Keys of solved
     * rings are listed in each plan's {@code ringKeys} and must be removed
     * from the aggregation total by the caller (the net bundle takes over
     * their scheduling).
     *
     * @param recipeOf     key → its pattern's per-craft typed inputs/outputs
     *                     ({@code null} for keys without a pattern — leaves)
     * @param startStockOf key → starting stock (executeStartStock snapshot)
     * @param rootKey      the plan's delivery key
     * @param rootDeliver  the plan's delivery amount for {@code rootKey}
     */
    static List<RingPlan> solve(
            Map<IAEItemStack, BigInteger> total,
            Function<IAEItemStack, RecipeView> recipeOf,
            Function<IAEItemStack, BigInteger> startStockOf,
            IAEItemStack rootKey,
            BigInteger rootDeliver) {

        List<RingPlan> plans = new ArrayList<>();
        // Node set: scheduled keys plus their pattern-having inputs (the
        // propagation loop drops ring back-edges, so a ring member reached only
        // through a dropped back-edge is not in total — the node set must look
        // through the recipes' condensed inputs to see the whole ring).
        Set<IAEItemStack> nodes = new LinkedHashSet<>();
        nodes.addAll(total.keySet());
        List<IAEItemStack> work = new ArrayList<>(total.keySet());
        while (!work.isEmpty()) {
            IAEItemStack k = work.remove(work.size() - 1);
            RecipeView v = recipeOf.apply(k);
            if (v == null) continue;
            for (IAEItemStack in : v.inputs().keySet()) {
                if (in.isSameType(k)) continue;
                if (recipeOf.apply(in) != null && nodes.add(in)) work.add(in);
            }
        }
        // dependency edges: member -> the recipe-having keys its recipe consumes
        Map<IAEItemStack, Set<IAEItemStack>> deps = new LinkedHashMap<>();
        for (IAEItemStack k : nodes) {
            RecipeView v = recipeOf.apply(k);
            if (v == null) continue;
            Set<IAEItemStack> d = new LinkedHashSet<>();
            for (IAEItemStack in : v.inputs().keySet()) {
                if (nodes.contains(in) && !in.isSameType(k)) d.add(in);
            }
            if (!d.isEmpty()) deps.put(k, d);
        }
        Set<IAEItemStack> handled = new HashSet<>();
        List<Set<IAEItemStack>> allSccs = tarjan(deps, nodes);
        for (Set<IAEItemStack> scc : allSccs) {
            if (scc.size() < 2 || scc.size() > 64) continue;
            boolean touched = false;
            for (IAEItemStack k : scc) {
                if (handled.stream().anyMatch(h -> h.isSameType(k))) {
                    touched = true;
                    break;
                }
            }
            if (touched) continue;
            RingPlan plan = trySimpleRing(scc, total, recipeOf, startStockOf, rootKey, rootDeliver);
            if (plan == null) continue;
            plans.add(plan);
            handled.addAll(plan.ringKeys);
        }
        return plans;
    }

    private static RingPlan trySimpleRing(
            Set<IAEItemStack> scc,
            Map<IAEItemStack, BigInteger> total,
            Function<IAEItemStack, RecipeView> recipeOf,
            Function<IAEItemStack, BigInteger> startStockOf,
            IAEItemStack rootKey,
            BigInteger rootDeliver) {

        // ---- shape: pure single cycle. Every member's recipe consumes exactly
        // one in-ring key; every in-ring key is consumed by exactly one member;
        // no self-adjacency.
        Map<IAEItemStack, IAEItemStack> consumedBy = new HashMap<>(); // key -> member consuming it
        for (IAEItemStack m : scc) {
            RecipeView v = recipeOf.apply(m);
            IAEItemStack the = null;
            for (IAEItemStack in : v.inputs().keySet()) {
                if (!scc.contains(in)) continue;
                if (in.isSameType(m)) return null; // self-adjacent member
                if (the != null && !the.isSameType(in)) return null; // two in-ring inputs
                the = in;
            }
            if (the == null) return null; // no in-ring input: not a cycle member
            IAEItemStack prev = consumedBy.putIfAbsent(the, m);
            if (prev != null && !prev.isSameType(m)) return null; // two consumers
        }
        if (consumedBy.size() != scc.size()) return null; // some member consumes nothing in-ring

        // ---- Jacobian fixed point over the member craft counts.
        Map<IAEItemStack, BigInteger> x = new HashMap<>();
        for (IAEItemStack k : scc) {
            x.put(k, total.getOrDefault(k, BigInteger.ZERO));
        }
        boolean converged = false;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // needed[k] = Σ consumer crafts × per-craft input (+ root delivery)
            Map<IAEItemStack, BigInteger> needed = new HashMap<>();
            for (IAEItemStack m : scc) {
                RecipeView v = recipeOf.apply(m);
                BigInteger xf = x.get(m);
                if (xf.signum() <= 0) continue;
                for (var e : v.inputs().entrySet()) {
                    needed.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
                }
            }
            if (rootKey != null && rootDeliver.signum() > 0 && scc.contains(rootKey)) {
                needed.merge(rootKey, rootDeliver, BigInteger::add);
            }
            boolean changed = false;
            for (IAEItemStack k : scc) {
                RecipeView v = recipeOf.apply(k);
                BigInteger outPer = v.outputs().getOrDefault(k, BigInteger.ZERO);
                if (outPer.signum() <= 0) return null; // cannot scale itself
                BigInteger dem = needed.getOrDefault(k, BigInteger.ZERO);
                BigInteger cov = nonNeg(startStockOf.apply(k)).add(x.get(k).multiply(outPer));
                if (dem.compareTo(cov) > 0) {
                    BigInteger bump = dem.subtract(cov).add(outPer).subtract(BigInteger.ONE).divide(outPer);
                    BigInteger nx = x.get(k).add(bump);
                    if (nx.compareTo(CRAFT_CAP) > 0) return null; // diverged
                    x.put(k, nx);
                    changed = true;
                }
            }
            if (!changed) {
                converged = true;
                break;
            }
        }
        if (!converged) return null; // net-draining ring: honest fallback

        // ---- gain gate: only pure-gain rings are ours. A ring whose merged
        // net effect has a drain key or nets to zero (value-conserving
        // conversion ring) belongs to the conversion-ring guard / the
        // working-capital simulation — folding it into a gross bundle would
        // break their conservation semantics.
        boolean anyGain = false;
        Map<IAEItemStack, BigInteger> produced = new HashMap<>();
        Map<IAEItemStack, BigInteger> consumed = new HashMap<>();
        for (IAEItemStack m : scc) {
            RecipeView v = recipeOf.apply(m);
            BigInteger xf = x.get(m);
            if (xf.signum() <= 0) continue;
            for (var e : v.inputs().entrySet()) {
                consumed.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
            for (var e : v.outputs().entrySet()) {
                produced.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
        }
        java.util.Set<IAEItemStack> keys = new HashSet<>();
        keys.addAll(produced.keySet());
        keys.addAll(consumed.keySet());
        for (IAEItemStack k : keys) {
            BigInteger netGain = produced.getOrDefault(k, BigInteger.ZERO)
                    .subtract(consumed.getOrDefault(k, BigInteger.ZERO));
            if (netGain.signum() < 0) return null; // drain key: conversion/lossy domain
            if (netGain.signum() > 0) anyGain = true;
        }
        if (!anyGain) return null; // value-conserving ring: nothing to amplify

        // ---- gross-flow bundle. Emitted/used are keyed by ALL recipe
        // inputs/outputs (byproducts land in emitted, external materials in
        // used) so the plan reports and extracts the full material flow.
        RingPlan plan = new RingPlan();
        for (IAEItemStack k : scc) {
            RecipeView v = recipeOf.apply(k);
            plan.patterns.put(v.pattern(), x.get(k));
        }
        for (IAEItemStack m : scc) {
            RecipeView v = recipeOf.apply(m);
            BigInteger xf = x.get(m);
            if (xf.signum() <= 0) continue;
            for (var e : v.outputs().entrySet()) {
                plan.emitted.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
            for (var e : v.inputs().entrySet()) {
                plan.used.merge(e.getKey(), xf.multiply(e.getValue()), BigInteger::add);
            }
        }
        plan.ringKeys.addAll(scc);

        // ---- startup seed: simulate one round along the ring (forced firing,
        // available may go negative), report the deepest per-key deficit as a
        // seed shortfall — timing capital the network must hold before the
        // ring's first output lands; the ring pays it back within the first
        // round. Both firing directions are probed, the smaller seed is kept.
        Map<IAEItemStack, BigInteger> best = null;
        List<IAEItemStack> order0 = new ArrayList<>(scc);
        order0.sort(Comparator.comparing(k -> find(plan.ringKeys, k) == null ? 1 : 0));
        for (int dir = 0; dir < 2; dir++) {
            Map<IAEItemStack, BigInteger> available = new HashMap<>();
            for (IAEItemStack k : scc) {
                BigInteger s = nonNeg(startStockOf.apply(k));
                if (s.signum() > 0) available.put(k, s);
            }
            Map<IAEItemStack, BigInteger> seed = new HashMap<>();
            List<IAEItemStack> order = new ArrayList<>(order0);
            if (dir == 1) java.util.Collections.reverse(order);
            for (IAEItemStack k : order) {
                RecipeView v = recipeOf.apply(k);
                for (var e : v.inputs().entrySet()) {
                    BigInteger avail = available.getOrDefault(e.getKey(), BigInteger.ZERO);
                    if (e.getValue().compareTo(avail) > 0) {
                        BigInteger deficit = e.getValue().subtract(avail);
                        BigInteger prev = seed.getOrDefault(e.getKey(), BigInteger.ZERO);
                        if (deficit.compareTo(prev) > 0) seed.put(e.getKey(), deficit);
                        available.put(e.getKey(), e.getValue()); // forced: ledger goes negative
                    } else {
                        available.put(e.getKey(), avail);
                    }
                }
                for (var e : v.outputs().entrySet()) {
                    available.merge(e.getKey(), e.getValue(), BigInteger::add);
                }
            }
            BigInteger totalSeed = seed.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger bestTotal = best == null ? null
                    : best.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
            if (best == null || totalSeed.compareTo(bestTotal) < 0) best = seed;
        }
        if (best != null) {
            plan.seedShortfall.putAll(best);
        }
        return plan;
    }

    private static BigInteger nonNeg(BigInteger v) {
        return v.signum() > 0 ? v : BigInteger.ZERO;
    }

    private static IAEItemStack find(List<IAEItemStack> list, IAEItemStack key) {
        for (IAEItemStack k : list) {
            if (k.isSameType(key)) return k;
        }
        return null;
    }

    /** Tarjan SCC (iterative) over {@code deps}; returns SCCs of size ≥ 2. */
    private static List<Set<IAEItemStack>> tarjan(Map<IAEItemStack, Set<IAEItemStack>> deps,
                                                  Set<IAEItemStack> nodes) {
        Map<IAEItemStack, Integer> index = new HashMap<>();
        Map<IAEItemStack, Integer> low = new HashMap<>();
        Map<IAEItemStack, Boolean> onStack = new HashMap<>();
        List<IAEItemStack> stack = new ArrayList<>();
        int[] counter = {0};
        List<Set<IAEItemStack>> sccs = new ArrayList<>();
        for (IAEItemStack root : nodes) {
            if (index.containsKey(root)) continue;
            Deque<Object[]> frames = new ArrayDeque<>();
            frames.push(new Object[]{root, deps.getOrDefault(root, java.util.Collections.<IAEItemStack>emptySet()).iterator()});
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.add(root);
            onStack.put(root, true);
            while (!frames.isEmpty()) {
                Object[] frame = frames.peek();
                IAEItemStack v = (IAEItemStack) frame[0];
                @SuppressWarnings("unchecked")
                java.util.Iterator<IAEItemStack> it = (java.util.Iterator<IAEItemStack>) frame[1];
                boolean descended = false;
                while (it.hasNext()) {
                    IAEItemStack w = it.next();
                    if (!nodes.contains(w)) continue;
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.add(w);
                        onStack.put(w, true);
                        frames.push(new Object[]{w, deps.getOrDefault(w, java.util.Collections.<IAEItemStack>emptySet()).iterator()});
                        descended = true;
                        break;
                    } else if (onStack.getOrDefault(w, false)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                }
                if (descended) continue;
                frames.pop();
                if (!frames.isEmpty()) {
                    IAEItemStack parent = (IAEItemStack) frames.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    Set<IAEItemStack> comp = new HashSet<>();
                    IAEItemStack w;
                    do {
                        w = stack.remove(stack.size() - 1);
                        onStack.put(w, false);
                        comp.add(w);
                    } while (!w.isSameType(v));
                    if (comp.size() >= 2) sccs.add(comp);
                }
            }
        }
        return sccs;
    }
}
