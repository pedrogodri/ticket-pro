using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using PaymentService.Modules.Payments.Consumers;
using PaymentService.Modules.Payments.Provider;
using PaymentService.Modules.Payments.Workers;
using TicketPro.Platform;

namespace PaymentService.Tests.Integration;

[Trait("Category", "Integration")]
[Collection("payments-db")]
public sealed class PaymentFlowTests
{
    private const string Topic = "arn:aws:sns:us-east-1:000000000000:ticketpro-payments";
    private static readonly WorkerOptions Options = new(TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(2), Topic);

    private static Envelope PaymentRequested(Guid orderId) => Envelope.Create(
        "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "PaymentRequested", 1,
        new { orderId = orderId.ToString(), userId = "0193a6f0-1111-7000-8000-000000000001", amountCents = 50000, currency = "BRL" });

    private static async Task<string> Consume(NpgsqlDataSource ds, Envelope env)
    {
        await using var conn = await ds.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        try
        {
            await new PaymentRequestedHandler().HandleAsync(env, conn, tx, CancellationToken.None);
            await tx.CommitAsync();
            return "ok";
        }
        catch (StaleEventException)
        {
            return "stale";
        }
    }

    private static async Task<long> Count(NpgsqlDataSource ds, string sql, object arg)
    {
        await using var cmd = ds.CreateCommand(sql);
        cmd.Parameters.Add(new NpgsqlParameter { Value = arg });
        return Convert.ToInt64(await cmd.ExecuteScalarAsync(), System.Globalization.CultureInfo.InvariantCulture);
    }

    [Fact]
    public async Task ConsumerStoresPendingPaymentOnce()
    {
        await using var ds = await TestDatabase.CreateAsync();
        var order = Guid.NewGuid();
        var env = PaymentRequested(order);
        Assert.Equal("ok", await Consume(ds, env));
        Assert.Equal("stale", await Consume(ds, PaymentRequested(order)));
        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM payments WHERE order_id = $1 AND status = 'PENDING'", order));
    }

    [Fact]
    public async Task WorkerApprovesAndPublishesPaymentApproved()
    {
        await using var ds = await TestDatabase.CreateAsync();
        var order = Guid.NewGuid();
        await Consume(ds, PaymentRequested(order));
        var provider = new SimulatedPaymentProvider(new ProviderOptions(TimeSpan.Zero, TimeSpan.Zero, 0), new PostgresChargeStore(ds), () => 0.5, Task.Delay);
        var worker = new PaymentWorker(ds, provider, Options, NullLogger<PaymentWorker>.Instance);

        while (await worker.ProcessOnceAsync(CancellationToken.None))
        {
        }

        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM payments WHERE order_id = $1 AND status = 'APPROVED'", order));
        await using var cmd = ds.CreateCommand("SELECT message_id::text, correlation_id::text, type, version, occurred_at, payload::text FROM outbox WHERE payload->>'orderId' = $1");
        cmd.Parameters.Add(new NpgsqlParameter { Value = order.ToString() });
        await using var r = await cmd.ExecuteReaderAsync();
        Assert.True(await r.ReadAsync());
        Assert.Equal("PaymentApproved", r.GetString(2));
        using var payload = JsonDocument.Parse(r.GetString(5));
        var env = new Envelope(r.GetString(0), r.GetString(1), r.GetString(2), r.GetInt32(3), r.GetFieldValue<DateTimeOffset>(4), payload.RootElement.Clone());
        new ContractValidator(ContractsDir()).Validate(EnvelopeJson.Serialize(env));
    }

    [Fact]
    public async Task ProviderTimeoutFailsThePayment()
    {
        await using var ds = await TestDatabase.CreateAsync();
        var order = Guid.NewGuid();
        await Consume(ds, PaymentRequested(order));
        var slow = new SimulatedPaymentProvider(new ProviderOptions(TimeSpan.FromSeconds(10), TimeSpan.Zero, 0), new PostgresChargeStore(ds), () => 0.5, Task.Delay);
        var worker = new PaymentWorker(ds, slow, Options with { ProviderTimeout = TimeSpan.FromMilliseconds(100) }, NullLogger<PaymentWorker>.Instance);
        while (await worker.ProcessOnceAsync(CancellationToken.None))
        {
        }
        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM payments WHERE order_id = $1 AND status = 'FAILED' AND failure_reason = 'PROVIDER_TIMEOUT'", order));
        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM outbox WHERE type = 'PaymentFailed' AND payload->>'orderId' = $1", order.ToString()));
    }

    [Fact]
    public async Task TwoWorkersChargeOnce()
    {
        await using var ds = await TestDatabase.CreateAsync();
        var order = Guid.NewGuid();
        await Consume(ds, PaymentRequested(order));
        var provider = new SimulatedPaymentProvider(new ProviderOptions(TimeSpan.FromMilliseconds(200), TimeSpan.Zero, 0), new PostgresChargeStore(ds), () => 0.5, Task.Delay);
        var w1 = new PaymentWorker(ds, provider, Options, NullLogger<PaymentWorker>.Instance);
        var w2 = new PaymentWorker(ds, provider, Options, NullLogger<PaymentWorker>.Instance);
        await Task.WhenAll(w1.ProcessOnceAsync(CancellationToken.None), w2.ProcessOnceAsync(CancellationToken.None));
        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM provider_charges c JOIN payments p ON p.id = c.payment_id WHERE p.order_id = $1", order));
        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM outbox WHERE payload->>'orderId' = $1", order.ToString()));
    }

    private static string ContractsDir()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "contracts");
            if (File.Exists(Path.Combine(candidate, ".vendored.json")))
            {
                return candidate;
            }
        }
        throw new DirectoryNotFoundException("contracts/");
    }
}

[CollectionDefinition("payments-db", DisableParallelization = true)]
public sealed class PaymentsDbGroup;
