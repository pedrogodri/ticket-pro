using Npgsql;

namespace PaymentService.Modules.Payments.Provider;

/// <summary>Knobs of the simulated provider (R5): PAYMENT_LATENCY_MS, PAYMENT_LATENCY_JITTER_MS, PAYMENT_FAILURE_RATE.</summary>
public sealed record SimulationOptions(TimeSpan Latency, TimeSpan Jitter, double FailureRate)
{
    /// <summary>Latency ± jitter, never negative. <paramref name="random"/> returns a value in [0, 1).</summary>
    public TimeSpan NextDelay(Func<double> random)
    {
        ArgumentNullException.ThrowIfNull(random);
        var offset = (random() * 2 - 1) * Jitter.TotalMilliseconds;
        return TimeSpan.FromMilliseconds(Math.Max(0, Latency.TotalMilliseconds + offset));
    }

    /// <summary>True with probability 1 - FailureRate: rate 0 always approves, rate 1 always declines.</summary>
    public bool NextApproved(Func<double> random)
    {
        ArgumentNullException.ThrowIfNull(random);
        return random() >= FailureRate;
    }
}

/// <summary>In-process stand-in for a slow, unreliable provider (RFC 0003 §5.6). It keeps its own memory
/// of charges in provider_charges (as a real provider would on its side), which makes it idempotent by
/// paymentId: a retried call — e.g. after a worker died mid-charge — returns the recorded result.</summary>
public sealed partial class SimulatedPaymentProvider(
    NpgsqlDataSource db, SimulationOptions options, TimeProvider time, Func<double> random, ILogger<SimulatedPaymentProvider> logger)
    : IPaymentProvider
{
    public async Task<ChargeResult> ChargeAsync(Guid paymentId, long amountCents, string currency, CancellationToken ct)
    {
        // Not cut by the hard timeout: a slow lookup of an already recorded APPROVED charge must not
        // turn into FAILED(PROVIDER_TIMEOUT) for money that was actually charged.
        if (await FindAsync(paymentId, CancellationToken.None) is { } previous)
        {
            LogReplayed(logger, paymentId);
            return previous;
        }

        // The slow part. Cancelling ct (the caller's hard timeout) aborts here, before anything is
        // recorded: a timed-out charge never happened, so it cannot be approved later.
        await Task.Delay(options.NextDelay(random), time, ct);

        var now = time.GetUtcNow();
        var approved = options.NextApproved(random);
        var reference = approved ? $"SIM-{now:yyyyMMdd}-{paymentId.ToString("N")[^12..].ToUpperInvariant()}" : null;

        // ON CONFLICT: two concurrent calls for the same payment converge on the first recorded result.
        await using (var insert = db.CreateCommand("""
            INSERT INTO provider_charges (payment_id, result, reason, provider_reference, created_at)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (payment_id) DO NOTHING
            """))
        {
            insert.Parameters.Add(new NpgsqlParameter { Value = paymentId });
            insert.Parameters.Add(new NpgsqlParameter { Value = approved ? "APPROVED" : "DECLINED" });
            insert.Parameters.Add(new NpgsqlParameter { Value = approved ? DBNull.Value : FailureReasons.Declined });
            insert.Parameters.Add(new NpgsqlParameter { Value = (object?)reference ?? DBNull.Value });
            insert.Parameters.Add(new NpgsqlParameter { Value = now.UtcDateTime });
            await insert.ExecuteNonQueryAsync(CancellationToken.None);
        }
        return await FindAsync(paymentId, CancellationToken.None)
            ?? throw new InvalidOperationException($"provider charge for {paymentId} vanished");
    }

    private async Task<ChargeResult?> FindAsync(Guid paymentId, CancellationToken ct)
    {
        await using var cmd = db.CreateCommand(
            "SELECT result, provider_reference, created_at FROM provider_charges WHERE payment_id = $1");
        cmd.Parameters.Add(new NpgsqlParameter { Value = paymentId });
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        if (!await reader.ReadAsync(ct))
        {
            return null;
        }
        var at = new DateTimeOffset(reader.GetDateTime(2), TimeSpan.Zero);
        return reader.GetString(0) == "APPROVED"
            ? ChargeResult.Approve(reader.GetString(1), at)
            : ChargeResult.Decline(at);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "provider_charge_replayed {PaymentId}")]
    private static partial void LogReplayed(ILogger logger, Guid paymentId);
}
