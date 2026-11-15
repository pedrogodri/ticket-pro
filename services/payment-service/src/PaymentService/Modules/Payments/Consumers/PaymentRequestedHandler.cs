using Npgsql;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments.Consumers;

/// <summary>
/// Stores the payment intent (PENDING) and returns, so the message is acked fast; the slow provider call
/// happens in the worker (ADR-0006, RFC 0003 §5.6). A second request for the same order is stale.
/// </summary>
public sealed class PaymentRequestedHandler : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var p = envelope.Payload;
        Guid orderId;
        long amount;
        string currency;
        try
        {
            orderId = Guid.Parse(p.GetProperty("orderId").GetString()!);
            amount = p.GetProperty("amountCents").GetInt64();
            currency = p.GetProperty("currency").GetString()!;
        }
        catch (Exception e) when (e is KeyNotFoundException or FormatException or InvalidOperationException or ArgumentNullException)
        {
            throw new InvalidMessageException("PaymentRequested payload: " + e.Message, e);
        }
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO payments (id, order_id, amount_cents, currency, status) VALUES ($1, $2, $3, $4, 'PENDING')
            ON CONFLICT (order_id) DO NOTHING
            """, connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.CreateVersion7() });
        cmd.Parameters.Add(new NpgsqlParameter { Value = orderId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = amount });
        cmd.Parameters.Add(new NpgsqlParameter { Value = currency });
        if (await cmd.ExecuteNonQueryAsync(ct) == 0)
        {
            throw new StaleEventException("payment already requested for this order");
        }
    }
}
