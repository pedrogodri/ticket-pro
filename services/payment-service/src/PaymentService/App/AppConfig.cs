using System.Globalization;

namespace PaymentService.App;

/// <summary>Validated runtime configuration read from environment variables (docs/padroes.md §8).</summary>
public sealed record AppConfig(string ServiceName, int Port, string LogLevel)
{
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

        return new AppConfig(serviceName, port, logLevel);
    }

    private static string Required(IReadOnlyDictionary<string, string?> env, string name) =>
        env.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value)
            ? value.Trim()
            : throw new InvalidOperationException($"{name} is required");
}
