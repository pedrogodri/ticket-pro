using System.Globalization;
using System.Text;

namespace UserService.Application.Paging;

/// <summary>A query parameter the client got wrong; the HTTP layer turns it into 400 VALIDATION_ERROR.</summary>
public sealed class QueryValidationException : Exception
{
    public QueryValidationException() { }

    public QueryValidationException(string message) : base(message) { }

    public QueryValidationException(string message, Exception inner) : base(message, inner) { }

    public QueryValidationException(string field, string message) : base(message) => Field = field;

    public string Field { get; } = "";
}

/// <summary>Keyset position: the sort timestamp and id of the last item of the previous page.</summary>
public sealed record PageCursor(DateTime At, Guid Id)
{
    private static readonly DateTime Epoch = DateTime.UnixEpoch;

    /// <summary>Opaque to clients (docs/padroes.md §4): base64url of "&lt;unix microseconds&gt;.&lt;id&gt;".
    /// Microseconds are PostgreSQL's timestamptz precision, so the round trip is exact.</summary>
    public string Encode()
    {
        var micros = (At.ToUniversalTime() - Epoch).Ticks / 10;
        var raw = micros.ToString(CultureInfo.InvariantCulture) + "." + Id.ToString("N");
        return Convert.ToBase64String(Encoding.ASCII.GetBytes(raw)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public static PageCursor? TryDecode(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        try
        {
            var b64 = value.Replace('-', '+').Replace('_', '/');
            b64 = b64.PadRight(b64.Length + ((4 - (b64.Length % 4)) % 4), '=');
            var parts = Encoding.ASCII.GetString(Convert.FromBase64String(b64)).Split('.');
            if (parts.Length != 2
                || !long.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out var micros)
                || !Guid.TryParseExact(parts[1], "N", out var id))
            {
                return null;
            }
            return new PageCursor(DateTime.SpecifyKind(Epoch.AddTicks(checked(micros * 10)), DateTimeKind.Utc), id);
        }
        catch (Exception e) when (e is FormatException or OverflowException or ArgumentOutOfRangeException)
        {
            return null;
        }
    }
}

/// <summary>Validated ?limit=&amp;cursor= of a list endpoint.</summary>
public sealed record PageRequest(int Limit, PageCursor? After)
{
    /// <exception cref="QueryValidationException">limit is not an integer in [1, maxLimit] or cursor is invalid.</exception>
    public static PageRequest Parse(string? limit, string? cursor, int defaultLimit, int maxLimit)
    {
        var size = defaultLimit;
        if (limit is not null
            && (!int.TryParse(limit, NumberStyles.None, CultureInfo.InvariantCulture, out size) || size < 1 || size > maxLimit))
        {
            throw new QueryValidationException("limit", $"deve ser um inteiro entre 1 e {maxLimit}");
        }
        PageCursor? after = null;
        if (cursor is not null && (after = PageCursor.TryDecode(cursor)) is null)
        {
            throw new QueryValidationException("cursor", "cursor inválido");
        }
        return new PageRequest(size, after);
    }
}

/// <summary>One page of a list: <see cref="NextCursor"/> is null on the last page.</summary>
public sealed record Page<T>(IReadOnlyList<T> Items, string? NextCursor);
