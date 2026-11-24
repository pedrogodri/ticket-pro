using Npgsql;

namespace PaymentService.App;

/// <summary>Converts the URL form used in every service's env (postgres://user:pass@host:port/db?sslmode=disable)
/// into an Npgsql connection string; Npgsql itself only accepts key=value strings.</summary>
public static class PostgresUrl
{
    /// <exception cref="FormatException">The value is not a postgres:// URL with user, host and database.</exception>
    public static string ToConnectionString(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("postgres" or "postgresql"))
        {
            throw new FormatException("must be a postgres:// URL");
        }
        var userInfo = uri.UserInfo.Split(':', 2);
        var database = uri.AbsolutePath.Trim('/');
        if (string.IsNullOrEmpty(userInfo[0]) || string.IsNullOrEmpty(uri.Host) || string.IsNullOrEmpty(database))
        {
            throw new FormatException("must contain user, host and database");
        }
        var builder = new NpgsqlConnectionStringBuilder
        {
            Host = uri.Host,
            Port = uri.IsDefaultPort || uri.Port < 0 ? 5432 : uri.Port,
            Username = Uri.UnescapeDataString(userInfo[0]),
            Password = userInfo.Length > 1 ? Uri.UnescapeDataString(userInfo[1]) : null,
            Database = Uri.UnescapeDataString(database),
        };
        foreach (var pair in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var kv = pair.Split('=', 2);
            if (kv.Length == 2 && kv[0].Equals("sslmode", StringComparison.OrdinalIgnoreCase))
            {
                // libpq spells verify-full / verify-ca; Npgsql's enum has no hyphen.
                builder.SslMode = Enum.TryParse<SslMode>(kv[1].Replace("-", "", StringComparison.Ordinal), ignoreCase: true, out var mode)
                    ? mode
                    : throw new FormatException($"invalid sslmode '{kv[1]}'");
            }
        }
        return builder.ConnectionString;
    }
}
