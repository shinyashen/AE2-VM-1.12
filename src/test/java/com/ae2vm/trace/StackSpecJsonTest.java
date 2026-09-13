package com.ae2vm.trace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M0 acceptance: StackSpec canonical-JSON round trips + forward compatibility. */
class StackSpecJsonTest {

    @Test
    void specRoundTripsThroughCanonicalJson() {
        StackSpec withNbt = new StackSpec(false, "i#0f3c", 166, "n#5d2");
        assertEquals(withNbt, StackSpec.fromJson(withNbt.toJson()));

        StackSpec fluid = new StackSpec(true, "f#00aa", 0, null);
        assertEquals(fluid, StackSpec.fromJson(fluid.toJson()));
    }

    @Test
    void unknownMembersAreIgnored() {
        JsonObject o = new StackSpec(false, "i#0001", 3, null).toJson();
        o.addProperty("futureField", "whatever a newer writer added");
        StackSpec back = StackSpec.fromJson(o);
        assertEquals("i#0001", back.token);
        assertEquals(3, back.damage);
        assertNull(back.nbtToken);
    }

    @Test
    void countsSurviveBigIntegerMagnitudes() {
        StackEntry e = new StackEntry(
                new StackSpec(false, "i#0002", 0, null),
                "123456789012345678901234567890", true);
        StackEntry back = StackEntry.fromJson(e.toJson());
        assertEquals("123456789012345678901234567890", back.count);
        assertTrue(back.craftable);
        assertEquals(e.spec, back.spec);
    }

    @Test
    void malformedShapesReturnNull() {
        assertNull(StackSpec.fromJson(null));
        assertNull(StackSpec.fromJson(new JsonObject()));
        assertNull(StackEntry.fromJson(new JsonObject()));
    }
}
