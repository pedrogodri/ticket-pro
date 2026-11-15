using System.Globalization;
using TicketPro.Platform;

namespace PaymentService.App;

/// <summary>Validated runtime configuration read from environment variables (docs/padroes.md §8).</summary>
/// <param name="DatabaseUrl">Npgsql connection string of the API role (RLS applies).</param>
/// <param name="DatabaseWorkerUrl">Npgsql connection string of the worker role (relay, consumers, jobs).</param>
public sealed record AppConfig(
    string ServiceName,
    int Port,
    string LogLevel,
    string DatabaseUrl,
    string DatabaseWorkerUrl,
    string TopicArn,
    OutboxRelayOptions Outbox,
    TimeSpan HttpClientTimeout)
{
    /// <summary>Variables read by <see cref="FromEnvironment"/>.</summary>
    public static readonly string[] Variables =
    [
        "SERVICE_NAME", "PORT", "LOG_LEVEL", "DATABASE_URL", "DATABASE_WORKER_URL", "PAYMENTS_TOPIC_ARN",
        "OUTBOX_POLL_INTERVAL_MS", "OUTBOX_BATCH_SIZE", "OUTBOX_MAX_BACKOFF_MS", "OUTBOX_CLAIM_LEASE_MS", "HTTP_CLIENT_TIMEOUT_MS",
    ];

    private static readonly string[] LogLevels = ["debug", "info", "warn", "error"];

    /// <summary>Builds the configuration, failing fast with the name of the offending variable.</summary>
    /// <exception cref="InvalidOperationException">A variable is missing or invalid.</exception>
    public static AppConfig FromEnvironment(IReadOnlyDictionary<string, string?> env)
    {
        ArgumentNullException.ThrowIfNull(env);

        var serviceName = Required(env, "SERVICE_NAME");
        var rawPort = Required(env, "PORT");
        if (!int.TryParse(rawPort, NumberStyles.None, CultureInfo.InvariantCulture, out var port) || port is < 1 or > 65535)
        {
            throw new InvalidOperationException($"PORT must be an integer between 1 and 65535, got '{rawPort}'");
        }
        var logLevel = Required(env, "LOG_LEVEL").ToLowerInvariant();
        if (!LogLevels.Contains(logLevel))
        {
            throw new InvalidOperationException($"LOG_LEVEL must be one of debug, info, warn, error; got '{logLevel}'");
        }

        return new AppConfig(
            serviceName,
            port,
            logLevel,
            ToNpgsqlConnectionString(Required(env, "DATABASE_URL"), "DATABASE_URL"),
            ToNpgsqlConnectionString(Required(env, "DATABASE_WORKER_URL"), "DATABASE_WORKER_URL"),
            Required(env, "PAYMENTS_TOPIC_ARN"),
            new OutboxRelayOptions
            {
                PollInterval = Millis(env, "OUTBOX_POLL_INTERVAL_MS", 200),
                BatchSize = PositiveInt(env, "OUTBOX_BATCH_SIZE", 50),
                MaxBackoff = Millis(env, "OUTBOX_MAX_BACKOFF_MS", 30000),
                ClaimLease = Millis(env, "OUTBOX_CLAIM_LEASE_MS", 30000),
            },
            Millis(env, "HTTP_CLIENT_TIMEOUT_MS", 3000));
    }

    /// <summary>Converts postgres://user:pass@host[:port]/db (docs/padroes.md §3.1) to an Npgsql connection string.</summary>
    public static string ToNpgsqlConnectionString(string url, string variable)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("postgres" or "postgresql")
            || string.IsNullOrEmpty(uri.Host) || uri.AbsolutePath.Length <= 1 || !uri.UserInfo.Contains(':', StringComparison.Ordinal))
        {
            throw new InvalidOperationException($"{variable} must be postgres://user:password@host:port/database");
        }
        var user = uri.UserInfo.Split(':', 2);
        var port = uri.Port > 0 ? uri.Port : 5432;
        return $"Host={uri.Host};Port={port};Username={Uri.UnescapeDataString(user[0])};Password={Uri.UnescapeDataString(user[1])};Database={uri.AbsolutePath.TrimStart('/')}";
    }

    private static string Required(IReadOnlyDictionary<string, string?> env, string name) =>
        env.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value)
            ? value.Trim()
            : throw new InvalidOperationException($"{name} is required");

    private static int PositiveInt(IReadOnlyDictionary<string, string?> env, string name, int fallback)
    {
        if (!env.TryGetValue(name, out var raw) || string.IsNullOrWhiteSpace(raw))
        {
            return fallback;
        }
        return int.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out var n) && n > 0
            ? n
            : throw new InvalidOperationException($"{name} must be a positive integer, got '{raw}'");
    }

    private static TimeSpan Millis(IReadOnlyDictionary<string, string?> env, string name, int fallback) =>
        TimeSpan.FromMilliseconds(PositiveInt(env, name, fallback));
}
