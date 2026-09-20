package com.ae2vm.vm.closure;

import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.vm.PlanClosure;
import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closure's ledger semantics on hand-checkable shapes: linear chains
 * withdraw exactly the net draw, injected chains re-balance a ring to cover
 * (the EcaseMemberRebalance requirement — coverage, not billing), net-losing
 * cycles diverge into the escape hatch, the delivery root's own shortfall is
 * production-invariant and disclosed, catalysts net to zero — and the family
 * allocation's per-consumer extraction semantics (AE2UEL canCraft :444-521):
 * a processing consumer is findPrecise-exact and never family-covered, a
 * craftable consumer fuzzy-extracts and may draw the sibling pools.
 */
class PlanClosureTest {

    private static BenchAEItemStack k(String id) {
        return new BenchAEItemStack(id, 1);
    }

    /** A recipe line: outputs first (primary first), then {id, amount} inputs. */
    private static BenchPatternDetails pat(String[] outs, long[] outAmts, Object... inPairs) {
        List<IAEItemStack> in = new ArrayList<>();
        for (int i = 0; i < inPairs.length; i += 2) {
            long amt = (Long) inPairs[i + 1];
            in.add(k((String) inPairs[i]).setStackSize(amt));
        }
        List<IAEItemStack> out = new ArrayList<>();
        for (int i = 0; i < outs.length; i++) {
            out.add(k(outs[i]).setStackSize(outAmts[i]));
        }
        return BenchPatternDetails.custom(in.toArray(new IAEItemStack[0]),
                out.toArray(new IAEItemStack[0]));
    }

    /** A closure view over the given patterns (one producer per key asserted). */
    private static PlanClosure.View view(List<BenchPatternDetails> patterns,
                                         Map<String, Long> stock) {
        return view(patterns, stock, Map.of(), Map.of(), Map.of());
    }

    /**
     * Family-aware view: {@code nbtFamilies}/{@code substitutes} map a key id
     * to its sibling/substitute ids (their stock is the shared stock map),
     * {@code fuzzyShares} maps a pattern to its replacement-slot per-craft
     * shares.
     */
    private static PlanClosure.View view(List<BenchPatternDetails> patterns,
                                         Map<String, Long> stock,
                                         Map<String, List<String>> nbtFamilies,
                                         Map<String, List<String>> substitutes,
                                         Map<BenchPatternDetails, Map<String, Long>> fuzzyShares) {
        Map<BenchAEItemStack, ICraftingPatternDetails> producers = new LinkedHashMap<>();
        for (BenchPatternDetails p : patterns) {
            for (IAEItemStack o : p.getCondensedOutputs()) {
                BenchAEItemStack key = (BenchAEItemStack) o.copy().reset().setStackSize(1);
                if (producers.put(key, p) != null) {
                    throw new IllegalStateException("two producers for " + key);
                }
            }
        }
        Map<BenchAEItemStack, BigInteger> stockMap = new HashMap<>();
        for (var e : stock.entrySet()) {
            stockMap.put(k(e.getKey()), BigInteger.valueOf(e.getValue()));
        }
        return new PlanClosure.View() {
            @Override
            public ICraftingPatternDetails producerOf(IAEItemStack key) {
                return producers.get((BenchAEItemStack) key.copy().reset().setStackSize(1));
            }

            @Override
            public Map<IAEItemStack, BigInteger> inputsOf(ICraftingPatternDetails p) {
                return perCraft(((BenchPatternDetails) p).getCondensedInputs());
            }

            @Override
            public Map<IAEItemStack, BigInteger> outputsOf(ICraftingPatternDetails p) {
                return perCraft(((BenchPatternDetails) p).getCondensedOutputs());
            }

            @Override
            public BigInteger stockOf(IAEItemStack key) {
                return stockMap.getOrDefault(
                        (BenchAEItemStack) key.copy().reset().setStackSize(1),
                        BigInteger.ZERO);
            }

            @Override
            public Map<IAEItemStack, BigInteger> fuzzyInputsOf(ICraftingPatternDetails p) {
                Map<String, Long> shares = fuzzyShares.get(p);
                Map<IAEItemStack, BigInteger> out = new LinkedHashMap<>();
                if (shares != null) {
                    for (var e : shares.entrySet()) {
                        out.put(k(e.getKey()), BigInteger.valueOf(e.getValue()));
                    }
                }
                return out;
            }

            @Override
            public List<IAEItemStack> nbtFamilyOf(IAEItemStack key) {
                return familyOf(key, nbtFamilies);
            }

            @Override
            public List<IAEItemStack> substitutesOf(IAEItemStack key) {
                return familyOf(key, substitutes);
            }

            private List<IAEItemStack> familyOf(IAEItemStack key,
                                                Map<String, List<String>> table) {
                List<IAEItemStack> out = new ArrayList<>();
                String id = ((BenchAEItemStack) key.copy().reset().setStackSize(1)).id;
                for (String s : table.getOrDefault(id, List.of())) {
                    out.add(k(s));
                }
                return out;
            }
        };
    }

