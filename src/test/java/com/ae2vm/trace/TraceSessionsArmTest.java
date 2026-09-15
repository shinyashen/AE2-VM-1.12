package com.ae2vm.trace;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1: the arm state machine — NEXT is one-shot and player-matched
 * (machine orders never consume another player's slot), WINDOW is global
 * until disarmed.
 */
class TraceSessionsArmTest {

    @Test
    void nextModeConsumesOnlyOnMatchingPlayer() {
        UUID me = UUID.randomUUID();
        assertTrue(TraceSessions.armNext(me));
        assertEquals("next:" + me, TraceSessions.status());
        assertTrue(TraceSessions.armed());

        assertFalse(TraceSessions.shouldRecord(null), "machine order must not consume the slot");
        assertTrue(TraceSessions.armed(), "a non-matching order must leave the arm intact");
        assertFalse(TraceSessions.shouldRecord(UUID.randomUUID()));

        assertTrue(TraceSessions.shouldRecord(me), "the arming player's order consumes the arm");
        assertFalse(TraceSessions.armed(), "NEXT is one-shot");
        assertFalse(TraceSessions.shouldRecord(me), "a consumed arm records nothing");
    }

    @Test
    void windowModeIsGlobalAndDisarmable() {
        assertTrue(TraceSessions.armWindow());
        assertEquals("window", TraceSessions.status());
        assertTrue(TraceSessions.shouldRecord(null));
        assertTrue(TraceSessions.shouldRecord(UUID.randomUUID()));
        assertTrue(TraceSessions.disarm());
        assertFalse(TraceSessions.armed());
        assertFalse(TraceSessions.disarm(), "disarming an unarmed state is a no-op");
        assertFalse(TraceSessions.shouldRecord(UUID.randomUUID()));
    }

    @Test
    void doubleArmIsRejected() {
        assertTrue(TraceSessions.armNext(UUID.randomUUID()));
        assertFalse(TraceSessions.armWindow(), "an armed state must not be re-armed");
        assertFalse(TraceSessions.armNext(UUID.randomUUID()));
        assertTrue(TraceSessions.disarm());
    }
}
