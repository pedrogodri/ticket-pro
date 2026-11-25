namespace UserService.Http;

/// <summary>Identity and correlation of a request. Both come only from headers injected by the gateway
/// (RFC 0002 §5.5): the gateway strips any X-User-Id sent by the client, so this service trusts it, and never
/// reads identity from the body or the query (RFC 0002 §5.6).</summary>
public static class RequestContext
{
    public const string UserIdHeader = "X-User-Id";
    public const string CorrelationIdHeader = "X-Correlation-Id";
    private const string UserIdKey = "ticketpro.userId";

    /// <summary>The X-User-Id header as a non-empty UUID, or null.</summary>
    public static Guid? ReadUserId(IHeaderDictionary headers)
    {
        ArgumentNullException.ThrowIfNull(headers);
        var values = headers[UserIdHeader];
        return values.Count == 1 && Guid.TryParse(values[0], out var id) && id != Guid.Empty ? id : null;
    }

    public static string? CorrelationId(HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        var value = context.Request.Headers[CorrelationIdHeader].ToString();
        return string.IsNullOrWhiteSpace(value) ? null : value;
    }

    /// <summary>Set by the /me filter once X-User-Id is validated.</summary>
    public static Guid UserId(HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        return context.Items[UserIdKey] is Guid id ? id : throw new InvalidOperationException("X-User-Id filter did not run");
    }

    internal static void SetUserId(HttpContext context, Guid userId) => context.Items[UserIdKey] = userId;
}
