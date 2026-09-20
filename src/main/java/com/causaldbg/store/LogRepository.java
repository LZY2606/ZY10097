package com.causaldbg.store;

import com.causaldbg.domain.EventLog;
import com.causaldbg.domain.RawEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class LogRepository {

    private final JdbcTemplate jdbc;

    public LogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<EventLog> LOG_MAPPER = (rs, rowNum) -> new EventLog(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("fingerprint"),
            rs.getInt("event_count"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<RawEvent> EVENT_MAPPER = (rs, rowNum) -> new RawEvent(
            rs.getLong("id"),
            rs.getLong("log_id"),
            rs.getString("event_uid"),
            rs.getString("service"),
            rs.getLong("seq"),
            rs.getString("corr_key"),
            rs.getString("parent_uid"),
            rs.getString("event_type"),
            rs.getString("payload_hash"),
            timestamp(rs, "wall_clock"),
            rs.getLong("received_index"),
            false);

    private static Instant timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    @Transactional
    public EventLog appendLog(String name, String fingerprint, List<RawEvent> events) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO event_log(name, fingerprint, event_count, created_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, fingerprint);
            statement.setInt(3, events.size());
            statement.setTimestamp(4, Timestamp.from(now));
            return statement;
        }, keyHolder);
        long logId = keyHolder.getKey().longValue();
        int index = 0;
        for (RawEvent event : events) {
            jdbc.update("""
                    INSERT INTO raw_event(log_id, event_uid, service, seq, corr_key,
                        parent_uid, event_type, payload_hash, wall_clock, received_index)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,
                    logId, event.eventUid(), event.service(), event.seq(), event.key(),
                    event.parentUid(), event.type(), event.payloadHash(),
                    event.wallClock() == null ? null : Timestamp.from(event.wallClock()),
                    (long) index);
            index++;
        }
        return new EventLog(logId, name, fingerprint, events.size(), now);
    }

    public List<EventLog> findAllLogs() {
        return jdbc.query("SELECT * FROM event_log ORDER BY id", LOG_MAPPER);
    }

    public EventLog findLog(long id) {
        List<EventLog> result = jdbc.query("SELECT * FROM event_log WHERE id=?", LOG_MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    public List<RawEvent> findEvents(long logId) {
        return jdbc.query("SELECT * FROM raw_event WHERE log_id=? ORDER BY received_index, id",
                EVENT_MAPPER, logId);
    }
}
