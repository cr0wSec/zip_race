package com.github.cr0wsec.ziprace.service;

import com.github.cr0wsec.ziprace.model.UploadBatch;
import com.github.cr0wsec.ziprace.repository.BatchRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-side service for retrieving batch state. Exposes a thin facade
 * over {@link BatchRepository} for use by HTTP controllers.
 */
@Service
public class BatchService {

    private final BatchRepository batchRepository;

    public BatchService(BatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    public Optional<UploadBatch> findById(UUID id) {
        return batchRepository.findById(id);
    }
}