    private static Map<IAEItemStack, BigInteger> perCraft(IAEItemStack[] lines) {
        Map<IAEItemStack, BigInteger> map = new LinkedHashMap<>();
        for (IAEItemStack l : lines) {
            map.put((IAEItemStack) l.copy().reset().setStackSize(1),
                    BigInteger.valueOf(l.getStackSize()));
        }
        return map;
    }

    private static long craftsOf(PlanClosure.Result r, ICraftingPatternDetails p) {
        return r.plans.getOrDefault(p, BigInteger.ZERO).longValue();
    }

    private static long drawOf(PlanClosure.Result r, String key) {
        return r.netDraw.getOrDefault(k(key), BigInteger.ZERO).longValue();
    }

    @Test
    void linearChainWithdrawsExactlyTheNetDraw() {
        BenchPatternDetails pB = pat(new String[]{"B"}, new long[]{1}, "A", 1L);
        BenchPatternDetails pC = pat(new String[]{"C"}, new long[]{1}, "B", 2L);
        List<BenchPatternDetails> pats = List.of(pB, pC);
        Map<String, Long> stock = Map.of("A", 100L);
        PlanClosure.Result r = PlanClosure.close(k("C"), BigInteger.TEN, pC, view(pats, stock));
        assertTrue(r.converged);
        assertEquals(10L, craftsOf(r, pC), "the seed covers the delivery");
        assertEquals(20L, craftsOf(r, pB), "ceil(20 B / 1 per craft)");
        assertEquals(20L, drawOf(r, "A"), "only the net draw leaves the network");
        assertEquals(0L, drawOf(r, "B"), "B's production refills its consumption");
        assertTrue(r.missing.isEmpty());
        assertEquals(20L, r.withdraw.get(k("A")).longValue());
    }

    @Test
    void injectedChainRebalancesTheRingToCover() {
        // ring: 4 s -> 1 i, 1 i -> 12 s; the root C draws 1 i per craft — the
        // ring must RE-BALANCE around the injected draw, not bill it. Hand
        // solution from zero s/i stock: pC's 10 i + pS's own 5 i draw = 15 i
        // for pI=15; pI's 60 s draw = pS=5's 60 s — every net draw closes at
        // zero (the LEAST fixpoint: the loop stops the round nothing grows).
        BenchPatternDetails pI = pat(new String[]{"i"}, new long[]{1}, "s", 4L);
        BenchPatternDetails pS = pat(new String[]{"s"}, new long[]{12}, "i", 1L);
        BenchPatternDetails pC = pat(new String[]{"C"}, new long[]{1}, "i", 1L, "X", 1L);
        List<BenchPatternDetails> pats = List.of(pI, pS, pC);
        Map<String, Long> stock = Map.of("X", 100L);
        PlanClosure.Result r = PlanClosure.close(k("C"), BigInteger.TEN, pC, view(pats, stock));
        assertTrue(r.converged);
        assertEquals(10L, craftsOf(r, pC));
        assertEquals(15L, craftsOf(r, pI), "the ring re-balances to the injected draw");
        assertEquals(5L, craftsOf(r, pS));
        assertEquals(0L, drawOf(r, "i"), "i is covered by its re-balanced production");
        assertEquals(0L, drawOf(r, "s"));
        assertEquals(10L, drawOf(r, "X"), "only the true external input is drawn");
        assertTrue(r.missing.isEmpty(), "coverage, not billing: " + r.missing);
    }

