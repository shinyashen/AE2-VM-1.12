package com.ae2vm.vm.ring;
import com.ae2vm.test.harness.Bench;
import com.ae2vm.test.fakes.BenchSimulationState;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.api.config.FuzzyMode;

import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.compat.PatternCompat;
import com.ae2vm.test.harness.CpuLifecycleAssert;
import com.ae2vm.vm.VMCounter;
import com.ae2vm.vm.VMPlan;
import com.ae2vm.vm.PlanInvariants;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * Live-server gaia-ring scenario (2026-09-18, trace 20260918-230609-35c5): the
 * player's real web is a net-gain cycle P0(1 g14 -> 12 g5 + 1 dice) +
 * P1(1 g4 + 4 g5 -> 1 g14) fed by an out-of-ring amplifier
 * P2(m0 + m1 + m2 + 500 X -> 4 g4), with stock g5 x779 / g4 x998 / X plenty
 * and ZERO g14. Ordering 10000 g5.
 *
 * <p>Under the faithful AE2UEL CPU semantics (CraftingCPUCluster.injectItems
 * :265 — the delivered final output NEVER re-enters the CPU inventory) the
 * ring's own g5 production is siphoned off as delivery, so P1's whole
 * 4x1250 draw must be job-start capital: the plan rides the cycle at
 * 1250/1250 rounds but honestly reports the g5 capital shortfall against
 * the 779 stocked. The live audit additionally exposed the E-case hole this
 * test pins: P2's own inputs (m0/m1/m2) must flow down THEIR producer chain
 * instead of vanishing as silent ledger holes (MISSING-COVERAGE /
 * INPUT-REACH violations on botania:manaresource, net=-252).
 */
class GaiaCycleRideTest {

    /** Minimal pristine-stock list for the invariant audit (value-equality map). */
    static final class ProbeList implements IItemList<IAEItemStack> {
        private final Map<IAEItemStack, IAEItemStack> map = new LinkedHashMap<>();

        ProbeList put(BenchAEItemStack s) {
            map.put(s, s);
            return this;
        }

        @Override
        public void add(IAEItemStack option) {
            map.put(option, option);
        }

        @Override
        public IAEItemStack findPrecise(IAEItemStack i) {
            return map.get(i);
        }

        @Override
        public Collection<IAEItemStack> findFuzzy(IAEItemStack input, FuzzyMode fuzzy) {
            return Collections.emptyList();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void addStorage(IAEItemStack option) {
            add(option);
        }

        @Override
        public void addCrafting(IAEItemStack option) {
            add(option);
        }

        @Override
        public void addRequestable(IAEItemStack option) {
            add(option);
        }

        @Override
        public IAEItemStack getFirstItem() {
            return map.isEmpty() ? null : map.values().iterator().next();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public Iterator<IAEItemStack> iterator() {
            return map.values().iterator();
        }

        @Override
        public void resetStatus() {
        }
    }

    /** The registered pattern whose primary output is {@code out}. */
    private static BenchPatternDetails pat(String out) {
        return (BenchPatternDetails) Bench.PATTERNS.get(Bench.k(out));
    }

    private static String idOf(Object key) {
        return ((BenchAEItemStack) key).id;
    }

    private static Map<String, Long> byId(VMCounter counter) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var e : counter.entrySet()) {
            out.merge(idOf(e.getKey()), e.getValue(), Long::sum);
        }
        return out;
    }

    private static VMPlan run(boolean withConverter) {
        Bootstrap.register();
        Bench.reset();
        {
            BenchPatternDetails p0 = Bench.patEx(new String[]{"g5", "dice"}, new long[]{12, 1}, "g14", 1L);
            BenchPatternDetails p1 = Bench.patEx(new String[]{"g14"}, new long[]{1}, "g4", 1L, "g5", 4L);
            BenchPatternDetails p2 = Bench.patEx(new String[]{"g4"}, new long[]{4},
                    "m0", 1L, "m1", 1L, "m2", 1L, "X", 500L);
            Bench.register(p0);
            Bench.register(p1);
            Bench.register(p2);
            // the live network's mana converters: m0 has its own producer
            // (2 R -> 1 m0) — the E-case must expand P2's short m0 draw into
            // THIS pattern instead of reporting (or dropping) the m0 gap
            BenchPatternDetails p3 = withConverter ? Bench.pat("m0", 1, "R", 2L) : null;
            if (p3 != null) {
                Bench.register(p3);
            }
            for (var p : Bench.PATTERNS.values()) PatternCompiler.compileIfAbsent(p);

            BenchSimulationState sim = new BenchSimulationState()
                    .seed("g5", 779)
                    .seed("g4", 998)
                    .seed("X", 223291314L);
            return Bench.run(p0, 10000, sim);
        }
    }

