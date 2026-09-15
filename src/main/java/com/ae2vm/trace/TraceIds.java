package com.ae2vm.trace;

import java.security.SecureRandom;

/** Trace file handle generation: "yyyyMMdd-HHmmss-xxxx". */
public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIds() {
    }

    public static String newTraceId(long wallMillis) {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                .format(new java.util.Date(wallMillis));
        StringBuilder sb = new StringBuilder(ts.length() + 5);
        sb.append(ts).append('-');
        for (int i = 0; i < 4; i++) {
            sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }
}
