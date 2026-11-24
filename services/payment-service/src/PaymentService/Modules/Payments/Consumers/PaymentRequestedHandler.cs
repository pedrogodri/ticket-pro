using Npgsql;
using TicketPro.Platform;

namespace PaymentService.Modules.Payments.Consumers;

/// <summary>PaymentRequested → payments(PENDING) inside the consumer's transaction, then the consumer
/// acknowledges (RFC 0003 §5.6). The charge is NOT made here: the slow provider would hold the message
/// past its visibility timeout. <see cref="Workers.PaymentWorker"/> charges later.</summary>
public sealed partial class PaymentRequestedHandler(ILogger<PaymentRequestedHandler> logger) : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var request = PaymentEvents.Read<PaymentRequestedV1>(envelope);
        // The contract allows 0, the table does not (CHECK amount_cents > 0). Without this, the CHECK
        // violation would look transient and be retried until the DLQ; poison sends it there directly.
        if (request.AmountCents <= 0)
        {
            throw new InvalidMessageException($"PaymentRequested for order {request.OrderId} has amountCents {request.AmountCents}; nothing to charge");
        }

        // order_id UNIQUE: a second PaymentRequested for the same order (new messageId, e.g. the order
        // service retried) must not create a second charge. processed_messages only catches same messageId.
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO payments (id, order_id, amount_cents, currency, status, correlation_id, traceparent)
            VALUES ($1, $2, $3, $4, 'PENDING', $5, $6)
            ON CONFLICT (order_id) DO NOTHING
            """, connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.CreateVersion7() });
        cmd.Parameters.Add(new NpgsqlParameter { Value = request.OrderId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = request.AmountCents });
        cmd.Parameters.Add(new NpgsqlParameter { Value = request.Currency });
        cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(envelope.CorrelationId) });
        // The consumer span (ADR-0012): the worker continues this trace when it charges.
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)TraceContext.Inject() ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });

        if (await cmd.ExecuteNonQueryAsync(ct) == 1)
        {
            LogAccepted(logger, request.OrderId);
        }
        else
        {
            LogAlreadyExists(logger, request.OrderId);
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "payment_accepted {OrderId}")]
    private static partial void LogAccepted(ILogger logger, Guid orderId);

    [LoggerMessage(Level = LogLevel.Information, Message = "payment_already_exists {OrderId}")]
    private static partial void LogAlreadyExists(ILogger logger, Guid orderId);
}
