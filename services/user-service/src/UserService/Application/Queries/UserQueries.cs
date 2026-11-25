using UserService.Application.Paging;
using UserService.Domain.Tickets;

namespace UserService.Application.Queries;

/// <summary>Use cases behind GET /me, /me/tickets and /me/orders. The user always comes from the
/// gateway-injected X-User-Id; there is no route that takes a userId (RFC 0002 §5.6).</summary>
public sealed class UserQueries(IUserReadStore store)
{
    /// <summary>Null while UserRegistered has not been projected yet (eventual consistency, arquitetura §5).</summary>
    public Task<ProfileView?> GetProfileAsync(Guid userId, CancellationToken ct) => store.GetProfileAsync(userId, ct);

    public async Task<Page<MyTicket>> ListTicketsAsync(Guid userId, PageRequest page, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(page);
        var rows = await store.ListTicketsAsync(userId, page.Limit + 1, page.After, ct);
        return ToPage(rows, page.Limit, t => new PageCursor(t.IssuedAt, t.TicketId));
    }

    public async Task<Page<OrderView>> ListOrdersAsync(Guid userId, PageRequest page, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(page);
        var rows = await store.ListOrdersAsync(userId, page.Limit + 1, page.After, ct);
        return ToPage(rows, page.Limit, o => new PageCursor(o.Order.ConfirmedAt, o.Order.OrderId));
    }

    // The store returns one row more than the limit: its presence means there is a next page.
    private static Page<T> ToPage<T>(IReadOnlyList<T> rows, int limit, Func<T, PageCursor> cursorOf)
    {
        if (rows.Count <= limit)
        {
            return new Page<T>(rows, null);
        }
        var items = rows.Take(limit).ToList();
        return new Page<T>(items, cursorOf(items[^1]).Encode());
    }
}
