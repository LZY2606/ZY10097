package com.example.causalworkbench.repo;

import com.example.causalworkbench.domain.DebugSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DebugSessionRepository extends JpaRepository<DebugSession, String> {
    List<DebugSession> findByParentSessionId(String parentSessionId);
}
