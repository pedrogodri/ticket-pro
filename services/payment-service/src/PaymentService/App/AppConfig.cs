using System.Globalization;
using Microsoft.Extensions.Logging;

namespace PaymentService.App;

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

    /// <summary>payments_app (RLS): used only by the HTTP side, i.e. the readiness probe.</summary>
    public required string DatabaseUrl { get; init; }

    /// <summary>payments_worker (BYPASSRLS): consumers, outbox relay and payment worker.</summary>
    public required string DatabaseWorkerUrl { get; init; }

    /// <summary>MiniStack endpoint; null means real AWS for the configured region.</summary>
    public string? AwsEndpointUrl { get; init; }
    public required string AwsRegion { get; init; }
    public required string PaymentsTopicArn { get; init; }
    public required string QueueUrl { get; init; }
    public required string ContractsDir { get; init; }

    public required TimeSpan OutboxPollInterval { get; init; }
    public required int OutboxBatchSize { get; init; }
    public required TimeSpan OutboxMaxBackoff { get; init; }
    public required TimeSpan OutboxClaimLease { get; init; }
    public required TimeSpan HttpClientTimeout { get; init; }

    public required int ConsumerConcurrency { get; init; }
    public required int SqsWaitTimeSeconds { get; init; }

    public required TimeSpan PaymentLatency { get; init; }
    public required TimeSpan PaymentLatencyJitter { get; init; }
    public required double PaymentFailureRate { get; init; }
    public required TimeSpan PaymentProviderTimeout { get; init; }
    public required TimeSpan PaymentWorkerLease { get; init; }
    public required TimeSpan PaymentWorkerPoll { get; init; }

    /// <summary>OTEL_EXPORTER_OTLP_ENDPOINT (http/https with a scheme, divergence D3); null turns tracing off.</summary>
    public Uri? OtlpEndpoint { get; init; }

    /// <summary>Reads and validates the configuration; <paramref name="env"/> is usually Environment.GetEnvironmentVariable.</summary>
    /// <exception cref="ConfigException">The first invalid or missing variable.</exception>
    public static AppConfig Load(Func<string, string?> env)
    {
        ArgumentNullException.ThrowIfNull(env);
        var config = new AppConfig
        {
            ServiceName = Required(env, "SERVICE_NAME"),
            Port = Int(env, "PORT", null, 1, 65535),
            LogLevel = ParseLogLevel(Required(env, "LOG_LEVEL")),
            DatabaseUrl = PostgresUrl(env, "DATABASE_URL"),
            DatabaseWorkerUrl = PostgresUrl(env, "DATABASE_WORKER_URL"),
            AwsEndpointUrl = Optional(env, "AWS_ENDPOINT_URL"),
            AwsRegion = Required(env, "AWS_REGION"),
            PaymentsTopicArn = Arn(env, "PAYMENTS_TOPIC_ARN"),
            QueueUrl = Required(env, "QUEUE_URL"),
            ContractsDir = Required(env, "CONTRACTS_DIR"),
            OutboxPollInterval = Millis(env, "OUTBOX_POLL_INTERVAL_MS", 200, 1),
            OutboxBatchSize = Int(env, "OUTBOX_BATCH_SIZE", 50, 1, 1000),
            OutboxMaxBackoff = Millis(env, "OUTBOX_MAX_BACKOFF_MS", 30000, 1),
            OutboxClaimLease = Millis(env, "OUTBOX_CLAIM_LEASE_MS", 30000, 1),
            HttpClientTimeout = Millis(env, "HTTP_CLIENT_TIMEOUT_MS", 3000, 1),
            ConsumerConcurrency = Int(env, "CONSUMER_CONCURRENCY", 4, 1, 10),
            SqsWaitTimeSeconds = Int(env, "SQS_WAIT_TIME_S", 10, 0, 20),
            PaymentLatency = Millis(env, "PAYMENT_LATENCY_MS", 500, 0),
            PaymentLatencyJitter = Millis(env, "PAYMENT_LATENCY_JITTER_MS", 300, 0),
            PaymentFailureRate = Rate(env, "PAYMENT_FAILURE_RATE", 0.1),
            PaymentProviderTimeout = Millis(env, "PAYMENT_PROVIDER_TIMEOUT_MS", 8000, 1),
            PaymentWorkerLease = Millis(env, "PAYMENT_WORKER_LEASE_MS", 30000, 1),
            PaymentWorkerPoll = Millis(env, "PAYMENT_WORKER_POLL_MS", 200, 1),
            OtlpEndpoint = HttpUri(env, "OTEL_EXPORTER_OTLP_ENDPOINT"),
        };
        // RFC 0003 §5.1: a lease shorter than the provider timeout would let a second worker take a
        // payment whose charge is still running.
        if (config.PaymentWorkerLease <= config.PaymentProviderTimeout)
        {
            throw new ConfigException($"PAYMENT_WORKER_LEASE_MS: must be greater than PAYMENT_PROVIDER_TIMEOUT_MS ({config.PaymentWorkerLease.TotalMilliseconds} <= {config.PaymentProviderTimeout.TotalMilliseconds})");
        }
        return config;
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

    private static double Rate(Func<string, string?> env, string name, double fallback)
    {
        var raw = Optional(env, name);
        if (raw is null)
        {
            return fallback;
        }
        if (!double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) || value is < 0 or > 1 || double.IsNaN(value))
        {
            throw new ConfigException($"{name}: must be a number between 0.0 and 1.0, got '{raw}'");
        }
        return value;
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

    private static Uri? HttpUri(Func<string, string?> env, string name)
    {
        var raw = Optional(env, name);
        if (raw is null)
        {
            return null;
        }
        return Uri.TryCreate(raw, UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https"
            ? uri
            : throw new ConfigException($"{name}: must be an http(s) URL with the scheme, e.g. http://otel-collector:4317, got '{raw}'");
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
