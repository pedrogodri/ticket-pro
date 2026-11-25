using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using UserService.App;
using UserService.Application.Queries;
using UserService.Domain.Orders;
using UserService.Domain.Tickets;
using UserService.Http;

namespace UserService.Tests.Http;

/// <summary>The real HTTP pipeline (filters, problem+json, error handler) in memory, over a fake read store.
/// RLS itself is covered by Integration/ReadStoreTests.</summary>
public sealed class UserEndpointsTests : IAsyncLifetime
{
    private static readonly Guid UserA = Guid.Parse("0193a6f0-1111-7000-8000-000000000001");
    private readonly FakeReadStore _store = new();
    private WebApplication _app = null!;
    private HttpClient _client = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Logging.ClearProviders();
        builder.Services.AddSingleton(TestEnv.Config());
        builder.Services.AddSingleton<IUserReadStore>(_store);
        builder.Services.AddSingleton<UserQueries>();
        _app = builder.Build();
        ErrorHandling.Use(_app);
        UserEndpoints.Map(_app);
        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        _client.Dispose();
        await _app.DisposeAsync();
    }

    private async Task<(HttpStatusCode Status, string? ContentType, JsonElement Body)> GetAsync(string path, Guid? user = null, string? rawUser = null)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, path);
        if (rawUser is not null || user is not null)
        {
            request.Headers.Add(RequestContext.UserIdHeader, rawUser ?? user!.Value.ToString());
        }
        request.Headers.Add(RequestContext.CorrelationIdHeader, "corr-123");
        using var response = await _client.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        return (response.StatusCode, response.Content.Headers.ContentType?.MediaType, JsonElement.Parse(text));
    }

    private static void AssertProblem((HttpStatusCode Status, string? ContentType, JsonElement Body) r, int status, string code)
    {
        Assert.Equal(status, (int)r.Status);
        Assert.Equal(Problems.ContentType, r.ContentType);
        Assert.Equal(code, r.Body.GetProperty("code").GetString());
        Assert.Equal(status, r.Body.GetProperty("status").GetInt32());
        Assert.Equal("corr-123", r.Body.GetProperty("correlationId").GetString());
        Assert.False(string.IsNullOrEmpty(r.Body.GetProperty("title").GetString()));
    }

    [Theory]
    [InlineData("/me")]
    [InlineData("/me/tickets")]
    [InlineData("/me/orders")]
    public async Task Every_route_requires_X_User_Id(string path)
    {
        AssertProblem(await GetAsync(path), 401, "UNAUTHENTICATED");
        Assert.Empty(_store.ReadsFor);
    }

    [Theory]
    [InlineData("nao-e-uuid")]
    [InlineData("00000000-0000-0000-0000-000000000000")]
    public async Task Malformed_X_User_Id_is_401(string raw)
    {
        AssertProblem(await GetAsync("/me", rawUser: raw), 401, "UNAUTHENTICATED");
    }

    [Fact]
    public async Task Me_returns_the_profile_of_the_header_user()
    {
        _store.Profiles[UserA] = new ProfileView(UserA, "ana@exemplo.com", "Ana Souza", new DateTime(2026, 11, 20, 18, 30, 12, 345, DateTimeKind.Utc));

        var (status, _, body) = await GetAsync("/me", UserA);

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(UserA, body.GetProperty("userId").GetGuid());
        Assert.Equal("ana@exemplo.com", body.GetProperty("email").GetString());
        Assert.Equal("Ana Souza", body.GetProperty("name").GetString());
        Assert.Equal("2026-11-20T18:30:12.345Z", body.GetProperty("createdAt").GetString());
        Assert.Equal([UserA], _store.ReadsFor);
    }

    [Fact]
    public async Task Me_is_404_while_the_profile_is_not_projected()
    {
        AssertProblem(await GetAsync("/me", UserA), 404, "NOT_FOUND");
    }

    [Fact]
    public async Task Identity_in_the_query_is_ignored_and_rejected()
    {
        var other = Guid.CreateVersion7();

        var r = await GetAsync($"/me/tickets?userId={other}", UserA);

        AssertProblem(r, 400, "VALIDATION_ERROR");
        Assert.Equal("userId", r.Body.GetProperty("errors")[0].GetProperty("field").GetString());
        Assert.Empty(_store.ReadsFor);
    }

    [Theory]
    [InlineData("/me/tickets?limit=0", "limit")]
    [InlineData("/me/tickets?limit=51", "limit")]
    [InlineData("/me/orders?limit=x", "limit")]
    [InlineData("/me/tickets?cursor=lixo", "cursor")]
    public async Task Invalid_paging_is_400(string path, string field)
    {
        var r = await GetAsync(path, UserA);

        AssertProblem(r, 400, "VALIDATION_ERROR");
        Assert.Equal(field, r.Body.GetProperty("errors")[0].GetProperty("field").GetString());
    }

    [Fact]
    public async Task Tickets_list_has_items_and_null_next_cursor()
    {
        var ticket = new MyTicket(Guid.CreateVersion7(), "K7Q2-9XMA-41PZ", UserA, Guid.CreateVersion7(), Guid.CreateVersion7(),
            "Festival de Verão 2026", new DateTime(2026, 12, 12, 21, 0, 0, DateTimeKind.Utc), "Pista",
            new DateTime(2026, 11, 20, 18, 30, 12, DateTimeKind.Utc));
        _store.Tickets.Add(ticket);

        var (status, _, body) = await GetAsync("/me/tickets?limit=10", UserA);

        Assert.Equal(HttpStatusCode.OK, status);
        var item = Assert.Single(body.GetProperty("items").EnumerateArray());
        Assert.Equal("K7Q2-9XMA-41PZ", item.GetProperty("code").GetString());
        Assert.Equal(ticket.OrderId, item.GetProperty("orderId").GetGuid());
        Assert.Equal("2026-12-12T21:00:00.000Z", item.GetProperty("eventStartsAt").GetString());
        Assert.False(item.TryGetProperty("userId", out _)); // the read model's user_id never leaves the service
        Assert.Equal(JsonValueKind.Null, body.GetProperty("nextCursor").ValueKind);
    }

    [Fact]
    public async Task Orders_list_shows_confirmed_purchases_and_issued_tickets()
    {
        var order = new MyOrder(Guid.CreateVersion7(), UserA, Guid.CreateVersion7(), Guid.CreateVersion7(), 2, 50000, "BRL",
            new DateTime(2026, 11, 20, 18, 30, 14, 120, DateTimeKind.Utc));
        _store.Orders.Add(new OrderView(order, 1));

        var (status, _, body) = await GetAsync("/me/orders", UserA);

        Assert.Equal(HttpStatusCode.OK, status);
        var item = Assert.Single(body.GetProperty("items").EnumerateArray());
        Assert.Equal(order.OrderId, item.GetProperty("orderId").GetGuid());
        Assert.Equal(2, item.GetProperty("quantity").GetInt32());
        Assert.Equal(50000, item.GetProperty("totalCents").GetInt64());
        Assert.Equal("BRL", item.GetProperty("currency").GetString());
        Assert.Equal("2026-11-20T18:30:14.120Z", item.GetProperty("confirmedAt").GetString());
        Assert.Equal(1, item.GetProperty("ticketsIssued").GetInt32());
    }

    [Fact]
    public async Task Unexpected_failure_is_500_without_internals()
    {
        _store.Fail = new InvalidOperationException("connection string password=secret");

        var r = await GetAsync("/me", UserA);

        AssertProblem(r, 500, "INTERNAL_ERROR");
        Assert.DoesNotContain("secret", r.Body.GetRawText(), StringComparison.Ordinal);
    }
}
