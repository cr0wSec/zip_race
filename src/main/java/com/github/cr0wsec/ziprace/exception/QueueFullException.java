package com.github.cr0wsec.ziprace.exception;

/**
 * Thrown when the write queue is at capacity and cannot accept a new task.
 * Translated to HTTP 503 by the global exception handler.
 */
public class QueueFullException extends RuntimeException {
    public QueueFullException() {
        super("Write queue is full, cannot accept new uploads");
    }
}