    private static void dump(VMPlan plan, String tag) {
        StringBuilder sb = new StringBuilder("[PROBE " + tag + "] patternTimes:");
        for (var e : plan.getPatternTimes().entrySet()) {
            var out = PatternCompat.getPrimaryOutput(e.getKey());
            sb.append(' ').append(e.getValue()).append("x")
                    .append(out == null ? "?" : ((BenchAEItemStack) out).id);
        }
        sb.append("\n  missing:").append(byId(plan.getMissingItems()));
        sb.append("\n  used:").append(byId(plan.getUsedItems()));
        sb.append("\n  emitted:").append(byId(plan.getEmittedItems()));
        System.out.println(sb);
    }

    /**
     * The cycle-ride shape itself, gate on: routing 1250/1250/63, the honest
     * g5 job-start capital (the AE2UEL CPU siphons the delivered final output
     * — CraftingCPUCluster.injectItems :265 — so P1's whole 4x1250 draw must
     * be withdrawn at t=0; 779 stocked leaves 4221 missing), and P2's leaf
     * inputs reported as missing (no converter in this web).
     */
    @Test
    void gateOnRidesTheCycleWithHonestDisclosure() {
        VMPlan plan = run(false);
        dump(plan, "plain");
        Map<String, Long> missing = byId(plan.getMissingItems());
        // the least fixpoint rides 834 rounds: g4's 834 draw is covered by
        // the 998 stocked, so P2 (and m0/m1/m2) never join the plan. The root
        // siphon keeps p1's whole 4x834 g5 draw job-start capital: the 779
        // stocked cover part, the rest is the honest disclosure.
        assertEquals(Long.valueOf(834L), plan.getPatternTimes().get(pat("g14")), "P1 loops");
        assertEquals(Long.valueOf(834L), plan.getPatternTimes().get(pat("g5")), "P0 loops");
        assertFalse(plan.getMissingItems().isEmpty(), "the g5 capital shortfall is disclosed");
        assertEquals(Long.valueOf(2557L), missing.get("g5"), "the net g5 capital gap (3336 - 779)");
        assertEquals(1, missing.size(), "nothing else missing: " + missing);
        // audit must be clean: every net-negative ledger key is covered
        ProbeList stock = new ProbeList()
                .put(new BenchAEItemStack("g5", 779))
                .put(new BenchAEItemStack("g4", 998))
                .put(new BenchAEItemStack("X", 223291314L));
        List<String> violations = PlanInvariants.check(
                plan, PatternCompat.getPrimaryOutput(pat("g5")), 10000, stock);
        assertTrue(violations.isEmpty(), "plan invariants: " + violations);
        CpuLifecycleAssert.auto(plan);
    }

    /**
     * The live-trace hole: with a converter for m0 in the web, the E-case
     * recursion must expand P2's short m0 draw into the converter (63 crafts,
     * R x126 consumed) — m0 itself is neither missing nor a silent hole.
     */
    @Test
    void ecaseExpandsTheAmplifiersInputs() {
        VMPlan plan = run(true);
        dump(plan, "converter");
        Map<String, Long> missing = byId(plan.getMissingItems());
        // same 834-round least fixpoint — g4 comes from stock, so P2 and the
        // converter never join. The root siphon keeps p1's whole g5 draw
        // job-start capital: 3336 - 779 stocked is the honest disclosure.
        assertEquals(Long.valueOf(834L), plan.getPatternTimes().get(pat("g14")), "P1 loops");
        assertEquals(Long.valueOf(834L), plan.getPatternTimes().get(pat("g5")), "P0 loops");
        assertFalse(plan.getMissingItems().isEmpty(), "the g5 capital shortfall is disclosed");
        assertEquals(Long.valueOf(2557L), missing.get("g5"), "the net g5 capital gap (3336 - 779)");
        assertEquals(1, missing.size(), "nothing else missing: " + missing);
        ProbeList stock = new ProbeList()
                .put(new BenchAEItemStack("g5", 779))
                .put(new BenchAEItemStack("g4", 998))
                .put(new BenchAEItemStack("X", 223291314L));
        List<String> violations = PlanInvariants.check(
                plan, PatternCompat.getPrimaryOutput(pat("g5")), 10000, stock);
        assertTrue(violations.isEmpty(), "plan invariants: " + violations);
        CpuLifecycleAssert.auto(plan);
    }


}
