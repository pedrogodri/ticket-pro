using UserService.Domain.Tickets;

namespace UserService.Application.Tickets;

public sealed record IssuedTicket(Guid TicketId, string Code);

/// <summary>Data of a TicketsIssued event.</summary>
public sealed record ProjectTicketsIssuedCommand(
    Guid OrderId,
    Guid UserId,
    Guid EventId,
    string EventName,
    DateTime EventStartsAt,
    string SectorName,
    IReadOnlyList<IssuedTicket> Tickets,
    DateTime IssuedAt);

/// <summary>TicketsIssued → one row per ticket in my_tickets (CQRS). It does not depend on OrderConfirmed
/// having arrived first: each event fills its own table and reads join them, so the final state is the same
/// in any delivery order (R4). Inserting an existing ticket is a no-op, so redeliveries converge (R1).</summary>
public sealed class ProjectTicketsIssued
{
    /// <returns>How many tickets were new.</returns>
    public Task<int> ExecuteAsync(ProjectTicketsIssuedCommand command, ITicketProjectionStore store, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(command);
        ArgumentNullException.ThrowIfNull(store);
        var tickets = command.Tickets
            .Select(t => new MyTicket(t.TicketId, t.Code, command.UserId, command.OrderId, command.EventId,
                command.EventName, command.EventStartsAt, command.SectorName, command.IssuedAt))
            .ToList();
        return store.AddAsync(tickets, ct);
    }
}
