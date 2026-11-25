using Npgsql;

namespace UserService.App;

/// <summary>Probes of RFC 0003 §5.9, same JSON as the Go services: readiness is 503 only when the
/// database is down; a broker outage is "degraded" because the outbox keeps accepting work.</summary>
public static class HealthEndpoints
{
    private static readonly TimeSpan PingTimeout = TimeSpan.FromSeconds(2);

    public static void Map(WebApplication app, NpgsqlDataSource appDb, Func<bool> brokerUp)
    {
        ArgumentNullException.ThrowIfNull(app);
        app.MapGet("/health/live", () => Results.Text("""{"status":"up"}""", "application/json"));
        app.MapGet("/health/ready", async (CancellationToken ct) =>
        {
            var (code, body) = await ReadinessAsync(token => PingAsync(appDb, token), brokerUp, ct);
            return Results.Text(body, "application/json", statusCode: code);
        });
    }

    /// <summary>Status code and body of /health/ready; kept apart from ASP.NET so it is unit-testable.</summary>
    public static async Task<(int Code, string Body)> ReadinessAsync(Func<CancellationToken, Task<bool>> dbUp, Func<bool> brokerUp, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(dbUp);
        ArgumentNullException.ThrowIfNull(brokerUp);
        var db = await dbUp(ct);
        var broker = brokerUp() ? "up" : "degraded";
        var (code, state) = db ? (StatusCodes.Status200OK, "up") : (StatusCodes.Status503ServiceUnavailable, "down");
        return (code, "{\"status\":\"" + state + "\",\"checks\":{\"db\":\"" + state + "\",\"broker\":\"" + broker + "\"}}");
    }

    private static async Task<bool> PingAsync(NpgsqlDataSource db, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(PingTimeout);
        try
        {
            await using var cmd = db.CreateCommand("SELECT 1");
            await cmd.ExecuteScalarAsync(timeout.Token);
            return true;
        }
        catch (Exception e) when (e is NpgsqlException or OperationCanceledException or TimeoutException or InvalidOperationException)
        {
            return false;
        }
    }
}
