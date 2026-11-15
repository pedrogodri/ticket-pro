using System.Globalization;
using System.Text;
using System.Text.Json;

namespace TicketPro.Platform;

/// <summary>Envelope serialization and SQS body unwrapping (SNS notification or raw delivery, RFC 0001 §5.2).</summary>
public static class EnvelopeJson
{
    private const string OccurredAtFormat = "yyyy-MM-ddTHH:mm:ss.fffZ";

    /// <summary>Exactly the six envelope keys, occurredAt as RFC 3339 UTC with milliseconds.</summary>
    public static string Serialize(Envelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        using var buffer = new MemoryStream();
        using (var w = new Utf8JsonWriter(buffer))
        {
            w.WriteStartObject();
            w.WriteString("messageId", envelope.MessageId);
            w.WriteString("correlationId", envelope.CorrelationId);
            w.WriteString("type", envelope.Type);
            w.WriteNumber("version", envelope.Version);
            w.WriteString("occurredAt", envelope.OccurredAt.UtcDateTime.ToString(OccurredAtFormat, CultureInfo.InvariantCulture));
            w.WritePropertyName("payload");
            envelope.Payload.WriteTo(w);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>The envelope JSON carried by an SQS body. Throws <see cref="PoisonMessageException"/>.</summary>
    public static string RawEnvelope(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                throw new PoisonMessageException("body is not a JSON object");
            }
            if (root.TryGetProperty("Type", out var t) && t.ValueKind == JsonValueKind.String && t.GetString() == "Notification"
                && root.TryGetProperty("Message", out var m) && m.ValueKind == JsonValueKind.String)
            {
                return m.GetString()!;
            }
            return body;
        }
        catch (JsonException e)
        {
            throw new PoisonMessageException("body is not JSON", e);
        }
    }

    /// <summary>Parses the envelope from an SQS body. Any failure throws <see cref="PoisonMessageException"/>.</summary>
    public static Envelope Unwrap(string body)
    {
        var raw = RawEnvelope(body);
        try
        {
            using var doc = JsonDocument.Parse(raw);
            var e = doc.RootElement;
            if (e.ValueKind != JsonValueKind.Object)
            {
                throw new PoisonMessageException("envelope is not a JSON object");
            }
            var envelope = new Envelope(
                e.GetProperty("messageId").GetString() ?? "",
                e.GetProperty("correlationId").GetString() ?? "",
                e.GetProperty("type").GetString() ?? "",
                e.GetProperty("version").GetInt32(),
                DateTimeOffset.Parse(e.GetProperty("occurredAt").GetString()!, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal),
                e.GetProperty("payload").Clone());
            if (envelope.MessageId.Length == 0 || envelope.Type.Length == 0 || envelope.Version < 1)
            {
                throw new PoisonMessageException("envelope without messageId/type/version");
            }
            return envelope;
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            throw new PoisonMessageException("invalid envelope: " + e.Message, e);
        }
    }
}
