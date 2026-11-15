using PaymentService.App;

namespace PaymentService.Tests;

public class AppConfigTests
{
    private static Dictionary<string, string?> ValidEnv() => new()
    {
        ["SERVICE_NAME"] = "payment-service",
        ["PORT"] = "8080",
        ["LOG_LEVEL"] = "info",
        ["DATABASE_URL"] = "postgres://payments_app:secret@payments-db:5432/payments",
        ["DATABASE_WORKER_URL"] = "postgres://payments_worker:secret@payments-db:5432/payments",
        ["PAYMENTS_TOPIC_ARN"] = "arn:aws:sns:us-east-1:000000000000:ticketpro-payments",
    };

    [Theory]
    [InlineData("SERVICE_NAME")]
    [InlineData("PORT")]
    [InlineData("LOG_LEVEL")]
    [InlineData("DATABASE_URL")]
    [InlineData("DATABASE_WORKER_URL")]
    [InlineData("PAYMENTS_TOPIC_ARN")]
    public void MissingRequiredVariable_ThrowsNamingIt(string name)
    {
        var env = ValidEnv();
        env.Remove(name);
        var ex = Assert.Throws<InvalidOperationException>(() => AppConfig.FromEnvironment(env));
        Assert.Contains(name, ex.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("PORT", "abc")]
    [InlineData("LOG_LEVEL", "verbose")]
    [InlineData("OUTBOX_BATCH_SIZE", "0")]
    [InlineData("OUTBOX_POLL_INTERVAL_MS", "soon")]
    [InlineData("DATABASE_URL", "mysql://x")]
    public void InvalidValue_ThrowsNamingIt(string name, string value)
    {
        var env = ValidEnv();
        env[name] = value;
        var ex = Assert.Throws<InvalidOperationException>(() => AppConfig.FromEnvironment(env));
        Assert.Contains(name, ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ValidEnv_UsesRfc0003Defaults()
    {
        var config = AppConfig.FromEnvironment(ValidEnv());
        Assert.Equal("payment-service", config.ServiceName);
        Assert.Equal(8080, config.Port);
        Assert.Equal("info", config.LogLevel);
        Assert.Equal("Host=payments-db;Port=5432;Username=payments_app;Password=secret;Database=payments", config.DatabaseUrl);
        Assert.Equal("Host=payments-db;Port=5432;Username=payments_worker;Password=secret;Database=payments", config.DatabaseWorkerUrl);
        Assert.Equal("arn:aws:sns:us-east-1:000000000000:ticketpro-payments", config.TopicArn);
        Assert.Equal(50, config.Outbox.BatchSize);
        Assert.Equal(TimeSpan.FromMilliseconds(200), config.Outbox.PollInterval);
        Assert.Equal(TimeSpan.FromSeconds(30), config.Outbox.MaxBackoff);
        Assert.Equal(TimeSpan.FromSeconds(30), config.Outbox.ClaimLease);
        Assert.Equal(TimeSpan.FromSeconds(3), config.HttpClientTimeout);
    }

    [Theory]
    [InlineData("postgres://u:p@h:5432/d", "Host=h;Port=5432;Username=u;Password=p;Database=d")]
    [InlineData("postgresql://u:p%40ss@h/d?sslmode=disable", "Host=h;Port=5432;Username=u;Password=p@ss;Database=d")]
    public void PostgresUrl_IsConvertedToNpgsql(string url, string expected) =>
        Assert.Equal(expected, AppConfig.ToNpgsqlConnectionString(url, "DATABASE_URL"));
}
