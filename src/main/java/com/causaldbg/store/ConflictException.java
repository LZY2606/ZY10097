package com.causaldbg.store;

/** Thrown when a later writer submits a stale session revision. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
