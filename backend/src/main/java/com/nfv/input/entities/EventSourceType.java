package com.nfv.input.entities;

public enum EventSourceType {
    /** Traffic feed that is polled, delivers {@link TrafficEvent}s. */
    TRAFFIC_FEED,
    /** Third party that pushes to a webhook, delivers {@link WebhookEvent}s. */
    WEBHOOK
}
