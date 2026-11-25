using Npgsql;
using UserService.Application.Paging;
using UserService.Application.Queries;
using UserService.Infrastructure.Persistence;

namespace UserService.Tests.Integration;

/// <summary>The HTTP read side as users_app (RLS). BOLA (RFC 0002 §5.6, docs/padroes.md §11.2): user B never
/// sees user A's data, and the database enforces it even for a query without "WHERE user_id".</summary>
[Trait("Category", "Integration")]
[Collection("users-db")]
public sealed class ReadStoreTests(UsersDatabase db) : IAsyncLifetime
{
    private static readonly Guid UserA = Guid.CreateVersion7();
    private static readonly Guid UserB = Guid.CreateVersion7();

    public async Task InitializeAsync()
    {
        await db.ResetAsync();
        await db.ExecAsync("INSERT INTO profiles (user_id, email, name) VALUES ($1, 'ana@exemplo.com', 'Ana')", UserA);
        var order = Guid.CreateVersion7();
        await db.ExecAsync("""
            INSERT INTO my_orders (order_id, user_id, event_id, sector_id, quantity, total_cents, currency, confirmed_at)
            VALUES ($1, $2, $3, $4, 5, 125000, 'BRL', '2026-11-20T18:30:00Z')
            """, order, UserA, Guid.CreateVersion7(), Guid.CreateVersion7());
        for (var i = 1; i <= 5; i++)
        {
            await db.ExecAsync($"""
                INSERT INTO my_tickets (ticket_id, ticket_code, user_id, order_id, event_id, event_name, event_starts_at, sector_name, issued_at)
                VALUES ($1, 'AAAA-AAAA-000{i}', $2, $3, $4, 'Festival', '2026-12-12T21:00:00Z', 'Pista', '2026-11-20T18:3{i}:00Z')
                """, Guid.CreateVersion7(), UserA, order, Guid.CreateVersion7());
        }
    }

    public Task DisposeAsync() => Task.CompletedTask;

    private UserReadStore Store => new(db.App);

    [Fact]
    public async Task Owner_sees_their_profile_and_another_user_sees_nothing()
    {
        Assert.Equal("ana@exemplo.com", (await Store.GetProfileAsync(UserA, CancellationToken.None))?.Email);
        Assert.Null(await Store.GetProfileAsync(UserB, CancellationToken.None));
    }

    [Fact]
    public async Task BOLA_user_B_does_not_see_user_A_tickets_or_orders()
    {
        Assert.Equal(5, (await Store.ListTicketsAsync(UserA, 50, null, CancellationToken.None)).Count);
        Assert.Empty(await Store.ListTicketsAsync(UserB, 50, null, CancellationToken.None));
        Assert.Empty(await Store.ListOrdersAsync(UserB, 50, null, CancellationToken.None));
    }

    [Theory]
    [InlineData("SELECT count(*) FROM my_tickets")]
    [InlineData("SELECT count(*) FROM my_orders")]
    [InlineData("SELECT count(*) FROM profiles")]
    public async Task RLS_filters_even_a_query_without_a_user_filter(string sqlWithoutWhere)
    {
        Assert.Equal(0L, await CountAsAppAsync(sqlWithoutWhere, UserB));
        Assert.Equal(0L, await CountAsAppAsync(sqlWithoutWhere, null)); // no app.user_id: nothing at all
        Assert.True(await CountAsAppAsync(sqlWithoutWhere, UserA) > 0);
    }

    [Fact]
    public async Task Connection_reused_after_a_request_sees_nothing_without_app_user_id()
    {
        // A pooled connection that served user A: in its next transaction app.user_id reads as '' (V0008).
        await using var connection = await db.App.OpenConnectionAsync();
        await using (var first = await connection.BeginTransactionAsync())
        {
            await SetUserAsync(connection, first, UserA);
            await first.CommitAsync();
        }
        await using var second = await connection.BeginTransactionAsync();
        await using var cmd = new NpgsqlCommand("SELECT count(*) FROM my_tickets", connection, second);

        Assert.Equal(0L, (long)(await cmd.ExecuteScalarAsync())!);
    }

    [Fact]
    public async Task App_role_cannot_write_rows_of_another_user()
    {
        await using var connection = await db.App.OpenConnectionAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        await SetUserAsync(connection, transaction, UserB);
        await using var insert = new NpgsqlCommand(
            "INSERT INTO profiles (user_id, email, name) VALUES ($1, 'x@exemplo.com', 'X')", connection, transaction);
        insert.Parameters.Add(new NpgsqlParameter { Value = UserA });

        var e = await Assert.ThrowsAsync<PostgresException>(() => insert.ExecuteNonQueryAsync());

        Assert.Equal("42501", e.SqlState); // insufficient_privilege: row-level security WITH CHECK
    }

    [Fact]
    public async Task Pagination_walks_every_ticket_once_newest_first()
    {
        var queries = new UserQueries(Store);
        var codes = new List<string>();
        PageCursor? after = null;
        var pages = 0;
        do
        {
            var page = await queries.ListTicketsAsync(UserA, new PageRequest(2, after), CancellationToken.None);
            codes.AddRange(page.Items.Select(t => t.Code));
            after = page.NextCursor is null ? null : PageCursor.TryDecode(page.NextCursor);
            pages++;
        }
        while (after is not null);

        Assert.Equal(3, pages);
        Assert.Equal(["AAAA-AAAA-0005", "AAAA-AAAA-0004", "AAAA-AAAA-0003", "AAAA-AAAA-0002", "AAAA-AAAA-0001"], codes);
    }

    [Fact]
    public async Task Orders_report_how_many_tickets_were_issued()
    {
        var view = Assert.Single(await Store.ListOrdersAsync(UserA, 50, null, CancellationToken.None));

        Assert.Equal((5, 5, 125000L), (view.Order.Quantity, view.TicketsIssued, view.Order.TotalCents));
    }

    private async Task<long> CountAsAppAsync(string sql, Guid? userId)
    {
        await using var connection = await db.App.OpenConnectionAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        if (userId is { } id)
        {
            await SetUserAsync(connection, transaction, id);
        }
        await using var cmd = new NpgsqlCommand(sql, connection, transaction);
        return (long)(await cmd.ExecuteScalarAsync())!;
    }

    private static async Task SetUserAsync(NpgsqlConnection connection, NpgsqlTransaction transaction, Guid userId)
    {
        await using var cmd = new NpgsqlCommand("SELECT set_config('app.user_id', $1, true)", connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = userId.ToString() });
        await cmd.ExecuteNonQueryAsync();
    }
}
