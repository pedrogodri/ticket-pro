using System.Globalization;
using Microsoft.Extensions.Logging;

namespace UserService.App;

/// <summary>Configuration error found at boot; the message always starts with the variable name.</summary>
public sealed class ConfigException : Exception
{
    public ConfigException() { }

    public ConfigException(string message) : base(message) { }

    public ConfigException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>Every environment variable the service reads, validated once at boot (docs/padroes.md §8).
/// Defaults are the RFC 0003 §5.1 values; the service refuses to start on any invalid value.</summary>
public sealed record AppConfig
{
    public required string ServiceName { get; init; }
    public required int Port { get; init; }
    public required LogLevel LogLevel { get; init; }

    /// <summary>users_app (RLS): the HTTP API (/me*) and the readiness probe.</summary>
    public required string DatabaseUrl { get; init; }

    /// <summary>users_worker (BYPASSRLS): consumers and outbox relay.</summary>
    public required string DatabaseWorkerUrl { get; init; }

    /// <summary>MiniStack endpoint; null means real AWS for the configured region.</summary>
    public string? AwsEndpointUrl { get; init; }
    public required string AwsRegion { get; init; }
    public required string UsersTopicArn { get; init; }
    public required string QueueUrl { get; init; }
    public required string ContractsDir { get; init; }

    public required TimeSpan OutboxPollInterval { get; init; }
    public required int OutboxBatchSize { get; init; }
    public required TimeSpan OutboxMaxBackoff { get; init; }
    public required TimeSpan OutboxClaimLease { get; init; }
    public required TimeSpan HttpClientTimeout { get; init; }

    public required int ConsumerConcurrency { get; init; }
    public required int SqsWaitTimeSeconds { get; init; }

    /// <summary>E-mail domains whose registration is refused (UserCreationFailed → auth compensates).
    /// Lower case; a listed domain also blocks its subdomains. Empty means nothing is blocked.</summary>
    public required IReadOnlySet<string> ProfileBlockedEmailDomains { get; init; }

    /// <summary>Page size of /me/tickets and /me/orders when the client sends no limit.</summary>
    public required int PageDefaultLimit { get; init; }

    /// <summary>Largest limit a client may ask for (docs/padroes.md §4: limit ≤ 50).</summary>
    public required int PageMaxLimit { get; init; }

    /// <summary>Reads and validates the configuration; <paramref name="env"/> is usually Environment.GetEnvironmentVariable.</summary>
    /// <exception cref="ConfigException">The first invalid or missing variable.</exception>
    public static AppConfig Load(Func<string, string?> env)
    {
        ArgumentNullException.ThrowIfNull(env);
        var pageMaxLimit = Int(env, "PAGE_MAX_LIMIT", 50, 1, 50);
        return new AppConfig
        {
            ServiceName = Required(env, "SERVICE_NAME"),
            Port = Int(env, "PORT", null, 1, 65535),
            LogLevel = ParseLogLevel(Required(env, "LOG_LEVEL")),
            DatabaseUrl = PostgresUrl(env, "DATABASE_URL"),
            DatabaseWorkerUrl = PostgresUrl(env, "DATABASE_WORKER_URL"),
            AwsEndpointUrl = Optional(env, "AWS_ENDPOINT_URL"),
            AwsRegion = Required(env, "AWS_REGION"),
            UsersTopicArn = Arn(env, "USERS_TOPIC_ARN"),
            QueueUrl = Required(env, "QUEUE_URL"),
            ContractsDir = Required(env, "CONTRACTS_DIR"),
            OutboxPollInterval = Millis(env, "OUTBOX_POLL_INTERVAL_MS", 200, 1),
            OutboxBatchSize = Int(env, "OUTBOX_BATCH_SIZE", 50, 1, 1000),
            OutboxMaxBackoff = Millis(env, "OUTBOX_MAX_BACKOFF_MS", 30000, 1),
            OutboxClaimLease = Millis(env, "OUTBOX_CLAIM_LEASE_MS", 30000, 1),
            HttpClientTimeout = Millis(env, "HTTP_CLIENT_TIMEOUT_MS", 3000, 1),
            ConsumerConcurrency = Int(env, "CONSUMER_CONCURRENCY", 4, 1, 10),
            SqsWaitTimeSeconds = Int(env, "SQS_WAIT_TIME_S", 10, 0, 20),
            ProfileBlockedEmailDomains = Domains(env, "PROFILE_BLOCKED_EMAIL_DOMAINS"),
            PageDefaultLimit = Int(env, "PAGE_DEFAULT_LIMIT", Math.Min(20, pageMaxLimit), 1, pageMaxLimit),
            PageMaxLimit = pageMaxLimit,
        };
    }

    private static string Required(Func<string, string?> env, string name) =>
        Optional(env, name) ?? throw new ConfigException($"{name}: required");

    private static string? Optional(Func<string, string?> env, string name)
    {
        var value = env(name)?.Trim();
        return string.IsNullOrEmpty(value) ? null : value;
    }

    private static int Int(Func<string, string?> env, string name, int? fallback, int min, int max)
    {
        var raw = Optional(env, name);
        if (raw is null)
        {
            return fallback ?? throw new ConfigException($"{name}: required");
        }
        if (!int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) || value < min || value > max)
        {
            throw new ConfigException($"{name}: must be an integer between {min} and {max}, got '{raw}'");
        }
        return value;
    }

    private static TimeSpan Millis(Func<string, string?> env, string name, int fallback, int min) =>
        TimeSpan.FromMilliseconds(Int(env, name, fallback, min, int.MaxValue));

    // Comma-separated host names (letters, digits, '-' and '.'), stored lower case.
    private static HashSet<string> Domains(Func<string, string?> env, string name)
    {
        var domains = new HashSet<string>(StringComparer.Ordinal);
        foreach (var part in (Optional(env, name) ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var domain = part.ToLowerInvariant();
            var valid = domain.Contains('.', StringComparison.Ordinal)
                && !domain.StartsWith('.') && !domain.EndsWith('.')
                && domain.All(c => c is (>= 'a' and <= 'z') or (>= '0' and <= '9') or '-' or '.');
            if (!valid)
            {
                throw new ConfigException($"{name}: '{part}' is not a domain name (comma-separated, e.g. bloqueado.test)");
            }
            domains.Add(domain);
        }
        return domains;
    }

    private static string PostgresUrl(Func<string, string?> env, string name)
    {
        var raw = Required(env, name);
        try
        {
            return App.PostgresUrl.ToConnectionString(raw);
        }
        catch (FormatException e)
        {
            throw new ConfigException($"{name}: {e.Message}", e);
        }
    }

    private static string Arn(Func<string, string?> env, string name)
    {
        var raw = Required(env, name);
        return raw.StartsWith("arn:", StringComparison.Ordinal)
            ? raw
            : throw new ConfigException($"{name}: must be an ARN (arn:aws:sns:...)");
    }

    private static LogLevel ParseLogLevel(string raw) => raw.ToUpperInvariant() switch
    {
        "DEBUG" => LogLevel.Debug,
        "INFO" => LogLevel.Information,
        "WARN" => LogLevel.Warning,
        "ERROR" => LogLevel.Error,
        _ => throw new ConfigException($"LOG_LEVEL: must be debug, info, warn or error, got '{raw}'"),
    };
}
