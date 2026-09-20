package com.causaldbg.domain;

import java.util.List;

/** Built-in rule document; sessions pin its version + fingerprint. */
public final class DefaultRules {

    public static final String VERSION = "rules-v1";

    private DefaultRules() {
    }

    public static RuleSet get() {
        return new RuleSet(
                VERSION,
                "NONEXISTENT",
                List.of(
                        new RuleSet.AggregateRule(
                                "order:",
                                "NONEXISTENT",
                                List.of(
                                        new RuleSet.Transition("NONEXISTENT", "OrderCreated", "OPEN"),
                                        new RuleSet.Transition("OPEN", "PaymentAccepted", "PAID"),
                                        new RuleSet.Transition("OPEN", "PaymentRejected", "PAYMENT_FAILED"),
                                        new RuleSet.Transition("PAID", "Shipped", "SHIPPED"),
                                        new RuleSet.Transition("SHIPPED", "Delivered", "DELIVERED"),
                                        new RuleSet.Transition("PAID", "Refunded", "REFUNDED"),
                                        new RuleSet.Transition("OPEN", "Cancelled", "CANCELLED"),
                                        new RuleSet.Transition("PAYMENT_FAILED", "PaymentAccepted", "PAID"),
                                        new RuleSet.Transition("DELIVERED", "Returned", "RETURNED")
                                ),
                                List.of("no event may be applied without a declared transition")),
                        new RuleSet.AggregateRule(
                                "inv:",
                                "ABSENT",
                                List.of(
                                        new RuleSet.Transition("ABSENT", "StockIn", "AVAILABLE"),
                                        new RuleSet.Transition("AVAILABLE", "Reserved", "RESERVED"),
                                        new RuleSet.Transition("RESERVED", "StockOut", "ABSENT"),
                                        new RuleSet.Transition("AVAILABLE", "StockOut", "ABSENT")
                                ),
                                List.of("no event may be applied without a declared transition"))
                )
        );
    }
}
