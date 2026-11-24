using Npgsql;
using PaymentService.App;
using PaymentService.Modules.Payments;
using PaymentService.Modules.Payments.Provider;
using TicketPro.Platform;

namespace PaymentService.Tests.Unit;

public sealed class PostgresUrlTests
{
    [Fact]
    public void Converts_the_env_url_into_an_npgsql_connection_string()
    {
        var cs = new NpgsqlConnectionStringBuilder(
            PostgresUrl.ToConnectionString("postgres://payments_worker:p%40ss@payments-db:5433/payments?sslmode=disable"));

        Assert.Equal("payments-db", cs.Host);
        Assert.Equal(5433, cs.Port);
        Assert.Equal("payments_worker", cs.Username);
        Assert.Equal("p@ss", cs.Password);
        Assert.Equal("payments", cs.Database);
        Assert.Equal(SslMode.Disable, cs.SslMode);
    }

    [Theory]
    [InlineData("verify-full", SslMode.VerifyFull)]
    [InlineData("require", SslMode.Require)]
    public void Accepts_libpq_sslmode_spelling(string sslmode, SslMode expected)
    {
        var cs = new NpgsqlConnectionStringBuilder(PostgresUrl.ToConnectionString($"postgres://u:p@h/db?sslmode={sslmode}"));

        Assert.Equal(expected, cs.SslMode);
    }

    [Fact]
    public void Invalid_sslmode_is_a_config_error_not_a_crash()
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { ["DATABASE_URL"] = "postgres://u:p@h/db?sslmode=banana" }));

        Assert.StartsWith("DATABASE_URL:", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Default_port_is_5432()
    {
        var cs = new NpgsqlConnectionStringBuilder(PostgresUrl.ToConnectionString("postgres://u:p@h/db"));

        Assert.Equal(5432, cs.Port);
    }
}

public sealed class HealthTests
{
    [Fact]
    public async Task Ready_is_200_with_broker_degraded_when_only_the_broker_is_down()
    {
        var (code, body) = await HealthEndpoints.ReadinessAsync(_ => Task.FromResult(true), () => false, CancellationToken.None);

        Assert.Equal(200, code);
        Assert.Equal("""{"status":"up","checks":{"db":"up","broker":"degraded"}}""", body);
    }

    [Fact]
    public async Task Ready_is_503_when_the_database_is_down()
    {
        var (code, body) = await HealthEndpoints.ReadinessAsync(_ => Task.FromResult(false), () => true, CancellationToken.None);

        Assert.Equal(503, code);
        Assert.Equal("""{"status":"down","checks":{"db":"down","broker":"up"}}""", body);
    }
}

public sealed class SimulationOptionsTests
{
    [Theory]
    [InlineData(0.0, 0.0, true)]
    [InlineData(0.0, 0.999, true)]
    [InlineData(1.0, 0.0, false)]
    [InlineData(1.0, 0.999, false)]
    [InlineData(0.5, 0.49, false)]
    [InlineData(0.5, 0.5, true)]
    public void Failure_rate_decides_approval(double rate, double draw, bool approved)
    {
        var options = new SimulationOptions(TimeSpan.Zero, TimeSpan.Zero, rate);

        Assert.Equal(approved, options.NextApproved(() => draw));
    }

    [Theory]
    [InlineData(0.0, 200)]   // latency - jitter
    [InlineData(0.5, 500)]   // latency
    [InlineData(0.999, 799)] // just under latency + jitter
    public void Delay_is_latency_plus_or_minus_jitter(double draw, int expectedMs)
    {
        var options = new SimulationOptions(TimeSpan.FromMilliseconds(500), TimeSpan.FromMilliseconds(300), 0);

        Assert.Equal(expectedMs, (int)options.NextDelay(() => draw).TotalMilliseconds);
    }

    [Fact]
    public void Delay_never_goes_negative()
    {
        var options = new SimulationOptions(TimeSpan.FromMilliseconds(100), TimeSpan.FromMilliseconds(300), 0);

        Assert.Equal(TimeSpan.Zero, options.NextDelay(() => 0.0));
    }
}

/// <summary>What this service publishes must pass the same schema validation its consumers apply.</summary>
public sealed class PublishedContractTests
{
    private static readonly ContractValidator Validator = new(TestEnv.ContractsDir);
    private const string Correlation = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";

    [Fact]
    public void PaymentApproved_matches_its_schema()
    {
        var envelope = PaymentEvents.Create(Correlation, PaymentEvents.PaymentApproved, new PaymentApprovedV1(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 50000, "BRL", "SIM-20261120-ABC", PaymentEvents.Timestamp(DateTimeOffset.UtcNow)));

        Validator.Validate(EnvelopeJson.Serialize(envelope));
    }

    [Theory]
    [InlineData(FailureReasons.Declined)]
    [InlineData(FailureReasons.ProviderError)]
    [InlineData(FailureReasons.ProviderTimeout)]
    public void PaymentFailed_matches_its_schema(string reason)
    {
        var envelope = PaymentEvents.Create(Correlation, PaymentEvents.PaymentFailed, new PaymentFailedV1(
            Guid.CreateVersion7(), Guid.CreateVersion7(), reason));

        Validator.Validate(EnvelopeJson.Serialize(envelope));
    }

    [Fact]
    public void PaymentRefunded_matches_its_schema()
    {
        var envelope = PaymentEvents.Create(Correlation, PaymentEvents.PaymentRefunded, new PaymentRefundedV1(
            Guid.CreateVersion7(), Guid.CreateVersion7(), Guid.CreateVersion7(), 50000, "BRL"));

        Validator.Validate(EnvelopeJson.Serialize(envelope));
    }

    [Theory]
    [InlineData("PaymentRequested")]
    [InlineData("RefundRequested")]
    public void Consumed_examples_deserialize_into_our_records(string type)
    {
        var json = File.ReadAllText(Path.Combine(TestEnv.ContractsDir, "examples", type + ".v1.json"));
        Validator.Validate(json);
        var envelope = EnvelopeJson.Unwrap(json);

        object payload = type == "PaymentRequested"
            ? PaymentEvents.Read<PaymentRequestedV1>(envelope)
            : PaymentEvents.Read<RefundRequestedV1>(envelope);

        Assert.NotNull(payload);
    }
}
