package com.ae2vm.vm.closure;

import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.vm.PlanClosure;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closure's acceptance baselines (CLOSURE-DESIGN.md §5.2): two live-server
 * webs (controller orders on the NovaEng machine network) closed offline by
 * {@code local/tools/closure-baseline.py} — the ground truth the Java
 * implementation must reproduce BIT-FOR-BIT: every pattern's craft count and
 * the full missing disclosure.
 *
 * <p>A1ywmJB = 356 patterns / 19,522,791 crafts / 29 rounds / 6 tiny NBT-leaf
 * missing (both engine entries were false; 172 producers were under-sized).
 * D65dvbu = 363 patterns / 19,343,813 crafts / 20 rounds / 6 missing.
 */
class PlanClosureBaselineTest {

    private static BaselineWeb load(String resource) throws Exception {
        try (InputStream raw = PlanClosureBaselineTest.class
                .getResourceAsStream("/com/ae2vm/vm/closure/" + resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return new BaselineWeb(root);
        }
    }

    /** The web rebuilt from the baseline: keys, patterns, stock, expectation. */
    private static final class BaselineWeb {
        final BenchAEItemStack rootKey;
        final long deliver;
        final List<ICraftingPatternDetails> patterns;
        final PlanClosure.View view;
        final Map<String, Long> expectedPlans;
        final Map<BenchAEItemStack, Long> expectedMissing;
        final int expectedRounds;

        BaselineWeb(JsonObject root) {
            rootKey = key(root.getAsJsonObject("root"));
            deliver = root.get("deliver").getAsLong();

            Map<BenchAEItemStack, ICraftingPatternDetails> producers = new LinkedHashMap<>();
            List<ICraftingPatternDetails> pats = new ArrayList<>();
            for (var pe : root.getAsJsonArray("patterns")) {
                JsonObject po = pe.getAsJsonObject();
                List<IAEItemStack> in = new ArrayList<>();
                for (var e : po.getAsJsonArray("in")) {
                    JsonObject en = e.getAsJsonObject();
                    in.add(key(en.getAsJsonObject("s"))
                            .setStackSize(en.get("c").getAsLong()));
                }
                List<IAEItemStack> out = new ArrayList<>();
                for (var e : po.getAsJsonArray("out")) {
                    JsonObject en = e.getAsJsonObject();
                    out.add(key(en.getAsJsonObject("s"))
                            .setStackSize(en.get("c").getAsLong()));
                }
                BenchPatternDetails p = BenchPatternDetails.custom(
                        in.toArray(new IAEItemStack[0]),
                        out.toArray(new IAEItemStack[0]));
                pats.add(p);
                for (IAEItemStack o : out) {
                    BenchAEItemStack k = norm(o);
                    ICraftingPatternDetails prev = producers.put(k, p);
                    if (prev != null) {
                        throw new IllegalStateException("two producers for " + k);
                    }
                }
            }
            patterns = pats;

            Map<BenchAEItemStack, BigInteger> stock = new HashMap<>();
            for (var e : root.getAsJsonArray("stock")) {
                JsonObject so = e.getAsJsonObject();
                stock.put(norm(key(so)), BigInteger.valueOf(so.get("c").getAsLong()));
            }
            Map<BenchAEItemStack, ICraftingPatternDetails> producersFinal = producers;
            Map<BenchAEItemStack, BigInteger> stockFinal = stock;
            this.view = new PlanClosure.View() {
                @Override
                public ICraftingPatternDetails producerOf(IAEItemStack k) {
                    return producersFinal.get(norm(k));
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
                public BigInteger stockOf(IAEItemStack k) {
                    BigInteger s = stockFinal.get(norm(k));
                    return s == null ? BigInteger.ZERO : s;
                }
            };

            JsonObject gt = root.getAsJsonObject("ground_truth");
            expectedPlans = new LinkedHashMap<>();
            for (var e : gt.getAsJsonObject("plans").entrySet()) {
                expectedPlans.put(e.getKey(), e.getValue().getAsLong());
            }
            expectedMissing = new LinkedHashMap<>();
            for (var e : gt.getAsJsonArray("missing")) {
                JsonObject mo = e.getAsJsonObject();
                BenchAEItemStack k = new BenchAEItemStack(
                        mo.get("k").getAsString(), mo.get("d").getAsInt(), 0, 1)
                        .withNbt(blankToNull(mo.get("n").getAsString()));
                expectedMissing.put(k, mo.get("count").getAsLong());
            }
            expectedRounds = gt.get("iterations").getAsInt();
        }
    }

    private static Map<IAEItemStack, BigInteger> perCraft(IAEItemStack[] lines) {
        Map<IAEItemStack, BigInteger> map = new LinkedHashMap<>();
        for (IAEItemStack l : lines) {
            if (l == null || l.getStackSize() <= 0) continue;
            map.put(norm(l), BigInteger.valueOf(l.getStackSize()));
        }
        return map;
    }

    private static BenchAEItemStack norm(IAEItemStack k) {
        return (BenchAEItemStack) k.copy().reset().setStackSize(1);
    }

    private static BenchAEItemStack key(JsonObject s) {
        BenchAEItemStack k = new BenchAEItemStack(s.get("id").getAsString(),
                s.has("d") ? s.get("d").getAsInt() : 0, 0, 1);
        return k.withNbt(blankToNull(s.has("n") ? s.get("n").getAsString() : ""));
    }

    private static String blankToNull(String n) {
        return n == null || n.isEmpty() ? null : n;
    }

    private void assertBaseline(String resource) throws Exception {
        BaselineWeb web = load(resource);
        PlanClosure.Result r = PlanClosure.close(web.rootKey,
                BigInteger.valueOf(web.deliver),
                web.view.producerOf(web.rootKey), web.view);
        assertTrue(r.converged, resource + ": the web must converge");
        assertEquals(web.expectedPlans.size(), r.plans.size(),
                resource + ": scheduled pattern count");
        for (int i = 0; i < web.patterns.size(); i++) {
            long expected = web.expectedPlans.getOrDefault(String.valueOf(i), 0L);
            BigInteger actual = r.plans.getOrDefault(web.patterns.get(i), BigInteger.ZERO);
            assertEquals(expected, actual.longValue(),
                    resource + ": pattern #" + i + " craft count");
        }
        Map<BenchAEItemStack, Long> missing = new LinkedHashMap<>();
        for (var e : r.missing.entrySet()) {
            missing.put(norm(e.getKey()), e.getValue().longValue());
        }
        assertEquals(web.expectedMissing, missing, resource + ": missing disclosure");
        assertEquals(web.expectedRounds, r.rounds,
                resource + ": rounds to the fixpoint");
    }

    @Test
    void a1ywmJBControllerWebReproducesBitForBit() throws Exception {
        assertBaseline("closure-web-A1ywmJB.json.gz");
    }

    @Test
    void d65dvbuControllerWebReproducesBitForBit() throws Exception {
        assertBaseline("closure-web-D65dvbu.json.gz");
    }
}
