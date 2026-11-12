using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using PaymentService.App;

namespace PaymentService.Tests;

public sealed class HealthEndpointsTests
{
    private static async Task<(HttpStatusCode Code, string Body)> GetAsync(string path, bool dbUp, bool? brokerUp)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        await using var app = builder.Build();
        Func<bool>? broker = brokerUp is null ? null : () => brokerUp.Value;
        app.MapHealthEndpoints(_ => Task.FromResult(dbUp), broker);
        await app.StartAsync();
        var response = await app.GetTestClient().GetAsync(new Uri(path, UriKind.Relative));
        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
        return (response.StatusCode, await response.Content.ReadAsStringAsync());
    }

    [Theory]
    [InlineData(true, true, HttpStatusCode.OK, """{"status":"up","checks":{"db":"up","broker":"up"}}""")]
    [InlineData(true, false, HttpStatusCode.OK, """{"status":"up","checks":{"db":"up","broker":"degraded"}}""")]
    [InlineData(false, true, HttpStatusCode.ServiceUnavailable, """{"status":"down","checks":{"db":"down","broker":"up"}}""")]
    public async Task Ready_ReportsDatabaseAndBroker(bool dbUp, bool brokerUp, HttpStatusCode code, string body)
    {
        var (gotCode, gotBody) = await GetAsync("/health/ready", dbUp, brokerUp);
        Assert.Equal(code, gotCode);
        Assert.Equal(body, gotBody);
    }

    [Fact]
    public async Task Ready_WithoutBroker_ReportsDatabaseOnly()
    {
        var (code, body) = await GetAsync("/health/ready", true, null);
        Assert.Equal(HttpStatusCode.OK, code);
        Assert.Equal("""{"status":"up","checks":{"db":"up"}}""", body);
    }

    [Fact]
    public async Task Live_IgnoresDependencies()
    {
        var (code, body) = await GetAsync("/health/live", false, false);
        Assert.Equal(HttpStatusCode.OK, code);
        Assert.Equal("""{"status":"up"}""", body);
    }
}
