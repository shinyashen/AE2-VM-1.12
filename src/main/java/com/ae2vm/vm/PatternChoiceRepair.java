package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.compiler.PatternCompiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-pattern allocation solver — closes the last unsolved engine issue
 * inherited from the original AE2-VM: the reference suite's
 * {@code multi-dag/fibonacci/minimum} false positive (optimal multi-pattern
 * selection).
 *
 * <p><b>The problem.</b> The resolver picks exactly ONE pattern per output key
 * (smallest per-craft output, ties by registration order) and never revisits
 * the choice. When several patterns produce the same key, one fixed pattern
 * per key can over-demand some leaf relative to a MIXED assignment — the
 * reference scenario's optimal stock contains {@code X0=1}, meetable only by
 * splitting a key's crafts 4×A + 1×B, which no single choice can demand.
 *
 * <p><b>This approach — solve, then encode the split as data.</b> The VM
 * re-executes a request in milliseconds, and the JIT bundle cache is
 * pattern-qualified (keys are (key, pattern bytecode) pairs), so trying an
 * alternative assignment is cheap. The greedy first pass is kept
 * byte-for-byte identical to the pre-solver behavior; only when it reports
 * missing do we:
 * <ol>
 *   <li><b>Enumerate</b> every single-choice assignment of the contended keys
 *       (≤12 keys, algebra only — candidate patterns' consumed inputs form a
 *       linear demand system cascaded topologically from the root, leaves
 *       priced against a pristine stock view) to find the best baseline.</li>
 *   <li><b>Refine with split moves, from every enumeration optimum</b>: a
 *       weight vector per key redistributes its demanded craft count across
 *       its candidates (largest-remainder rounding, so the split exactly
 *       covers the demanded crafts); local search transfers one unit of
 *       weight between candidates, or adds one unit to a single candidate
 *       (ADD — a one-hot state cannot express 4:1 ratios by transfer alone),
 *       each candidate state evaluated by the same linear cascade, accepted
 *       only on strict improvement of the predicted total shortfall; equal
 *       moves step the search position (bounded) to cross plateaus. Refinement
 *       starts from ALL tied enumeration optima (bounded), because which
 *       optimum a mixed split can grow from differs by graph shape — from the
 *       wrong one, every single move can be non-improving. The visited-set
 *       key reduces each weight row by its GCD: weights are ratios, so
 *       scale-equivalent states (e.g. [0,2] vs [0,1]) are the same allocation,
 *       and without reduction the ADD move burns the lateral budget on an
 *       endless ladder of equivalent states.</li>
 *   <li><b>Synthesize and confirm</b>: a refined MIXED weight vector is
 *       encoded as a {@link VirtualPatternDetails} (one condensed line per
 *       merged input, combined batch output) and handed to the engine through
 *       the ordinary preference mechanism — the execution loop, bundle cache
 *       and aggregation simply see another pattern. The prediction is adopted
 *       only after one real pass confirms strictly fewer missing materials.</li>
 * </ol>
 *
 * <p><b>Safety properties.</b> Adoption always requires a real pass reporting
 * strictly fewer missing materials; a mispredicted allocation fails
 * confirmation and keeps the incumbent plan. All iteration follows
 * insertion-ordered maps and recorded candidate lists — the solver is
 * deterministic for a given network state. Splits are restricted to
 * candidate sets with equal per-craft output; ordinary flipped (unsplit)
 * choices are unaffected.
 */
public final class PatternChoiceRepair {

    /** Hard bounds: enumeration width and the solver's key budget. */
    private static final int MAX_ENUMERATION_KEYS = 12;
    private static final long MAX_ENUMERATION_COMBINATIONS = 1L << 20;
    private static final int MAX_SPLIT_REFINEMENT_ROUNDS = 48;
    /** Per-candidate weight cap for the ADD move (weights are ratios). */
    private static final int MAX_SINGLE_WEIGHT = 32;
    /**
     * Equal-prediction moves the split search may step through to cross
     * plateaus (some optimal mixes are only reachable through an
     * equal-shortfall intermediate state). Lateral states are search
     * positions only — the confirmed plan must still be strictly better than
     * the greedy pass.
     */
    private static final int MAX_SPLIT_LATERAL_MOVES = 8;
    /** Cap on enumeration optima the split refinement starts from. */
    private static final int MAX_REFINEMENT_STARTS = 8;

