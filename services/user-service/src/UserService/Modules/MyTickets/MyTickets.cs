using System.Globalization;
using System.Text;
using Npgsql;
using TicketPro.Platform;
using UserService.App;

namespace UserService.Modules.MyTickets;

/// <summary>One ticket in GET /me/tickets.</summary>
public sealed record MyTicketView(string TicketId, string Code, string OrderId, string EventName, DateTimeOffset EventStartsAt, string SectorName);

/// <summary>A page of tickets; NextCursor is null on the last page.</summary>
public sealed record MyTicketsPage(IReadOnlyList<MyTicketView> Items, string? NextCursor);

/// <summary>TicketsIssued → one read-model row per ticket (TicketsIssued carries everything, RFC 0001 §5.6).</summary>
public sealed class TicketsIssuedHandler : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var p = envelope.Payload;
        foreach (var ticket in p.GetProperty("tickets").EnumerateArray())
        {
            await using var cmd = new NpgsqlCommand("""
                INSERT INTO my_tickets (ticket_id, ticket_code, user_id, order_id, event_id, event_name, event_starts_at, sector_name)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (ticket_id) DO NOTHING
                """, connection, transaction);
            cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(ticket.GetProperty("ticketId").GetString()!) });
            cmd.Parameters.Add(new NpgsqlParameter { Value = ticket.GetProperty("code").GetString()! });
            cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(p.GetProperty("userId").GetString()!) });
            cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(p.GetProperty("orderId").GetString()!) });
            cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(p.GetProperty("eventId").GetString()!) });
            cmd.Parameters.Add(new NpgsqlParameter { Value = p.GetProperty("eventName").GetString()! });
            cmd.Parameters.Add(new NpgsqlParameter { Value = DateTimeOffset.Parse(p.GetProperty("eventStartsAt").GetString()!, CultureInfo.InvariantCulture).UtcDateTime });
            cmd.Parameters.Add(new NpgsqlParameter { Value = p.GetProperty("sectorName").GetString()! });
            await cmd.ExecuteNonQueryAsync(ct);
        }
    }
}

/// <summary>
/// OrderConfirmed reaches this queue (filter policy, arquitetura §7) but the read model comes from TicketsIssued:
/// recorded as processed, no effect.
/// </summary>
public sealed class OrderConfirmedHandler : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct) => Task.CompletedTask;
}

/// <summary>Reads the caller's tickets through the API role (RLS), newest first, keyset cursor.</summary>
public sealed class MyTicketsQueries(NpgsqlDataSource app)
{
    public Task<MyTicketsPage> ListAsync(string userId, int limit, string? cursor, CancellationToken ct)
    {
        var after = cursor is null ? null : Decode(cursor);
        return UserScope.RunAsync(app, userId, async (conn, tx) =>
        {
            await using var cmd = new NpgsqlCommand(after is null
                ? """
                  SELECT ticket_id::text, ticket_code, order_id::text, event_name, event_starts_at, sector_name, issued_at
                  FROM my_tickets WHERE user_id = $1::uuid ORDER BY issued_at DESC, ticket_id DESC LIMIT $2
                  """
                : """
                  SELECT ticket_id::text, ticket_code, order_id::text, event_name, event_starts_at, sector_name, issued_at
                  FROM my_tickets WHERE user_id = $1::uuid AND (issued_at, ticket_id) < ($3, $4::uuid)
                  ORDER BY issued_at DESC, ticket_id DESC LIMIT $2
                  """, conn, tx);
            cmd.Parameters.Add(new NpgsqlParameter { Value = userId });
            cmd.Parameters.Add(new NpgsqlParameter { Value = limit + 1 });
            if (after is not null)
            {
                cmd.Parameters.Add(new NpgsqlParameter { Value = after.Value.IssuedAt.UtcDateTime });
                cmd.Parameters.Add(new NpgsqlParameter { Value = after.Value.TicketId });
            }
            var rows = new List<(MyTicketView View, DateTimeOffset IssuedAt)>();
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct))
            {
                rows.Add((new MyTicketView(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3),
                    r.GetFieldValue<DateTimeOffset>(4), r.GetString(5)), r.GetFieldValue<DateTimeOffset>(6)));
            }
            var items = rows.Take(limit).ToList();
            var next = rows.Count > limit ? Encode(items[^1].IssuedAt, items[^1].View.TicketId) : null;
            return new MyTicketsPage(items.Select(i => i.View).ToList(), next);
        }, ct);
    }

    private static string Encode(DateTimeOffset issuedAt, string ticketId) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes($"{issuedAt.UtcTicks}|{ticketId}"));

    /// <summary>Throws <see cref="FormatException"/> for a malformed cursor (→ 400).</summary>
    private static (DateTimeOffset IssuedAt, string TicketId)? Decode(string cursor)
    {
        var parts = Encoding.UTF8.GetString(Convert.FromBase64String(cursor)).Split('|');
        if (parts.Length != 2 || !long.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out var ticks) || !Guid.TryParse(parts[1], out _))
        {
            throw new FormatException("invalid cursor");
        }
        return (new DateTimeOffset(ticks, TimeSpan.Zero), parts[1]);
    }
}
