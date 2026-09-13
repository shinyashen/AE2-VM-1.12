package com.ae2vm.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * One lifecycle phase of an order's trace (design doc §2): CALC (the VM
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

    public TraceSegment(String phase) {
        this.phase = phase;
    }
}
