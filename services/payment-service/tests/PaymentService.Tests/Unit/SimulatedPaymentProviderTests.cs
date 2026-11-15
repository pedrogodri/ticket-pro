using PaymentService.Modules.Payments.Provider;

namespace PaymentService.Tests.Unit;

/// <summary>R5: latency and failure rate follow the env vars; charges are idempotent per paymentId (RFC 0003 §5.6).</summary>
public sealed class SimulatedPaymentProviderTests
{
    private sealed class RecordingDelay
    {
        public List<TimeSpan> Waits { get; } = [];

        public Task Wait(TimeSpan d, CancellationToken ct)
        {
            Waits.Add(d);
            return Task.Delay(TimeSpan.Zero, ct);
        }
    }

    private static SimulatedPaymentProvider Provider(double failureRate, Func<double> rnd, RecordingDelay delay, IChargeStore? store = null) =>
        new(new ProviderOptions(TimeSpan.FromMilliseconds(500), TimeSpan.FromMilliseconds(300), failureRate),
            store ?? new InMemoryChargeStore(), rnd, delay.Wait);

    [Fact]
    public async Task ZeroFailureRate_Approves()
    {
        var result = await Provider(0, () => 0.5, new RecordingDelay()).ChargeAsync(Guid.NewGuid(), 50000, CancellationToken.None);
        Assert.True(result.Approved);
        Assert.StartsWith("SIM-", result.ProviderReference, StringComparison.Ordinal);
    }

    [Fact]
    public async Task FullFailureRate_Declines()
    {
        var result = await Provider(1, () => 0.5, new RecordingDelay()).ChargeAsync(Guid.NewGuid(), 50000, CancellationToken.None);
        Assert.False(result.Approved);
        Assert.Equal("DECLINED", result.Reason);
    }

    [Theory]
    [InlineData(0.0, 200)]
    [InlineData(0.5, 500)]
    [InlineData(0.999999, 800)]
    public async Task Latency_IsBaseMinusOrPlusJitter(double rnd, double expectedMs)
    {
        var delay = new RecordingDelay();
        await Provider(0, () => rnd, delay).ChargeAsync(Guid.NewGuid(), 1, CancellationToken.None);
        Assert.InRange(delay.Waits.Single().TotalMilliseconds, expectedMs - 1, expectedMs + 1);
    }

    [Fact]
    public async Task SecondChargeWithSamePaymentId_ReturnsFirstResultWithoutWaiting()
    {
        var store = new InMemoryChargeStore();
        var delay = new RecordingDelay();
        var id = Guid.NewGuid();
        var first = await Provider(0, () => 0.5, delay, store).ChargeAsync(id, 1, CancellationToken.None);
        var second = await Provider(1, () => 0.5, delay, store).ChargeAsync(id, 1, CancellationToken.None);
        Assert.Equal(first, second);
        Assert.Single(delay.Waits);
    }

    [Fact]
    public async Task CancelledWait_PropagatesForTheHardTimeout()
    {
        var provider = new SimulatedPaymentProvider(
            new ProviderOptions(TimeSpan.FromSeconds(10), TimeSpan.Zero, 0), new InMemoryChargeStore(), () => 0.5, Task.Delay);
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => provider.ChargeAsync(Guid.NewGuid(), 1, cts.Token));
    }
}
