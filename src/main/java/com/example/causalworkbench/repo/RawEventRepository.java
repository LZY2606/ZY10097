package com.example.causalworkbench.repo;

import com.example.causalworkbench.domain.RawEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawEventRepository extends JpaRepository<RawEvent, String> {
    List<RawEvent> findAllByOrderByReceivedOrderAsc();
    List<RawEvent> findByEventIdOrderByReceivedOrderAsc(String eventId);
    List<RawEvent> findByServiceNameAndSeqNoOrderByReceivedOrderAsc(String serviceName, long seqNo);
}
