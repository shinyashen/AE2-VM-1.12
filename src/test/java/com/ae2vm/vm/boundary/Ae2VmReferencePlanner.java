package com.ae2vm.vm.boundary;
import com.ae2vm.test.fakes.TraceSimulationState;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.PatternChoiceRepair;
import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import com.moakiee.thunderbolt.core.planner.CraftOutput;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.12 port of the reference-suite translation layer: drives the ported VM engine
 * ({@link PatternCompiler} + {@link CraftingVM}) through the Thunderbolt reference
 * capability suite.
 *
 * <p>The reference graphs speak the 1.21 key model ({@code CraftInput.returned} /
 * {@code finiteUse} / {@code returnedFrom} host stock); 1.12 patterns expose none
 * of those, so the translation ENCODES each concept the way real 1.12 patterns do
 * and the engine's 1.12-native detection ({@code PatternCompiler#detectReturnedInput})
 * picks it up — exactly the encodings the ported unit tests use:
 * <ul>
 *   <li><b>catalyst</b> ({@code returned}, infinite uses) → the returned key is
 *       added as a BYPRODUCT of the same amount; the strict output-equality probe
 *       classifies it as a CATALYST_SEED (the marker / essence pattern shape).</li>
 *   <li><b>durability</b> ({@code returned}, finite uses) → the key becomes a
 *       damageable item (maxDamage = uses, profiled everywhere it appears) and a
 *       degraded damage+1 byproduct is added; the damaged-output probe derives
 *       uses = maxDamage (the tool pattern shape from DurabilityToolTest).</li>
 *   <li><b>host-owned reusable stock</b> ({@code returnedFrom}) → accepted
 *       physical variants become per-slot substitute variants (FUZZY_SLOT) and
 *       the host pool is merged into the network stock.</li>
 *   <li>plain inputs are consumed per craft; byproducts are inserted like AE2.</li>
 * </ul>
 */
public final class Ae2VmReferencePlanner implements ReferencePlanner {

    /** Replay budget for the multi-pattern choice repair (see PatternChoiceRepair). */
    private static final int REPAIR_EXTRA_PASSES = 32;

    /** Per-key item profile: (damage, maxDamage) on the fake item stack. */
    private record Profile(int damage, int maxDamage) {
        static final Profile PLAIN = new Profile(0, 0);
    }

    @Override
    public boolean check(ReferenceScenario scenario) {
        // Mirror Thunderbolt's own suite: the VM always attempts the calculation.
        return true;
    }

    @Override
    public CraftPlan<String> plan(ReferenceScenario scenario) {
        CraftGraph<String> graph = scenario.graph();
        String target = scenario.target();
        long amount = scenario.amount();

        // 1) Collect every key reachable from the target (pattern outputs + inputs).
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(target);
        reachable.add(target);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (CraftPattern<String> pattern : graph.patternsFor(key)) {
                for (CraftInput<String> input : pattern.inputs()) {
                    if (reachable.add(input.key())) {
                        queue.add(input.key());
                    }
                }
            }
        }

        // 2) Item profiles: a finiteUse input key is a damageable item whose
        //    maxDamage encodes its uses (step-1 degradation). Pre-resolve the
        //    reusable-stock route candidates per pattern input as well.
        Map<String, Profile> profiles = new HashMap<>();
        Map<CraftInput<String>, List<String>> routes = new HashMap<>();
        for (String output : reachable) {
            for (CraftPattern<String> pattern : graph.patternsFor(output)) {
                for (CraftInput<String> input : pattern.inputs()) {
                    if (input.returned() && input.uses() != CraftInput.INFINITE_USES) {
                        profiles.put(input.key(), new Profile(0, Math.toIntExact(input.uses())));
                    }
                    if (input.reusableStockSource() != null) {
                        routes.put(input, graph.reusableStockCandidates(
                                input.reusableStockSource(), input.key()));
                    }
                }
            }
        }

        // 3) Translate each reachable pattern into a BenchPatternDetails. The
        //    translated details are cached per CraftPattern so the identity is
        //    stable between the byOutput resolver and setAllPatternsResolver.
        Map<IAEItemStack, ICraftingPatternDetails> byOutput = new LinkedHashMap<>();
        Map<IAEItemStack, List<ICraftingPatternDetails>> candidatesByOutput =
                new LinkedHashMap<>();
        Map<BenchPatternDetails, CraftPattern<String>> origin = new HashMap<>();
        Map<CraftPattern<String>, BenchPatternDetails> translated = new HashMap<>();
        for (String output : reachable) {
            List<ICraftingPatternDetails> forOutput = new ArrayList<>();
            for (CraftPattern<String> pattern : graph.patternsFor(output)) {
                BenchPatternDetails details = translated.computeIfAbsent(pattern,
                        p -> toDetails(p, profiles, routes));
                byOutput.put(profileKey(output, profiles), details);
                forOutput.add(details);
                origin.put(details, pattern);
            }
            if (forOutput.size() > 1) {
                // Verified alternatives for the multi-pattern repair loop; the
                // base resolver itself keeps the LAST registered pattern.
                candidatesByOutput.put(profileKey(output, profiles), forOutput);
            }
        }

