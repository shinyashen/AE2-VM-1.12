package com.ae2vm.compat;
import com.ae2vm.test.fakes.BenchPatternDetails;
import com.ae2vm.test.fakes.BenchAEItemStack;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.compiler.PatternCompiler;
import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.CraftingVM;
import com.ae2vm.vm.SimulationState;
import com.ae2vm.vm.VMCounter;
import com.ae2vm.vm.VMPlan;
import com.glodblock.github.common.item.ItemFluidDrop;
import com.glodblock.github.common.item.ItemFluidPacket;
import com.glodblock.github.common.item.fake.FakeFluids;
import com.glodblock.github.loader.FCItems;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.fluids.util.AEFluidStack;
import appeng.util.item.AEItemStack;
import com.glodblock.github.common.item.fake.FakeItemRegister;
import java.util.List;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;

/**
 * Fluid-compat tests against the REAL AE2 Fluid Craft Rework classes (the
 * AE2-UEL org build; the API surface is identical in the Circulate233 fork).
 * In a plain-JVM test the Forge registry never runs, so AE2FC's ObjectHolder
 * field is populated with a live fake item by hand and the fake-item handler
 * registered via {@link FakeFluids#init()} — after that the production
 * {@link AE2FCCompat} bridge and the real AE2 item key machinery are
 * exercised exactly as in game:
 *
 * <ul>
 *   <li>fluid drops: NBT identity, mB amount carried on the AE stack size,
 *       long-quantity decode (identity-only ItemStack probe);</li>
 *   <li>fluid packets: amount encoded in NBT, oversized amounts rejected
 *       instead of truncated;</li>
 *   <li>compiler normalization: packet-form pattern inputs are compiled into
 *       the canonical drop form;</li>
 *   <li>VM integration: a craftable-fluid chain keyed by real AE2FC drops
 *       plans and extracts mB amounts end to end.</li>
 * </ul>
 */
class AE2FCCompatTest {

