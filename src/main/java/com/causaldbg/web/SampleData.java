package com.causaldbg.web;

import java.util.List;

/**
 * Built-in incident: two services order-svc / payment-svc share aggregate
 * order:42 with a duplicate delivery, a late event, a missing causal parent,
 * and an ordering-sensitive concurrent pair that makes Shipped arrive in a
 * state where it has no valid transition.
 */
public final class SampleData {

    private SampleData() {
    }

    public static List<Dtos.IngestEvent> events() {
        return List.of(
                new Dtos.IngestEvent("e1", "order-svc", 1, "order:42", null,
                        "OrderCreated", "h:100", "2026-09-21T10:00:00Z"),
                new Dtos.IngestEvent("e2", "payment-svc", 1, "order:42", "e1",
                        "PaymentAccepted", "h:200", "2026-09-21T10:00:02Z"),
                new Dtos.IngestEvent("e2", "payment-svc", 1, "order:42", "e1",
                        "PaymentAccepted", "h:200", "2026-09-21T10:00:02Z"),
                new Dtos.IngestEvent("e4", "shipping-svc", 1, "order:42", "e9",
                        "Shipped", "h:400", "2026-09-21T10:00:09Z"),
                new Dtos.IngestEvent("e5", "order-svc", 3, "order:42", "e2",
                        "Refunded", "h:500", "2026-09-21T10:00:05Z"),
                new Dtos.IngestEvent("e3", "order-svc", 2, "order:42", "e2",
                        "Delivered", "h:300", "2026-09-21T10:00:11Z"),
                new Dtos.IngestEvent("e6", "order-svc", 4, "order:42", "e3",
                        "OrderCreated", "h:600", "2026-09-21T10:00:13Z"),
                new Dtos.IngestEvent("i1", "inventory-svc", 1, "inv:7", null,
                        "StockIn", "h:i1", "2026-09-21T10:00:01Z"),
                new Dtos.IngestEvent("i2", "inventory-svc", 2, "inv:7", "i1",
                        "Reserved", "h:i2", "2026-09-21T10:00:03Z")
        );
    }
}
