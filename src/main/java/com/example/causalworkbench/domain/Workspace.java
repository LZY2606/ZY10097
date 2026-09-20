package com.example.causalworkbench.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspace")
public class Workspace {
    @Id
    private String sessionId;

    @Column(nullable = false)
    private String status;

    private String selectedCandidateId;

    @Column(length = 4000)
    private String note;

    @Version
    private long revision;

    @Column(nullable = false)
    private String updatedAt;

    protected Workspace() {
    }

    public Workspace(String sessionId, String status, String selectedCandidateId, String note, String updatedAt) {
        this.sessionId = sessionId;
        this.status = status;
        this.selectedCandidateId = selectedCandidateId;
        this.note = note;
        this.updatedAt = updatedAt;
    }

    public String getSessionId() { return sessionId; }
    public String getStatus() { return status; }
    public String getSelectedCandidateId() { return selectedCandidateId; }
    public String getNote() { return note; }
    public long getRevision() { return revision; }
    public String getUpdatedAt() { return updatedAt; }

    public void update(String status, String selectedCandidateId, String note, String updatedAt) {
        this.status = status;
        this.selectedCandidateId = selectedCandidateId;
        this.note = note;
        this.updatedAt = updatedAt;
    }
}
