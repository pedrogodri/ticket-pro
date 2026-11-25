using UserService.Application.Queries;
using UserService.Domain.Tickets;
using UserService.Infrastructure.Messaging;

namespace UserService.Http;

/// <summary>GET /me.</summary>
public sealed record ProfileResponse(Guid UserId, string Email, string Name, string CreatedAt)
{
    public static ProfileResponse From(ProfileView p)
    {
        ArgumentNullException.ThrowIfNull(p);
        return new(p.UserId, p.Email, p.Name, Messages.Timestamp(p.CreatedAt));
    }
}

/// <summary>Item of GET /me/tickets: only what identifies the ticket and where it is valid.</summary>
public sealed record TicketResponse(
    Guid TicketId, string Code, Guid OrderId, Guid EventId, string EventName, string EventStartsAt, string SectorName, string IssuedAt)
{
    public static TicketResponse From(MyTicket t)
    {
        ArgumentNullException.ThrowIfNull(t);
        return new(t.TicketId, t.Code, t.OrderId, t.EventId, t.EventName, Messages.Timestamp(t.EventStartsAt), t.SectorName,
            Messages.Timestamp(t.IssuedAt));
    }
}

/// <summary>Item of GET /me/orders: a confirmed purchase. ticketsIssued &lt; quantity means the tickets are
/// still on their way (TicketsIssued not projected yet).</summary>
public sealed record OrderResponse(
    Guid OrderId, Guid EventId, Guid SectorId, int Quantity, long TotalCents, string Currency, string ConfirmedAt, int TicketsIssued)
{
    public static OrderResponse From(OrderView v)
    {
        ArgumentNullException.ThrowIfNull(v);
        var o = v.Order;
        return new(o.OrderId, o.EventId, o.SectorId, o.Quantity, o.TotalCents, o.Currency, Messages.Timestamp(o.ConfirmedAt), v.TicketsIssued);
    }
}

/// <summary>Cursor pagination (docs/padroes.md §4): nextCursor is null on the last page.</summary>
public sealed record PageResponse<T>(IReadOnlyList<T> Items, string? NextCursor);
