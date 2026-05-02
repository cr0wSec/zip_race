package com.github.cr0wsec.ziprace.exception;

/**
 * Thrown when a zip is structurally valid but contains no file entries
 * (only directories or completely empty).
 */
public class EmptyZipException extends RuntimeException {
    public EmptyZipException(String message) {
        super(message);
    }
}
