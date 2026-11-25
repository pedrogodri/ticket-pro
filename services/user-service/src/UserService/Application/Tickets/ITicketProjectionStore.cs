using UserService.Domain.Tickets;

namespace UserService.Application.Tickets;

/// <summary>Writes to the "my tickets" read model inside the consumer's transaction.</summary>
public interface ITicketProjectionStore
{
    /// <summary>Inserts the tickets not yet projected; returns how many were new.</summary>
    Task<int> AddAsync(IReadOnlyList<MyTicket> tickets, CancellationToken ct);
}
