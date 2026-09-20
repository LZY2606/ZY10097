package com.example.causalworkbench.repo;

import com.example.causalworkbench.domain.RuleSet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleSetRepository extends JpaRepository<RuleSet, Long> {
    Optional<RuleSet> findByActiveTrue();
    Optional<RuleSet> findByCodeAndVersion(String code, int version);
}
