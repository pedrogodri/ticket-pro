using Npgsql;
using TicketPro.Platform;
using UserService.Application.Orders;
using UserService.Infrastructure.Messaging;
using UserService.Infrastructure.Persistence;

namespace UserService.Consumers;

/// <summary>OrderConfirmed → my_orders (CQRS). OrderConfirmed has two consumers: inventory issues the tickets,
/// this service records the purchase.</summary>
public sealed partial class OrderConfirmedHandler(ProjectOrderConfirmed projectOrderConfirmed, ILogger<OrderConfirmedHandler> logger)
    : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var m = Messages.Read<OrderConfirmedV1>(envelope);
        var added = await projectOrderConfirmed.ExecuteAsync(
            new ProjectOrderConfirmedCommand(m.OrderId, m.UserId, m.EventId, m.SectorId, m.Quantity, m.TotalCents, m.Currency,
                Messages.ParseTimestamp(m.ConfirmedAt)),
            new ProjectionStore(connection, transaction),
            ct);
        LogProjected(logger, m.OrderId, m.UserId, added);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "order_projected orderId={OrderId} userId={UserId} new={New}")]
    private static partial void LogProjected(ILogger logger, Guid orderId, Guid userId, bool @new);
}
