using System.Net;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;

namespace PaymentService.Tests;

/// <summary>Boots the real Program with an unreachable database: the process stays alive (live = 200)
/// and readiness reports the database down (RFC 0003 §5.9).</summary>
public sealed class ProgramTests : IClassFixture<ProgramTests.Factory>
{
    public sealed class Factory : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseSetting("SERVICE_NAME", "payment-service");
            builder.UseSetting("PORT", "8080");
            builder.UseSetting("LOG_LEVEL", "info");
            builder.UseSetting("DATABASE_URL", "postgres://payments_app:x@127.0.0.1:1/payments");
            builder.UseSetting("DATABASE_WORKER_URL", "postgres://payments_worker:x@127.0.0.1:1/payments");
            builder.UseSetting("PAYMENTS_TOPIC_ARN", "arn:aws:sns:us-east-1:000000000000:ticketpro-payments");
            builder.UseSetting("AWS_ENDPOINT_URL", "http://127.0.0.1:1");
            builder.UseSetting("AWS_REGION", "us-east-1");
        }
    }

    private readonly HttpClient _client;

    public ProgramTests(Factory factory) => _client = factory.CreateClient();

    [Fact]
    public async Task Live_IsUp()
    {
        var response = await _client.GetAsync(new Uri("/health/live", UriKind.Relative));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Ready_ReportsDatabaseDown()
    {
        var response = await _client.GetAsync(new Uri("/health/ready", UriKind.Relative));
        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.Contains("\"db\":\"down\"", await response.Content.ReadAsStringAsync(), StringComparison.Ordinal);
    }
}
