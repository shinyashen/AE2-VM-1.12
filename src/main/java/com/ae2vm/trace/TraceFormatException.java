package com.ae2vm.trace;

/** A trace file could not be parsed at all (bad gzip, bad JSON, unsupported format). */
public class TraceFormatException extends RuntimeException {

    public TraceFormatException(String message) {
        super(message);
    }

    public TraceFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
