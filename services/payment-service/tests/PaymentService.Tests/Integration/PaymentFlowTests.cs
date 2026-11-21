using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using PaymentService.App;
using PaymentService.Modules.Payments;
using PaymentService.Modules.Payments.Consumers;
using PaymentService.Modules.Payments.Provider;
using PaymentService.Modules.Payments.Workers;
using TicketPro.Platform;

namespace PaymentService.Tests.Integration;

/// <summary>RFC 0003 §5.6 end to end against a real database: consumer → PENDING, worker → provider →
/// APPROVED|FAILED + outbox, and the refund path. The SQS loop itself is covered by shared/dotnet.</summary>
[Trait("Category", "Integration")]
[Collection("payments-db")]
public sealed class PaymentFlowTests(PaymentsDatabase db) : IAsyncLifetime
{
    private const string Correlation = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";

    public Task InitializeAsync() => db.ResetAsync();

    public Task DisposeAsync() => Task.CompletedTask;

    // ---------- helpers ----------

    private static AppConfig Config(string failureRate = "0", string latencyMs = "0", string timeoutMs = "8000") =>
        TestEnv.Config(new()
        {
            ["PAYMENT_FAILURE_RATE"] = failureRate,
            ["PAYMENT_LATENCY_MS"] = latencyMs,
            ["PAYMENT_LATENCY_JITTER_MS"] = "0",
            ["PAYMENT_PROVIDER_TIMEOUT_MS"] = timeoutMs,
        });

    private PaymentWorker Worker(AppConfig config, IPaymentProvider? provider = null) =>
        new(db.Worker, provider ?? Provider(config), config, NullLogger<PaymentWorker>.Instance);

    private SimulatedPaymentProvider Provider(AppConfig config) =>
        new(db.Worker, new SimulationOptions(config.PaymentLatency, config.PaymentLatencyJitter, config.PaymentFailureRate),
            TimeProvider.System, Random.Shared.NextDouble, NullLogger<SimulatedPaymentProvider>.Instance);

    /// <summary>Runs a handler the way SqsConsumer does: inside one transaction, then commit.</summary>
    private async Task HandleAsync(IMessageHandler handler, Envelope envelope)
    {
        await using var connection = await db.Worker.OpenConnectionAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        await handler.HandleAsync(envelope, connection, transaction, CancellationToken.None);
        await transaction.CommitAsync();
    }

    private static Envelope Request(Guid orderId, long amount = 50000) =>
        PaymentEvents.Create(Correlation, PaymentEvents.PaymentRequested, new PaymentRequestedV1(orderId, Guid.CreateVersion7(), amount, "BRL"));

    private Task RequestAsync(Guid orderId) =>
        HandleAsync(new PaymentRequestedHandler(NullLogger<PaymentRequestedHandler>.Instance), Request(orderId));

    private Task<Guid> PaymentIdAsync(Guid orderId) =>
        db.ScalarAsync<Guid>("SELECT id FROM payments WHERE order_id = $1", orderId);

    private async Task<List<(string Type, JsonElement Payload, Guid Correlation)>> OutboxAsync()
    {
        await using var cmd = db.Worker.CreateCommand("SELECT type, payload::text, correlation_id FROM outbox ORDER BY id");
        await using var reader = await cmd.ExecuteReaderAsync();
        var rows = new List<(string, JsonElement, Guid)>();
        while (await reader.ReadAsync())
        {
            rows.Add((reader.GetString(0), JsonDocument.Parse(reader.GetString(1)).RootElement.Clone(), reader.GetGuid(2)));
        }
        return rows;
    }

    // ---------- PaymentRequested ----------

    [Fact]
    public async Task PaymentRequested_creates_one_pending_payment_and_charges_nothing()
    {
        var orderId = Guid.CreateVersion7();

        await RequestAsync(orderId);

        Assert.Equal("PENDING", await db.ScalarAsync<string>("SELECT status FROM payments WHERE order_id = $1", orderId));
        Assert.Equal(Guid.Parse(Correlation), await db.ScalarAsync<Guid>("SELECT correlation_id FROM payments WHERE order_id = $1", orderId));
        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM provider_charges"));
        Assert.Empty(await OutboxAsync());
    }

    [Fact]
    public async Task Second_request_for_the_same_order_with_another_message_id_is_ignored()
    {
        var orderId = Guid.CreateVersion7();

        await RequestAsync(orderId);
        await RequestAsync(orderId); // new messageId: processed_messages would not catch it, order_id UNIQUE does

        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM payments WHERE order_id = $1", orderId));
    }

