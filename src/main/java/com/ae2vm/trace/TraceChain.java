package com.ae2vm.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The per-event chained checksum (WAL frame-chain model, design doc
 * §3.1): H = SHA-256(prevHex + "\n" + canonicalEventJson). Any edit,
 * reorder or deletion after the fact breaks every subsequent link, which
 * is the technical backing for "please don't edit" and for the loader's
 * truncate-at-first-bad-frame semantics.
 */
public final class TraceChain {

    private TraceChain() {
    }

    /** Chain anchor derived from the trace id. */
    public static String genesis(String traceId) {
        return sha256Hex("aevm-trace-v1:" + traceId);
    }

    public static String next(String prevHex, String canonicalEventJson) {
        return sha256Hex(prevHex + "\n" + canonicalEventJson);
    }

    public static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
