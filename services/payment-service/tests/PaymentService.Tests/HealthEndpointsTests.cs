using System.Net;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;

namespace PaymentService.Tests;

public sealed class HealthEndpointsTests : IClassFixture<HealthEndpointsTests.Factory>
{
    public sealed class Factory : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseSetting("SERVICE_NAME", "payment-service");
            builder.UseSetting("PORT", "8080");
            builder.UseSetting("LOG_LEVEL", "info");
        }
    }

    private readonly HttpClient _client;

    public HealthEndpointsTests(Factory factory) => _client = factory.CreateClient();

    [Fact]
    public async Task Live_ReturnsUp()
    {
        var response = await _client.GetAsync(new Uri("/health/live", UriKind.Relative));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal("""{"status":"up"}""", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Ready_ReturnsUpWithEmptyChecks()
    {
        var response = await _client.GetAsync(new Uri("/health/ready", UriKind.Relative));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("""{"status":"up","checks":{}}""", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Post_IsNotAllowed()
    {
        using var content = new StringContent("");
        var response = await _client.PostAsync(new Uri("/health/live", UriKind.Relative), content);

        Assert.Equal(HttpStatusCode.MethodNotAllowed, response.StatusCode);
    }
}
