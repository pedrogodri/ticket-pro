package br.furb.ticketpro.order.platform.outbox;

import java.time.Duration;

/**
 * Outbox relay tuning (OUTBOX_* environment variables, RFC 0003 §5.1). {@code claimLease} hides
 * claimed rows from other relays while they are published outside a transaction.
 */
public record OutboxRelayProperties(
    int batchSize, Duration pollInterval, Duration maxBackoff, Duration claimLease) {}
