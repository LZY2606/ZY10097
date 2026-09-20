package com.example.causalworkbench.service;

import com.example.causalworkbench.domain.RuleSet;
import com.example.causalworkbench.repo.RuleSetRepository;
import com.example.causalworkbench.service.ReplayModels.WorkbenchRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleService {
    private final RuleSetRepository repository;
    private final ObjectMapper objectMapper;
    private final Hashes hashes;

    public RuleService(RuleSetRepository repository, ObjectMapper objectMapper, Hashes hashes) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.hashes = hashes;
    }

    @PostConstruct
    @Transactional
    public void seedDefaultRules() {
        if (repository.findByActiveTrue().isPresent()) {
            return;
        }
        String json;
        try (InputStream stream = getClass().getResourceAsStream("/default-rules.json")) {
            if (stream == null) {
                throw new IllegalStateException("default-rules.json missing");
            }
            json = new String(stream.readAllBytes());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        parseRules(json);
        repository.save(new RuleSet("aggregate-lifecycle", 1, json, hashes.sha256(json), true,
                Instant.now().toString()));
    }

    @Transactional
    public RuleSet createVersion(String definitionJson) {
        parseRules(definitionJson);
        RuleSet current = active();
        repository.findAll().stream().filter(RuleSet::isActive).forEach(RuleSet::deactivate);
        RuleSet updated = new RuleSet(current.getCode(), current.getVersion() + 1, definitionJson,
                hashes.sha256(definitionJson), true, Instant.now().toString());
        return repository.save(updated);
    }

    @Transactional(readOnly = true)
    public RuleSet active() {
        return repository.findByActiveTrue().orElseThrow(() -> new IllegalStateException("No active rules"));
    }

    public WorkbenchRules parseRules(String json) {
        try {
            return objectMapper.readValue(json, WorkbenchRules.class);
        } catch (IOException exception) {
            throw new BadRequestException("invalid rule JSON: " + exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public WorkbenchRules activeRules() {
        return parseRules(active().getDefinitionJson());
    }
}
