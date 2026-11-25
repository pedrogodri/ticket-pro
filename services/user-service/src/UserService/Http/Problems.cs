namespace UserService.Http;

public sealed record FieldError(string Field, string Message);

/// <summary>RFC 9457 body (docs/padroes.md §4.1).</summary>
public sealed record ProblemBody(
    string Type, string Title, int Status, string Detail, string Instance, string Code, string? CorrelationId,
    IReadOnlyList<FieldError>? Errors);

/// <summary>Builds application/problem+json responses from the <see cref="Errors"/> catalog.</summary>
public static class Problems
{
    public const string ContentType = "application/problem+json";

    public static IResult Of(HttpContext context, ErrorKind kind, string detail, IReadOnlyList<FieldError>? errors = null)
    {
        ArgumentNullException.ThrowIfNull(context);
        ArgumentNullException.ThrowIfNull(kind);
        var body = new ProblemBody("https://ticketpro.local/errors/" + kind.TypeSlug, kind.Title, kind.Status, detail,
            context.Request.Path, kind.Code, RequestContext.CorrelationId(context), errors);
        return Results.Json(body, contentType: ContentType, statusCode: kind.Status);
    }
}
