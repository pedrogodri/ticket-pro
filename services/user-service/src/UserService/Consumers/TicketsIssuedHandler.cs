using Npgsql;
using TicketPro.Platform;
using UserService.Application.Tickets;
using UserService.Infrastructure.Messaging;
using UserService.Infrastructure.Persistence;

namespace UserService.Consumers;

/// <summary>TicketsIssued → my_tickets (CQRS). TicketsIssued has two consumers: this read model and the
/// notification-service receipt in S3. issuedAt is the event's occurredAt (the payload has no timestamp).</summary>
public sealed partial class TicketsIssuedHandler(ProjectTicketsIssued projectTicketsIssued, ILogger<TicketsIssuedHandler> logger)
    : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var m = Messages.Read<TicketsIssuedV1>(envelope);
        var added = await projectTicketsIssued.ExecuteAsync(
            new ProjectTicketsIssuedCommand(m.OrderId, m.UserId, m.EventId, m.EventName, Messages.ParseTimestamp(m.EventStartsAt),
                m.SectorName, m.Tickets.Select(t => new IssuedTicket(t.TicketId, t.Code)).ToList(), envelope.OccurredAt.UtcDateTime),
            new ProjectionStore(connection, transaction),
            ct);
        LogProjected(logger, m.OrderId, m.UserId, m.Tickets.Count, added);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "tickets_projected orderId={OrderId} userId={UserId} tickets={Tickets} new={New}")]
    private static partial void LogProjected(ILogger logger, Guid orderId, Guid userId, int tickets, int @new);
}
