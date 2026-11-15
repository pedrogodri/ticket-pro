namespace PaymentService.App;

/// <summary>Liveness and readiness probes (RFC 0003 §5.9).</summary>
public static class HealthEndpoints
{
    /// <summary>Readiness is 503 only when the database is down; a broker outage is "degraded" because the
    /// outbox keeps accepting work. <paramref name="brokerUp"/> is null for services without a relay.</summary>
    public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app, Func<CancellationToken, Task<bool>> dbPing, Func<bool>? brokerUp)
    {
        app.MapGet("/health/live", () => Results.Text("""{"status":"up"}""", "application/json"));
        app.MapGet("/health/ready", async (HttpContext http) =>
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(http.RequestAborted);
            timeout.CancelAfter(TimeSpan.FromSeconds(2));
            var dbIsUp = await dbPing(timeout.Token);
            var checks = $"\"db\":\"{(dbIsUp ? "up" : "down")}\"";
            if (brokerUp is not null)
            {
                checks += $",\"broker\":\"{(brokerUp() ? "up" : "degraded")}\"";
            }
            var body = $"{{\"status\":\"{(dbIsUp ? "up" : "down")}\",\"checks\":{{{checks}}}}}";
            return Results.Text(body, "application/json", statusCode: dbIsUp ? StatusCodes.Status200OK : StatusCodes.Status503ServiceUnavailable);
        });
        return app;
    }
}
