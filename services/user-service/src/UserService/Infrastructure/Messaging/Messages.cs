using System.Globalization;
using System.Text.Json;
using TicketPro.Platform;

namespace UserService.Infrastructure.Messaging;

/// <summary>Messages this service consumes and publishes (contracts/events/*/v1.schema.json). The schemas are
/// the source of truth; these records only mirror the payloads.</summary>
public static class Messages
{
    public const string UserRegistered = "UserRegistered";
    public const string OrderConfirmed = "OrderConfirmed";
    public const string TicketsIssued = "TicketsIssued";
    public const string UserCreationFailed = "UserCreationFailed";

    /// <summary>camelCase, as in the contracts.</summary>
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    /// <summary>Reads a payload already validated against its schema by the consumer.</summary>
    public static T Read<T>(Envelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        return envelope.Payload.Deserialize<T>(Json)
            ?? throw new InvalidMessageException($"{envelope.Type}: empty payload");
    }

    /// <summary>New v1 envelope (UUID v7 messageId, occurredAt = now) carrying the saga's correlationId.</summary>
    public static Envelope Create(string correlationId, string type, object payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        return Envelope.Create(correlationId, type, 1, JsonSerializer.SerializeToElement(payload, payload.GetType(), Json));
    }

    /// <summary>RFC 3339 UTC with milliseconds, the format of the contracts and of the HTTP API.</summary>
    public static string Timestamp(DateTime at) =>
        at.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);

    /// <summary>Parses a contract timestamp into a UTC DateTime (what Npgsql expects for timestamptz).</summary>
    public static DateTime ParseTimestamp(string value) =>
        DateTimeOffset.Parse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal).UtcDateTime;
}

public sealed record UserRegisteredV1(Guid UserId, string Email, string Name);

public sealed record OrderConfirmedV1(
    Guid OrderId, Guid UserId, Guid EventId, Guid SectorId, int Quantity, long TotalCents, string Currency, string ConfirmedAt);

public sealed record TicketsIssuedV1(
    Guid OrderId, Guid UserId, Guid EventId, string EventName, string EventStartsAt, Guid SectorId, string SectorName,
    IReadOnlyList<TicketV1> Tickets);

public sealed record TicketV1(Guid TicketId, string Code);

public sealed record UserCreationFailedV1(Guid UserId, string Reason);
