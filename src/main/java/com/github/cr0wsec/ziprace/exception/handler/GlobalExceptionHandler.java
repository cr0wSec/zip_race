package com.github.cr0wsec.ziprace.exception.handler;

import com.github.cr0wsec.ziprace.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralizes the translation of application exceptions into HTTP responses.
 * <p>
 * Applies a defense-in-depth policy: clients receive generic error messages
 * to prevent information disclosure, while detailed messages
 * and stack traces are logged server-side for debugging.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<?> handleQueueFull(QueueFullException e) {
        log.warn("Request rejected: {}", e.getMessage());
        return ResponseEntity.status(503).body(
                Map.of("error", "Service temporarily unavailable, please retry later")
        );
    }

    @ExceptionHandler({ZipProcessingException.class, InvalidZipEntryException.class, EmptyZipException.class})
    public ResponseEntity<?> handleInvalidZip(RuntimeException e) {
        log.warn("Invalid zip upload: {}", e.getMessage());
        return ResponseEntity.badRequest().body(
                Map.of("error", "Invalid zip file")
        );
    }

    /**
     * Catches all unmapped exceptions. Logs at ERROR level (others are WARN)
     * because reaching here means a case we didn't anticipate.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(
                Map.of("error", "Internal server error")
        );
    }

    @ExceptionHandler(TooManyZipEntriesException.class)
    public ResponseEntity<?> handleZipTooLarge(TooManyZipEntriesException e) {
        log.warn("Zip exceeds limits: {}", e.getMessage());
        return ResponseEntity.status(413).body(
                Map.of("error", "Zip file exceeds maximum allowed entry count")
        );
    }
}
