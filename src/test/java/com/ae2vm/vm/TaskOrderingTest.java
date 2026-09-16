package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.test.fakes.BenchPatternDetails;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.ae2vm.test.fakes.BenchPatternDetails.processing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constructed task order (TaskOrdering): DAG producers before consumers,
 * folded rings keeping their probed firing order, bridge patterns folded
 * into their SCC, self-loops inert, counts preserved, fully deterministic.
 * These pin the by-construction correctness the fallback replica assertion
 * relies on (proof in {@link TaskOrdering}).
 */
class TaskOrderingTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    /** CraftingVM's priming view: all condensed inputs, per-craft, typed. */
    private static final Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> INS = d -> {
        Map<IAEItemStack, BigInteger> m = new HashMap<>();
        IAEItemStack[] ins = d.getCondensedInputs();
        if (ins != null) {
            for (IAEItemStack i : ins) {
                if (i == null || i.getStackSize() <= 0) continue;
                IAEItemStack k = i.copy().setStackSize(1);
                k.reset();
                m.merge(k, BigInteger.valueOf(i.getStackSize()), BigInteger::add);
            }
        }
        return m;
    };

    private static final Function<ICraftingPatternDetails, Map<IAEItemStack, BigInteger>> OUTS = d -> {
        Map<IAEItemStack, BigInteger> m = new HashMap<>();
        IAEItemStack[] outs = d.getOutputs();
        if (outs != null) {
            for (IAEItemStack o : outs) {
                if (o == null || o.getStackSize() <= 0) continue;
                IAEItemStack k = o.copy().setStackSize(1);
                k.reset();
                m.merge(k, BigInteger.valueOf(o.getStackSize()), BigInteger::add);
            }
        }
        return m;
    };

    private static LinkedHashMap<ICraftingPatternDetails, Long> times(BenchPatternDetails... ps) {
        LinkedHashMap<ICraftingPatternDetails, Long> m = new LinkedHashMap<>();
        for (BenchPatternDetails p : ps) {
            m.put(p, 1L);
        }
        return m;
    }

    private static LinkedHashMap<ICraftingPatternDetails, Long> construct(
            LinkedHashMap<ICraftingPatternDetails, Long> times,
            List<ICraftingPatternDetails> ringTaskOrder) {
        return TaskOrdering.construct(times, ringTaskOrder, INS, OUTS);
    }

    /** The three cycle patterns: rC produces A, rA produces B, rB produces C. */
    private static final BenchPatternDetails rA = processing(new long[][]{{1, 1}}, new long[][]{{0, 1}});
    private static final BenchPatternDetails rB = processing(new long[][]{{2, 1}}, new long[][]{{1, 1}});
    private static final BenchPatternDetails rC = processing(new long[][]{{0, 1}}, new long[][]{{2, 1}});

    @Test
    void dagEmitsProducersBeforeConsumers() {
        BenchPatternDetails leaf = processing(new long[][]{}, new long[][]{{0, 1}});
        BenchPatternDetails mid = processing(new long[][]{{0, 1}}, new long[][]{{1, 1}});
        BenchPatternDetails top = processing(new long[][]{{1, 1}}, new long[][]{{2, 1}});
        // discovery order: root-first, consumers before producers
        var out = construct(times(top, mid, leaf), List.of());
        assertEquals(List.of(leaf, mid, top), List.copyOf(out.keySet()));
    }

    @Test
    void ringKeepsProbedFiringOrder() {
        // probed rotation of the fold — must survive construction intact
        var out = construct(times(rA, rB, rC), List.of(rB, rC, rA));
        assertEquals(List.of(rB, rC, rA), List.copyOf(out.keySet()));
    }

    @Test
    void upstreamBeforeRingAndDownstreamAfter() {
        BenchPatternDetails upstream = processing(new long[][]{}, new long[][]{{1, 1}}); // produces B
        BenchPatternDetails bridge = processing(new long[][]{{0, 1}}, new long[][]{{1, 1}}); // A -> B
        BenchPatternDetails downstream = processing(new long[][]{{0, 1}}, new long[][]{{3, 1}}); // A -> D
        // the bridge consumes a ring key AND produces one: it joins the SCC
        var out = construct(times(rA, rB, rC, bridge, downstream, upstream),
                List.of(rB, rC, rA));
        var seq = List.copyOf(out.keySet());
        assertEquals(List.of(upstream, rB, rC, rA, bridge, downstream), seq);
    }

    @Test
    void selfLoopIsInert() {
        BenchPatternDetails self = processing(new long[][]{{0, 1}}, new long[][]{{0, 2}, {1, 1}});
        var out = construct(times(self), List.of());
        assertEquals(List.of(self), List.copyOf(out.keySet()));
    }

    @Test
    void cycleWithoutProbedOrderFallsBackToInsertion() {
        var out = construct(times(rA, rB, rC), List.of());
        assertEquals(List.of(rA, rB, rC), List.copyOf(out.keySet()));
    }

    @Test
    void countsArePreserved() {
        BenchPatternDetails leaf = processing(new long[][]{}, new long[][]{{0, 1}});
        BenchPatternDetails mid = processing(new long[][]{{0, 1}}, new long[][]{{1, 1}});
        LinkedHashMap<ICraftingPatternDetails, Long> t = new LinkedHashMap<>();
        t.put(mid, 7L);
        t.put(leaf, 5L);
        var out = construct(t, List.of());
        assertEquals(5L, out.get(leaf));
        assertEquals(7L, out.get(mid));
    }

    @Test
    void constructionIsDeterministic() {
        BenchPatternDetails upstream = processing(new long[][]{}, new long[][]{{1, 1}});
        var first = construct(times(rA, rB, rC, upstream), List.of(rB, rC, rA));
        var second = construct(times(rA, rB, rC, upstream), List.of(rB, rC, rA));
        assertEquals(new ArrayList<>(first.keySet()), new ArrayList<>(second.keySet()));
    }

    @Test
    void singlePatternAndEmptyPassThrough() {
        BenchPatternDetails solo = processing(new long[][]{}, new long[][]{{0, 1}});
        var one = construct(times(solo), List.of());
        assertEquals(List.of(solo), List.copyOf(one.keySet()));
        assertTrue(TaskOrdering.construct(new LinkedHashMap<>(), List.of(), INS, OUTS).isEmpty());
    }
}
