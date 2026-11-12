package br.furb.ticketpro.auth.platform.messaging;

import br.furb.ticketpro.auth.platform.envelope.Envelope;
import br.furb.ticketpro.auth.platform.envelope.EnvelopeCodec;
import br.furb.ticketpro.auth.platform.outbox.OutboxPublisher;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publishes envelopes as the SNS Message with type, version, correlationId and traceparent
 * attributes (RFC 0001 §5.2).
 */
public final class SnsOutboxPublisher implements OutboxPublisher {

  private final SnsClient sns;

  public SnsOutboxPublisher(SnsClient sns) {
    this.sns = sns;
  }

  /** An empty traceparent is omitted: SNS rejects empty String attributes. */
  @Override
  public void publish(String topicArn, Envelope envelope, Optional<String> traceparent) {
    Map<String, MessageAttributeValue> attributes = new HashMap<>();
    attributes.put("type", attr(envelope.type()));
    attributes.put("version", attr(Integer.toString(envelope.version())));
    attributes.put("correlationId", attr(envelope.correlationId()));
    traceparent
        .filter(tp -> !tp.isEmpty())
        .ifPresent(tp -> attributes.put("traceparent", attr(tp)));
    sns.publish(b ->
        b.topicArn(topicArn)
            .message(EnvelopeCodec.serialize(envelope))
            .messageAttributes(attributes));
  }

  private static MessageAttributeValue attr(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }
}
