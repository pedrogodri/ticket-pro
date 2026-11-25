using Npgsql;
using NpgsqlTypes;
using UserService.Application.Orders;
using UserService.Application.Tickets;
using UserService.Domain.Orders;
using UserService.Domain.Tickets;

namespace UserService.Infrastructure.Persistence;

/// <summary>my_tickets and my_orders (CQRS read models), inside the consumer's transaction (worker role).
/// ON CONFLICT DO NOTHING is the second barrier of R1, behind processed_messages: a TicketsIssued or
/// OrderConfirmed republished with a new messageId still yields a single row.</summary>
public sealed class ProjectionStore(NpgsqlConnection connection, NpgsqlTransaction transaction)
    : ITicketProjectionStore, IOrderProjectionStore
{
    public async Task<int> AddAsync(IReadOnlyList<MyTicket> tickets, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(tickets);
        var inserted = 0;
        foreach (var t in tickets)
        {
            await using var cmd = new NpgsqlCommand("""
                INSERT INTO my_tickets (ticket_id, ticket_code, user_id, order_id, event_id, event_name, event_starts_at, sector_name, issued_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                ON CONFLICT DO NOTHING
                """, connection, transaction);
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.TicketId });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.Code });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.UserId });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.OrderId });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.EventId });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.EventName });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.EventStartsAt, NpgsqlDbType = NpgsqlDbType.TimestampTz });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.SectorName });
            cmd.Parameters.Add(new NpgsqlParameter { Value = t.IssuedAt, NpgsqlDbType = NpgsqlDbType.TimestampTz });
            inserted += await cmd.ExecuteNonQueryAsync(ct);
        }
        return inserted;
    }

    public async Task<bool> AddAsync(MyOrder order, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(order);
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO my_orders (order_id, user_id, event_id, sector_id, quantity, total_cents, currency, confirmed_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            ON CONFLICT (order_id) DO NOTHING
            """, connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.OrderId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.UserId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.EventId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.SectorId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.Quantity });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.TotalCents });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.Currency });
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.ConfirmedAt, NpgsqlDbType = NpgsqlDbType.TimestampTz });
        return await cmd.ExecuteNonQueryAsync(ct) == 1;
    }
}
