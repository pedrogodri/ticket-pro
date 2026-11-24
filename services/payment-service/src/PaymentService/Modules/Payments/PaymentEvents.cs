using System.Globalization;
using System.Text.Json;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments;

/// <summary>Messages this service consumes and publishes (contracts/events/*/v1.schema.json).
/// The schemas are the source of truth; these records only mirror the payloads.</summary>
public static class PaymentEvents
{
    public const string PaymentRequested = "PaymentRequested";
    public const string RefundRequested = "RefundRequested";
    public const string PaymentApproved = "PaymentApproved";
    public const string PaymentFailed = "PaymentFailed";
    public const string PaymentRefunded = "PaymentRefunded";

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
    public static Envelope Create(string correlationId, string type, object payload) =>
        Envelope.Create(correlationId, type, 1, JsonSerializer.SerializeToElement(payload, payload.GetType(), Json));

    /// <summary>RFC 3339 UTC with milliseconds, the format of the contract examples.</summary>
    public static string Timestamp(DateTimeOffset at) =>
        at.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
}

public sealed record PaymentRequestedV1(Guid OrderId, Guid UserId, long AmountCents, string Currency);

public sealed record RefundRequestedV1(Guid OrderId, Guid PaymentId, long AmountCents, string Currency, string Reason);

public sealed record PaymentApprovedV1(Guid OrderId, Guid PaymentId, long AmountCents, string Currency, string ProviderReference, string ApprovedAt);

public sealed record PaymentFailedV1(Guid OrderId, Guid PaymentId, string Reason);

public sealed record PaymentRefundedV1(Guid OrderId, Guid PaymentId, Guid RefundId, long AmountCents, string Currency);

/// <summary>Values of payments.failure_reason and of PaymentFailed.reason.</summary>
public static class FailureReasons
{
    public const string Declined = "DECLINED";
    public const string ProviderError = "PROVIDER_ERROR";
    public const string ProviderTimeout = "PROVIDER_TIMEOUT";
}
