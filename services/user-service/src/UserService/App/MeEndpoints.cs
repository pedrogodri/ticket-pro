using System.Globalization;
using Npgsql;
using UserService.Modules.MyTickets;
using UserService.Modules.Profiles;

namespace UserService.App;

/// <summary>GET /me and GET /me/tickets through the API role (RLS by app.user_id).</summary>
public static class MeEndpoints
{
    private const int DefaultLimit = 20;
    private const int MaxLimit = 50; // padroes §4

    public static IEndpointRouteBuilder MapMeEndpoints(this IEndpointRouteBuilder app, NpgsqlDataSource appDb)
    {
        var profiles = new ProfileQueries(appDb);
        var tickets = new MyTicketsQueries(appDb);

        app.MapGet("/me", async (HttpContext http, CancellationToken ct) =>
        {
            if (Problems.UserOf(http) is not { } user)
            {
                return Problems.Of(http, 401, "UNAUTHENTICATED");
            }
            return await profiles.GetAsync(user, ct) is { } me ? Results.Json(me) : Problems.Of(http, 404, "NOT_FOUND");
        });

        app.MapGet("/me/tickets", async (HttpContext http, CancellationToken ct) =>
        {
            if (Problems.UserOf(http) is not { } user)
            {
                return Problems.Of(http, 401, "UNAUTHENTICATED");
            }
            var limit = DefaultLimit;
            if (http.Request.Query["limit"].ToString() is { Length: > 0 } raw
                && (!int.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out limit) || limit is < 1 or > MaxLimit))
            {
                return Problems.Of(http, 400, "VALIDATION_ERROR", "limit must be between 1 and 50");
            }
            var cursor = http.Request.Query["cursor"].ToString() is { Length: > 0 } c ? c : null;
            try
            {
                return Results.Json(await tickets.ListAsync(user, limit, cursor, ct));
            }
            catch (FormatException)
            {
                return Problems.Of(http, 400, "VALIDATION_ERROR", "cursor is invalid");
            }
        });
        return app;
    }
}
