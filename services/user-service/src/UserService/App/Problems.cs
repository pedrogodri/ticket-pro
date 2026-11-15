namespace UserService.App;

/// <summary>application/problem+json errors (padroes §4.1) with PT-BR titles.</summary>
public static class Problems
{
    private static readonly Dictionary<string, string> Titles = new(StringComparer.Ordinal)
    {
        ["VALIDATION_ERROR"] = "Requisição inválida",
        ["UNAUTHENTICATED"] = "Não autenticado",
        ["NOT_FOUND"] = "Não encontrado",
    };

    public static IResult Of(HttpContext http, int status, string code, string? detail = null)
    {
        ArgumentNullException.ThrowIfNull(http);
        var body = new Dictionary<string, object?>
        {
            ["type"] = "https://ticketpro.local/errors/" + code.ToLowerInvariant(),
            ["title"] = Titles[code],
            ["status"] = status,
            ["instance"] = http.Request.Path.Value,
            ["code"] = code,
            ["correlationId"] = http.Request.Headers["X-Correlation-Id"].ToString() is { Length: > 0 } c ? c : null,
        };
        if (detail is not null)
        {
            body["detail"] = detail;
        }
        return Results.Json(body, statusCode: status, contentType: "application/problem+json");
    }

    /// <summary>The user injected by the gateway (X-User-Id); null means answer 401 (RFC 0002 §5.6).</summary>
    public static string? UserOf(HttpContext http)
    {
        ArgumentNullException.ThrowIfNull(http);
        var value = http.Request.Headers["X-User-Id"].ToString();
        return Guid.TryParse(value, out _) ? value : null;
    }
}
