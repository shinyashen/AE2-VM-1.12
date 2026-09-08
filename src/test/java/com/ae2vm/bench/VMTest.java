package com.ae2vm.bench;

import com.ae2vm.vm.CraftingBytecode;
import com.ae2vm.vm.Opcode;
import org.junit.jupiter.api.Test;

import static com.ae2vm.bench.Bench.k;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of the original VMTest — core VM operations and the CALL_BY_KEY
 * architecture, exercised directly on the bytecode Builder.
 */
class VMTest {

    @Test
    void testDivRoundUp() {
        assertEquals(4, divRoundUp(10, 3));
        assertEquals(3, divRoundUp(9, 3));
        assertEquals(1, divRoundUp(1, 1));
        assertEquals(0, divRoundUp(0, 5));
        assertEquals(39088196, divRoundUp(39088196, 1));
        System.out.println("[TEST] DIV_ROUNDUP tests passed!");
    }

    @Test
    void testSimpleBytecode() {
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        int outputIdx = 0;
        builder.setOutput(outputIdx, 1);
        builder.emitPushLong(5);
        builder.emitRecordOutput(outputIdx);

        CraftingBytecode bytecode = builder.build();

        assertTrue(bytecode.getCodeLength() > 0);
        assertEquals(1, bytecode.getOutputAmountPerCraft());
        System.out.println("[TEST] Simple bytecode: " + bytecode.getCodeLength() + " bytes, OK!");
    }

    @Test
    void testCallByKeyBytecode() {
        // Test that CALL_BY_KEY bytecode is generated correctly
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        builder.addConstant(k("vm_dummy")); // placeholder key at index 0
        builder.setOutput(0, 1);

        // Emit CALL_BY_KEY instruction
        builder.emitCallByKey(0);

        CraftingBytecode bytecode = builder.build();
        byte[] code = bytecode.getCode();

        // Verify the CALL_BY_KEY opcode is present; the following 2 bytes are the
        // short operand (0x00, 0x00 for index 0).
        boolean foundCallByKey = false;
        for (int i = 0; i < code.length; i++) {
            if ((code[i] & 0xFF) == Opcode.CALL_BY_KEY.code) {
                foundCallByKey = true;
                assertEquals(0x00, code[i + 1] & 0xFF, "High byte should be 0");
                assertEquals(0x00, code[i + 2] & 0xFF, "Low byte should be 0");
                break;
            }
        }
        assertTrue(foundCallByKey, "CALL_BY_KEY opcode not found in bytecode");
        System.out.println("[TEST] CALL_BY_KEY bytecode: " + bytecode.getCodeLength() + " bytes, OK!");
    }

    @Test
    void testRequestWrapperBytecode() {
        // Test the request wrapper pattern:
        // PUSH_LONG craftTimes → CALL patternIdx → HALT
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        builder.setOutput(0, 1);

        // PUSH_LONG 42 (craft 42 times)
        builder.emitPushLong(42);

        // CALL pattern at index 0
        int patternIdx = builder.addPattern(null); // placeholder
        builder.emit(Opcode.CALL);
        builder.emitShort(patternIdx);

        // HALT
        builder.emitHalt();

        CraftingBytecode bytecode = builder.build();
        byte[] code = bytecode.getCode();

        boolean foundPushLong42 = false;
        for (int i = 0; i < code.length - 8; i++) {
            if ((code[i] & 0xFF) == Opcode.PUSH_LONG.code) {
                // emitLong writes big-endian
                long v = 0;
                for (int b = 0; b < 8; b++) {
                    v = (v << 8) | (code[i + 1 + b] & 0xFF);
                }
                if (v == 42L) {
                    foundPushLong42 = true;
                    break;
                }
            }
        }
        assertTrue(foundPushLong42, "PUSH_LONG 42 not found in request wrapper");
        assertTrue((code[code.length - 1] & 0xFF) == Opcode.HALT.code,
                "request wrapper must end with HALT");
        System.out.println("[TEST] Request wrapper bytecode OK!");
    }

    @Test
    void testMultiplication() {
        assertEquals(6L, 2L * 3L);
        assertEquals(39088196L, 39088196L * 1);
        System.out.println("[TEST] Multiplication tests passed!");
    }

    @Test
    void testFullPatternBytecode() {
        // Simulate what compilePattern generates:
        // DUP → RECORD_PATTERN → (for each input: DUP, PUSH_LONG, MUL, EXTRACT, CALL_BY_KEY) → POP → RETURN
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        builder.addConstant(k("vm_out"));  // output at idx 0
        builder.addPattern(null);          // pattern at idx 0 (pattern pool tolerates null)
        builder.setOutput(0, 1);

        // DUP
        builder.emit(Opcode.DUP);
        // RECORD_PATTERN 0
        builder.emitRecordPattern(0);

        // Input 1: DUP → PUSH_LONG 3 → MUL → EXTRACT 1 → CALL_BY_KEY 1
        builder.addConstant(k("vm_input")); // input key at idx 1
        builder.emit(Opcode.DUP);
        builder.emitPushLong(3);
        builder.emit(Opcode.MUL);
        builder.emitExtractIngredient(1);
        builder.emitCallByKey(1);

        // POP
        builder.emit(Opcode.POP);
        // RETURN
        builder.emit(Opcode.RETURN);

        CraftingBytecode bytecode = builder.build();

        assertTrue(bytecode.getCodeLength() > 0);
        System.out.println("[TEST] Full pattern bytecode: " + bytecode.getCodeLength() + " bytes, OK!");
    }

    private static long divRoundUp(long a, long b) {
        if (a <= 0) return 0;
        return (a + b - 1) / b;
    }
}
