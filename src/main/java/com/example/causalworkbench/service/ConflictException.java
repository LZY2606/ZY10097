package com.example.causalworkbench.service;

import java.util.Map;

public class ConflictException extends RuntimeException {
    private final Map<String, Object> body;

    public ConflictException(String message, Map<String, Object> body) {
        super(message);
        this.body = Map.copyOf(body);
    }

    public Map<String, Object> getBody() {
        return body;
    }
}
