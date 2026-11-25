using UserService.App;
using UserService.Application.Paging;
using UserService.Application.Queries;

namespace UserService.Http;

/// <summary>GET /me, /me/tickets and /me/orders (reached through the gateway's "me" route). Every route
/// requires X-User-Id; there is no route with a userId in the URL (RFC 0002 §5.6).</summary>
public static class UserEndpoints
{
    private static readonly string[] PageParameters = ["limit", "cursor"];

    public static void Map(IEndpointRouteBuilder app)
    {
        var me = app.MapGroup("/me").AddEndpointFilter(async (context, next) =>
        {
            var userId = RequestContext.ReadUserId(context.HttpContext.Request.Headers);
            if (userId is null)
            {
                // Defense in depth: only a caller that bypassed the gateway gets here without the header.
                return Problems.Of(context.HttpContext, Errors.Unauthenticated, Errors.MissingUserId);
            }
            RequestContext.SetUserId(context.HttpContext, userId.Value);
            return await next(context);
        });

        me.MapGet("", async (HttpContext http, UserQueries queries, CancellationToken ct) =>
        {
            if (RejectUnknownQuery(http, []) is { } rejected)
            {
                return rejected;
            }
            var profile = await queries.GetProfileAsync(RequestContext.UserId(http), ct);
            return profile is null
                ? Problems.Of(http, Errors.NotFound, Errors.ProfileNotFound)
                : Results.Ok(ProfileResponse.From(profile));
        });

        me.MapGet("/tickets", (HttpContext http, UserQueries queries, AppConfig config, CancellationToken ct) =>
            ListAsync(http, config, async page =>
            {
                var result = await queries.ListTicketsAsync(RequestContext.UserId(http), page, ct);
                return new PageResponse<TicketResponse>(result.Items.Select(TicketResponse.From).ToList(), result.NextCursor);
            }));

        me.MapGet("/orders", (HttpContext http, UserQueries queries, AppConfig config, CancellationToken ct) =>
            ListAsync(http, config, async page =>
            {
                var result = await queries.ListOrdersAsync(RequestContext.UserId(http), page, ct);
                return new PageResponse<OrderResponse>(result.Items.Select(OrderResponse.From).ToList(), result.NextCursor);
            }));
    }

    private static async Task<IResult> ListAsync<T>(HttpContext http, AppConfig config, Func<PageRequest, Task<PageResponse<T>>> list)
    {
        if (RejectUnknownQuery(http, PageParameters) is { } rejected)
        {
            return rejected;
        }
        PageRequest page;
        try
        {
            var query = http.Request.Query;
            page = PageRequest.Parse(
                query.TryGetValue("limit", out var limit) ? limit.ToString() : null,
                query.TryGetValue("cursor", out var cursor) ? cursor.ToString() : null,
                config.PageDefaultLimit, config.PageMaxLimit);
        }
        catch (QueryValidationException e)
        {
            return Problems.Of(http, Errors.Validation, Errors.InvalidQuery, [new FieldError(e.Field, e.Message)]);
        }
        return Results.Ok(await list(page));
    }

    // docs/padroes.md §4: unknown input fields are a 400, not silently ignored.
    private static IResult? RejectUnknownQuery(HttpContext http, string[] allowed)
    {
        var unknown = http.Request.Query.Keys.Where(k => !allowed.Contains(k, StringComparer.Ordinal)).Order(StringComparer.Ordinal).ToList();
        return unknown.Count == 0
            ? null
            : Problems.Of(http, Errors.Validation, Errors.InvalidQuery, unknown.Select(k => new FieldError(k, Errors.UnknownQueryParameter)).ToList());
    }
}
