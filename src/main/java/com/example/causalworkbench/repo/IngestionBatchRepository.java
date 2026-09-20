package com.example.causalworkbench.repo;

import com.example.causalworkbench.domain.IngestionBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, String> {
}