    /**
     * Choice records gathered while one pass resolved keys. Recorded in
     * resolution order; iteration over them keeps the solver deterministic.
     */
    public static final class Choices {
        private final Map<IAEItemStack, ICraftingPatternDetails> chosen =
                new LinkedHashMap<>();
        private final Map<IAEItemStack, List<ICraftingPatternDetails>> contended =
                new LinkedHashMap<>();

        /**
         * Records one resolved key. {@code candidates} are all output-verified
         * patterns for the key in registration order (including the chosen one);
         * keys with a single candidate are not contended and are not recorded
         * in the contended map.
         */
        public void record(IAEItemStack key, ICraftingPatternDetails chosenPattern,
                           List<ICraftingPatternDetails> candidates) {
            if (key == null || chosenPattern == null) {
                return;
            }
            chosen.putIfAbsent(key, chosenPattern);
            if (candidates != null && candidates.size() > 1) {
                contended.putIfAbsent(key, candidates);
            }
        }

        /** Resolved key → chosen pattern, in resolution order. */
        public Map<IAEItemStack, ICraftingPatternDetails> chosen() {
            return Collections.unmodifiableMap(chosen);
        }

        /** Resolved key → verified alternative patterns (>1 entry only). */
        public Map<IAEItemStack, List<ICraftingPatternDetails>> contended() {
            return Collections.unmodifiableMap(contended);
        }
    }

    /** The outcome of one engine pass: its plan, choice records and stock view. */
    public static final class PassResult {
        public final VMPlan plan;
        public final Choices choices;
        /**
         * The pass's read-only network/simulation view, used by the solver to
         * price leaf demand against stock. May be null (the solver is then
         * skipped).
         */
        public final SimulationState simulation;

        public PassResult(VMPlan plan, Choices choices, SimulationState simulation) {
            this.plan = plan;
            this.choices = choices;
            this.simulation = simulation;
        }
    }

    /**
     * One full engine calculation under a preference override map (resolved
     * key → forced pattern; the pattern may be a synthesized
     * {@link VirtualPatternDetails} encoding a split). Implementations must
     * give every pass a fresh per-request state (resolver cache, network or
     * simulation snapshot) so a replay sees the same network as the first
     * pass.
     */
    public interface Pass {
        PassResult run(Map<IAEItemStack, ICraftingPatternDetails> preferences);
    }

    private PatternChoiceRepair() {
    }

