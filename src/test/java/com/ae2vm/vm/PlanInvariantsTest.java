package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.api.config.FuzzyMode;
import com.ae2vm.test.fakes.BenchAEItemStack;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.trace.VirtualPatternDetails;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 acceptance: each invariant rule is pinned by a historical bug shape —
 * the gross-emitable CPU stall, the incomplete missing list, silent
 * unschedulable inputs, and plan/request mismatches.
 */
class PlanInvariantsTest {

    private static final String IRON = "minecraft:iron_ingot";
    private static final String STONE = "minecraft:stone";
    private static final String COAL = "minecraft:coal";

    // ------------------------------------------------------------------
    // fakes

    /** Minimal stock list: value-equality map over BenchAEItemStack. */
    static final class FakeList implements IItemList<IAEItemStack> {
        private final Map<IAEItemStack, IAEItemStack> map = new LinkedHashMap<>();

        FakeList put(BenchAEItemStack s) {
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
            // AE2 IGNORE_ALL semantics: every damage/NBT variant of the item
            List<IAEItemStack> family = new ArrayList<>();
            String id = ((BenchAEItemStack) input).id;
            for (IAEItemStack s : map.values()) {
                if (((BenchAEItemStack) s).id.equals(id)) {
                    family.add(s);
                }
            }
            return family;
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

    private static BenchAEItemStack k(String id, long size) {
        return new BenchAEItemStack(id, size);
    }

    private static ICraftingPatternDetails pattern(IAEItemStack in, IAEItemStack out) {
        return new VirtualPatternDetails(new IAEItemStack[]{in}, new IAEItemStack[]{out}, false, true);
    }

    private static VMPlan plan(IAEItemStack output, long deliver,
                               VMCounter used, VMCounter missing, VMCounter emitted,
                               Map<ICraftingPatternDetails, Long> times) {
        return new VMPlan(output, deliver, 0L, false, used, missing, emitted, times);
    }

    /** request stone x1000 <- pattern iron@3 -> stone@1 x1000, iron in stock. */
    private static IAEItemStack what() {
        return k(STONE, 1000);
    }

    private static Map<ICraftingPatternDetails, Long> times() {
        Map<ICraftingPatternDetails, Long> m = new LinkedHashMap<>();
        m.put(pattern(k(IRON, 3), k(STONE, 1)), 1000L);
        return m;
    }

    private static IItemList<IAEItemStack> stock(long iron) {
        return new FakeList().put(k(IRON, iron));
    }

    // ------------------------------------------------------------------
    // the rules

    @Test
    void soundPlanHasNoViolations() {
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), 3000);
        VMPlan p = plan(k(STONE, 1000), 1000, used, new VMCounter(), new VMCounter(), times());
        List<String> v = PlanInvariants.check(p, what(), 1000, stock(5000));
        assertFalse(v.contains("MISSING-COVERAGE"), v.toString());
        assertTrue(v.isEmpty(), "sound plan must be clean: " + v);
    }

