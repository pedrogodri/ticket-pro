using UserService.Application.Paging;
using UserService.Domain.Orders;
using UserService.Domain.Tickets;

namespace UserService.Application.Queries;

public sealed record ProfileView(Guid UserId, string Email, string Name, DateTime CreatedAt);

/// <summary>A confirmed order and how many of its tickets were already projected (TicketsIssued may arrive later).</summary>
public sealed record OrderView(MyOrder Order, int TicketsIssued);

/// <summary>Reads of the HTTP API. Implementations run as the RLS-bound role with app.user_id set to
/// <c>userId</c>, so even a query missing its user filter only sees that user's rows (BOLA, RFC 0002 §5.6).</summary>
public interface IUserReadStore
{
    Task<ProfileView?> GetProfileAsync(Guid userId, CancellationToken ct);

    /// <summary>Newest first (issued_at DESC, ticket_id DESC), strictly after <paramref name="after"/>.</summary>
    Task<IReadOnlyList<MyTicket>> ListTicketsAsync(Guid userId, int take, PageCursor? after, CancellationToken ct);

    /// <summary>Newest first (confirmed_at DESC, order_id DESC), strictly after <paramref name="after"/>.</summary>
    Task<IReadOnlyList<OrderView>> ListOrdersAsync(Guid userId, int take, PageCursor? after, CancellationToken ct);
}