    private static IAEItemStack waterDrop;

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
        // AE2FC's ObjectHolder fields are only populated by the Forge registry;
        // hand them live fake items and register the decode handlers by class.
        if (FCItems.FLUID_DROP == null) {
            FCItems.FLUID_DROP = new ItemFluidDrop();
        }
        if (FCItems.FLUID_PACKET == null) {
            FCItems.FLUID_PACKET = new ItemFluidPacket();
        }
        try {
            FakeFluids.init();
        } catch (IllegalArgumentException alreadyRegistered) {
            // another test class registered the handlers first
        }
        assertTrue(AE2FCCompat.isAvailable(), "AE2FC classes must be loadable");
        waterDrop = AE2FCCompat.packFluid(fluid(FluidRegistry.WATER, 1000));
        assertNotNull(waterDrop, "packing a fluid must produce a fake drop");
    }

    private static IAEItemStack realItem(Item item) {
        IAEItemStack stack = AEItemStack.fromItemStack(
                new ItemStack(item));
        assertNotNull(stack);
        return stack.copy();
    }

    private static IAEFluidStack fluid(Fluid fluid, int amount) {
        // AEFluidStack directly: AEApi's static init needs the Forge registry and
        // cannot run in a plain JVM, but the fluid stack impl is self-contained.
        IAEFluidStack stack = AEFluidStack.fromFluidStack(
                new FluidStack(fluid, amount));
        assertNotNull(stack, "fluid stack must create for " + fluid.getName());
        return stack;
    }

    @Test
    void dropCarriesFluidIdentityAndAmount() {
        assertTrue(AE2FCCompat.isFluidFakeItem(waterDrop), "drop must classify as a fluid fake");
        assertEquals(1000L, waterDrop.getStackSize(), "drop AE size is the mB amount");
        // identity decodes back to the same fluid at ItemStack count granularity
        IAEItemStack probe = waterDrop.copy().setStackSize(1L);
        FluidStack decoded = FakeItemRegister.getStack(probe.createItemStack());
        assertNotNull(decoded, "drop must decode through FakeItemRegister");
        assertEquals(1, decoded.amount, "ItemStack count is the decode granularity");
        assertEquals(FluidRegistry.WATER, decoded.getFluid());
    }

    @Test
    void longDropAmountsSurviveNormalization() {
        // AE quantity beyond the int ItemStack count must survive: the decoder
        // probes identity at count 1 and keeps the amount on the AE stack.
        IAEItemStack big = waterDrop.copy().setStackSize(2_500_000L);
        IAEItemStack normalized = AE2FCCompat.normalizeFluidItem(big);
        assertNotNull(normalized);
        assertTrue(AE2FCCompat.isFluidFakeItem(normalized));
        assertEquals(2_500_000L, normalized.getStackSize(), "long mB amounts are preserved");
    }

    @Test
    void normalizeIsIdempotentForDrops() {
        IAEItemStack normalized = AE2FCCompat.normalizeFluidItem(waterDrop);
        assertNotNull(normalized);
        assertTrue(normalized.isSameType(waterDrop));
        assertEquals(waterDrop.getStackSize(), normalized.getStackSize());
    }

    @Test
    void plainItemsPassThroughNormalization() {
        BenchAEItemStack plain = new BenchAEItemStack("iron", 64);
        assertFalse(AE2FCCompat.isFluidFakeItem(plain));
        IAEItemStack out = AE2FCCompat.normalizeFluidItem(plain);
        assertNotNull(out);
        assertTrue(out.isSameType(plain), "non-fluid items pass through unchanged");
        assertEquals(64L, out.getStackSize());
    }

    @Test
    void packetKeyEncodesNbtAmount() {
        IAEItemStack packet = AE2FCCompat.packFluidPacket(waterDrop, 500);
        assertNotNull(packet, "a drop with a positive amount hint must pack a packet");
        assertFalse(packet.isSameType(waterDrop), "packet and drop are distinct key forms");
        // the packet decodes to exactly 500 mB through the real register
        FluidStack decoded = FakeItemRegister.getStack(packet.createItemStack());
        assertNotNull(decoded);
        assertEquals(500, decoded.amount, "packet NBT carries the encoded amount");
    }

    @Test
    void oversizePacketIsRejectedNotTruncated() {
        assertNull(AE2FCCompat.packFluidPacket(waterDrop, Long.MAX_VALUE),
                "an order-sized amount is not a valid packet amount");
        assertNull(AE2FCCompat.packFluidPacket(waterDrop, 0L), "zero hints produce no packet");
    }

    @Test
    void compilerNormalizesPacketInputsToDropForm() {
        // pattern whose fluid input arrives in the PACKET form (as providers
        // index them); the compiled bytecode must hold the canonical DROP key.
        IAEItemStack packet = AE2FCCompat.packFluidPacket(waterDrop, 1000);
        assertNotNull(packet);
        IAEItemStack product = new BenchAEItemStack("product", 1);
        BenchPatternDetails pattern = BenchPatternDetails.custom(
                new IAEItemStack[]{packet.copy()},
                new IAEItemStack[]{product});
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(pattern);
        CraftingBytecode bc = PatternCompiler.getCompiled(pattern);
        assertNotNull(bc);
        boolean dropFormFound = false;
        for (IAEItemStack key : bc.getConstantPool()) {
            if (key != null && key.isSameType(waterDrop)) {
                dropFormFound = true;
            }
        }
        assertTrue(dropFormFound,
                "compiled pattern inputs must be normalized to the drop form");
    }

    /**
     * VM integration over REAL AE2FC drop keys: blank <- board + 1000mB fluid;
     * the fluid is itself craftable from water; the network holds partial fluid.
     * Planning and extraction run on the real AE2 item key machinery.
     */
    @Test
    void vmPlansAndExtractsRealFluidDrops() {
        IAEItemStack water = AE2FCCompat.packFluid(fluid(FluidRegistry.WATER, 1000));
        IAEItemStack fluidX = AE2FCCompat.packFluid(fluid(FluidRegistry.LAVA, 1000));
        // Every non-fluid key must be a REAL AE item stack too: AEItemStack's
        // isSameType bridge casts its argument, so real and fake key types
        // must never mix within one graph (in game they never do).
        IAEItemStack raw = realItem(Items.IRON_INGOT);
        IAEItemStack boardOut = realItem(Items.GOLD_INGOT);
        IAEItemStack blankOut = realItem(Items.DIAMOND);

        BenchPatternDetails board = BenchPatternDetails.custom(
                new IAEItemStack[]{raw.copy()},
                new IAEItemStack[]{boardOut.copy()});
        BenchPatternDetails makeFluid = BenchPatternDetails.custom(
                new IAEItemStack[]{water.copy().setStackSize(1)},
                new IAEItemStack[]{fluidX.copy().setStackSize(1000)});
        BenchPatternDetails blank = BenchPatternDetails.custom(
                new IAEItemStack[]{boardOut.copy(),
                        fluidX.copy().setStackSize(1000)},
                new IAEItemStack[]{blankOut.copy()});

        Map<IAEItemStack, ICraftingPatternDetails> byOutput =
                new LinkedHashMap<>();
        byOutput.put(board.getOutputs()[0], board);
        byOutput.put(makeFluid.getOutputs()[0], makeFluid);
        byOutput.put(blank.getOutputs()[0], blank);

        PatternCompiler.clearCache();
        for (var p : byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(blank, 2);
        CraftingVM vm = new CraftingVM("ae2fc-fluid", byOutput::get);

        RealKeySimulationState sim = new RealKeySimulationState()
                .seed(raw, 1_000_000L)
                .seed(water.copy().setStackSize(1), 1_000_000L)
                .seed(fluidX.copy().setStackSize(1), 1_500L); // partial: 1.5 crafts

        VMPlan plan = vm.execute(req, sim);
        assertTrue(plan.getMissingItems().isEmpty(),
                "fluid deficit must be crafted: missing=" + plan.getMissingItems());
        assertEquals(1L, plan.getPatternTimes().getOrDefault(makeFluid, 0L),
                "ceil(deficit 500 / 1000 per craft) = 1 fluid sub-craft");
        // stock-aware split: min(stock 1500, demand 2000) = the whole 1500 from
        // the network; ceil(deficit 500 / 1000) = 1 craft tops it up (the 500
        // over-served mB is normal AE2 craft-granularity surplus).
        assertEquals(1500L, plan.getUsedItems().get(fluidX),
                "the stocked 1500mB is fully consumed from the network");
        assertEquals(2L, plan.getPatternTimes().getOrDefault(blank, 0L));
    }

    /**
     * SimulationState keyed by REAL AE2 item stacks (type equality), mirroring
     * {@link NetworkCraftingSandbox}: inserts served first, then the read-only
     * network view with a private extraction overlay.
     */
    private static final class RealKeySimulationState implements SimulationState {
        private final Map<IAEItemStack, Long> stock = new LinkedHashMap<>();
        private final Map<IAEItemStack, Long> extracted = new LinkedHashMap<>();
        private final VMCounter insertedCounter = new VMCounter();

        RealKeySimulationState seed(IAEItemStack key, long amount) {
            stock.merge(key, amount, Long::sum);
            return this;
        }

        @Override
        public long extract(IAEItemStack key, long amount, boolean simulate) {
            long taken = 0L;
            long ins = insertedCounter.get(key);
            long fromIns = Math.min(ins, amount);
            if (fromIns > 0 && !simulate) {
                insertedCounter.add(key, -fromIns);
            }
            taken += fromIns;
            long remaining = amount - fromIns;
            if (remaining > 0) {
                Long have = stock.get(key);
                long haveL = have == null ? 0L : have;
                long already = extracted.getOrDefault(key, 0L);
                long fromStock = Math.min(haveL - already, remaining);
                if (fromStock > 0 && !simulate) {
                    extracted.put(key, already + fromStock);
                }
                taken += fromStock;
            }
            return taken;
        }

        @Override
        public void insert(IAEItemStack key, long amount) {
            if (amount > 0) {
                insertedCounter.add(key, amount);
            }
        }

        @Override
        public List<IAEItemStack> findFuzzyFamily(IAEItemStack key) {
            return List.of();
        }

        @Override
        public void addCrafting(ICraftingPatternDetails pattern, long times) {
        }

        @Override
        public void addBytes(double amount) {
        }

        @Override
        public long getBytes() {
            return 0L;
        }

        @Override
        public void ignore(IAEItemStack key) {
            stock.remove(key);
        }
    }
}
