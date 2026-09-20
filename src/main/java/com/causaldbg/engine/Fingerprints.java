package com.causaldbg.engine;

import com.causaldbg.domain.Hypothesis;
import com.causaldbg.domain.RawEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 source fingerprints for immutable evidence and derived artifacts. */
public final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    public static String logFingerprint(Iterable<RawEvent> events) {
        MessageDigest digest = sha256();
        for (RawEvent event : events) {
            String line = String.join("|",
                    nullToDash(event.eventUid()),
                    nullToDash(event.service()),
                    Long.toString(event.seq()),
                    nullToDash(event.key()),
                    nullToDash(event.parentUid()),
                    nullToDash(event.type()),
                    nullToDash(event.payloadHash()),
                    event.wallClock() == null ? "-" : event.wallClock().toString(),
                    Long.toString(event.receivedIndex()));
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String ruleFingerprint(Object ruleSet) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(ruleSet);
            return HexFormat.of().formatHex(sha256().digest(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to fingerprint rules", e);
        }
    }

    public static String hypothesisFingerprint(Hypothesis hypothesis) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(hypothesis == null ? Hypothesis.empty() : hypothesis);
            return HexFormat.of().formatHex(sha256().digest(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to fingerprint hypothesis", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }
}
