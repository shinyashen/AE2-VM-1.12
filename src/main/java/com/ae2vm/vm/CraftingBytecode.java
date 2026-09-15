package com.ae2vm.vm;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiled bytecode for a crafting tree.
 * This is the result of pattern compilation - all recursion is eliminated,
 * bytecode is completely flat and ready for linear execution.
 *
 * 1.12 port note: the 1.21 original stores AEKey in the constant pool; here the
 * canonical key type is IAEItemStack (AE2FC fluid fake items normalize to the
 * drop form before entering the pool).
 */
public class CraftingBytecode {
    /**
     * Constant pool: maps index -> item type key (items and AE2FC fluid drops).
     * Indices are 0-based, referenced by PUSH_ITEM/EXTRACT_INGREDIENT/etc.
     */
    private final IAEItemStack[] constantPool;

    /**
     * Pattern pool: maps index -> ICraftingPatternDetails (for crafting job recording)
     */
    private final ICraftingPatternDetails[] patternPool;

    /**
     * Actual bytecode instructions.
     * Each opcode is 1 byte, followed by operands as defined in Opcode.
     */
    private final byte[] code;

    /**
     * Cached hash for quick lookup
     */
    private final int hash;

    /**
     * Output item index in constant pool (for quick access)
     */
    private final int outputIndex;

    /**
     * Output amount per craft
     */
    private final long outputAmountPerCraft;

    public CraftingBytecode(IAEItemStack[] constantPool, ICraftingPatternDetails[] patternPool, byte[] code,
                            int outputIndex, long outputAmountPerCraft) {
        this.constantPool = constantPool;
        this.patternPool = patternPool;
        this.code = code;
        this.outputIndex = outputIndex;
        this.outputAmountPerCraft = outputAmountPerCraft;
        this.hash = Arrays.hashCode(code) * 31 + Arrays.hashCode(constantPool);
    }

    public IAEItemStack[] getConstantPool() {
        return constantPool;
    }

    public ICraftingPatternDetails[] getPatternPool() {
        return patternPool;
    }

    public byte[] getCode() {
        return code;
    }

    public int getOutputIndex() {
        return outputIndex;
    }

    public long getOutputAmountPerCraft() {
        return outputAmountPerCraft;
    }

    public IAEItemStack getOutput() {
        return constantPool[outputIndex];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CraftingBytecode that = (CraftingBytecode) o;
        return hash == that.hash &&
               Arrays.equals(constantPool, that.constantPool) &&
               Arrays.equals(patternPool, that.patternPool) &&
               Arrays.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Get total number of unique ingredients in this crafting tree
     */
    public int getIngredientCount() {
        return constantPool.length;
    }

    /**
     * Get bytecode length in bytes
     */
    public int getCodeLength() {
        return code.length;
    }

    /**
     * Builder for constructing bytecode during compilation
     */
    public static class Builder {
        private final List<IAEItemStack> constantPool = new ArrayList<>();
        private final List<ICraftingPatternDetails> patternPool = new ArrayList<>();
        private final ByteArrayOutputStream codeStream = new ByteArrayOutputStream();
        private int outputIndex = -1;
        private long outputAmountPerCraft = 0;

        public int addConstant(IAEItemStack key) {
            for (int i = 0; i < constantPool.size(); i++) {
                if (constantPool.get(i).isSameType(key)) {
                    return i;
                }
            }
            IAEItemStack copy = key.copy();
            copy.reset();
            // Preserve the encoded size: for AE2FC fluid drops the AE stack
            // size IS the fluid amount and the CALL_BY_KEY resolver needs it
            // to build the packet key. Type equality ignores size, so plain
            // items are unaffected.
            copy.setStackSize(key.getStackSize());
            constantPool.add(copy);
            return constantPool.size() - 1;
        }

        public IAEItemStack getConstant(int index) {
            return constantPool.get(index);
        }

        public int addPattern(ICraftingPatternDetails pattern) {
            int existing = patternPool.indexOf(pattern);
            if (existing >= 0) {
                return existing;
            }
            patternPool.add(pattern);
            return patternPool.size() - 1;
        }

        public void setOutput(int outputIndex, long amountPerCraft) {
            this.outputIndex = outputIndex;
            this.outputAmountPerCraft = amountPerCraft;
        }

        public void emit(Opcode op) {
            codeStream.write(op.code);
        }

        public void emitShort(int value) {
            codeStream.write((value >> 8) & 0xFF);
            codeStream.write(value & 0xFF);
        }

        public void emitLong(long value) {
            for (int i = 56; i >= 0; i -= 8) {
                codeStream.write((int) ((value >> i) & 0xFF));
            }
        }

        public void emitPushItem(int constantIndex, long count) {
            emit(Opcode.PUSH_ITEM);
            emitShort(constantIndex);
            emitLong(count);
        }

        public void emitPushLong(long value) {
            emit(Opcode.PUSH_LONG);
            emitLong(value);
        }

        public void emitExtractIngredient(int constantIndex) {
            emit(Opcode.EXTRACT_INGREDIENT);
            emitShort(constantIndex);
        }

        public void emitRecordIngredient(int constantIndex) {
            emit(Opcode.RECORD_INGREDIENT);
            emitShort(constantIndex);
        }

        public void emitRecordMissing(int constantIndex) {
            emit(Opcode.RECORD_MISSING);
            emitShort(constantIndex);
        }

        public void emitRecordOutput(int constantIndex) {
            emit(Opcode.RECORD_OUTPUT);
            emitShort(constantIndex);
        }

        public void emitRecordPattern(int patternIndex) {
            emit(Opcode.RECORD_PATTERN);
            emitShort(patternIndex);
        }

        public void emitCall(int patternIndex) {
            emit(Opcode.CALL);
            emitShort(patternIndex);
        }

        public void emitCallByKey(int constantIndex) {
            emit(Opcode.CALL_BY_KEY);
            emitShort(constantIndex);
        }

        /** Marks the next CALL_BY_KEY as coming from a replacement-enabled (fuzzy) input slot. */
        public void emitFuzzySlot() {
            emit(Opcode.FUZZY_SLOT);
        }

        public void emitInsertOutput(int constantIndex) {
            emit(Opcode.INSERT_OUTPUT);
            emitShort(constantIndex);
        }

        /** Records a one-time catalyst/container seed demand (pops the seed amount). */
        public void emitCatalystSeed(int constantIndex) {
            emit(Opcode.CATALYST_SEED);
            emitShort(constantIndex);
        }

        public void emitHalt() {
            emit(Opcode.HALT);
        }

        /** Get current bytecode length (before build) */
        public int getCodeLength() {
            return codeStream.size();
        }

        public CraftingBytecode build() {
            if (outputIndex < 0) {
                throw new IllegalStateException("Output not set");
            }
            emitHalt();
            return new CraftingBytecode(
                constantPool.toArray(new IAEItemStack[0]),
                patternPool.toArray(new ICraftingPatternDetails[0]),
                codeStream.toByteArray(),
                outputIndex,
                outputAmountPerCraft
            );
        }
    }
}