        // 4) Seed the simulation with the scenario's network stock PLUS the
        //    host-private reusable stock for every route variant.
        Map<BenchAEItemStack, Long> stock = new LinkedHashMap<>();
        for (String key : reachable) {
            long available = graph.stock(key);
            if (available > 0) {
                stock.merge(profileKey(key, profiles), available, Long::sum);
            }
        }
        for (String output : reachable) {
            for (CraftPattern<String> pattern : graph.patternsFor(output)) {
                for (CraftInput<String> input : pattern.inputs()) {
                    if (input.reusableStockSource() == null) {
                        continue;
                    }
                    for (String candidate : routes.getOrDefault(input, List.of())) {
                        long rs = graph.reusableStock(
                                input.reusableStockSource().storageScope(), candidate);
                        if (rs > 0) {
                            stock.merge(profileKey(candidate, profiles), rs, Long::sum);
                        }
                    }
                }
            }
        }

        ICraftingPatternDetails top = byOutput.get(profileKey(target, profiles));
        if (top == null) {
            // No pattern crafts the target: report the whole request as missing.
            return new CraftPlan<>(true, false, Map.of(), Map.of(), Map.of(),
                    Map.of(target, amount), Map.of(target, amount), 0, false);
        }

        // 5) Compile + run the VM (global compile cache cleared to isolate
        //    per-scenario cost; it also resets the fuzzy groups). ALL candidate
        //    patterns are pre-compiled so a repair replay resolving an
        //    alternative never pays compile cost inside the measured pass.
        PatternCompiler.clearCache();
        for (ICraftingPatternDetails details : byOutput.values()) {
            PatternCompiler.compileIfAbsent(details);
        }
        for (List<ICraftingPatternDetails> candidates : candidatesByOutput.values()) {
            for (ICraftingPatternDetails details : candidates) {
                PatternCompiler.compileIfAbsent(details);
            }
        }
        CraftingBytecode requestBytecode = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("ae2vm-bench", byOutput::get);
        // Feed ALL patterns per output so the pure-conversion-ring analysis sees
        // the full ring (A<->B<->C): a single chosen pattern per key hides
        // exchange orientations and would let a seedless ring slip through as
        // "feasible". The translated copies are identity-stable, so plan
        // firings still map back through `origin`.
        vm.setAllPatternsResolver(key -> {
            String id = ((BenchAEItemStack) key).id;
            List<ICraftingPatternDetails> list = new ArrayList<>();
            for (CraftPattern<String> pattern : graph.patternsFor(id)) {
                list.add(translated.computeIfAbsent(pattern, p -> toDetails(p, profiles, routes)));
            }
            return list;
        });
        // Pristine stock view for the repair model: never executed against.
        BenchSimulationState stockView = simulationFrom(stock);
        // Per-pass choice records: the view (base map merged with the pass's
        // preferences) is what the repair loop blames against. A preference on
        // the root key re-compiles the request bytecode for that pattern.
        PatternChoiceRepair.Pass pass = prefs -> {
            Map<IAEItemStack, ICraftingPatternDetails> view =
                    new LinkedHashMap<>(byOutput);
            view.putAll(prefs);
            ICraftingPatternDetails passTop = view.get(requestBytecode.getOutput());
            CraftingBytecode passCode = requestBytecode;
            if (passTop != null && passTop != top) {
                passCode = PatternCompiler.compileRequest(passTop, amount);
            }
            vm.setPatternResolver(view::get);
            PatternChoiceRepair.Choices passChoices = new PatternChoiceRepair.Choices();
            for (Map.Entry<IAEItemStack, ICraftingPatternDetails> e : view.entrySet()) {
                passChoices.record(e.getKey(), e.getValue(),
                        candidatesByOutput.get(e.getKey()));
            }
            BenchSimulationState sim = simulationFrom(stock);
            // To trace per-craft consumption for one scenario, wrap `sim` in
            // TraceSimulationState (test-source diagnostic tool) here.
            com.ae2vm.vm.VMPlan p = vm.execute(passCode, sim);
            return new PatternChoiceRepair.PassResult(p, passChoices, stockView);
        };
        com.ae2vm.vm.VMPlan plan = PatternChoiceRepair.repair(pass, REPAIR_EXTRA_PASSES);

