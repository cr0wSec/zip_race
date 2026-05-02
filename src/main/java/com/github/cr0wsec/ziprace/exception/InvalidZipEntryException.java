package com.github.cr0wsec.ziprace.exception;

/**
 * Thrown when a zip entry has invalid metadata, specifically when the
 * declared size is unavailable (header reports -1). Indicates a malformed
 * or tampered zip header.
 */
public class InvalidZipEntryException extends RuntimeException {
    public InvalidZipEntryException(String message) {
        super(message);
    }
}
