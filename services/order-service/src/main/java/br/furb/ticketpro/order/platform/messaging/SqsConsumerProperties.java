package br.furb.ticketpro.order.platform.messaging;

import java.time.Duration;

/**
 * Consumer settings: QUEUE_URL, consumer name, CONSUMER_CONCURRENCY, SQS_WAIT_TIME_S and the HTTP
 * timeout of the other SQS calls (HTTP_CLIENT_TIMEOUT_MS).
 */
public record SqsConsumerProperties(
    String queueUrl, String name, int concurrency, int waitTimeSeconds, Duration requestTimeout) {}
