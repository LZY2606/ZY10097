package com.example.causalworkbench.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rule_set")
public class RuleSet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 4000)
    private String definitionJson;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private String createdAt;

    protected RuleSet() {
    }

    public RuleSet(String code, int version, String definitionJson, String fingerprint, boolean active, String createdAt) {
        this.code = code;
        this.version = version;
        this.definitionJson = definitionJson;
        this.fingerprint = fingerprint;
        this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public int getVersion() { return version; }
    public String getDefinitionJson() { return definitionJson; }
    public String getFingerprint() { return fingerprint; }
    public boolean isActive() { return active; }
    public String getCreatedAt() { return createdAt; }

    public void deactivate() {
        this.active = false;
    }
}
