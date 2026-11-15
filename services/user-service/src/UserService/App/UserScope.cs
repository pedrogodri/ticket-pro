using Npgsql;

namespace UserService.App;

/// <summary>Runs queries in a transaction scoped to the caller: app.user_id makes RLS filter every row (padroes §6.1).</summary>
public static class UserScope
{
    public static async Task<T> RunAsync<T>(NpgsqlDataSource app, string userId, Func<NpgsqlConnection, NpgsqlTransaction, Task<T>> work, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(work);
        await using var conn = await app.OpenConnectionAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        await using (var scope = new NpgsqlCommand("SELECT set_config('app.user_id', $1, true)", conn, tx))
        {
            scope.Parameters.Add(new NpgsqlParameter { Value = userId });
            await scope.ExecuteNonQueryAsync(ct);
        }
        var result = await work(conn, tx);
        await tx.CommitAsync(ct);
        return result;
    }
}