    @Test
    void netLosingCycleDivergesIntoTheEscapeHatch() {
        // cycle 2 A -> 1 B, 1 B -> 1 A behind the root R (1 A -> 1 R): every
        // round the ring NET-LOSES one A on a NON-root key, so the bump grows
        // unboundedly — the CAP/round bound must report divergence, and the
        // diverged result must carry no triple to adopt. (A net loss on the
        // ROOT key would disclose as missing instead — production-invariant.)
        BenchPatternDetails pB = pat(new String[]{"B"}, new long[]{1}, "A", 2L);
        BenchPatternDetails pA = pat(new String[]{"A"}, new long[]{1}, "B", 1L);
        BenchPatternDetails pR = pat(new String[]{"R"}, new long[]{1}, "A", 1L);
        List<BenchPatternDetails> pats = List.of(pB, pA, pR);
        PlanClosure.Result r = PlanClosure.close(k("R"), BigInteger.TEN, pR,
                view(pats, Map.of()));
        assertFalse(r.converged, "a net-losing cycle diverges");
        assertTrue(r.withdraw.isEmpty() && r.missing.isEmpty() && r.netDraw.isEmpty(),
                "a diverged result carries no triple to adopt");
    }

    @Test
    void rootShortfallIsProductionInvariantAndDisclosed() {
        // ring through the root: A -> 2 B -> 1 A. The root A's production
        // siphons to the delivery (:265), so pB's A consumption can never be
        // crafted away — it discloses as missing instead of diverging on
        // dead bumps (bumping A's producer adds siphoned units only).
        BenchPatternDetails pB = pat(new String[]{"B"}, new long[]{2}, "A", 1L);
        BenchPatternDetails pA = pat(new String[]{"A"}, new long[]{1}, "B", 1L);
        List<BenchPatternDetails> pats = List.of(pB, pA);
        PlanClosure.Result r = PlanClosure.close(k("A"), BigInteger.TEN, pA,
                view(pats, Map.of()));
        assertTrue(r.converged);
        assertEquals(10L, craftsOf(r, pA), "the seed covers the delivery");
        assertEquals(5L, craftsOf(r, pB), "B's demand is demand-driven (2 per craft)");
        assertEquals(5L, r.missing.get(k("A")).longValue(),
                "pB consumes 5 A; all 10 produced A siphon to the delivery; stock 0");
        assertTrue(r.missing.size() == 1, "no other gaps: " + r.missing);
    }

    @Test
    void crossPatternCatalystCycleNetsToZero() {
        // cat + X -> Y and Y -> 2 cat: the catalyst circulates across TWO
        // patterns (no single pattern re-consumes its own output, so the
        // ledger owns it) — the least fixpoint balances cat exactly, X is the
        // only net draw. Root R = Y -> R keeps the siphon out of the cycle.
        BenchPatternDetails pR = pat(new String[]{"R"}, new long[]{1}, "Y", 1L);
        BenchPatternDetails pY = pat(new String[]{"Y"}, new long[]{1}, "X", 1L, "cat", 1L);
        BenchPatternDetails pCat = pat(new String[]{"cat"}, new long[]{2}, "Y", 1L);
        List<BenchPatternDetails> pats = List.of(pR, pY, pCat);
        Map<String, Long> stock = Map.of("X", 100L);
        PlanClosure.Result r = PlanClosure.close(k("R"), BigInteger.TEN, pR, view(pats, stock));
        assertTrue(r.converged);
        // Y: pR 10 + pCat 10 consumed = pY 20 produced; cat: 20 consumed = 20 produced
        assertEquals(10L, craftsOf(r, pR));
        assertEquals(20L, craftsOf(r, pY));
        assertEquals(10L, craftsOf(r, pCat));
        assertEquals(20L, drawOf(r, "X"), "only X leaves the network");
        assertEquals(0L, drawOf(r, "cat"), "the catalyst's return covers its draw");
        assertEquals(0L, drawOf(r, "Y"));
        assertTrue(r.missing.isEmpty());
    }

