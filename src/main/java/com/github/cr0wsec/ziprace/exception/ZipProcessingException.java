package com.github.cr0wsec.ziprace.exception;

/**
 * Thrown when the zip stream cannot be read due to an underlying I/O error
 * or a malformed archive structure. Distinct from {@link InvalidZipEntryException}
 * (which signals semantically valid entries with bad metadata) and
 * {@link EmptyZipException} (which signals a valid zip with no files).
 */
public class ZipProcessingException extends RuntimeException {
    public ZipProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
