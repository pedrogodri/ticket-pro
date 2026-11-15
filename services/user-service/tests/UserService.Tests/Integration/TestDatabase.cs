using Npgsql;
using Testcontainers.PostgreSql;

namespace UserService.Tests.Integration;

/// <summary>Postgres 16 with this service's migrations/ applied in order, as Flyway does. One per test run.</summary>
internal static class TestDatabase
{
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static PostgreSqlContainer? _container;

    /// <summary>Connects as users_app (password from the V0001 placeholder), so RLS applies.</summary>
    public static async Task<NpgsqlDataSource> CreateAppRoleAsync()
    {
        await (await CreateAsync()).DisposeAsync();
        var builder = new NpgsqlConnectionStringBuilder(_container!.GetConnectionString()) { Username = "users_app", Password = "app_test" };
        return NpgsqlDataSource.Create(builder.ConnectionString);
    }

    public static async Task<NpgsqlDataSource> CreateAsync()
    {
        await Gate.WaitAsync();
        try
        {
            if (_container is null)
            {
                var c = new PostgreSqlBuilder("postgres:16-alpine").Build();
                await c.StartAsync();
                await using var ds = NpgsqlDataSource.Create(c.GetConnectionString());
                foreach (var file in Directory.GetFiles(MigrationsDir(), "V*__*.sql").Order(StringComparer.Ordinal))
                {
                    var sql = (await File.ReadAllTextAsync(file)).Replace("${app_password}", "app_test", StringComparison.Ordinal)
                        .Replace("${worker_password}", "worker_test", StringComparison.Ordinal);
                    await using var cmd = ds.CreateCommand(sql);
                    await cmd.ExecuteNonQueryAsync();
                }
                _container = c;
            }
            return NpgsqlDataSource.Create(_container.GetConnectionString());
        }
        finally
        {
            Gate.Release();
        }
    }

    private static string MigrationsDir()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "migrations");
            if (Directory.Exists(candidate) && File.Exists(Path.Combine(dir.FullName, "UserService.sln")))
            {
                return candidate;
            }
        }
        throw new DirectoryNotFoundException("migrations/ not found");
    }
}