    @Test
    void selfConsumingRootDisclosesTheSiphonedDemand() {
        // X + cat -> X + Y rooted at X: every produced X is DELIVERED (:265),
        // so the pattern's net output of X is zero — the seed cannot be
        // crafted away and the whole delivery discloses as honest missing
        BenchPatternDetails pX = pat(new String[]{"X", "Y"}, new long[]{1, 1},
                "X", 1L, "cat", 1L);
        List<BenchPatternDetails> pats = List.of(pX);
        Map<String, Long> stock = Map.of("cat", 5L);
        PlanClosure.Result r = PlanClosure.close(k("X"), BigInteger.TEN, pX, view(pats, stock));
        assertTrue(r.converged, "net-zero self-consumption discloses, never diverges");
        assertEquals(10L, craftsOf(r, pX), "the delivery seed");
        assertEquals(10L, r.missing.get(k("X")).longValue(),
                "the siphoned self-consumption is the honest disclosure");
    }

    @Test
    void recursionAmplifierBumpsByNetOutput() {
        // X + A -> 2X: each craft NETS one X and burns one A — the bump is
        // ceil(gap / netPer) and the ledger closes on A's stock
        BenchPatternDetails pX = pat(new String[]{"X"}, new long[]{2}, "X", 1L, "A", 1L);
        List<BenchPatternDetails> pats = List.of(pX);
        Map<String, Long> stock = Map.of("X", 5L, "A", 100L);
        PlanClosure.Result r = PlanClosure.close(k("X"), BigInteger.TEN, pX, view(pats, stock));
        assertTrue(r.converged);
        // seed ceil(10/2) = 5 crafts produce the whole delivery; the siphon
        // keeps their 5 self-consumed X on stock (5 >= 5) — no bump, no more
        assertEquals(5L, craftsOf(r, pX), "ceil(deliver / outPer)");
        assertEquals(5L, drawOf(r, "A"), "each craft burns one A");
        assertTrue(r.missing.isEmpty());
    }

    // ------------------------------------------------------------------
    // per-consumer extraction semantics: AE2UEL canCraft :444-521 — a
    // processing pattern SIMULATE-extracts findPrecise (FULL identity, no
    // variant serves it); a craftable pattern fuzzy-extracts. A family
    // member may therefore only ever serve a craftable consumer's share.

    @Test
    void processingConsumerIsNeverFamilyCovered() {
        // the pre-fix shape that stalled the live CPU: K leaf, sibling V
        // stocked — the allocation drew V for a PROCESSING consumer, the
        // withdrawal booked V, and the CPU's findPrecise(K) starved. The
        // gap must disclose on K instead.
        BenchPatternDetails pR = pat(new String[]{"R"}, new long[]{1}, "K", 1L);
        List<BenchPatternDetails> pats = List.of(pR);
        PlanClosure.Result r = PlanClosure.close(k("R"), BigInteger.TEN, pR,
                view(pats, Map.of("V", 50L), Map.of("K", List.of("V")),
                        Map.of("K", List.of("V")), Map.of()));
        assertTrue(r.converged);
        assertEquals(10L, craftsOf(r, pR));
        assertEquals(10L, r.missing.get(k("K")).longValue(),
                "the strict gap discloses on the exact key");
        assertTrue(r.missing.size() == 1, "no sibling leakage: " + r.missing);
        assertTrue(r.withdraw.isEmpty(),
                "a processing consumer's plan must not withdraw the sibling: " + r.withdraw);
        assertEquals(10L, drawOf(r, "K"));
        assertEquals(0L, drawOf(r, "V"));
    }

