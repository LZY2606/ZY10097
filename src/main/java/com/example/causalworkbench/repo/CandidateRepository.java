package com.example.causalworkbench.repo;

import com.example.causalworkbench.domain.Candidate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<Candidate, String> {
    List<Candidate> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<Candidate> findByParentCandidateId(String parentCandidateId);
}
