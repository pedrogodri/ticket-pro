using Microsoft.Extensions.Logging;
using PaymentService.App;

namespace PaymentService.Tests.Unit;

public sealed class AppConfigTests
{
    [Fact]
    public void Env_example_loads_with_the_rfc_defaults()
    {
        var config = TestEnv.Config();

        Assert.Equal("payment-service", config.ServiceName);
        Assert.Equal(8080, config.Port);
        Assert.Equal(LogLevel.Information, config.LogLevel);
        Assert.Equal(0.1, config.PaymentFailureRate);
        Assert.Equal(TimeSpan.FromMilliseconds(500), config.PaymentLatency);
        Assert.Equal(TimeSpan.FromMilliseconds(8000), config.PaymentProviderTimeout);
        Assert.Equal(TimeSpan.FromMilliseconds(30000), config.PaymentWorkerLease);
        Assert.Equal(10, config.SqsWaitTimeSeconds);
        Assert.Contains("Username=payments_worker", config.DatabaseWorkerUrl, StringComparison.Ordinal);
        Assert.Equal(new Uri("http://otel-collector:4317"), config.OtlpEndpoint);
    }

    [Fact]
    public void Tracing_is_off_without_an_otlp_endpoint()
    {
        Assert.Null(TestEnv.Config(new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = null }).OtlpEndpoint);
    }

    [Fact]
    public void Optional_tuning_falls_back_to_rfc_defaults_when_absent()
    {
        var config = TestEnv.Config(new() { ["PAYMENT_WORKER_POLL_MS"] = null, ["CONSUMER_CONCURRENCY"] = null });

        Assert.Equal(TimeSpan.FromMilliseconds(200), config.PaymentWorkerPoll);
        Assert.Equal(4, config.ConsumerConcurrency);
    }

    [Theory]
    [InlineData("SERVICE_NAME")]
    [InlineData("PORT")]
    [InlineData("LOG_LEVEL")]
    [InlineData("DATABASE_URL")]
    [InlineData("DATABASE_WORKER_URL")]
    [InlineData("AWS_REGION")]
    [InlineData("PAYMENTS_TOPIC_ARN")]
    [InlineData("QUEUE_URL")]
    [InlineData("CONTRACTS_DIR")]
    public void Missing_required_variable_names_it(string variable)
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { [variable] = null }));

        Assert.StartsWith(variable + ":", e.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("PAYMENT_FAILURE_RATE", "1.5")]
    [InlineData("PAYMENT_FAILURE_RATE", "-0.1")]
    [InlineData("PAYMENT_FAILURE_RATE", "abc")]
    [InlineData("SQS_WAIT_TIME_S", "21")]
    [InlineData("PORT", "0")]
    [InlineData("CONSUMER_CONCURRENCY", "11")]
    [InlineData("PAYMENT_LATENCY_MS", "-1")]
    [InlineData("LOG_LEVEL", "verbose")]
    [InlineData("DATABASE_URL", "mysql://u:p@h/db")]
    [InlineData("DATABASE_WORKER_URL", "postgres://host-only")]
    [InlineData("PAYMENTS_TOPIC_ARN", "ticketpro-payments")]
    [InlineData("OTEL_EXPORTER_OTLP_ENDPOINT", "otel-collector:4317")] // no scheme (divergence D3)
    public void Invalid_value_is_rejected_with_the_variable_name(string variable, string value)
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { [variable] = value }));

        Assert.StartsWith(variable + ":", e.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("8000", "8000")] // equal
    [InlineData("7000", "8000")] // shorter
    public void Lease_must_exceed_the_provider_timeout(string lease, string timeout)
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new()
        {
            ["PAYMENT_WORKER_LEASE_MS"] = lease,
            ["PAYMENT_PROVIDER_TIMEOUT_MS"] = timeout,
        }));

        Assert.StartsWith("PAYMENT_WORKER_LEASE_MS:", e.Message, StringComparison.Ordinal);
        Assert.Contains("PAYMENT_PROVIDER_TIMEOUT_MS", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void F7_values_without_a_larger_lease_are_refused()
    {
        // What scripts/f7-late-response.sh passes today (latency 20 s, timeout = latency + 10 s), with the
        // default lease of .env.example: 30000 is not > 30000, so the script must also pass the lease (P03).
        Assert.Throws<ConfigException>(() => TestEnv.Config(new()
        {
            ["PAYMENT_LATENCY_MS"] = "20000",
            ["PAYMENT_PROVIDER_TIMEOUT_MS"] = "30000",
        }));
    }

    [Fact]
    public void F7_settings_are_accepted()
    {
        // F7 once the script also passes PAYMENT_WORKER_LEASE_MS (plan P03: latency + 20 s).
        var config = TestEnv.Config(new()
        {
            ["PAYMENT_LATENCY_MS"] = "20000",
            ["PAYMENT_PROVIDER_TIMEOUT_MS"] = "30000",
            ["PAYMENT_WORKER_LEASE_MS"] = "40000",
            ["PAYMENT_FAILURE_RATE"] = "0",
        });

        Assert.Equal(0, config.PaymentFailureRate);
    }
}