    @Test
    void craftableConsumerDrawsTheFamily() {
        // the same shape with a CRAFTABLE consumer: the fuzzy-extracting
        // branch (:454-516) accepts the damage-equal sibling — the family
        // draw is executable and the gap closes without crafting
        BenchPatternDetails pR = pat(new String[]{"R"}, new long[]{1}, "K", 1L).asCraftable();
        List<BenchPatternDetails> pats = List.of(pR);
        PlanClosure.Result r = PlanClosure.close(k("R"), BigInteger.TEN, pR,
                view(pats, Map.of("V", 50L), Map.of("K", List.of("V")),
                        Map.of("K", List.of("V")), Map.of()));
        assertTrue(r.converged);
        assertEquals(10L, craftsOf(r, pR));
        assertTrue(r.missing.isEmpty(), "the family covers the craftable share: " + r.missing);
        assertEquals(10L, r.withdraw.get(k("V")).longValue(),
                "the withdrawal books the ACTUAL (sibling) key");
        assertEquals(10L, drawOf(r, "V"),
                "the draw lands on V as pending consumption");
    }

    @Test
    void mixedConsumersSplitTheShare() {
        // K consumed by a processing leg AND a craftable leg: only the
        // craftable 10 may draw the sibling; the strict 10 discloses
        BenchPatternDetails pR = pat(new String[]{"R"}, new long[]{1}, "M1", 1L, "M2", 1L);
        BenchPatternDetails pM1 = pat(new String[]{"M1"}, new long[]{1}, "K", 1L);
        BenchPatternDetails pM2 = pat(new String[]{"M2"}, new long[]{1}, "K", 1L).asCraftable();
        List<BenchPatternDetails> pats = List.of(pR, pM1, pM2);
        PlanClosure.Result r = PlanClosure.close(k("R"), BigInteger.TEN, pR,
                view(pats, Map.of("V", 50L), Map.of("K", List.of("V")),
                        Map.of("K", List.of("V")), Map.of()));
        assertTrue(r.converged);
        assertEquals(10L, craftsOf(r, pM1));
        assertEquals(10L, craftsOf(r, pM2));
        assertEquals(10L, r.missing.get(k("K")).longValue(),
                "the strict share discloses on the exact key");
        assertTrue(r.missing.size() == 1, "the craftable share closed: " + r.missing);
        assertEquals(10L, r.withdraw.get(k("V")).longValue(),
                "only the craftable share's family draw is booked");
        assertEquals(1, r.withdraw.size());
    }

    @Test
    void groupOnAProcessingPatternStaysExact() {
        // defense in depth (the compile never registers groups on processing
        // patterns, PatternHelper :87): a fuzzy share DEMANDED BY a
        // processing pattern must not draw the group — canCraft :445-453
        // finds the key precisely
        BenchPatternDetails pR = pat(new String[]{"R"}, new long[]{1}, "K", 1L);
        List<BenchPatternDetails> pats = List.of(pR);
        PlanClosure.Result r = PlanClosure.close(k("R"), BigInteger.TEN, pR,
                view(pats, Map.of("V", 50L), Map.of(),
                        Map.of("K", List.of("V")),
                        Map.of(pR, Map.of("K", 1L))));
        assertTrue(r.converged);
        assertEquals(10L, r.missing.get(k("K")).longValue(),
                "the demand stays strict despite the registered share");
        assertTrue(r.withdraw.isEmpty(), "no group draw: " + r.withdraw);
    }
}
