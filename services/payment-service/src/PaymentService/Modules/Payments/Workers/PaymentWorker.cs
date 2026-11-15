using Microsoft.Extensions.Logging;
using Npgsql;
using PaymentService.Modules.Payments.Provider;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments.Workers;

/// <summary>PAYMENT_WORKER_LEASE_MS, PAYMENT_PROVIDER_TIMEOUT_MS and the payments topic.</summary>
public sealed record WorkerOptions(TimeSpan Lease, TimeSpan ProviderTimeout, string TopicArn);

/// <summary>
/// Claims one PENDING payment with a lease (FOR UPDATE SKIP LOCKED), charges the provider with a hard
/// timeout and stores the outcome + PaymentApproved/PaymentFailed in one transaction. No charge retry:
/// a failure is compensated by the order (RFC 0003 §5.6, decision 3).
/// </summary>
public sealed partial class PaymentWorker(NpgsqlDataSource dataSource, IPaymentProvider provider, WorkerOptions options, ILogger<PaymentWorker> logger)
{
    private sealed record Claimed(Guid PaymentId, Guid OrderId, long Amount, string Currency, string CorrelationId);

    /// <summary>Processes at most one payment; false when there was nothing to claim.</summary>
    public async Task<bool> ProcessOnceAsync(CancellationToken ct)
    {
        var claimed = await ClaimAsync(ct);
        if (claimed is null)
        {
            return false;
        }

        ChargeResult result;
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            timeout.CancelAfter(options.ProviderTimeout);
            try
            {
                result = await provider.ChargeAsync(claimed.PaymentId, claimed.Amount, timeout.Token);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                result = new ChargeResult(false, "PROVIDER_TIMEOUT", null);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                LogProviderError(logger, e.Message);
                result = new ChargeResult(false, "PROVIDER_ERROR", null);
            }
        }
        await CompleteAsync(claimed, result, ct);
        return true;
    }

    private async Task<Claimed?> ClaimAsync(CancellationToken ct)
    {
        await using var cmd = dataSource.CreateCommand("""
            UPDATE payments SET lease_until = now() + ($1::bigint * interval '1 millisecond'), updated_at = now()
            WHERE id = (
                SELECT id FROM payments
                WHERE status = 'PENDING' AND (lease_until IS NULL OR lease_until < now())
                ORDER BY created_at LIMIT 1
                FOR UPDATE SKIP LOCKED)
            RETURNING id, order_id, amount_cents, currency
            """);
        cmd.Parameters.Add(new NpgsqlParameter { Value = (long)options.Lease.TotalMilliseconds });
        await using var r = await cmd.ExecuteReaderAsync(ct);
        return await r.ReadAsync(ct)
            ? new Claimed(r.GetGuid(0), r.GetGuid(1), r.GetInt64(2), r.GetString(3), Guid.NewGuid().ToString())
            : null;
    }

    private async Task CompleteAsync(Claimed c, ChargeResult result, CancellationToken ct)
    {
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        await using (var update = new NpgsqlCommand("""
            UPDATE payments SET status = $2, failure_reason = $3, provider_reference = $4, lease_until = NULL, updated_at = now()
            WHERE id = $1 AND status = 'PENDING'
            """, conn, tx))
        {
            update.Parameters.Add(new NpgsqlParameter { Value = c.PaymentId });
            update.Parameters.Add(new NpgsqlParameter { Value = result.Approved ? "APPROVED" : "FAILED" });
            update.Parameters.Add(new NpgsqlParameter { Value = (object?)result.Reason ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });
            update.Parameters.Add(new NpgsqlParameter { Value = (object?)result.ProviderReference ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });
            if (await update.ExecuteNonQueryAsync(ct) == 0)
            {
                return; // another worker finished it after our lease expired
            }
        }
        var envelope = result.Approved
            ? Envelope.Create(c.CorrelationId, "PaymentApproved", 1, new
            {
                orderId = c.OrderId.ToString(),
                paymentId = c.PaymentId.ToString(),
                amountCents = c.Amount,
                currency = c.Currency,
                providerReference = result.ProviderReference,
                approvedAt = DateTimeOffset.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ", System.Globalization.CultureInfo.InvariantCulture),
            })
            : Envelope.Create(c.CorrelationId, "PaymentFailed", 1, new
            {
                orderId = c.OrderId.ToString(),
                paymentId = c.PaymentId.ToString(),
                reason = result.Reason,
            });
        await OutboxWriter.EnqueueAsync(conn, tx, options.TopicArn, envelope, ct);
        await tx.CommitAsync(ct);
        LogCompleted(logger, c.OrderId, result.Approved ? "APPROVED" : "FAILED", result.Reason ?? "");
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "payment_completed orderId={OrderId} status={Status} reason={Reason}")]
    private static partial void LogCompleted(ILogger logger, Guid orderId, string status, string reason);

    [LoggerMessage(Level = LogLevel.Warning, Message = "payment_provider_error error={Error}")]
    private static partial void LogProviderError(ILogger logger, string error);
}