    /**
     * Runs the greedy first pass; when it reports missing, drives the
     * enumeration + split refinement + confirmation pipeline and returns the
     * best plan found (never worse than the first pass). {@code maxExtraPasses}
     * bounds the confirmation replays; 0 disables the solver entirely.
     */
    public static VMPlan repair(Pass pass, int maxExtraPasses) {
        PassResult current = pass.run(Collections.emptyMap());
        VMPlan best = current.plan;
        if (best == null || best.getMissingItems().isEmpty() || maxExtraPasses <= 0) {
            return best;
        }
        if (current.simulation == null) {
            return best;
        }

        Model model = buildModel(current);
        if (model == null) {
            return best;
        }

        // 1) Single-choice enumeration: every discrete optimum (ties included).
        List<int[]> starts = enumerateAssignments(model, missingTotal(best));
        if (starts.isEmpty()) {
            return best;
        }

        // 2) Split refinement from every enumeration optimum: which optimum a
        // mixed split can grow from differs, and from the wrong one every
        // single move can be non-improving (the improving split needs a
        // coordinated multi-key flip from there).
        int[][] refinedWeights = null;
        Eval refined = null;
        for (int[] startAssignment : starts) {
            int[][] weights = new int[model.n][];
            for (int i = 0; i < model.n; i++) {
                if (model.contendedList[i] != null) {
                    weights[i] = new int[model.contendedList[i].size()];
                    weights[i][startAssignment[i]] = 1;
                }
            }
            Eval baseline = predictFull(model, null, weights);
            if (baseline == null) {
                continue;
            }
            int[][] candidate = refineSplits(model, weights, baseline.missing);
            Eval candidateEval = predictFull(model, null, candidate);
            if (candidateEval == null) {
                continue;
            }
            if (refined == null
                    || candidateEval.missing.compareTo(refined.missing) < 0) {
                refined = candidateEval;
                refinedWeights = candidate;
            }
            if (refined.missing.signum() == 0) {
                break;
            }
        }
        if (refined == null) {
            return best;
        }

        // 3) Synthesize preferences and confirm with one real pass.
        Map<IAEItemStack, ICraftingPatternDetails> prefs = new LinkedHashMap<>();
        for (int i = 0; i < model.n; i++) {
            List<ICraftingPatternDetails> candidates = model.contendedList[i];
            if (candidates == null) {
                continue;
            }
            ICraftingPatternDetails incumbent = model.chosen.get(model.universe.get(i));
            int[] w = refinedWeights[i];
            int nonzero = 0, last = 0;
            for (int j = 0; j < w.length; j++) {
                if (w[j] > 0) {
                    nonzero++;
                    last = j;
                }
            }
            if (nonzero == 1) {
                if (candidates.get(last) != incumbent) {
                    prefs.put(model.universe.get(i), candidates.get(last));
                }
            } else {
                BigInteger d = refined.demand.getOrDefault(model.universe.get(i),
                        BigInteger.ZERO);
                long opc = model.shapes.get(candidates.get(0)).outputPerCraft;
                long total = d.add(BigInteger.valueOf(opc - 1))
                        .divide(BigInteger.valueOf(opc))
                        .min(BigInteger.valueOf(1L << 40)).max(BigInteger.ONE)
                        .longValueExact();
                VirtualPatternDetails virtual =
                        internVirtual(model, i, largestRemainder(total, w));
                if (virtual != null) {
                    prefs.put(model.universe.get(i), virtual);
                }
            }
        }
        PassResult confirmed = pass.run(prefs);
        if (confirmed.plan != null
                && missingTotal(confirmed.plan).compareTo(missingTotal(best)) < 0) {
            return confirmed.plan;
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Model: universe, topology, pattern shapes
    // ------------------------------------------------------------------

    /** Parsed per-craft consumption and output of one candidate pattern. */
    private static final class PatternShape {
        final List<IAEItemStack> inputs = new ArrayList<>();
        final List<Long> inputAmounts = new ArrayList<>();
        long outputPerCraft = 1L;
    }

    /** Everything the solver needs, precomputed once per call. */
    private static final class Model {
        final int n;
        final List<IAEItemStack> universe;
        final int[] order;
        final int rootIdx;
        final long deliver;
        final Map<IAEItemStack, List<ICraftingPatternDetails>> contended;
        final Map<IAEItemStack, ICraftingPatternDetails> chosen;
        final Map<ICraftingPatternDetails, PatternShape> shapes;
        final List<ICraftingPatternDetails>[] contendedList;
        final SimulationState simulation;

        @SuppressWarnings("unchecked")
        Model(int n, List<IAEItemStack> universe, int[] order, int rootIdx,
              long deliver, Map<IAEItemStack, List<ICraftingPatternDetails>> contended,
              Map<IAEItemStack, ICraftingPatternDetails> chosen,
              Map<ICraftingPatternDetails, PatternShape> shapes,
              List<ICraftingPatternDetails>[] contendedList,
              SimulationState simulation) {
            this.n = n;
            this.universe = universe;
            this.order = order;
            this.rootIdx = rootIdx;
            this.deliver = deliver;
            this.contended = contended;
            this.chosen = chosen;
            this.shapes = shapes;
            this.contendedList = contendedList;
            this.simulation = simulation;
        }
    }

    /**
     * Builds the solver model: universe (root + contended keys + keys with a
     * chosen pattern), parsed pattern shapes and the topological order over
     * candidate edges. Returns null when the model does not apply (cycles,
     * unparseable patterns, too many contended keys).
     */
    private static Model buildModel(PassResult state) {
        Map<IAEItemStack, List<ICraftingPatternDetails>> contended =
                state.choices.contended();
        Map<IAEItemStack, ICraftingPatternDetails> chosen = state.choices.chosen();
        IAEItemStack rootKey = state.plan.getOutputKey();
        if (contended.isEmpty() || contended.size() > MAX_ENUMERATION_KEYS) {
            return null;
        }
        ICraftingPatternDetails rootPattern = chosen.get(rootKey);
        if (rootPattern == null) {
            return null;
        }

        Map<ICraftingPatternDetails, PatternShape> shapes = new HashMap<>();
        if (!shapeOf(rootPattern, shapes)) {
            return null;
        }
        for (List<ICraftingPatternDetails> candidates : contended.values()) {
            for (ICraftingPatternDetails candidate : candidates) {
                if (!shapeOf(candidate, shapes)) {
                    return null;
                }
            }
        }
        for (ICraftingPatternDetails fixed : chosen.values()) {
            if (fixed != null && !shapeOf(fixed, shapes)) {
                return null;
            }
        }

        List<IAEItemStack> universe = new ArrayList<>();
        universe.add(rootKey);
        for (IAEItemStack k : contended.keySet()) {
            if (!containsType(universe, k)) {
                universe.add(k);
            }
        }
        for (IAEItemStack k : chosen.keySet()) {
            if (!containsType(universe, k)) {
                universe.add(k);
            }
        }
        int n = universe.size();
        int[][] edges = new int[n][];
        for (int i = 0; i < n; i++) {
            IAEItemStack k = universe.get(i);
            List<Integer> outs = new ArrayList<>();
            List<ICraftingPatternDetails> candidates = contended.get(k);
            List<ICraftingPatternDetails> variants = candidates != null ? candidates
                    : Collections.singletonList(chosen.get(k));
            if (variants == null || containsNull(variants)) {
                return null;
            }
            Set<Integer> seen = new HashSet<>();
            for (ICraftingPatternDetails variant : variants) {
                PatternShape shape = shapes.get(variant);
                if (shape == null) {
                    return null;
                }
                for (IAEItemStack input : shape.inputs) {
                    for (int j = 0; j < n; j++) {
                        if (j != i && universe.get(j).isSameType(input) && seen.add(j)) {
                            outs.add(j);
                        }
                    }
                }
            }
            int[] arr = new int[outs.size()];
            for (int j = 0; j < arr.length; j++) {
                arr[j] = outs.get(j);
            }
            edges[i] = arr;
        }
        int[] order = topologicalOrder(edges);
        if (order == null) {
            return null;
        }
        int rootIdx = -1;
        for (int i = 0; i < n; i++) {
            if (universe.get(i).isSameType(rootKey)) {
                rootIdx = i;
                break;
            }
        }
        if (rootIdx < 0) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<ICraftingPatternDetails>[] contendedList =
                (List<ICraftingPatternDetails>[]) new List[n];
        for (int i = 0; i < n; i++) {
            contendedList[i] = contended.get(universe.get(i));
        }
        long deliver = Math.max(1L, state.plan.getDeliverAmount());
        return new Model(n, universe, order, rootIdx, deliver, contended, chosen,
                shapes, contendedList, state.simulation);
    }

    private static boolean containsNull(List<ICraftingPatternDetails> list) {
        for (ICraftingPatternDetails item : list) {
            if (item == null) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Stage 1: single-choice enumeration
    // ------------------------------------------------------------------

    /**
     * Evaluates every discrete assignment of the contended keys (algebra only)
     * and returns ALL assignments predicting the smallest total shortfall
     * (bounded by {@link #MAX_REFINEMENT_STARTS}). One optimum is rarely the
     * only one, and which of them a mixed split can grow from differs: the
     * shared-descendant case (a leaf stock merging demands routed through the
     * same intermediate under different parents) is exactly realizable only
     * from specific optima. Returns an empty list when no assignment predicts
     * better than the incumbent plan.
     */
    private static List<int[]> enumerateAssignments(Model model, BigInteger incumbentMissing) {
        long combinations = 1L;
        for (int i = 0; i < model.n; i++) {
            List<ICraftingPatternDetails> candidates = model.contendedList[i];
            combinations *= candidates != null ? candidates.size() : 1;
            if (combinations > MAX_ENUMERATION_COMBINATIONS) {
                return Collections.emptyList();
            }
        }
        int[] assignment = new int[model.n];
        List<int[]> best = new ArrayList<>();
        BigInteger bestMissing = incumbentMissing;
        boolean improved = false;
        for (long mask = 0; mask < combinations; mask++) {
            if (mask > 0) {
                for (int i = 0; i < model.n; i++) {
                    if (++assignment[i] < radix(model, i)) {
                        break;
                    }
                    assignment[i] = 0;
                }
            }
            BigInteger predicted = predict(model, assignment, null);
            if (predicted == null) {
                continue;
            }
            int cmp = predicted.compareTo(bestMissing);
            if (cmp < 0) {
                bestMissing = predicted;
                best.clear();
                best.add(assignment.clone());
                improved = true;
                if (bestMissing.signum() == 0) {
                    break;
                }
            } else if (cmp == 0 && improved
                    && best.size() < MAX_REFINEMENT_STARTS) {
                best.add(assignment.clone());
            }
        }
        return best;
    }

    private static int radix(Model model, int i) {
        List<ICraftingPatternDetails> candidates = model.contendedList[i];
        return candidates != null ? candidates.size() : 1;
    }

    // ------------------------------------------------------------------
    // Stage 2: split refinement (weight-vector local search)
    // ------------------------------------------------------------------

    /**
     * Local search over per-key weight vectors: each candidate move transfers
     * one unit of weight between two candidates of one key (the cascade
     * distributes the key's demanded crafts over its candidates by largest
     * remainder, so a mixed split like 4×A + 1×B is expressible), evaluated by
     * the same linear model. Strict improvements only, bounded rounds.
     * Splitting is restricted to candidate sets with equal per-craft output.
     */
    private static int[][] refineSplits(Model model, int[][] startWeights,
                                        BigInteger startMissing) {
        // Search position vs. best-found state: equal-prediction moves only
        // step the POSITION (bounded, visited-guarded) so the search can cross
        // plateaus toward a split that strictly improves; the returned state is
        // always the best found.
        int[][] position = cloneWeights(startWeights);
        int[][] best = cloneWeights(startWeights);
        BigInteger positionMissing = startMissing;
        BigInteger bestMissing = startMissing;
        Set<String> visited = new HashSet<>();
        visited.add(weightsKey(position));
        int lateralsLeft = MAX_SPLIT_LATERAL_MOVES;
        for (int round = 0; round < MAX_SPLIT_REFINEMENT_ROUNDS; round++) {
            int[] improving = null;
            int[] lateral = null;
            BigInteger lateralMissing = null;
            moveLoop:
            for (int i = 0; i < model.n; i++) {
                List<ICraftingPatternDetails> candidates = model.contendedList[i];
                if (candidates == null || candidates.size() < 2) {
                    continue;
                }
                boolean equalOpc = true;
                long opc = model.shapes.get(candidates.get(0)).outputPerCraft;
                for (ICraftingPatternDetails candidate : candidates) {
                    if (model.shapes.get(candidate).outputPerCraft != opc) {
                        equalOpc = false;
                        break;
                    }
                }
                if (!equalOpc) {
                    continue;
                }
                for (int from = 0; from < position[i].length; from++) {
                    if (position[i][from] <= 0) {
                        continue;
                    }
                    for (int to = 0; to < position[i].length; to++) {
                        if (to == from) {
                            continue;
                        }
                        position[i][from]--;
                        position[i][to]++;
                        String key = weightsKey(position);
                        boolean fresh = visited.add(key);
                        Eval eval = fresh ? predictFull(model, null, position) : null;
                        if (eval == null) {
                            position[i][from]++;
                            position[i][to]--;
                            continue;
                        }
                        if (eval.missing.compareTo(bestMissing) < 0) {
                            bestMissing = eval.missing;
                            best = cloneWeights(position);
                            improving = new int[]{i, from, to};
                        } else if (eval.missing.compareTo(positionMissing) == 0
                                && lateral == null && lateralsLeft > 0 && fresh) {
                            lateral = new int[]{i, from, to};
                            lateralMissing = eval.missing;
                        }
                        position[i][from]++;
                        position[i][to]--;
                        if (improving != null) {
                            break moveLoop;
                        }
                    }
                }
                // ADD moves: raise one candidate's weight without lowering
                // another. Weights are ratios of the key's demanded craft count
                // (largest-remainder distribution), so from a one-hot state
                // only ADD moves can express mixes like 4:1 — a pure transfer
                // would first zero the hot candidate.
                for (int to = 0; to < position[i].length; to++) {
                    if (position[i][to] >= MAX_SINGLE_WEIGHT) {
                        continue;
                    }
                    position[i][to]++;
                    String key = weightsKey(position);
                    boolean fresh = visited.add(key);
                    Eval eval = fresh ? predictFull(model, null, position) : null;
                    if (eval == null) {
                        position[i][to]--;
                        continue;
                    }
                    if (eval.missing.compareTo(bestMissing) < 0) {
                        bestMissing = eval.missing;
                        best = cloneWeights(position);
                        improving = new int[]{i, -1, to};
                    } else if (eval.missing.compareTo(positionMissing) == 0
                            && lateral == null && lateralsLeft > 0 && fresh) {
                        lateral = new int[]{i, -1, to};
                        lateralMissing = eval.missing;
                    }
                    position[i][to]--;
                    if (improving != null) {
                        break moveLoop;
                    }
                }
            }
            if (improving != null) {
                applyMove(position, improving);
                positionMissing = bestMissing;
                continue;
            }
            if (lateral != null) {
                applyMove(position, lateral);
                positionMissing = lateralMissing;
                lateralsLeft--;
                continue;
            }
            return best;
        }
        return best;
    }

    /** Applies a {from(-1 = pass), to} weight move produced by the search. */
    private static void applyMove(int[][] weights, int[] move) {
        if (move[1] >= 0) {
            weights[move[0]][move[1]]--;
        }
        weights[move[0]][move[2]]++;
    }

    private static int[][] cloneWeights(int[][] weights) {
        int[][] copy = new int[weights.length][];
        for (int i = 0; i < weights.length; i++) {
            copy[i] = weights[i] == null ? null : weights[i].clone();
        }
        return copy;
    }

    /**
     * Search-state key, REDUCED per row by its GCD: weights are ratios of the
     * key's demanded craft count (largest-remainder distribution), so [0,2]
     * and [0,1] denote the same allocation. Without reduction the ADD move
     * generates an endless ladder of scale-equivalent states that all look
     * fresh, and the bounded lateral budget is burned on them before the
     * search reaches a genuinely different mix.
     */
    private static String weightsKey(int[][] weights) {
        StringBuilder sb = new StringBuilder();
        for (int[] w : weights) {
            if (w == null) {
                sb.append('-');
                continue;
            }
            int g = 0;
            for (int v : w) {
                g = gcd(g, v);
            }
            for (int v : w) {
                sb.append(g > 1 ? v / g : v).append(',');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Linear demand model
    // ------------------------------------------------------------------

    /**
     * Linear demand model: craft counts cascade topologically from the root in
     * a single pass. A key either follows one pattern ({@code assignment},
     * {@code weights} null or one-hot) with crafts = ceil(demand / opc), or —
     * when {@code weights[i]} has more than one nonzero entry — its demanded
     * crafts are distributed over its candidates by largest remainder under
     * the weights, and its input demand is the weighted sum of the candidates'
     * inputs (topological order guarantees the key's own demand is final when
     * reached). Leaves are priced against the stock view; the total shortfall
     * is returned. Returns null when the state cannot be evaluated.
     */
    private static BigInteger predict(Model model, int[] assignment, int[][] weights) {
        Eval eval = predictFull(model, assignment, weights);
        return eval == null ? null : eval.missing;
    }

    /** The linear model's outcome: total shortfall plus the full leaf demand table. */
    private static final class Eval {
        final BigInteger missing;
        final Map<IAEItemStack, BigInteger> demand;

        Eval(BigInteger missing, Map<IAEItemStack, BigInteger> demand) {
            this.missing = missing;
            this.demand = demand;
        }
    }

    private static Eval predictFull(Model model, int[] assignment, int[][] weights) {
        List<IAEItemStack> universe = model.universe;
        int n = model.n;
        boolean[] produced = new boolean[n];
        Map<IAEItemStack, BigInteger> demand = new HashMap<>();
        for (int oi = 0; oi < model.order.length; oi++) {
            int i = model.order[oi];
            int[] w = weights != null ? weights[i] : null;
            List<ICraftingPatternDetails> candidates = model.contendedList[i];
            if (w != null && countNonzero(w) > 1) {
                // Split key: distribute ceil(demand / opc) over the weights.
                // The ROOT's production requirement is the request amount
                // (deliver), not an accumulated upstream demand — treating the
                // root as zero-demand made every root-split state predict a
                // spurious empty cascade (shortfall 0) that the real engine
                // execution then falsified.
                BigInteger d;
                if (i == model.rootIdx) {
                    d = BigInteger.valueOf(model.deliver);
                } else {
                    d = demand.getOrDefault(universe.get(i), BigInteger.ZERO);
                }
                if (d.signum() <= 0) {
                    produced[i] = true; // nothing demanded upstream: idle
                    continue;
                }
                long opc = model.shapes.get(candidates.get(0)).outputPerCraft;
                long total = d.add(BigInteger.valueOf(opc - 1))
                        .divide(BigInteger.valueOf(opc))
                        .min(BigInteger.valueOf(1L << 40))
                        .longValueExact();
                long[] shares = largestRemainder(total, w);
                for (int j = 0; j < shares.length; j++) {
                    if (shares[j] <= 0) {
                        continue;
                    }
                    PatternShape shape = model.shapes.get(candidates.get(j));
                    if (shape == null) {
                        return null;
                    }
                    accumulate(shape, BigInteger.valueOf(shares[j]), demand);
                }
                produced[i] = true;
                continue;
            }
            ICraftingPatternDetails pattern;
            if (candidates != null) {
                if (w != null && countNonzero(w) == 1) {
                    // One-hot weight vector: the hot candidate IS the choice
                    // (the enumeration's assignment is not passed down here).
                    int hot = 0;
                    while (w[hot] <= 0) {
                        hot++;
                    }
                    pattern = candidates.get(hot);
                } else {
                    int idx = assignment != null ? assignment[i] : 0;
                    pattern = candidates.get(idx);
                }
            } else {
                pattern = model.chosen.get(universe.get(i));
            }
            if (pattern == null) {
                continue; // leaf: demand stays as accumulated
            }
            PatternShape shape = model.shapes.get(pattern);
            if (shape == null) {
                return null;
            }
            BigInteger times = craftCount(model, i, shape, demand);
            if (times == null) {
                return null;
            }
            produced[i] = true;
            if (times.signum() == 0) {
                continue;
            }
            accumulate(shape, times, demand);
        }
        // Sum shortfalls over non-produced demands.
        BigInteger missing = BigInteger.ZERO;
        for (Map.Entry<IAEItemStack, BigInteger> e : demand.entrySet()) {
            boolean covered = false;
            for (int i = 0; i < n; i++) {
                if (produced[i] && universe.get(i).isSameType(e.getKey())) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }
            BigInteger stock;
            try {
                stock = BigInteger.valueOf(model.simulation.extract(
                        e.getKey(), Long.MAX_VALUE, true));
            } catch (Throwable t) {
                stock = BigInteger.ZERO;
            }
            BigInteger shortfall = e.getValue().subtract(stock);
            if (shortfall.signum() > 0) {
                missing = missing.add(shortfall);
            }
        }
        return new Eval(missing, demand);
    }

    private static int countNonzero(int[] w) {
        int nonzero = 0;
        for (int v : w) {
            if (v > 0) {
                nonzero++;
            }
        }
        return nonzero;
    }

    /** Root: ceil(deliver / opc). Other keys: ceil(accumulated demand / opc). */
    private static BigInteger craftCount(Model model, int i, PatternShape shape,
                                         Map<IAEItemStack, BigInteger> demand) {
        BigInteger needed = i == model.rootIdx
                ? BigInteger.valueOf(model.deliver)
                : demand.getOrDefault(model.universe.get(i), BigInteger.ZERO);
        return needed.add(BigInteger.valueOf(shape.outputPerCraft - 1))
                .divide(BigInteger.valueOf(shape.outputPerCraft));
    }

    private static void accumulate(PatternShape shape, BigInteger times,
                                   Map<IAEItemStack, BigInteger> demand) {
        for (int s = 0; s < shape.inputs.size(); s++) {
            BigInteger amount = BigInteger.valueOf(shape.inputAmounts.get(s));
            demand.merge(shape.inputs.get(s), times.multiply(amount), BigInteger::add);
        }
    }

    /**
     * Distributes {@code total} over the weight vector by largest remainder
     * (ties to the lower index); the shares sum to exactly {@code total}.
     */
    private static long[] largestRemainder(long total, int[] weights) {
        long sum = 0;
        for (int w : weights) {
            sum += w;
        }
        long[] shares = new long[weights.length];
        if (sum <= 0) {
            shares[0] = total;
            return shares;
        }
        long distributed = 0;
        for (int j = 0; j < weights.length; j++) {
            shares[j] = total * weights[j] / sum;
            distributed += shares[j];
        }
        int j = 0;
        while (distributed < total) {
            shares[j % weights.length]++;
            distributed++;
            j++;
        }
        return shares;
    }

    // ------------------------------------------------------------------
    // Virtual pattern synthesis
    // ------------------------------------------------------------------

    /**
     * Encodes one key's weight vector as a virtual pattern and interns it per
     * key (PatternCompiler retains compiled bytecodes, so unbounded synthesis
     * would leak; the latest virtual pattern per key covers the common case).
     */
    private static VirtualPatternDetails internVirtual(Model model, int i, long[] shares) {
        List<ICraftingPatternDetails> candidates = model.contendedList[i];
        long total = 0;
        for (int j = 0; j < shares.length; j++) {
            total += shares[j] * model.shapes.get(candidates.get(j)).outputPerCraft;
        }
        if (total <= 0) {
            return null;
        }
        // Merge the weighted inputs of all constituents by type.
        List<IAEItemStack> lines = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (int j = 0; j < shares.length; j++) {
            if (shares[j] <= 0) {
                continue;
            }
            PatternShape shape = model.shapes.get(candidates.get(j));
            for (int s = 0; s < shape.inputs.size(); s++) {
                IAEItemStack line = shape.inputs.get(s);
                long amount = shape.inputAmounts.get(s) * shares[j];
                int hit = -1;
                for (int e = 0; e < lines.size(); e++) {
                    if (lines.get(e).isSameType(line)) {
                        hit = e;
                        break;
                    }
                }
                if (hit >= 0) {
                    amounts.set(hit, amounts.get(hit) + amount);
                } else {
                    lines.add(line);
                    amounts.add(amount);
                }
            }
        }
        IAEItemStack output = PatternCompat.getPrimaryOutput(candidates.get(0));
        if (output == null) {
            return null;
        }
        IAEItemStack[] in = lines.toArray(new IAEItemStack[0]);
        long[] amt = new long[amounts.size()];
        for (int e = 0; e < amt.length; e++) {
            amt[e] = amounts.get(e);
        }
        return VirtualPatternDetails.of(output, total, in, amt);
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Parses one pattern's shape; returns false when the pattern is unusable
     * for the model (no parseable output, reflection failures).
     */
    private static boolean shapeOf(ICraftingPatternDetails pattern,
                                   Map<ICraftingPatternDetails, PatternShape> shapes) {
        if (shapes.containsKey(pattern)) {
            return shapes.get(pattern) != null;
        }
        try {
            IAEItemStack[] condensed = pattern.getCondensedInputs();
            IAEItemStack out = PatternCompat.getPrimaryOutput(pattern);
            if (out == null) {
                shapes.put(pattern, null);
                return false;
            }
            PatternShape shape = new PatternShape();
            shape.outputPerCraft = Math.max(1L, out.getStackSize());
            if (condensed != null) {
                for (IAEItemStack in : condensed) {
                    if (in == null || in.getStackSize() <= 0) {
                        continue;
                    }
                    // A returned/catalyst input is a seed, not a per-craft
                    // consumption — same exclusion the VM's own self-key and
                    // feedback-loop analyses apply.
                    if (PatternCompiler.detectReturnedInput(pattern, in) != null) {
                        continue;
                    }
                    shape.inputs.add(normalized(in));
                    shape.inputAmounts.add(in.getStackSize());
                }
            }
            shapes.put(pattern, shape);
            return true;
        } catch (Throwable t) {
            shapes.put(pattern, null);
            return false;
        }
    }

    /** Size-less canonical form for model keys (mirrors the VM's token keys). */
    private static IAEItemStack normalized(IAEItemStack key) {
        IAEItemStack copy = key.copy();
        copy.reset();
        return copy;
    }

    private static BigInteger missingTotal(VMPlan plan) {
        BigInteger total = BigInteger.ZERO;
        for (Map.Entry<IAEItemStack, Long> e : plan.getMissingItems().entrySet()) {
            total = total.add(e.getValue() == null ? BigInteger.ZERO
                    : BigInteger.valueOf(e.getValue()));
        }
        return total;
    }

    /** Kahn topological order over the universe graph; null when cyclic. */
    private static int[] topologicalOrder(int[][] edges) {
        int n = edges.length;
        int[] inDegree = new int[n];
        for (int[] outs : edges) {
            for (int j : outs) {
                inDegree[j]++;
            }
        }
        int[] order = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                order[tail++] = i;
            }
        }
        while (head < tail) {
            int i = order[head++];
            for (int j : edges[i]) {
                if (--inDegree[j] == 0) {
                    order[tail++] = j;
                }
            }
        }
        return tail == n ? order : null;
    }

    /** Type-only membership test ({@code isSameType}), order-free and small-n linear. */
    private static boolean containsType(List<IAEItemStack> list, IAEItemStack key) {
        if (key == null) {
            return false;
        }
        for (IAEItemStack candidate : list) {
            if (candidate != null && candidate.isSameType(key)) {
                return true;
            }
        }
        return false;
    }
}
