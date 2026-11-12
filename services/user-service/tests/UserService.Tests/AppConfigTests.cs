using UserService.App;

namespace UserService.Tests;

public class AppConfigTests
{
    private static Dictionary<string, string?> Env(params (string Key, string Value)[] pairs) =>
        pairs.ToDictionary(p => p.Key, p => (string?)p.Value);

    [Fact]
    public void MissingPort_ThrowsNamingVariable()
    {
        var ex = Assert.Throws<InvalidOperationException>(() =>
            AppConfig.FromEnvironment(Env(("SERVICE_NAME", "user-service"), ("LOG_LEVEL", "info"))));
        Assert.Contains("PORT", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void InvalidPort_ThrowsNamingVariable()
    {
        var ex = Assert.Throws<InvalidOperationException>(() =>
            AppConfig.FromEnvironment(Env(("SERVICE_NAME", "user-service"), ("PORT", "abc"), ("LOG_LEVEL", "info"))));
        Assert.Contains("PORT", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void MissingServiceName_ThrowsNamingVariable()
    {
        var ex = Assert.Throws<InvalidOperationException>(() =>
            AppConfig.FromEnvironment(Env(("PORT", "8080"), ("LOG_LEVEL", "info"))));
        Assert.Contains("SERVICE_NAME", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void InvalidLogLevel_ThrowsNamingVariable()
    {
        var ex = Assert.Throws<InvalidOperationException>(() =>
            AppConfig.FromEnvironment(Env(("SERVICE_NAME", "user-service"), ("PORT", "8080"), ("LOG_LEVEL", "verbose"))));
        Assert.Contains("LOG_LEVEL", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ValidEnv_BuildsConfig()
    {
        var config = AppConfig.FromEnvironment(Env(("SERVICE_NAME", "user-service"), ("PORT", "8080"), ("LOG_LEVEL", "info")));
        Assert.Equal(new AppConfig("user-service", 8080, "info"), config);
    }
}
