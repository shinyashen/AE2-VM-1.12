package com.ae2vm.vm;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import com.ae2vm.compiler.PatternCompiler;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.ae2vm.test.harness.Bench.k;
import static com.ae2vm.test.harness.Bench.pat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Dead-ring pattern pruning ({@link DeadCycleGuard}) — the 1.12 port of the
 * upstream GTL CYCLE-AWARE / SEEDED-RING work: a candidate pattern whose inputs
 * would close a DEAD ring (unseeded, externally-unfed SCC of the recipe graph,
 * e.g. steel dust↔ingot with neither in stock) is pruned so the greedy resolver
 * prefers a healthy alternative, while seeded rings, externally-fed rings and
 * re-flow rings stay craftable (the VM's CALL-time guard remains the backstop).
 *
 * <p>Scenario key (mirrors the upstream CycleAwarePatternSelectionBenchmark):
 * {@code ab: B←A}, {@code ba: A←B} form the dead ring; {@code ca: A←C} (with
 * {@code dc: C←D} as a deeper healthy chain) is the alternative that must win.
 */
class DeadCycleGuardTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    private static BenchPatternDetails custom(String out, long outAmt, Object... inPairs) {
        int n = inPairs.length / 2;
        IAEItemStack[] inputs = new IAEItemStack[n];
        for (int i = 0; i < n; i++) {
            String id = (String) inPairs[i * 2];
            long amt = ((Number) inPairs[i * 2 + 1]).longValue();
            inputs[i] = new BenchAEItemStack(id, amt).setStackSize(amt);
        }
        BenchAEItemStack output = new BenchAEItemStack(out, outAmt);
        output.setStackSize(outAmt);
        return BenchPatternDetails.custom(inputs, new IAEItemStack[]{output});
    }

    private static final BenchPatternDetails AB = custom("B", 1, "A", 1L);
    private static final BenchPatternDetails BA = custom("A", 1, "B", 1L);
    private static final BenchPatternDetails CA = custom("A", 1, "C", 1L);
    private static final BenchPatternDetails DC = custom("C", 1, "D", 1L);
    private static final BenchPatternDetails DA = custom("A", 1, "D", 1L);
    /** Externally fed: A←B+C — even though B is a ring member, C feeds from outside. */
    private static final BenchPatternDetails BA_FED = custom("A", 1, "B", 1L, "C", 1L);
    /** Direct self-edge: A←A+X can never fire without inventing A. */
    private static final BenchPatternDetails SELF = custom("A", 1, "A", 1L, "X", 1L);

    private static Function<IAEItemStack, Collection<ICraftingPatternDetails>> lookup(
            Map<IAEItemStack, List<ICraftingPatternDetails>> map) {
        return map::get;
    }

    private static Function<IAEItemStack, Long> stock(Map<IAEItemStack, Long> map) {
        return key -> map.getOrDefault(key, 0L);
    }

    // ------------------------------------------------------------------
    // wouldCloseDeadRing
    // ------------------------------------------------------------------

    /** A←B with B←A in the lookup closes the A↔B ring: pruned. */
    @Test
    void detectsMutualRing() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA));
        m.put(k("B"), List.of(AB));
        assertTrue(DeadCycleGuard.wouldCloseDeadRing(lookup(m), BA, k("A"), stock(Map.of())),
                "BA must be detected as closing the A↔B dead ring");
    }

    /** A candidate consuming its own output is pruned even when it holds stock. */
    @Test
    void selfEdgeAlwaysPruned() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        Map<IAEItemStack, Long> st = new HashMap<>();
        st.put(k("A"), 64L);
        st.put(k("X"), 64L);
        assertTrue(DeadCycleGuard.wouldCloseDeadRing(lookup(m), SELF, k("A"), stock(st)),
                "A←A+X can never fire without inventing A: always pruned");
    }

    /** A ring with stock on any member is a legitimate production cycle: kept. */
    @Test
    void seededRingKept() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA));
        m.put(k("B"), List.of(AB));
        Map<IAEItemStack, Long> st = new HashMap<>();
        st.put(k("B"), 1L);
        assertFalse(DeadCycleGuard.wouldCloseDeadRing(lookup(m), BA, k("A"), stock(st)),
                "a seeded ring (B in stock) must stay craftable");
    }

    /** A candidate consuming an outside key feeds the ring: kept. */
    @Test
    void externallyFedCandidateKept() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA_FED));
        m.put(k("B"), List.of(AB));
        assertFalse(DeadCycleGuard.wouldCloseDeadRing(lookup(m), BA_FED, k("A"), stock(Map.of())),
                "A←B+C consumes outside key C: the ring is externally fed");
    }

    /**
     * A sibling candidate producing the target does NOT count as feeding the
     * ring — the siblings are exactly what pruning is choosing between, so the
     * ring-prone one is pruned and the healthy sibling (D→A) takes over.
     */
    @Test
    void siblingCandidateDoesNotFeedRing() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA, DA));
        m.put(k("B"), List.of(AB));
        assertTrue(DeadCycleGuard.wouldCloseDeadRing(lookup(m), BA, k("A"), stock(Map.of())),
                "BA must be pruned in favour of the healthy sibling DA");
    }

    /**
     * An outside key (introduced by a sibling candidate's input) whose OWN
     * pattern outputs a ring member as a byproduct feeds the ring: kept.
     */
    @Test
    void byproductProducerFeedsRing() {
        BenchPatternDetails ca = custom("A", 1, "C", 1L);
        BenchPatternDetails cp = BenchPatternDetails.custom(
                new IAEItemStack[]{new BenchAEItemStack("Y", 1).setStackSize(1)},
                new IAEItemStack[]{new BenchAEItemStack("C", 1).setStackSize(1),
                        new BenchAEItemStack("A", 1).setStackSize(1)});
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA, ca));
        m.put(k("B"), List.of(AB));
        m.put(k("C"), List.of(cp));
        assertFalse(DeadCycleGuard.wouldCloseDeadRing(lookup(m), BA, k("A"), stock(Map.of())),
                "C's own pattern emits A as a byproduct: the ring is externally fed");
    }

    /** A deeper healthy chain (C←D) never forms a ring back to A. */
    @Test
    void deeperChainNoRing() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA, CA));
        m.put(k("B"), List.of(AB));
        m.put(k("C"), List.of(DC));
        assertFalse(DeadCycleGuard.wouldCloseDeadRing(lookup(m), CA, k("A"), stock(Map.of())),
                "A←C with C←D is a healthy chain: no ring");
    }

    // ------------------------------------------------------------------
    // pruneDeadRings
    // ------------------------------------------------------------------

    /** The healthy candidate survives, the ring-prone one is dropped. */
    @Test
    void pruneKeepsHealthyCandidate() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA, CA));
        m.put(k("B"), List.of(AB));
        List<ICraftingPatternDetails> viable = DeadCycleGuard.pruneDeadRings(
                lookup(m), k("A"), List.of(BA, CA), stock(Map.of()));
        assertEquals(1, viable.size(), "only the healthy candidate survives");
        assertEquals(CA, viable.get(0));
    }

    /** When EVERY candidate is ring-prone the originals are kept (runtime backstop). */
    @Test
    void allPrunedKeepsOriginals() {
        Map<IAEItemStack, List<ICraftingPatternDetails>> m = new HashMap<>();
        m.put(k("A"), List.of(BA));
        m.put(k("B"), List.of(AB));
        List<ICraftingPatternDetails> viable = DeadCycleGuard.pruneDeadRings(
                lookup(m), k("A"), List.of(BA), stock(Map.of()));
        assertEquals(1, viable.size(), "no choice must remain rather than none");
        assertEquals(BA, viable.get(0));
    }

    // ------------------------------------------------------------------
    // End to end: a resolver wired like AE2VMCrafting.resolve must route the
    // request through the healthy candidate.
    // ------------------------------------------------------------------

    /** Drives the VM with a resolver that prunes before picking, like production. */
    @Test
    void endToEndResolverRoutesThroughHealthyCandidate() {
        BenchPatternDetails z = pat("Z", 1, "A", 2L);
        Map<IAEItemStack, ICraftingPatternDetails> view = new LinkedHashMap<>();
        view.put(k("Z"), z);
        Map<IAEItemStack, List<ICraftingPatternDetails>> contended = new HashMap<>();
        contended.put(k("A"), List.of(BA, CA));
        // The lookup must see the ring's other half (B's producer A→B), exactly
        // like the real getCraftingFor index would.
        Map<IAEItemStack, List<ICraftingPatternDetails>> producers = new HashMap<>(contended);
        producers.put(k("B"), List.of(AB));
        Map<IAEItemStack, Long> emptyStock = new HashMap<>();

        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(z);
        PatternCompiler.compileIfAbsent(BA);
        PatternCompiler.compileIfAbsent(CA);
        PatternCompiler.compileIfAbsent(AB);
        CraftingBytecode request = PatternCompiler.compileRequest(z, 1);

        CraftingVM vm = new CraftingVM("dead-cycle-guard", view::get);
        BenchSimulationState sim = new BenchSimulationState();
        sim.seed("C", 2);
        vm.setPatternResolver(key -> {
            List<ICraftingPatternDetails> candidates = producers.get(key);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            List<ICraftingPatternDetails> viable = DeadCycleGuard.pruneDeadRings(
                    producers::get, key, candidates, stock(emptyStock));
            // Smallest-output pick over the survivors (pickBestPattern semantics).
            ICraftingPatternDetails best = null;
            long bestOut = Long.MAX_VALUE;
            for (ICraftingPatternDetails p : viable) {
                long out = p.getOutputs()[0].getStackSize();
                if (out < bestOut) {
                    bestOut = out;
                    best = p;
                }
            }
            return best;
        });
        VMPlan plan = vm.execute(request, sim);
        CpuLifecycleAssert.auto(plan);
        assertTrue(plan.getMissingItems().isEmpty(),
                "the request must complete through the healthy A←C path");
        assertEquals(0L, plan.getUsedItems().get(k("B")));
        assertEquals(2L, plan.getUsedItems().get(k("C")));
    }
}
