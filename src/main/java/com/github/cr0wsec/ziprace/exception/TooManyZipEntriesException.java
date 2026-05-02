package com.github.cr0wsec.ziprace.exception;

/**
 * Thrown when a zip contains more entries than the configured maximum.
 * Translated to HTTP 413 Payload Too Large.
 */
public class TooManyZipEntriesException extends RuntimeException {
    public TooManyZipEntriesException(String message) {
        super(message);
    }
}
