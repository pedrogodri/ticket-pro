using System.Diagnostics;
using OpenTelemetry.Trace;
using PaymentService.App;

namespace PaymentService.Tests.Unit;

/// <summary>ADR-0012: polling (worker claim, outbox relay) must not flood Jaeger, message work must be traced.</summary>
public sealed class TracingTests
{
    private static readonly QuietPollingSampler Sampler = new();

    private static SamplingDecision Decide(ActivityKind kind, ActivityContext parent = default) =>
        Sampler.ShouldSample(new SamplingParameters(parent, ActivityTraceId.CreateRandom(), "span", kind)).Decision;

    private static ActivityContext Parent(bool sampled) =>
        new(ActivityTraceId.CreateRandom(), ActivitySpanId.CreateRandom(), sampled ? ActivityTraceFlags.Recorded : ActivityTraceFlags.None, isRemote: true);

    [Fact]
    public void A_database_call_without_a_parent_is_polling_and_is_dropped()
    {
        Assert.Equal(SamplingDecision.Drop, Decide(ActivityKind.Client));
    }

    [Theory]
    [InlineData(ActivityKind.Consumer)]
    [InlineData(ActivityKind.Producer)]
    [InlineData(ActivityKind.Internal)]
    public void Message_spans_without_a_parent_are_recorded(ActivityKind kind)
    {
        Assert.Equal(SamplingDecision.RecordAndSample, Decide(kind));
    }

    [Fact]
    public void Calls_inside_a_sampled_trace_follow_their_parent()
    {
        Assert.Equal(SamplingDecision.RecordAndSample, Decide(ActivityKind.Client, Parent(sampled: true)));
        Assert.Equal(SamplingDecision.Drop, Decide(ActivityKind.Client, Parent(sampled: false)));
    }
}
