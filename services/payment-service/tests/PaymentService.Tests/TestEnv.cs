using PaymentService.App;

namespace PaymentService.Tests;

/// <summary>Paths and configuration shared by the tests. Everything is read from this service's own
/// folder (ADR-0016): the tests never look outside services/payment-service.</summary>
internal static class TestEnv
{
    public static string ServiceRoot { get; } = FindServiceRoot();

    public static string ContractsDir => Path.Combine(ServiceRoot, "contracts");

    public static string MigrationsDir => Path.Combine(ServiceRoot, "migrations");

    /// <summary>KEY=VALUE pairs of the real .env.example, so a default drifting from it breaks a test.</summary>
    public static Dictionary<string, string> EnvExample() =>
        File.ReadAllLines(Path.Combine(ServiceRoot, ".env.example"))
            .Select(l => l.Trim())
            .Where(l => l.Length > 0 && !l.StartsWith('#') && l.Contains('=', StringComparison.Ordinal))
            .Select(l => l.Split('=', 2))
            .ToDictionary(p => p[0], p => p[1]);

    /// <summary>AppConfig from .env.example with <paramref name="overrides"/> applied (null value removes the key).</summary>
    public static AppConfig Config(Dictionary<string, string?>? overrides = null)
    {
        var env = EnvExample().ToDictionary(kv => kv.Key, kv => (string?)kv.Value);
        foreach (var (key, value) in overrides ?? [])
        {
            env[key] = value;
        }
        return AppConfig.Load(name => env.GetValueOrDefault(name));
    }

    private static string FindServiceRoot()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            if (File.Exists(Path.Combine(dir.FullName, "PaymentService.sln")))
            {
                return dir.FullName;
            }
        }
        throw new DirectoryNotFoundException("PaymentService.sln not found above " + AppContext.BaseDirectory);
    }
}
