package com.ae2vm.trace;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import com.ae2vm.vm.CraftingBytecode;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 acceptance: the compiled bytecode survives tokenization, canonical
 * JSON and a headless rebuild — code bytes, pool identity graph, pattern
 * IO and output fields all preserved. The rebuilt pool lives in a
 * DIFFERENT world (dummy items), so comparisons are structural:
 * same-type relationships and sizes must match, never item instances.
 */
class BytecodeRoundTripTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    @Test
    void bytecodeSurvivesTokenizationJsonAndHeadlessRebuild() {
        TokenVault vault = TokenVault.inMemory();
        StackCodec codec = new StackCodec(vault);

        IAEItemStack iron = AEItemStack.fromItemStack(new ItemStack(Items.IRON_INGOT));
        IAEItemStack stone = AEItemStack.fromItemStack(new ItemStack(Items.COAL, 1, 1));
        VirtualPatternDetails pattern = new VirtualPatternDetails(
                new IAEItemStack[]{amount(iron, 3)},
                new IAEItemStack[]{amount(stone, 1)},
                false, true);

        CraftingBytecode.Builder b = new CraftingBytecode.Builder();
        int ironIdx = b.addConstant(iron);
        int stoneIdx = b.addConstant(stone);
        int patIdx = b.addPattern(pattern);
        b.setOutput(stoneIdx, 12);
        b.emitPushItem(ironIdx, 10000);
        b.emitExtractIngredient(ironIdx);
        b.emitRecordPattern(patIdx);
        b.emitCall(patIdx);
        b.emitInsertOutput(stoneIdx);
        CraftingBytecode original = b.build();

        TraceBytecode traced = BytecodeTraceCodec.toTrace(original, codec);
        // JSON layer must be lossless too
        TraceBytecode parsed = TraceBytecode.fromJson(traced.toJson());
        assertEquals(TraceJson.canonical(traced.toJson()), TraceJson.canonical(parsed.toJson()));

        CraftingBytecode rebuilt = BytecodeTraceCodec.fromTrace(parsed, new HeadlessStackFactory());

        assertArrayEquals(original.getCode(), rebuilt.getCode(), "opcode bytes must be exact");
        assertEquals(original.getOutputIndex(), rebuilt.getOutputIndex());
        assertEquals(original.getOutputAmountPerCraft(), rebuilt.getOutputAmountPerCraft());
        assertEquals(original.getConstantPool().length, rebuilt.getConstantPool().length);

        // structural identity graph: who is the same type as whom, and at what size
        for (int i = 0; i < original.getConstantPool().length; i++) {
            assertEquals(original.getConstantPool()[i].getStackSize(),
                    rebuilt.getConstantPool()[i].getStackSize(),
                    "pool entry " + i + " encoded size must survive");
            for (int j = 0; j < original.getConstantPool().length; j++) {
                boolean origSame = original.getConstantPool()[i].isSameType(original.getConstantPool()[j]);
                boolean rebuiltSame = rebuilt.getConstantPool()[i].isSameType(rebuilt.getConstantPool()[j]);
                if (origSame) {
                    assertTrue(rebuiltSame, "pool entries " + i + "/" + j + " were the same type");
                }
            }
        }
        // the two pool entries here are distinct (iron vs stone) and must stay distinct
        assertFalse(rebuilt.getConstantPool()[0].isSameType(rebuilt.getConstantPool()[1]),
                "distinct originals must stay distinct");
        // the pattern's input IS the iron pool identity: same token → same headless item
        VirtualPatternDetails rp2 = (VirtualPatternDetails) rebuilt.getPatternPool()[0];
        assertTrue(rp2.getCondensedInputs()[0].isSameType(rebuilt.getConstantPool()[ironIdx]),
                "pattern input and pool entry sharing one identity must rematerialize as the same type");

        assertEquals(1, rebuilt.getPatternPool().length);
        assertTrue(rebuilt.getPatternPool()[0] instanceof VirtualPatternDetails);
        VirtualPatternDetails rp = (VirtualPatternDetails) rebuilt.getPatternPool()[0];
        assertEquals(1, rp.getCondensedInputs().length);
        assertEquals(3, rp.getCondensedInputs()[0].getStackSize());
        assertEquals(1, rp.getCondensedOutputs().length);
        assertEquals(1, rp.getCondensedOutputs()[0].getStackSize());
        assertEquals(pattern.isCraftable(), rp.isCraftable());
        assertEquals(pattern.canSubstitute(), rp.canSubstitute());
    }

    @Test
    void distinctOriginalsMintDistinctTokens() {
        TokenVault vault = TokenVault.inMemory();
        StackCodec codec = new StackCodec(vault);
        IAEItemStack iron = AEItemStack.fromItemStack(new ItemStack(Items.IRON_INGOT));
        IAEItemStack stone = AEItemStack.fromItemStack(new ItemStack(Items.COAL));
        TraceBytecode t = BytecodeTraceCodec.toTrace(
                tiny(iron, stone), codec);
        assertNotEquals(t.pool.get(0).spec.token, t.pool.get(1).spec.token);
    }

    private static CraftingBytecode tiny(IAEItemStack a, IAEItemStack b) {
        CraftingBytecode.Builder b2 = new CraftingBytecode.Builder();
        int ai = b2.addConstant(a);
        int bi = b2.addConstant(b);
        b2.setOutput(bi, 1);
        b2.emitPushItem(ai, 1);
        b2.emitInsertOutput(bi);
        return b2.build();
    }

    private static IAEItemStack amount(IAEItemStack s, long n) {
        IAEItemStack c = s.copy();
        c.setStackSize(n);
        return c;
    }
}
