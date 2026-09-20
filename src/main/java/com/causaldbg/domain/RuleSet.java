package com.causaldbg.domain;

import java.util.List;
import java.util.Map;

/**
 * Versioned rule document bound to a debug session.
 * A rule version change forces new derived scenarios; old sessions keep
 * their pinned version and report staleness instead of being rewritten.
 */
public record RuleSet(
        String version,
        String defaultInitialState,
        List<AggregateRule> aggregates
) {
    public AggregateRule match(String key) {
        AggregateRule best = null;
        int bestLen = -1;
        if (aggregates != null) {
            for (AggregateRule rule : aggregates) {
                if (key != null && key.startsWith(rule.keyPrefix()) && rule.keyPrefix().length() > bestLen) {
                    best = rule;
                    bestLen = rule.keyPrefix().length();
                }
            }
        }
        return best;
    }

    public record AggregateRule(
            String keyPrefix,
            String initialState,
            List<Transition> transitions,
            List<String> invariants
    ) {
        public boolean allows(String state, String eventType) {
            for (Transition transition : transitions) {
                if (transition.from().equals(state) && transition.event().equals(eventType)) {
                    return true;
                }
            }
            return false;
        }

        public String target(String state, String eventType) {
            for (Transition transition : transitions) {
                if (transition.from().equals(state) && transition.event().equals(eventType)) {
                    return transition.to();
                }
            }
            return state;
        }
    }

    public record Transition(String from, String event, String to) {
    }

    public static List<String> defaultInvariants() {
        return List.of("every applied event must have a declared transition from the current state");
    }
}
