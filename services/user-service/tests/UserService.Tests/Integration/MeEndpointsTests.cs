using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using UserService.App;

namespace UserService.Tests.Integration;

/// <summary>GET /me and GET /me/tickets: identity only from X-User-Id (RFC 0002 §5.6), problem+json errors.</summary>
[Trait("Category", "Integration")]
[Collection("users-db")]
public sealed class MeEndpointsTests
{
    private static async Task<(HttpStatusCode, string, string?)> GetAsync(string path, string? user)
    {
        await using var app = await TestDatabase.CreateAppRoleAsync();
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        await using var web = builder.Build();
        web.MapMeEndpoints(app);
        await web.StartAsync();
        using var req = new HttpRequestMessage(HttpMethod.Get, path);
        if (user is not null)
        {
            req.Headers.Add("X-User-Id", user);
        }
        var res = await web.GetTestClient().SendAsync(req);
        return (res.StatusCode, await res.Content.ReadAsStringAsync(), res.Content.Headers.ContentType?.MediaType);
    }

    [Fact]
    public async Task WithoutUserIs401Problem()
    {
        var (code, body, type) = await GetAsync("/me", null);
        Assert.Equal(HttpStatusCode.Unauthorized, code);
        Assert.Equal("application/problem+json", type);
        Assert.Contains("\"code\":\"UNAUTHENTICATED\"", body, StringComparison.Ordinal);
        Assert.Equal(HttpStatusCode.Unauthorized, (await GetAsync("/me/tickets", null)).Item1);
    }

    [Fact]
    public async Task ProfileNotProjectedYetIs404()
    {
        Assert.Equal(HttpStatusCode.NotFound, (await GetAsync("/me", Guid.NewGuid().ToString())).Item1);
    }

    [Fact]
    public async Task TicketsValidateLimitAndCursor()
    {
        var user = Guid.NewGuid().ToString();
        Assert.Equal(HttpStatusCode.BadRequest, (await GetAsync("/me/tickets?limit=51", user)).Item1);
        Assert.Equal(HttpStatusCode.BadRequest, (await GetAsync("/me/tickets?cursor=bm9wZQ", user)).Item1);
        var (code, body, _) = await GetAsync("/me/tickets", user);
        Assert.Equal(HttpStatusCode.OK, code);
        Assert.Equal("""{"items":[],"nextCursor":null}""", body);
    }

    [Fact]
    public async Task InvalidUserIdHeaderIs401()
    {
        Assert.Equal(HttpStatusCode.Unauthorized, (await GetAsync("/me", "not-a-uuid")).Item1);
    }
}
