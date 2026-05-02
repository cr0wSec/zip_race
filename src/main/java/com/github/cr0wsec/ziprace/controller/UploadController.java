package com.github.cr0wsec.ziprace.controller;

import com.github.cr0wsec.ziprace.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/uploads")
public class UploadController {

    private final UploadService uploadService;
    private final long maxUploadSizeBytes;

    public UploadController(
            UploadService uploadService,
            @Value("${ziprace.upload.max-size-bytes:104857600}") long maxUploadSizeBytes) {
        this.uploadService = uploadService;
        this.maxUploadSizeBytes = maxUploadSizeBytes;
    }

    /**
     * Accepts a raw zip upload. The body must be the zip bytes directly
     * (Content-Type: application/zip or application/octet-stream).
     * <p>
     * Requires a Content-Length header. Chunked transfer encoding is rejected
     * with HTTP 411 Length Required because the size cannot be validated upfront.
     * Bodies exceeding the configured limit return HTTP 413 Content Too Large.
     * <p>
     * Returns 202 Accepted with {@code {"batchId": "<uuid>"}} once the task
     * is queued.
     */
    @PostMapping(consumes = {"application/zip", "application/octet-stream"})
    public ResponseEntity<?> upload(HttpServletRequest request) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            throw new ResponseStatusException(
                    HttpStatus.LENGTH_REQUIRED,
                    "Content-Length header required"
            );
        }
        if (contentLength > maxUploadSizeBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Upload exceeds limit");
        }
        UUID batchId = uploadService.handleUpload(request.getInputStream());
        return ResponseEntity.accepted().body(Map.of("batchId", batchId));
    }
}