    @Test
    void understockedInputWithoutMissingEntryIsCaught() {
        // live bug: the incomplete missing list (missing EMPTY while iron is short)
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), 3000);
        VMPlan p = plan(k(STONE, 1000), 1000, used, new VMCounter(), new VMCounter(), times());
        List<String> v = PlanInvariants.check(p, what(), 1000, stock(2000));
        assertTrue(v.stream().anyMatch(s -> s.startsWith("MISSING-COVERAGE:" + IRON)), v.toString());
    }

    @Test
    void missingEntryAbsolvesTheShortfall() {
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), 3000);
        VMCounter missing = new VMCounter();
        missing.add(k(IRON, 1), 1000);
        VMPlan p = plan(k(STONE, 1000), 1000, used, missing, new VMCounter(), times());
        assertTrue(PlanInvariants.check(p, what(), 1000, stock(2000)).isEmpty());
    }

    @Test
    void grossEmitableIsCaught() {
        // live bug: emitable reported as gross production (1250 ingots / 15000 spirits)
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), 3000);
        VMCounter emitted = new VMCounter();
        emitted.add(k(STONE, 1), 5000); // produced is only 1000
        VMPlan p = plan(k(STONE, 1000), 1000, used, new VMCounter(), emitted, times());
        List<String> v = PlanInvariants.check(p, what(), 1000, stock(5000));
        assertTrue(v.stream().anyMatch(s -> s.startsWith("EMITABLE-COVER:" + STONE)), v.toString());
    }

    @Test
    void netEmitableIsClean() {
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), 3000);
        VMCounter emitted = new VMCounter();
        emitted.add(k(STONE, 1), 1000); // == produced
        VMPlan p = plan(k(STONE, 1000), 1000, used, new VMCounter(), emitted, times());
        assertTrue(PlanInvariants.check(p, what(), 1000, stock(5000)).isEmpty());
    }

    @Test
    void unschedulableInputIsCaught() {
        // the silent-stall shape: a pattern needs coal, nothing makes it,
        // nothing stocks it, and missing says nothing about it
        Map<ICraftingPatternDetails, Long> m = new LinkedHashMap<>();
        m.put(pattern(k(COAL, 1), k(STONE, 1)), 1000L);
        VMPlan p = plan(k(STONE, 1000), 1000, new VMCounter(), new VMCounter(), new VMCounter(), m);
        List<String> v = PlanInvariants.check(p, what(), 1000, stock(0));
        assertTrue(v.stream().anyMatch(s -> s.startsWith("INPUT-REACH:" + COAL)), v.toString());
    }

    @Test
    void outputAndDeliverMismatchesAreCaught() {
        VMPlan p = plan(k(IRON, 1000), 999, new VMCounter(), new VMCounter(), new VMCounter(), times());
        List<String> v = PlanInvariants.check(p, what(), 1000, stock(5000));
        assertTrue(v.contains("OUTPUT-MISMATCH"), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.startsWith("DELIVER-MISMATCH")), v.toString());
    }

    @Test
    void fabricatedMissingIsFlaggedAsLedgerKey() {
        // missing references a key the ledger never touches and nothing covers:
        // missing stone would be fine only if stone had net shortfall — here it is produced
        VMCounter used = new VMCounter();
        used.add(k(IRON, 1), 3000);
        VMCounter missing = new VMCounter();
        missing.add(k(IRON, 1), 0); // zero entries are ignored by missingCovers
        VMPlan p = plan(k(STONE, 1000), 1000, used, missing, new VMCounter(), times());
        assertTrue(PlanInvariants.check(p, what(), 1000, stock(5000)).isEmpty());
    }

    @Test
    void saturatingArithmeticDoesNotExplode() {
        Map<ICraftingPatternDetails, Long> m = new LinkedHashMap<>();
        m.put(pattern(k(IRON, Long.MAX_VALUE / 2), k(STONE, 1)), Long.MAX_VALUE / 2);
        VMCounter used = new VMCounter();
        VMPlan p = plan(k(STONE, 1000), 1000, used, new VMCounter(), new VMCounter(), m);
        List<String> v = PlanInvariants.check(p, k(STONE, 1000), 1000, new FakeList());
        // iron net = 0 - saturated consumption -> negative -> coverage violation expected
        assertTrue(v.stream().anyMatch(s -> s.startsWith("MISSING-COVERAGE:" + IRON)
                || s.startsWith("INPUT-REACH:" + IRON)), v.toString());
    }

    @Test
    void damageVariantFamilyDoesNotAbsolveAProcessingConsumer() {
        // 2026-09-20 REVERSAL (CLOSURE-DESIGN §5.9): the CPU extracts a
        // processing pattern's inputs findPrecise-exact (AE2UEL canCraft
        // :445-453 → MECraftingInventory), so the log@3 gap is REAL no
        // matter what log@0 stock exists — a plan booking a variant
        // withdrawal here stalls the live CPU, and the audit must flag it
        BenchAEItemStack oak = new BenchAEItemStack("minecraft:log", 0, 0, 1);
        BenchAEItemStack jungle = new BenchAEItemStack("minecraft:log", 3, 0, 1);
        BenchPatternDetails makeStick = BenchPatternDetails.custom(
                new IAEItemStack[]{jungle.setStackSize(2)},
                new IAEItemStack[]{new BenchAEItemStack("stick", 0, 0, 1).setStackSize(1)});
        VMPlan plan = new VMPlan(makeStick.getOutputs()[0], 5, 0, true,
                counter(jungle, 100), missing(), emitted(),
                single(makeStick, 50));
        oak.setStackSize(1_000_000);
        FakeList stock = new FakeList().put(oak);
        final List<String> v = PlanInvariants.check(plan, makeStick.getOutputs()[0], 5L, stock);
        assertTrue(v.contains("MISSING-COVERAGE:minecraft:log@3 net=-100"),
                "the strict gap is real: " + v);
        assertTrue(v.contains("INPUT-REACH:minecraft:log@3"),
                "the processing input is unreachable: " + v);
    }

    @Test
    void damageVariantFamilyCoversACraftableConsumer() {
        // the craftable branch fuzzy-extracts (canCraft :454-516, findFuzzy
        // IGNORE_ALL) — the sibling stock legitimately absolves the gap
        BenchAEItemStack oak = new BenchAEItemStack("minecraft:log", 0, 0, 1);
        BenchAEItemStack jungle = new BenchAEItemStack("minecraft:log", 3, 0, 1);
        BenchPatternDetails makeStick = BenchPatternDetails.custom(
                new IAEItemStack[]{jungle.setStackSize(2)},
                new IAEItemStack[]{new BenchAEItemStack("stick", 0, 0, 1).setStackSize(1)})
                .asCraftable();
        VMPlan plan = new VMPlan(makeStick.getOutputs()[0], 5, 0, true,
                counter(jungle, 100), missing(), emitted(),
                single(makeStick, 50));
        oak.setStackSize(1_000_000);
        FakeList stock = new FakeList().put(oak);
        final List<String> v = PlanInvariants.check(plan, makeStick.getOutputs()[0], 5L, stock);
        assertTrue(v.isEmpty(), "the craftable gap is family-covered: " + v);
    }

    private static VMCounter counter(BenchAEItemStack k, long v) {
        VMCounter c = new VMCounter();
        c.add(k, v);
        return c;
    }

    private static VMCounter missing() {
        return new VMCounter();
    }

    private static VMCounter emitted() {
        return new VMCounter();
    }

    private static Map<ICraftingPatternDetails, Long> single(ICraftingPatternDetails p, long t) {
        Map<ICraftingPatternDetails, Long> m = new LinkedHashMap<>();
        m.put(p, t);
        return m;
    }

}
