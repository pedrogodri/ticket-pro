using System.Collections.Concurrent;
using System.Diagnostics;

namespace TicketPro.Platform.Tests.Unit;

/// <summary>Registers listeners on the global ActivitySource registry, and one test needs none at all:
/// run alone.</summary>
[CollectionDefinition("tracing", DisableParallelization = true)]
public sealed class TracingGroup;

/// <summary>ADR-0012 / RFC 0004 §5.9: the messaging spans mirror shared/go (consumer + link, producer).</summary>
[Collection("tracing")]
public sealed class SpansTests
{
    private const string Stored = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /// <summary>Records every activity of the platform source and of the test's own "upstream" source.</summary>
    private sealed class Recorder : IDisposable
    {
        private readonly ActivityListener _listener;
        public ConcurrentBag<Activity> Stopped { get; } = [];
        public ActivitySource Upstream { get; } = new("spans-test-upstream");

        public Recorder()
        {
            _listener = new ActivityListener
            {
                ShouldListenTo = s => s.Name is Spans.SourceName or "spans-test-upstream",
                Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllDataAndRecorded,
                ActivityStopped = Stopped.Add,
            };
            ActivitySource.AddActivityListener(_listener);
        }

        public Activity Only(ActivityKind kind) => Assert.Single(Stopped, a => a.Source.Name == Spans.SourceName && a.Kind == kind);

        public void Dispose()
        {
            _listener.Dispose();
            Upstream.Dispose();
        }
    }

    [Fact]
    public void Consumer_continues_the_producer_trace_and_links_to_it()
    {
        using var recorder = new Recorder();
        string traceparent;
        using (var producer = recorder.Upstream.StartActivity("publish", ActivityKind.Producer)!)
        {
            traceparent = producer.Id!;
        }
        var producerContext = ActivityContext.Parse(traceparent, null);

        Spans.Consumer(traceparent, "PaymentRequested", "queue-url", "msg-1", "payment-service")!.Dispose();

        var consumer = recorder.Only(ActivityKind.Consumer);
        Assert.Equal("process PaymentRequested", consumer.DisplayName);
        Assert.Equal(producerContext.TraceId, consumer.TraceId);
        Assert.Equal(producerContext.SpanId, consumer.ParentSpanId);
        Assert.Equal(producerContext.SpanId, Assert.Single(consumer.Links).Context.SpanId);
        Assert.Equal("aws_sqs", consumer.GetTagItem("messaging.system"));
        Assert.Equal("msg-1", consumer.GetTagItem("messaging.message.id"));
        Assert.Equal("payment-service", consumer.GetTagItem("messaging.consumer.group.name"));
    }

    [Fact]
    public void Consumer_without_traceparent_is_a_root_without_links()
    {
        using var recorder = new Recorder();

        Spans.Consumer(null, "PaymentRequested", "queue-url", "msg-1", "payment-service")!.Dispose();

        var consumer = recorder.Only(ActivityKind.Consumer);
        Assert.Equal(default, consumer.ParentSpanId);
        Assert.Empty(consumer.Links);
    }

    [Fact]
    public void Producer_continues_the_stored_trace_and_propagates_itself()
    {
        using var recorder = new Recorder();
        string? published;
        using (Spans.Producer(Stored, "PaymentApproved", "topic-arn", "msg-2"))
        {
            published = TraceContext.Inject();
        }

        var producer = recorder.Only(ActivityKind.Producer);
        Assert.Equal("publish PaymentApproved", producer.DisplayName);
        Assert.Equal("4bf92f3577b34da6a3ce929d0e0e4736", producer.TraceId.ToHexString());
        Assert.Equal("00f067aa0ba902b7", producer.ParentSpanId.ToHexString());
        Assert.Equal("aws_sns", producer.GetTagItem("messaging.system"));
        Assert.Equal("topic-arn", producer.GetTagItem("messaging.destination.name"));
        Assert.Equal($"00-4bf92f3577b34da6a3ce929d0e0e4736-{producer.SpanId.ToHexString()}-01", published);
    }

    [Fact]
    public void Child_continues_a_stored_trace()
    {
        using var recorder = new Recorder();

        Spans.Child("charge payment", Stored)!.Dispose();

        var child = recorder.Only(ActivityKind.Internal);
        Assert.Equal("4bf92f3577b34da6a3ce929d0e0e4736", child.TraceId.ToHexString());
        Assert.Equal("00f067aa0ba902b7", child.ParentSpanId.ToHexString());
    }

    /// <summary>Without an SDK nothing listens: the producer returns null (the relay then publishes the
    /// stored traceparent as is) and the consumer and child still carry the remote trace, so the next
    /// outbox row keeps it.</summary>
    [Fact]
    public void Without_a_listener_the_trace_still_propagates()
    {
        Assert.Null(Spans.Producer(Stored, "PaymentApproved", "topic-arn", "msg-2"));
        using (Spans.Consumer(Stored, "PaymentRequested", "queue-url", "msg-1", "payment-service"))
        {
            Assert.StartsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-", TraceContext.Inject());
        }
        using (Spans.Child("charge payment", Stored))
        {
            Assert.StartsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-", TraceContext.Inject());
        }
        Assert.Null(Spans.Consumer(null, "PaymentRequested", "queue-url", "msg-1", "payment-service"));
    }
}
