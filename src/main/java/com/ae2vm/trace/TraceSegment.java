package com.ae2vm.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * One lifecycle phase of an order's trace: CALC (the VM
 * calculation), START (setJob extraction/scheduling), AUDIT (invariants,
 * watchdog notes, completion stamps). Segments partition the event
 * timeline; the chained hash runs across all segments in order.
 */
public final class TraceSegment {

    public static final String CALC = "CALC";
    public static final String START = "START";
    public static final String AUDIT = "AUDIT";

    public final String phase;
    public final List<TraceEvent> events = new ArrayList<>();

    /**
     * O(1) ring eviction marker: events [0, headSkip) are logically
     * dropped (memory stays bounded without ArrayList shifts). The
     * writer skips them; they are never serialized.
     */
    public int headSkip;

    public TraceSegment(String phase) {
        this.phase = phase;
    }

    /** Logical (post-eviction) event count. */
    public int liveCount() {
        return events.size() - headSkip;
    }
}
