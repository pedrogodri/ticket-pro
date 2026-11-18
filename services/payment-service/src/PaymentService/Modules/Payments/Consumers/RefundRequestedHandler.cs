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
        long amount;
        string currency;
        try
        {
            orderId = Guid.Parse(p.GetProperty("orderId").GetString()!);
            paymentId = Guid.Parse(p.GetProperty("paymentId").GetString()!);
            amount = p.GetProperty("amountCents").GetInt64();
            currency = p.GetProperty("currency").GetString()!;
        }
        catch (Exception e) when (e is KeyNotFoundException or FormatException or InvalidOperationException or ArgumentNullException)
        {
            throw new InvalidMessageException("RefundRequested payload: " + e.Message, e);
        }

        var refundId = Guid.CreateVersion7();
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO refunds (id, payment_id, amount_cents, currency)
            SELECT $1, id, $3, $4 FROM payments WHERE id = $2 AND status = 'APPROVED'
            ON CONFLICT (payment_id) DO NOTHING
            """, connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = refundId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = paymentId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = amount });
        cmd.Parameters.Add(new NpgsqlParameter { Value = currency });
        if (await cmd.ExecuteNonQueryAsync(ct) == 0)
        {
            throw new StaleEventException("payment unknown, not approved, or already refunded");
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