        // 6) Map the VM plan back to the Thunderbolt CraftPlan<String>.
        Map<String, Long> used = new HashMap<>();
        for (IAEItemStack key : plan.getUsedItems().keys()) {
            used.put(((BenchAEItemStack) key).id, plan.getUsedItems().get(key));
        }
        Map<String, Long> missing = new HashMap<>();
        for (IAEItemStack key : plan.getMissingItems().keys()) {
            missing.put(((BenchAEItemStack) key).id, plan.getMissingItems().get(key));
        }
        Map<CraftPattern<String>, Long> firings = new HashMap<>();
        for (Map.Entry<ICraftingPatternDetails, Long> entry : plan.getPatternTimes().entrySet()) {
            CraftPattern<String> source = origin.get(entry.getKey());
            if (source != null) {
                firings.put(source, entry.getValue());
            }
        }
        if (scenario.target().startsWith("X")) {
            StringBuilder sb = new StringBuilder("[solver-db] firings " + scenario.id() + ":");
            for (Map.Entry<CraftPattern<String>, Long> e : firings.entrySet()) {
                sb.append(" ").append(e.getKey().output()).append("<-");
                for (CraftInput<String> i : e.getKey().inputs()) {
                    sb.append(i.key()).append('+');
                }
                sb.append("x").append(e.getValue());
            }
            sb.append(" missing=").append(missing);
            System.out.println(sb);
        }
        boolean feasible = missing.isEmpty();
        return new CraftPlan<>(true, feasible, firings, used, Map.of(), missing,
                Map.of(), 0, false);
    }

    private static BenchAEItemStack profileKey(String id, Map<String, Profile> profiles) {
        Profile p = profiles.getOrDefault(id, Profile.PLAIN);
        return new BenchAEItemStack(id, p.damage(), p.maxDamage(), 1);
    }

    /**
     * Translates one reference pattern. Returned inputs are re-encoded as real
     * 1.12 pattern shapes (byproduct hand-back / damaged tool output).
     */
    private BenchPatternDetails toDetails(CraftPattern<String> pattern,
                                          Map<String, Profile> profiles,
                                          Map<CraftInput<String>, List<String>> routes) {
        List<IAEItemStack> condensed = new ArrayList<>();
        List<IAEItemStack> outputs = new ArrayList<>();
        outputs.add(profileKey(pattern.output(), profiles).setStackSize(pattern.outputAmount()));
        int slot = 0;
        Map<Integer, List<IAEItemStack>> slotSubs = new HashMap<>();
        for (CraftInput<String> input : pattern.inputs()) {
            condensed.add(profileKey(input.key(), profiles).setStackSize(input.amount()));
            if (input.returned() && input.uses() == CraftInput.INFINITE_USES) {
                // Catalyst: same-key, same-amount byproduct -> strict output
                // equality -> CATALYST_SEED (one seed serves the whole batch).
                outputs.add(profileKey(input.key(), profiles).setStackSize(input.amount()));
            } else if (input.returned()) {
                // Finite-use tool: damaged (damage+1) byproduct of the same item;
                // the probe derives uses = (maxDamage - damage) / step = uses.
                Profile p = profiles.getOrDefault(input.key(), Profile.PLAIN);
                outputs.add(damaged(input.key(), p.damage() + 1, p.maxDamage(), input.amount()));
            }
            if (input.reusableStockSource() != null) {
                // Host-owned reusable stock: the slot accepts any candidate variant.
                List<IAEItemStack> variants = new ArrayList<>();
                for (String candidate : routes.getOrDefault(input, List.of())) {
                    if (candidate.equals(input.key())) {
                        continue;
                    }
                    variants.add(profileKey(candidate, profiles).setStackSize(input.amount()));
                }
                if (!variants.isEmpty()) {
                    slotSubs.put(slot, variants);
                }
            }
            slot++;
        }
        for (CraftOutput<String> output : pattern.byproducts()) {
            outputs.add(profileKey(output.key(), profiles).setStackSize(output.amount()));
        }
        BenchPatternDetails base = BenchPatternDetails.custom(
                condensed.toArray(new IAEItemStack[0]),
                outputs.toArray(new IAEItemStack[0]));
        if (slotSubs.isEmpty()) {
            return base;
        }
        return BenchPatternDetails.withSlotVariants(base, slotSubs);
    }

    private static BenchAEItemStack damaged(String id, int damage, int maxDamage, long amount) {
        return new BenchAEItemStack(id, damage, maxDamage, amount);
    }

    private static BenchSimulationState simulationFrom(Map<BenchAEItemStack, Long> stock) {
        BenchSimulationState sim = new BenchSimulationState();
        for (Map.Entry<BenchAEItemStack, Long> e : stock.entrySet()) {
            sim.seedKey(e.getKey(), e.getValue());
        }
        return sim;
    }
}
