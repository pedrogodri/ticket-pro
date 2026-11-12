namespace PaymentService.App;

/// <summary>Liveness and readiness probes (RFC 0003 §5.9). Dependency checks arrive in F1.</summary>
public static class HealthEndpoints
{
    private const string LiveBody = """{"status":"up"}""";
    private const string ReadyBody = """{"status":"up","checks":{}}""";

    public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health/live", () => Results.Text(LiveBody, "application/json"));
        app.MapGet("/health/ready", () => Results.Text(ReadyBody, "application/json"));
        return app;
    }
}
