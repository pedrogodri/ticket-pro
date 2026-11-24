using Npgsql;
using Testcontainers.PostgreSql;

namespace PaymentService.Tests.Integration;

/// <summary>One PostgreSQL 16 for the integration tests, built from this service's real migrations
/// (same order and placeholders Flyway uses). Tests connect as payments_worker, the role the
/// service uses, so a missing grant fails here and not only in the compose.</summary>
public sealed class PaymentsDatabase : IAsyncLifetime
{
    private const string WorkerPassword = "worker_test";
    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder("postgres:16-alpine")
        .WithUsername("payments_owner")
        .WithPassword("owner_test")
        .WithDatabase("payments")
        .Build();

    public NpgsqlDataSource Worker { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        await using (var owner = NpgsqlDataSource.Create(_container.GetConnectionString()))
        {
            foreach (var file in Directory.GetFiles(TestEnv.MigrationsDir, "V*.sql").Order(StringComparer.Ordinal))
            {
                var sql = (await File.ReadAllTextAsync(file))
                    .Replace("${app_password}", "app_test", StringComparison.Ordinal)
                    .Replace("${worker_password}", WorkerPassword, StringComparison.Ordinal);
                await using var cmd = owner.CreateCommand(sql);
                await cmd.ExecuteNonQueryAsync();
            }
        }
        var worker = new NpgsqlConnectionStringBuilder(_container.GetConnectionString())
        {
            Username = "payments_worker",
            Password = WorkerPassword,
        };
        Worker = NpgsqlDataSource.Create(worker.ConnectionString);
    }

    public async Task DisposeAsync()
    {
        await Worker.DisposeAsync();
        await _container.DisposeAsync();
    }

    /// <summary>Empties every table so each test starts from zero.</summary>
    public async Task ResetAsync()
    {
        await using var cmd = Worker.CreateCommand(
            "DELETE FROM refunds; DELETE FROM provider_charges; DELETE FROM payments; DELETE FROM outbox; DELETE FROM processed_messages;");
        await cmd.ExecuteNonQueryAsync();
    }

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
}

[CollectionDefinition("payments-db")]
public sealed class PaymentsDatabaseGroup : ICollectionFixture<PaymentsDatabase>;
