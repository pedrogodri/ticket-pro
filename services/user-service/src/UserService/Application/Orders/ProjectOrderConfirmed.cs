using UserService.Domain.Orders;

namespace UserService.Application.Orders;

/// <summary>Data of an OrderConfirmed event.</summary>
public sealed record ProjectOrderConfirmedCommand(
    Guid OrderId,
    Guid UserId,
    Guid EventId,
    Guid SectorId,
    int Quantity,
    long TotalCents,
    string Currency,
    DateTime ConfirmedAt);

/// <summary>OrderConfirmed → my_orders (CQRS). Same guarantees as <see cref="Tickets.ProjectTicketsIssued"/>:
/// idempotent and independent of the order in which the two events arrive.</summary>
public sealed class ProjectOrderConfirmed
{
    /// <returns>False when the order was already projected.</returns>
    public Task<bool> ExecuteAsync(ProjectOrderConfirmedCommand command, IOrderProjectionStore store, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(command);
        ArgumentNullException.ThrowIfNull(store);
        return store.AddAsync(new MyOrder(command.OrderId, command.UserId, command.EventId, command.SectorId,
            command.Quantity, command.TotalCents, command.Currency, command.ConfirmedAt), ct);
    }
}
