using System.Collections.Concurrent;
using Npgsql;

namespace PaymentService.Modules.Payments.Provider;

/// <summary>Result of a charge: approved with a provider reference, or failed with a reason.</summary>
public sealed record ChargeResult(bool Approved, string? Reason, string? ProviderReference);

/// <summary>The payment provider behind the worker (RFC 0003 §5.6).</summary>
public interface IPaymentProvider
{
    Task<ChargeResult> ChargeAsync(Guid paymentId, long amountCents, CancellationToken ct);
}

/// <summary>PAYMENT_LATENCY_MS, PAYMENT_LATENCY_JITTER_MS and PAYMENT_FAILURE_RATE (R5).</summary>
public sealed record ProviderOptions(TimeSpan Latency, TimeSpan Jitter, double FailureRate);

/// <summary>Where the provider remembers each charge, so a second call returns the first result.</summary>
public interface IChargeStore
{
    Task<ChargeResult?> FindAsync(Guid paymentId, CancellationToken ct);

    /// <summary>Stores the result unless one exists; returns the stored (winning) result.</summary>
    Task<ChargeResult> SaveAsync(Guid paymentId, ChargeResult result, CancellationToken ct);
}

/// <summary>
/// Slow and unreliable provider simulated inside the service: waits latency ± jitter, declines with
/// probability FailureRate, and is idempotent per paymentId. Cancellation of <c>ct</c> is the hard timeout.
/// </summary>
public sealed class SimulatedPaymentProvider(
    ProviderOptions options,
    IChargeStore store,
    Func<double> random,
    Func<TimeSpan, CancellationToken, Task> wait) : IPaymentProvider
{
    public async Task<ChargeResult> ChargeAsync(Guid paymentId, long amountCents, CancellationToken ct)
    {
        if (await store.FindAsync(paymentId, ct) is { } existing)
        {
            return existing;
        }
        // latency ± jitter: random() in [0,1) maps to [latency - jitter, latency + jitter).
        var latency = options.Latency + (options.Jitter * ((random() * 2) - 1));
        await wait(latency < TimeSpan.Zero ? TimeSpan.Zero : latency, ct);
        var result = random() < options.FailureRate
            ? new ChargeResult(false, "DECLINED", null)
            : new ChargeResult(true, null, $"SIM-{DateTime.UtcNow:yyyyMMdd}-{paymentId.ToString("N")[..6].ToUpperInvariant()}");
        return await store.SaveAsync(paymentId, result, ct);
    }
}

/// <summary>In-memory charge store (tests).</summary>
public sealed class InMemoryChargeStore : IChargeStore
{
    private readonly ConcurrentDictionary<Guid, ChargeResult> _charges = new();

    public Task<ChargeResult?> FindAsync(Guid paymentId, CancellationToken ct) =>
        Task.FromResult(_charges.TryGetValue(paymentId, out var r) ? r : null);

    public Task<ChargeResult> SaveAsync(Guid paymentId, ChargeResult result, CancellationToken ct) =>
        Task.FromResult(_charges.GetOrAdd(paymentId, result));
}

/// <summary>provider_charges table: the provider's own memory of charges (one row per paymentId).</summary>
public sealed class PostgresChargeStore(NpgsqlDataSource dataSource) : IChargeStore
{
    public async Task<ChargeResult?> FindAsync(Guid paymentId, CancellationToken ct)
    {
        await using var cmd = dataSource.CreateCommand("SELECT result, reason, provider_reference FROM provider_charges WHERE payment_id = $1");
        cmd.Parameters.Add(new NpgsqlParameter { Value = paymentId });
        await using var r = await cmd.ExecuteReaderAsync(ct);
        return await r.ReadAsync(ct) ? Read(r) : null;
    }

    public async Task<ChargeResult> SaveAsync(Guid paymentId, ChargeResult result, CancellationToken ct)
    {
        await using var cmd = dataSource.CreateCommand("""
            INSERT INTO provider_charges (payment_id, result, reason, provider_reference) VALUES ($1, $2, $3, $4)
            ON CONFLICT (payment_id) DO NOTHING
            """);
        cmd.Parameters.Add(new NpgsqlParameter { Value = paymentId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = result.Approved ? "APPROVED" : "DECLINED" });
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)result.Reason ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)result.ProviderReference ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });
        await cmd.ExecuteNonQueryAsync(ct);
        return await FindAsync(paymentId, ct) ?? result;
    }

    private static ChargeResult Read(NpgsqlDataReader r) => new(
        string.Equals(r.GetString(0), "APPROVED", StringComparison.Ordinal),
        r.IsDBNull(1) ? null : r.GetString(1),
        r.IsDBNull(2) ? null : r.GetString(2));
}

