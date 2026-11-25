using Npgsql;
using Testcontainers.PostgreSql;

namespace UserService.Tests.Integration;

/// <summary>One PostgreSQL 16 for the integration tests, built from this service's real migrations (same order
/// and placeholders Flyway uses). Consumers connect as users_worker and the read side as users_app, the roles
/// the service uses, so a missing grant or a broken RLS policy fails here and not only in the compose.</summary>
public sealed class UsersDatabase : IAsyncLifetime
{
    private const string AppPassword = "app_test";
    private const string WorkerPassword = "worker_test";
    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder("postgres:16-alpine")
        .WithUsername("users_owner")
        .WithPassword("owner_test")
        .WithDatabase("users")
        .Build();

    public NpgsqlDataSource Worker { get; private set; } = null!;

    public NpgsqlDataSource App { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        await using (var owner = NpgsqlDataSource.Create(_container.GetConnectionString()))
        {
            foreach (var file in Directory.GetFiles(TestEnv.MigrationsDir, "V*.sql").Order(StringComparer.Ordinal))
            {
                var sql = (await File.ReadAllTextAsync(file))
                    .Replace("${app_password}", AppPassword, StringComparison.Ordinal)
                    .Replace("${worker_password}", WorkerPassword, StringComparison.Ordinal);
                await using var cmd = owner.CreateCommand(sql);
                await cmd.ExecuteNonQueryAsync();
            }
        }
        Worker = NpgsqlDataSource.Create(As("users_worker", WorkerPassword));
        App = NpgsqlDataSource.Create(As("users_app", AppPassword));
    }

    public async Task DisposeAsync()
    {
        await App.DisposeAsync();
        await Worker.DisposeAsync();
        await _container.DisposeAsync();
    }

    /// <summary>Empties every table so each test starts from zero.</summary>
    public Task ResetAsync() => ExecAsync(
        "DELETE FROM my_tickets; DELETE FROM my_orders; DELETE FROM profiles; DELETE FROM outbox; DELETE FROM processed_messages;");

    public async Task<T?> ScalarAsync<T>(string sql, params object[] args)
    {
        await using var cmd = Worker.CreateCommand(sql);
        foreach (var arg in args)
        {
            cmd.Parameters.Add(new NpgsqlParameter { Value = arg });
        }
        var value = await cmd.ExecuteScalarAsync();
        return value is null or DBNull ? default : (T)value;
    }

    public async Task ExecAsync(string sql, params object[] args)
    {
        await using var cmd = Worker.CreateCommand(sql);
        foreach (var arg in args)
        {
            cmd.Parameters.Add(new NpgsqlParameter { Value = arg });
        }
        await cmd.ExecuteNonQueryAsync();
    }

    private string As(string user, string password) =>
        new NpgsqlConnectionStringBuilder(_container.GetConnectionString()) { Username = user, Password = password }.ConnectionString;
}

[CollectionDefinition("users-db")]
public sealed class UsersDatabaseGroup : ICollectionFixture<UsersDatabase>;
