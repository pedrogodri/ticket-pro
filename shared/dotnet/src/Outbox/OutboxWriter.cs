using Npgsql;
using NpgsqlTypes;

namespace TicketPro.Platform;

/// <summary>Sends one envelope to a topic; traceparent is null when the event has no trace context.</summary>
public interface IOutboxPublisher
{
    Task PublishAsync(string topicArn, Envelope envelope, string? traceparent, CancellationToken ct);
}

/// <summary>Writes events in the caller's transaction (ADR-0005).</summary>
public static class OutboxWriter
{
    /// <summary>Inserts the envelope with the traceparent of the current activity (NULL if none).</summary>
    public static async Task EnqueueAsync(NpgsqlConnection connection, NpgsqlTransaction transaction, string topicArn, Envelope envelope, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO outbox (message_id, topic_arn, type, version, correlation_id, traceparent, payload, occurred_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            """, connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(envelope.MessageId) });
        cmd.Parameters.Add(new NpgsqlParameter { Value = topicArn });
        cmd.Parameters.Add(new NpgsqlParameter { Value = envelope.Type });
        cmd.Parameters.Add(new NpgsqlParameter { Value = envelope.Version });
        cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(envelope.CorrelationId) });
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)TraceContext.Inject() ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Text });
        cmd.Parameters.Add(new NpgsqlParameter { Value = envelope.Payload.GetRawText(), NpgsqlDbType = NpgsqlDbType.Jsonb });
        cmd.Parameters.Add(new NpgsqlParameter { Value = envelope.OccurredAt.UtcDateTime, NpgsqlDbType = NpgsqlDbType.TimestampTz });
        await cmd.ExecuteNonQueryAsync(ct);
    }
}