    // ---------- worker + provider ----------

    [Fact]
    public async Task Approved_charge_moves_to_APPROVED_and_enqueues_PaymentApproved()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);

        Assert.Equal(1, await Worker(Config(failureRate: "0")).RunOnceAsync(CancellationToken.None));

        Assert.Equal("APPROVED", await db.ScalarAsync<string>("SELECT status FROM payments WHERE order_id = $1", orderId));
        Assert.Null(await db.ScalarAsync<DateTime?>("SELECT lease_until FROM payments WHERE order_id = $1", orderId));
        var (type, payload, correlation) = Assert.Single(await OutboxAsync());
        Assert.Equal("PaymentApproved", type);
        Assert.Equal(Guid.Parse(Correlation), correlation); // same saga correlation as the request
        Assert.Equal(orderId, payload.GetProperty("orderId").GetGuid());
        Assert.Equal(await PaymentIdAsync(orderId), payload.GetProperty("paymentId").GetGuid());
        Assert.Equal(50000, payload.GetProperty("amountCents").GetInt64());
        Assert.StartsWith("SIM-", payload.GetProperty("providerReference").GetString(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Failure_rate_1_declines_every_charge_F1()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);

        await Worker(Config(failureRate: "1")).RunOnceAsync(CancellationToken.None);

        Assert.Equal("FAILED", await db.ScalarAsync<string>("SELECT status FROM payments WHERE order_id = $1", orderId));
        Assert.Equal("DECLINED", await db.ScalarAsync<string>("SELECT failure_reason FROM payments WHERE order_id = $1", orderId));
        var (type, payload, _) = Assert.Single(await OutboxAsync());
        Assert.Equal("PaymentFailed", type);
        Assert.Equal("DECLINED", payload.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Hard_timeout_fails_with_PROVIDER_TIMEOUT_and_records_no_charge()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);

        // provider takes 5 s, the hard timeout cuts it at 200 ms
        await Worker(Config(latencyMs: "5000", timeoutMs: "200")).RunOnceAsync(CancellationToken.None);

        Assert.Equal("FAILED", await db.ScalarAsync<string>("SELECT status FROM payments WHERE order_id = $1", orderId));
        Assert.Equal("PROVIDER_TIMEOUT", await db.ScalarAsync<string>("SELECT failure_reason FROM payments WHERE order_id = $1", orderId));
        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM provider_charges"));
        Assert.Equal("PROVIDER_TIMEOUT", Assert.Single(await OutboxAsync()).Payload.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Provider_exception_fails_with_PROVIDER_ERROR()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);

        await Worker(Config(), new ThrowingProvider()).RunOnceAsync(CancellationToken.None);

        Assert.Equal("PROVIDER_ERROR", await db.ScalarAsync<string>("SELECT failure_reason FROM payments WHERE order_id = $1", orderId));
    }

    [Fact]
    public async Task Two_workers_in_parallel_charge_each_payment_exactly_once_R4()
    {
        var orders = Enumerable.Range(0, 20).Select(_ => Guid.CreateVersion7()).ToList();
        foreach (var orderId in orders)
        {
            await RequestAsync(orderId);
        }
        var config = Config(latencyMs: "50");
        var a = Worker(config);
        var b = Worker(config);

        // like the two compose replicas: run rounds until no PENDING is left
        while (await db.ScalarAsync<long>("SELECT count(*) FROM payments WHERE status = 'PENDING'") > 0)
        {
            await Task.WhenAll(a.RunOnceAsync(CancellationToken.None), b.RunOnceAsync(CancellationToken.None));
        }

        Assert.Equal(20L, await db.ScalarAsync<long>("SELECT count(*) FROM provider_charges"));
        Assert.Equal(20, (await OutboxAsync()).Count);
        Assert.Equal(20L, await db.ScalarAsync<long>("SELECT count(DISTINCT payload->>'paymentId') FROM outbox"));
    }

    [Fact]
    public async Task Pending_payment_under_a_live_lease_is_not_taken()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);
        await db.ExecAsync("UPDATE payments SET lease_until = now() + interval '1 minute' WHERE order_id = $1", orderId);

        Assert.Equal(0, await Worker(Config()).RunOnceAsync(CancellationToken.None));
    }

    [Fact]
    public async Task Worker_that_died_after_charging_is_resumed_without_a_second_charge()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);
        var paymentId = await PaymentIdAsync(orderId);
        // The dead worker got an approval from the provider but never wrote the result; its lease expired.
        await db.ExecAsync(
            "INSERT INTO provider_charges (payment_id, result, provider_reference) VALUES ($1, 'APPROVED', 'SIM-FIRST-CALL')", paymentId);
        await db.ExecAsync("UPDATE payments SET lease_until = now() - interval '1 second' WHERE id = $1", paymentId);

        // even with failure rate 1, the provider replays the recorded approval instead of charging again
        await Worker(Config(failureRate: "1")).RunOnceAsync(CancellationToken.None);

        Assert.Equal("APPROVED", await db.ScalarAsync<string>("SELECT status FROM payments WHERE id = $1", paymentId));
        Assert.Equal("SIM-FIRST-CALL", await db.ScalarAsync<string>("SELECT provider_reference FROM payments WHERE id = $1", paymentId));
        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM provider_charges WHERE payment_id = $1", paymentId));
    }

    // ---------- RefundRequested ----------

    private async Task<(Guid OrderId, Guid PaymentId)> ApprovedPaymentAsync()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId);
        await Worker(Config()).RunOnceAsync(CancellationToken.None);
        await db.ExecAsync("DELETE FROM outbox");
        return (orderId, await PaymentIdAsync(orderId));
    }

    private Task RefundAsync(Guid orderId, Guid paymentId, long amountInMessage = 50000) =>
        HandleAsync(new RefundRequestedHandler(Config(), NullLogger<RefundRequestedHandler>.Instance),
            PaymentEvents.Create(Correlation, PaymentEvents.RefundRequested,
                new RefundRequestedV1(orderId, paymentId, amountInMessage, "BRL", "LATE_APPROVAL")));

    [Fact]
    public async Task Refund_of_an_approved_payment_happens_once_F7()
    {
        var (orderId, paymentId) = await ApprovedPaymentAsync();

        await RefundAsync(orderId, paymentId);
        // second command for the same payment (new messageId): stale, nothing refunded or published
        await Assert.ThrowsAsync<StaleEventException>(() => RefundAsync(orderId, paymentId));

        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM refunds WHERE payment_id = $1", paymentId));
        var (type, payload, correlation) = Assert.Single(await OutboxAsync());
        Assert.Equal("PaymentRefunded", type);
        Assert.Equal(Guid.Parse(Correlation), correlation);
        Assert.Equal(paymentId, payload.GetProperty("paymentId").GetGuid());
        Assert.Equal(await db.ScalarAsync<Guid>("SELECT id FROM refunds WHERE payment_id = $1", paymentId),
            payload.GetProperty("refundId").GetGuid());
    }

    [Fact]
    public async Task Refund_uses_the_captured_amount_not_the_one_in_the_message()
    {
        var (orderId, paymentId) = await ApprovedPaymentAsync(); // captured 50000

        await RefundAsync(orderId, paymentId, amountInMessage: 99999);

        Assert.Equal(50000L, await db.ScalarAsync<long>("SELECT amount_cents FROM refunds WHERE payment_id = $1", paymentId));
        Assert.Equal(50000, Assert.Single(await OutboxAsync()).Payload.GetProperty("amountCents").GetInt64());
    }

    [Fact]
    public async Task PaymentRequested_with_zero_amount_is_poison_not_a_retry()
    {
        await Assert.ThrowsAsync<InvalidMessageException>(() => HandleAsync(
            new PaymentRequestedHandler(NullLogger<PaymentRequestedHandler>.Instance), Request(Guid.CreateVersion7(), amount: 0)));

        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM payments"));
    }

    [Fact]
    public async Task Refund_of_a_payment_that_is_not_approved_is_stale()
    {
        var orderId = Guid.CreateVersion7();
        await RequestAsync(orderId); // still PENDING

        var pendingId = await PaymentIdAsync(orderId);

        await Assert.ThrowsAsync<StaleEventException>(() => RefundAsync(orderId, Guid.CreateVersion7())); // unknown
        await Assert.ThrowsAsync<StaleEventException>(() => RefundAsync(orderId, pendingId));             // not APPROVED
        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM refunds"));
    }

    private sealed class ThrowingProvider : IPaymentProvider
    {
        public Task<ChargeResult> ChargeAsync(Guid paymentId, long amountCents, string currency, CancellationToken ct) =>
            throw new HttpRequestException("provider returned 500");
    }
}
