using Npgsql;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments.Consumers;

/// <summary>
/// Refunds an approved payment the order could no longer confirm (RFC 0003 §5.7, F7) and publishes
/// PaymentRefunded in the same transaction. refunds.payment_id UNIQUE makes a repeated request stale;
/// a payment this service does not know is stale too.
/// </summary>
public sealed class RefundRequestedHandler(string topicArn) : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var p = envelope.Payload;
        Guid orderId, paymentId;
        try
        {
            orderId = Guid.Parse(p.GetProperty("orderId").GetString()!);
            paymentId = Guid.Parse(p.GetProperty("paymentId").GetString()!);
        }
        catch (Exception e) when (e is KeyNotFoundException or FormatException or InvalidOperationException or ArgumentNullException)
        {
            throw new InvalidMessageException("RefundRequested payload: " + e.Message, e);
        }

        var refundId = Guid.CreateVersion7();
        // The refund returns what was captured: amount and currency come from the payment, not the message.
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO refunds (id, payment_id, amount_cents, currency)
            SELECT $1, id, amount_cents, currency FROM payments WHERE id = $2 AND status = 'APPROVED'
            ON CONFLICT (payment_id) DO NOTHING
            RETURNING amount_cents, currency
            """, connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = refundId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = paymentId });
        long amount;
        string currency;
        await using (var reader = await cmd.ExecuteReaderAsync(ct))
        {
            if (!await reader.ReadAsync(ct))
            {
                throw new StaleEventException("payment unknown, not approved, or already refunded");
            }
            amount = reader.GetInt64(0);
            currency = reader.GetString(1);
        }

        var refunded = Envelope.Create(envelope.CorrelationId, "PaymentRefunded", 1, new
        {
            orderId = orderId.ToString(),
            paymentId = paymentId.ToString(),
            refundId = refundId.ToString(),
            amountCents = amount,
            currency,
        });
        await OutboxWriter.EnqueueAsync(connection, transaction, topicArn, refunded, ct);
    }
}
