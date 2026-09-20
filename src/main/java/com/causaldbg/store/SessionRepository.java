package com.causaldbg.store;

import com.causaldbg.domain.DebugSession;
import com.causaldbg.domain.Hypothesis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private RowMapper<DebugSession> mapper() {
        return (rs, rowNum) -> new DebugSession(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getLong("log_id"),
                rs.getString("log_fingerprint"),
                rs.getString("rule_version"),
                rs.getString("rule_json"),
                readHypothesis(rs.getString("hypothesis_json")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getBoolean("invalidated"),
                rs.getString("invalidation_reason"));
    }

    private Hypothesis readHypothesis(String json) {
        try {
            return mapper.readValue(json, Hypothesis.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt hypothesis json", e);
        }
    }

    private String writeHypothesis(Hypothesis hypothesis) {
        try {
            return mapper.writeValueAsString(hypothesis == null ? Hypothesis.empty() : hypothesis);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public DebugSession create(String name, long logId, String logFingerprint,
                               String ruleVersion, String ruleJson, Hypothesis hypothesis) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO debug_session(name, log_id, log_fingerprint, rule_version, rule_json,
                        hypothesis_json, revision, invalidated, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setLong(2, logId);
            statement.setString(3, logFingerprint);
            statement.setString(4, ruleVersion);
            statement.setString(5, ruleJson);
            statement.setString(6, writeHypothesis(hypothesis));
            statement.setLong(7, 0L);
            statement.setBoolean(8, false);
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setTimestamp(10, Timestamp.from(now));
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue());
    }

    public List<DebugSession> findAll() {
        return jdbc.query("SELECT * FROM debug_session ORDER BY id", mapper());
    }

    public DebugSession findById(long id) {
        List<DebugSession> result = jdbc.query("SELECT * FROM debug_session WHERE id=?",
                mapper(), id);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Optimistic update. The WHERE clause pins the expected revision so two
     * concurrent editors cannot silently overwrite each other: the later commit
     * updates zero rows and receives a {@link ConflictException}.
     */
    @Transactional
    public DebugSession update(long id, String name, Hypothesis hypothesis,
                               String ruleVersion, String ruleJson, long expectedRevision) {
        int updated = jdbc.update("""
                UPDATE debug_session
                   SET name=?, hypothesis_json=?, rule_version=?, rule_json=?,
                       revision=revision+1, updated_at=?
                 WHERE id=? AND revision=?
                """,
                name, writeHypothesis(hypothesis), ruleVersion, ruleJson,
                Timestamp.from(Instant.now()), id, expectedRevision);
        if (updated == 0) {
            DebugSession current = findById(id);
            if (current == null) {
                throw new ConflictException("session " + id + " no longer exists");
            }
            throw new ConflictException("session " + id + " was modified concurrently"
                    + " (expected revision " + expectedRevision
                    + ", current revision " + current.revision() + ")");
        }
        return findById(id);
    }

    @Transactional
    public void markInvalidated(long id, String reason) {
        jdbc.update("UPDATE debug_session SET invalidated=TRUE, invalidation_reason=?, updated_at=? WHERE id=?",
                reason, Timestamp.from(Instant.now()), id);
    }
}
