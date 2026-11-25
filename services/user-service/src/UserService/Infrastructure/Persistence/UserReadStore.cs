using Npgsql;
using NpgsqlTypes;
using UserService.Application.Paging;
using UserService.Application.Queries;
using UserService.Domain.Orders;
using UserService.Domain.Tickets;

namespace UserService.Infrastructure.Persistence;

/// <summary>Reads of the HTTP API as users_app (DATABASE_URL), the role subject to RLS (docs/padroes.md §6.1).
/// Every read runs in a transaction that starts with SET LOCAL app.user_id = X-User-Id; the explicit
/// "WHERE user_id" in each query is kept too, but RLS would filter even without it (BOLA defense in depth).</summary>
public sealed class UserReadStore(NpgsqlDataSource appDb) : IUserReadStore
{
    public Task<ProfileView?> GetProfileAsync(Guid userId, CancellationToken ct) =>
        AsUserAsync(userId, async (connection, transaction) =>
        {
            await using var cmd = new NpgsqlCommand(
                "SELECT user_id, email, name, created_at FROM profiles WHERE user_id = $1", connection, transaction);
            cmd.Parameters.Add(new NpgsqlParameter { Value = userId });
            await using var reader = await cmd.ExecuteReaderAsync(ct);
            return await reader.ReadAsync(ct)
                ? new ProfileView(reader.GetGuid(0), reader.GetString(1), reader.GetString(2), reader.GetDateTime(3))
                : null;
        }, ct);

    public Task<IReadOnlyList<MyTicket>> ListTicketsAsync(Guid userId, int take, PageCursor? after, CancellationToken ct) =>
        AsUserAsync<IReadOnlyList<MyTicket>>(userId, async (connection, transaction) =>
        {
            await using var cmd = new NpgsqlCommand("""
                SELECT ticket_id, ticket_code, user_id, order_id, event_id, event_name, event_starts_at, sector_name, issued_at
                  FROM my_tickets
                 WHERE user_id = $1
                   AND ($2::timestamptz IS NULL OR (issued_at, ticket_id) < ($2, $3))
                 ORDER BY issued_at DESC, ticket_id DESC
                 LIMIT $4
                """, connection, transaction);
            AddPageParameters(cmd, userId, take, after);
            await using var reader = await cmd.ExecuteReaderAsync(ct);
            var rows = new List<MyTicket>();
            while (await reader.ReadAsync(ct))
            {
                rows.Add(new MyTicket(reader.GetGuid(0), reader.GetString(1), reader.GetGuid(2), reader.GetGuid(3), reader.GetGuid(4),
                    reader.GetString(5), reader.GetDateTime(6), reader.GetString(7), reader.GetDateTime(8)));
            }
            return rows;
        }, ct);

    public Task<IReadOnlyList<OrderView>> ListOrdersAsync(Guid userId, int take, PageCursor? after, CancellationToken ct) =>
        AsUserAsync<IReadOnlyList<OrderView>>(userId, async (connection, transaction) =>
        {
            await using var cmd = new NpgsqlCommand("""
                SELECT o.order_id, o.user_id, o.event_id, o.sector_id, o.quantity, o.total_cents, o.currency, o.confirmed_at,
                       (SELECT count(*) FROM my_tickets t WHERE t.order_id = o.order_id)::int
                  FROM my_orders o
                 WHERE o.user_id = $1
                   AND ($2::timestamptz IS NULL OR (o.confirmed_at, o.order_id) < ($2, $3))
                 ORDER BY o.confirmed_at DESC, o.order_id DESC
                 LIMIT $4
                """, connection, transaction);
            AddPageParameters(cmd, userId, take, after);
            await using var reader = await cmd.ExecuteReaderAsync(ct);
            var rows = new List<OrderView>();
            while (await reader.ReadAsync(ct))
            {
                var order = new MyOrder(reader.GetGuid(0), reader.GetGuid(1), reader.GetGuid(2), reader.GetGuid(3),
                    reader.GetInt32(4), reader.GetInt64(5), reader.GetString(6), reader.GetDateTime(7));
                rows.Add(new OrderView(order, reader.GetInt32(8)));
            }
            return rows;
        }, ct);

    private static void AddPageParameters(NpgsqlCommand cmd, Guid userId, int take, PageCursor? after)
    {
        cmd.Parameters.Add(new NpgsqlParameter { Value = userId });
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)after?.At ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.TimestampTz });
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)after?.Id ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Uuid });
        cmd.Parameters.Add(new NpgsqlParameter { Value = take });
    }

    // set_config(..., true) is SET LOCAL: the setting dies with the transaction, so a pooled connection never
    // carries one user's id into another user's request.
    private async Task<T> AsUserAsync<T>(Guid userId, Func<NpgsqlConnection, NpgsqlTransaction, Task<T>> read, CancellationToken ct)
    {
        await using var connection = await appDb.OpenConnectionAsync(ct);
        await using var transaction = await connection.BeginTransactionAsync(ct);
        await using (var scope = new NpgsqlCommand("SELECT set_config('app.user_id', $1, true)", connection, transaction))
        {
            scope.Parameters.Add(new NpgsqlParameter { Value = userId.ToString() });
            await scope.ExecuteNonQueryAsync(ct);
        }
        var result = await read(connection, transaction);
        await transaction.CommitAsync(ct);
        return result;
    }
}
