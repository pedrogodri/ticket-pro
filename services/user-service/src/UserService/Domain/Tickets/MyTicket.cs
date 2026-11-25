namespace UserService.Domain.Tickets;

/// <summary>One issued ticket in the "my tickets" read model (CQRS): a snapshot of TicketsIssued taken at
/// issuance, with the event and sector names copied so that reads never call another service.</summary>
public sealed record MyTicket(
    Guid TicketId,
    string Code,
    Guid UserId,
    Guid OrderId,
    Guid EventId,
    string EventName,
    DateTime EventStartsAt,
    string SectorName,
    DateTime IssuedAt);
