namespace UserService.Http;

/// <summary>One error of the catalog: stable code, HTTP status and PT-BR title (docs/padroes.md §4.1).</summary>
public sealed record ErrorKind(string Code, int Status, string Title, string TypeSlug);

/// <summary>Every error this API returns, in one place (docs/padroes.md §4.1).</summary>
public static class Errors
{
    public static readonly ErrorKind Validation = new("VALIDATION_ERROR", StatusCodes.Status400BadRequest, "Requisição inválida", "validation");
    public static readonly ErrorKind Unauthenticated = new("UNAUTHENTICATED", StatusCodes.Status401Unauthorized, "Não autenticado", "unauthenticated");
    public static readonly ErrorKind NotFound = new("NOT_FOUND", StatusCodes.Status404NotFound, "Recurso não encontrado", "not-found");
    public static readonly ErrorKind Internal = new("INTERNAL_ERROR", StatusCodes.Status500InternalServerError, "Erro interno", "internal");

    public const string MissingUserId = "requisição sem X-User-Id válido";
    public const string ProfileNotFound = "perfil ainda não existe (o cadastro pode levar alguns instantes para ser processado)";
    public const string UnknownQueryParameter = "parâmetro desconhecido";
    public const string InvalidQuery = "parâmetros de consulta inválidos";
    public const string Unexpected = "erro inesperado; consulte o log pelo correlationId";
}
