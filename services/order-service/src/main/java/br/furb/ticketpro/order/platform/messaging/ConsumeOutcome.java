package br.furb.ticketpro.order.platform.messaging;

/** What {@link SqsConsumer#process} did with one message (RFC 0003 §5.4). */
public enum ConsumeOutcome {
  SUCCEEDED,
  DUPLICATE,
  STALE,
  POISON,
  TRANSIENT
}
