using Npgsql;
using PaymentService.App;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments.Consumers;

/// <summary>RefundRequested → refunds(payment_id UNIQUE) + PaymentRefunded in the same transaction
/// (RFC 0003 §5.6–5.7, RFC 0009 §5.3, F7). The simulated refund never fails: instability is the
/// charge's job only.</summary>
public sealed partial class RefundRequestedHandler(AppConfig config, ILogger<RefundRequestedHandler> logger) : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var request = PaymentEvents.Read<RefundRequestedV1>(envelope);

        // Every check happens before any write, because the consumer commits the transaction when the
        // handler throws StaleEventException (it only records the message as processed).
        // FOR UPDATE: two RefundRequested for the same payment, on two replicas, are serialized here.
        long amountCents;
        string currency;
        await using (var check = new NpgsqlCommand("""
            SELECT p.status, p.amount_cents, p.currency, EXISTS (SELECT 1 FROM refunds r WHERE r.payment_id = p.id)
              FROM payments p
             WHERE p.id = $1 AND p.order_id = $2
               FOR UPDATE
            """, connection, transaction))
        {
            check.Parameters.Add(new NpgsqlParameter { Value = request.PaymentId });
            check.Parameters.Add(new NpgsqlParameter { Value = request.OrderId });
            await using var reader = await check.ExecuteReaderAsync(ct);
            if (!await reader.ReadAsync(ct))
            {
                throw new StaleEventException($"payment {request.PaymentId} of order {request.OrderId} is unknown");
            }
            var status = reader.GetString(0);
            if (status != "APPROVED")
            {
                throw new StaleEventException($"payment {request.PaymentId} is {status}, not APPROVED");
            }
            if (reader.GetBoolean(3))
            {
                // RFC 0009 §5.7: a repeated RefundRequested (new messageId) is stale; the first one
                // already produced the single refund and the single PaymentRefunded.
                throw new StaleEventException($"payment {request.PaymentId} was already refunded");
            }
            // RFC 0009 §5.6 invariant 3: refund what was captured, not what the message says.
            (amountCents, currency) = (reader.GetInt64(1), reader.GetString(2));
        }

        var refundId = Guid.CreateVersion7();
        await using (var insert = new NpgsqlCommand(
            "INSERT INTO refunds (id, payment_id, amount_cents, currency) VALUES ($1, $2, $3, $4)", connection, transaction))
        {
            insert.Parameters.Add(new NpgsqlParameter { Value = refundId });
            insert.Parameters.Add(new NpgsqlParameter { Value = request.PaymentId });
            insert.Parameters.Add(new NpgsqlParameter { Value = amountCents });
            insert.Parameters.Add(new NpgsqlParameter { Value = currency });
            await insert.ExecuteNonQueryAsync(ct);
        }

        var refunded = new PaymentRefundedV1(request.OrderId, request.PaymentId, refundId, amountCents, currency);
        await OutboxWriter.EnqueueAsync(connection, transaction, config.PaymentsTopicArn,
            PaymentEvents.Create(envelope.CorrelationId, PaymentEvents.PaymentRefunded, refunded), ct);
        LogRefunded(logger, request.OrderId, request.PaymentId, refundId, amountCents);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "late_payment_refund {OrderId} {PaymentId} {RefundId} {AmountCents}")]
    private static partial void LogRefunded(ILogger logger, Guid orderId, Guid paymentId, Guid refundId, long amountCents);
}
