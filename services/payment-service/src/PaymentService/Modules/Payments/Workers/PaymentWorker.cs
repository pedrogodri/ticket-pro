using Npgsql;
using PaymentService.App;
using PaymentService.Modules.Payments.Provider;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments.Workers;

/// <summary>Charges PENDING payments (RFC 0003 §5.6):
/// <list type="number">
/// <item>claim a batch with a lease (FOR UPDATE SKIP LOCKED: replicas never take the same row);</item>
/// <item>call the provider OUTSIDE any transaction, with a hard timeout and no retry;</item>
/// <item>in one transaction, move PENDING → APPROVED|FAILED and enqueue PaymentApproved|PaymentFailed.</item>
/// </list>
/// A worker that dies mid-charge leaves only the lease; once it expires another worker retries, and the
/// provider's idempotency by paymentId guarantees a single charge.</summary>
public sealed partial class PaymentWorker(
    NpgsqlDataSource db, IPaymentProvider provider, AppConfig config, ILogger<PaymentWorker> logger)
    : BackgroundService
{
    internal sealed record ClaimedPayment(Guid Id, Guid OrderId, long AmountCents, string Currency, string CorrelationId);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var failures = 0;
        while (!stoppingToken.IsCancellationRequested)
        {
            int processed;
            try
            {
                processed = await RunOnceAsync(stoppingToken);
                failures = 0;
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e) when (e is NpgsqlException or TimeoutException or InvalidOperationException)
            {
                // Database trouble: nothing was committed, leases expire on their own. Back off (RFC 0003 §5.2).
                LogLoopFailed(logger, e.Message);
                await DelayAsync(Backoff.Delay(failures++, Backoff.DefaultBase, Backoff.DefaultCap, Random.Shared.NextDouble), stoppingToken);
                continue;
            }
            if (processed == 0)
            {
                await DelayAsync(config.PaymentWorkerPoll, stoppingToken);
            }
        }
    }

    /// <summary>One claim-and-charge round; returns how many payments were claimed.</summary>
    public async Task<int> RunOnceAsync(CancellationToken ct)
    {
        var claimed = await ClaimAsync(ct);
        await Task.WhenAll(claimed.Select(p => ChargeAsync(p, ct)));
        return claimed.Count;
    }

    private async Task<List<ClaimedPayment>> ClaimAsync(CancellationToken ct)
    {
        // A single statement is its own short transaction: the row locks live only while the lease is
        // written, so the slow provider call never holds a lock or a connection.
        await using var cmd = db.CreateCommand("""
            UPDATE payments p
               SET lease_until = now() + ($1 * interval '1 millisecond'), updated_at = now()
             WHERE p.id IN (SELECT id FROM payments
                             WHERE status = 'PENDING' AND (lease_until IS NULL OR lease_until < now())
                             ORDER BY created_at
                             LIMIT $2
                             FOR UPDATE SKIP LOCKED)
            RETURNING p.id, p.order_id, p.amount_cents, p.currency, p.correlation_id
            """);
        cmd.Parameters.Add(new NpgsqlParameter { Value = (long)config.PaymentWorkerLease.TotalMilliseconds });
        // Batch = CONSUMER_CONCURRENCY on purpose: it is the "how much work in parallel per replica" knob
        // of RFC 0003 §5.1, and the claimed payments are charged in parallel below.
        cmd.Parameters.Add(new NpgsqlParameter { Value = config.ConsumerConcurrency });
        var claimed = new List<ClaimedPayment>();
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        while (await reader.ReadAsync(ct))
        {
            // correlation_id is NULL only for rows written before V0006; start a new correlation then.
            var correlationId = reader.IsDBNull(4) ? Guid.CreateVersion7() : reader.GetGuid(4);
            claimed.Add(new ClaimedPayment(reader.GetGuid(0), reader.GetGuid(1), reader.GetInt64(2), reader.GetString(3), correlationId.ToString()));
        }
        return claimed;
    }

    private async Task ChargeAsync(ClaimedPayment payment, CancellationToken stoppingToken)
    {
        using var scope = logger.BeginScope(new Dictionary<string, object?>
        {
            ["correlationId"] = payment.CorrelationId,
            ["paymentId"] = payment.Id,
            ["orderId"] = payment.OrderId,
        });

        ChargeResult? result = null;
        string? failure = null;
        using (var hardTimeout = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken))
        {
            hardTimeout.CancelAfter(config.PaymentProviderTimeout);
            try
            {
                result = await provider.ChargeAsync(payment.Id, payment.AmountCents, payment.Currency, hardTimeout.Token);
            }
            catch (OperationCanceledException) when (!stoppingToken.IsCancellationRequested)
            {
                failure = FailureReasons.ProviderTimeout; // the hard timeout fired, not a shutdown
            }
            catch (OperationCanceledException)
            {
                // Shutdown mid-charge (e.g. scripts recreating the replicas): give the payment back now
                // instead of leaving it stuck until the lease expires. Nothing was recorded as charged.
                await ReleaseLeaseAsync(payment.Id);
                throw;
            }
            catch (Exception e) when (e is not OperationCanceledException and not NpgsqlException)
            {
                LogProviderError(logger, e.Message);
                failure = FailureReasons.ProviderError;
            }
            // Database errors propagate: nothing is written, the lease expires and another worker takes
            // the payment (the provider replays a charge it already recorded).
        }

        if (result is { Approved: false })
        {
            failure = FailureReasons.Declined;
        }
        // The charge has an outcome: persist it even if a shutdown started meanwhile.
        await FinishAsync(payment, result, failure, CancellationToken.None);
    }

    private async Task ReleaseLeaseAsync(Guid paymentId)
    {
        try
        {
            await using var cmd = db.CreateCommand("UPDATE payments SET lease_until = NULL WHERE id = $1 AND status = 'PENDING'");
            cmd.Parameters.Add(new NpgsqlParameter { Value = paymentId });
            await cmd.ExecuteNonQueryAsync(CancellationToken.None);
        }
        catch (NpgsqlException)
        {
            // best effort: the lease still expires on its own
        }
    }

    private async Task FinishAsync(ClaimedPayment payment, ChargeResult? result, string? failure, CancellationToken ct)
    {
        var approved = failure is null && result is { Approved: true };
        await using var connection = await db.OpenConnectionAsync(ct);
        await using var transaction = await connection.BeginTransactionAsync(ct);

        // Guarded transition: only PENDING moves. If another worker already finished this payment
        // (its lease expired while we were slow), we publish nothing and the single event stays single.
        await using (var update = new NpgsqlCommand("""
            UPDATE payments
               SET status = $2, failure_reason = $3, provider_reference = $4, lease_until = NULL, updated_at = now()
             WHERE id = $1 AND status = 'PENDING'
            """, connection, transaction))
        {
            update.Parameters.Add(new NpgsqlParameter { Value = payment.Id });
            update.Parameters.Add(new NpgsqlParameter { Value = approved ? "APPROVED" : "FAILED" });
            update.Parameters.Add(new NpgsqlParameter { Value = (object?)failure ?? DBNull.Value });
            update.Parameters.Add(new NpgsqlParameter { Value = (object?)result?.ProviderReference ?? DBNull.Value });
            if (await update.ExecuteNonQueryAsync(ct) == 0)
            {
                await transaction.RollbackAsync(ct);
                LogAlreadyFinished(logger);
                return;
            }
        }

        var envelope = approved
            ? PaymentEvents.Create(payment.CorrelationId, PaymentEvents.PaymentApproved, new PaymentApprovedV1(
                payment.OrderId, payment.Id, payment.AmountCents, payment.Currency, result!.ProviderReference!, PaymentEvents.Timestamp(result.At)))
            : PaymentEvents.Create(payment.CorrelationId, PaymentEvents.PaymentFailed, new PaymentFailedV1(
                payment.OrderId, payment.Id, failure!));
        await OutboxWriter.EnqueueAsync(connection, transaction, config.PaymentsTopicArn, envelope, ct);
        await transaction.CommitAsync(ct);

        LogCompleted(logger, payment.OrderId, approved ? "APPROVED" : "FAILED", failure, result?.ProviderReference);
    }

    private static async Task DelayAsync(TimeSpan delay, CancellationToken ct)
    {
        try
        {
            await Task.Delay(delay, ct);
        }
        catch (OperationCanceledException)
        {
            // shutting down
        }
    }

    // RFC 0009 §5.8: one stable line per finished payment.
    [LoggerMessage(Level = LogLevel.Information, Message = "payment_completed {OrderId} {Status} {Reason} {ProviderReference}")]
    private static partial void LogCompleted(ILogger logger, Guid orderId, string status, string? reason, string? providerReference);

    [LoggerMessage(Level = LogLevel.Warning, Message = "payment_provider_error {Error}")]
    private static partial void LogProviderError(ILogger logger, string error);

    [LoggerMessage(Level = LogLevel.Information, Message = "payment_already_finished")]
    private static partial void LogAlreadyFinished(ILogger logger);

    [LoggerMessage(Level = LogLevel.Warning, Message = "payment_worker_error {Error}")]
    private static partial void LogLoopFailed(ILogger logger, string error);
}
