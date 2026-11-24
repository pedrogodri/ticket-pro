namespace PaymentService.Modules.Payments.Provider;

/// <summary>Result of one charge. Approved carries the provider's reference; declined carries DECLINED.</summary>
public sealed record ChargeResult(bool Approved, string? ProviderReference, DateTimeOffset At)
{
    public static ChargeResult Approve(string reference, DateTimeOffset at) => new(true, reference, at);

    public static ChargeResult Decline(DateTimeOffset at) => new(false, null, at);
}

/// <summary>The external payment provider (RFC 0003 §5.6). Contract: idempotent by paymentId — calling
/// twice for the same payment returns the first result and never charges twice. The caller enforces
/// the hard timeout by cancelling <paramref name="ct"/>.</summary>
public interface IPaymentProvider
{
    Task<ChargeResult> ChargeAsync(Guid paymentId, long amountCents, string currency, CancellationToken ct);
}
