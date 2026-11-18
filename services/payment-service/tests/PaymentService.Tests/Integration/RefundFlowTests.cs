using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using PaymentService.Modules.Payments.Consumers;
using PaymentService.Modules.Payments.Provider;
using PaymentService.Modules.Payments.Workers;
using TicketPro.Platform;

namespace PaymentService.Tests.Integration;

/// <summary>Late approval refund (RFC 0003 §5.7, F7): RefundRequested → refunds → PaymentRefunded.</summary>
[Trait("Category", "Integration")]
[Collection("payments-db")]
public sealed class RefundFlowTests
{
    private const string Topic = "arn:aws:sns:us-east-1:000000000000:ticketpro-payments";

    private static async Task<Guid> ApprovedPayment(NpgsqlDataSource ds, Guid orderId)
    {
        await using (var conn = await ds.OpenConnectionAsync())
        await using (var tx = await conn.BeginTransactionAsync())
        {
            await new PaymentRequestedHandler().HandleAsync(Envelope.Create("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "PaymentRequested", 1,
                new { orderId = orderId.ToString(), userId = "0193a6f0-1111-7000-8000-000000000001", amountCents = 50000, currency = "BRL" }),
                conn, tx, CancellationToken.None);
            await tx.CommitAsync();
        }
        var provider = new SimulatedPaymentProvider(new ProviderOptions(TimeSpan.Zero, TimeSpan.Zero, 0), new PostgresChargeStore(ds), () => 0.5, Task.Delay);
        var worker = new PaymentWorker(ds, provider, new WorkerOptions(TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(2), Topic), NullLogger<PaymentWorker>.Instance);
        while (await worker.ProcessOnceAsync(CancellationToken.None))
        {
        }
        await using var cmd = ds.CreateCommand("SELECT id FROM payments WHERE order_id = $1");
        cmd.Parameters.Add(new NpgsqlParameter { Value = orderId });
        return (Guid)(await cmd.ExecuteScalarAsync())!;
    }

    private static async Task<string> Refund(NpgsqlDataSource ds, Guid orderId, Guid paymentId, long amountCents = 50000)
    {
        var env = Envelope.Create("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "RefundRequested", 1,
            new { orderId = orderId.ToString(), paymentId = paymentId.ToString(), amountCents, currency = "BRL", reason = "LATE_APPROVAL" });
        await using var conn = await ds.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        try
        {
            await new RefundRequestedHandler(Topic).HandleAsync(env, conn, tx, CancellationToken.None);
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
    public async Task RefundRequestedRefundsOnceAndPublishesPaymentRefunded()
    {
        await using var ds = await TestDatabase.CreateAsync();
        var order = Guid.NewGuid();
        var payment = await ApprovedPayment(ds, order);

        Assert.Equal("ok", await Refund(ds, order, payment));
        Assert.Equal("stale", await Refund(ds, order, payment));

        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM refunds WHERE payment_id = $1", payment));
        Assert.Equal(1, await Count(ds, "SELECT count(*) FROM outbox WHERE type = 'PaymentRefunded' AND payload->>'paymentId' = $1", payment.ToString()));
    }

    // The refund returns what was captured, whatever amount the request carries.
    [Fact]
    public async Task RefundUsesTheCapturedAmountNotTheRequestedOne()
    {
        await using var ds = await TestDatabase.CreateAsync();
        var order = Guid.NewGuid();
        var payment = await ApprovedPayment(ds, order);
        Assert.Equal("ok", await Refund(ds, order, payment, amountCents: 1));
        Assert.Equal(50000, await Count(ds, "SELECT amount_cents FROM refunds WHERE payment_id = $1", payment));
        Assert.Equal(50000, await Count(ds, "SELECT (payload->>'amountCents')::bigint FROM outbox WHERE type = 'PaymentRefunded' AND payload->>'paymentId' = $1", payment.ToString()));
    }

    [Fact]
    public async Task RefundForAnUnknownPaymentIsStale()
    {
        await using var ds = await TestDatabase.CreateAsync();
        Assert.Equal("stale", await Refund(ds, Guid.NewGuid(), Guid.NewGuid()));
    }
}
