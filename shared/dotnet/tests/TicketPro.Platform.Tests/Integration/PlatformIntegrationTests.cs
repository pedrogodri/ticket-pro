using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using Amazon.SQS;
using Amazon.SQS.Model;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using TicketPro.Platform.Tests.TestEnv;

namespace TicketPro.Platform.Tests.Integration;

[Trait("Category", "Integration")]
[Collection("integration")] // shared containers: run sequentially
public sealed class PlatformIntegrationTests
{
    private const string TopicArn = "arn:aws:sns:us-east-1:000000000000:ticketpro-test";
    private const string MsgId = "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55";

    private static Envelope NewEnvelope() =>
        Envelope.Create("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "Ping", 1, new { n = 1 });

    private static async Task EnqueueAsync(NpgsqlDataSource ds, string topic, Envelope env, bool commit = true)
    {
        await using var conn = await ds.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        await OutboxWriter.EnqueueAsync(conn, tx, topic, env, CancellationToken.None);
        if (commit)
        {
            await tx.CommitAsync();
        }
        else
        {
            await tx.RollbackAsync();
        }
    }

    private static OutboxRelay NewRelay(NpgsqlDataSource ds, IOutboxPublisher publisher, int batchSize = 10) =>
        new(ds, publisher, new OutboxRelayOptions
        {
            BatchSize = batchSize,
            PollInterval = TimeSpan.FromMilliseconds(50),
            MaxBackoff = TimeSpan.FromSeconds(1),
            ClaimLease = TimeSpan.FromSeconds(30),
        }, NullLogger<OutboxRelay>.Instance);

    private static ActivitySource TracingSource(out ActivityListener listener)
    {
        listener = new ActivityListener
        {
            ShouldListenTo = s => s.Name == "integration",
            Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllDataAndRecorded,
        };
        ActivitySource.AddActivityListener(listener);
        return new ActivitySource("integration");
    }

    [Fact]
    public async Task P07_RecordIsFreshOncePerConsumerAndMessage()
    {
        await using var ds = await Containers.PostgresAsync();
        async Task<bool> Record(string consumer)
        {
            await using var conn = await ds.OpenConnectionAsync();
            await using var tx = await conn.BeginTransactionAsync();
            var fresh = await ProcessedMessages.RecordAsync(conn, tx, consumer, MsgId, CancellationToken.None);
            await tx.CommitAsync();
            return fresh;
        }
        Assert.True(await Record("c1"));
        Assert.False(await Record("c1"));
        Assert.True(await Record("c2"));
    }

    [Fact]
    public async Task P08_EnqueueIsPartOfTheCallerTransaction()
    {
        await using var ds = await Containers.PostgresAsync();
        var committed = NewEnvelope();
        await EnqueueAsync(ds, TopicArn, committed);
        await EnqueueAsync(ds, TopicArn, NewEnvelope(), commit: false);
        Assert.Equal(1, await Containers.CountAsync(ds, "SELECT count(*) FROM outbox"));

        await using (var cmd = ds.CreateCommand("SELECT type, version, correlation_id::text, payload::text, traceparent FROM outbox WHERE message_id = $1"))
        {
            cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(committed.MessageId) });
            await using var r = await cmd.ExecuteReaderAsync();
            Assert.True(await r.ReadAsync());
            Assert.Equal("Ping", r.GetString(0));
            Assert.Equal(1, r.GetInt32(1));
            Assert.Equal(committed.CorrelationId, r.GetString(2));
            Assert.Equal("""{"n": 1}""", r.GetString(3));
            Assert.True(r.IsDBNull(4));
        }

        using var source = TracingSource(out var listener);
        using (listener)
        using (source.StartActivity("op"))
        {
            var traced = NewEnvelope();
            await EnqueueAsync(ds, TopicArn, traced);
            Assert.Equal(1, await Containers.CountAsync(ds, "SELECT count(*) FROM outbox WHERE message_id = $1 AND traceparent LIKE '00-%'", Guid.Parse(traced.MessageId)));
        }
    }

    [Fact]
    public async Task P12_RelayAccumulatesWhileBrokerIsDownAndDrainsAfter()
    {
        await using var ds = await Containers.PostgresAsync();
        var ministack = await Containers.MiniStackAsync();
        using var sqs = await Containers.SqsAsync();
        using var sns = await Containers.SnsAsync();
        var (queueUrl, _) = await Containers.QueueAsync(sqs, "relay", 3);
        var topic = await Containers.TopicAsync(sns, sqs, "relay", queueUrl);

        var envs = new[] { NewEnvelope(), NewEnvelope(), NewEnvelope() };
        using var source = TracingSource(out var listener);
        using (listener)
        using (source.StartActivity("op"))
        {
            await EnqueueAsync(ds, topic, envs[0]);
        }
        await EnqueueAsync(ds, topic, envs[1]);
        await EnqueueAsync(ds, topic, envs[2]);

        var relay = NewRelay(ds, new SnsOutboxPublisher(sns));
        await ministack.PauseAsync();
        try
        {
            await Assert.ThrowsAnyAsync<Exception>(() => relay.RunOnceAsync(CancellationToken.None));
            Assert.False(relay.BrokerUp);
            Assert.Equal(3, await Containers.CountAsync(ds, "SELECT count(*) FROM outbox WHERE published_at IS NULL"));
            Assert.True(await Containers.CountAsync(ds, "SELECT coalesce(sum(attempts),0) FROM outbox") >= 1);
        }
        finally
        {
            await ministack.UnpauseAsync();
        }

        using var cts = new CancellationTokenSource();
        var run = relay.RunAsync(cts.Token);
        var deadline = DateTime.UtcNow.AddSeconds(30);
        while (await Containers.CountAsync(ds, "SELECT count(*) FROM outbox WHERE published_at IS NULL") > 0)
        {
            Assert.True(DateTime.UtcNow < deadline, "relay did not drain within 30s after the broker came back");
            await Task.Delay(100);
        }
        await cts.CancelAsync();
        await run;
        Assert.True(relay.BrokerUp);

        var got = new Dictionary<string, Message>();
        while (got.Count < 3 && DateTime.UtcNow < deadline.AddSeconds(10))
        {
            var resp = await sqs.ReceiveMessageAsync(new ReceiveMessageRequest
            {
                QueueUrl = queueUrl,
                MaxNumberOfMessages = 10,
                WaitTimeSeconds = 1,
                MessageAttributeNames = ["All"],
            });
            foreach (var m in resp.Messages ?? [])
            {
                got[EnvelopeJson.Unwrap(m.Body).MessageId] = m;
            }
        }
        for (var i = 0; i < envs.Length; i++)
        {
            Assert.True(got.TryGetValue(envs[i].MessageId, out var m), $"message {i} not delivered");
            string? Attr(string k) => m!.MessageAttributes?.TryGetValue(k, out var v) == true ? v.StringValue : null;
            Assert.Equal("Ping", Attr("type"));
            Assert.Equal("1", Attr("version"));
            Assert.Equal(envs[i].CorrelationId, Attr("correlationId"));
            Assert.Equal(i == 0, Attr("traceparent") is not null);
        }
    }

    private sealed class CountingPublisher : IOutboxPublisher
    {
        public ConcurrentDictionary<string, int> Calls { get; } = new();

        public Task PublishAsync(string topicArn, Envelope envelope, string? traceparent, CancellationToken ct)
        {
            Calls.AddOrUpdate(envelope.MessageId, 1, (_, n) => n + 1);
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task P13_ConcurrentRelaysPublishEachRowOnce()
    {
        await using var ds = await Containers.PostgresAsync();
        for (var i = 0; i < 20; i++)
        {
            await EnqueueAsync(ds, TopicArn, NewEnvelope());
        }
        var publisher = new CountingPublisher();
        async Task Drain(OutboxRelay relay)
        {
            while (await Containers.CountAsync(ds, "SELECT count(*) FROM outbox WHERE published_at IS NULL") > 0)
            {
                await relay.RunOnceAsync(CancellationToken.None);
            }
        }
        await Task.WhenAll(Drain(NewRelay(ds, publisher, 3)), Drain(NewRelay(ds, publisher, 3)));
        Assert.Equal(20, publisher.Calls.Count);
        Assert.All(publisher.Calls.Values, n => Assert.Equal(1, n));
    }

    // ---------- consumer ----------

    private sealed class EffectHandler(bool failFirst = false, Exception? result = null) : IMessageHandler
    {
        private int _calls;

        public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

        public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
        {
            if (Interlocked.Increment(ref _calls) == 1 && failFirst)
            {
                throw new TimeoutException("database temporarily unavailable");
            }
            await using var cmd = new NpgsqlCommand("INSERT INTO effects (message_id) VALUES ($1)", connection, transaction);
            cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(envelope.MessageId) });
            await cmd.ExecuteNonQueryAsync(ct);
            if (result is not null)
            {
                throw result;
            }
        }
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public required NpgsqlDataSource Db { get; init; }
        public required IAmazonSQS Sqs { get; init; }
        public required string QueueUrl { get; init; }
        public required string DlqUrl { get; init; }
        public required SqsConsumer Consumer { get; init; }

        public static async Task<Fixture> CreateAsync(IMessageHandler handler)
        {
            var db = await Containers.PostgresAsync();
            var options = new SqsConsumerOptions { Name = "test-consumer", Concurrency = 4, WaitTimeSeconds = 1, RequestTimeout = Containers.ClientTimeout };
            var sqs = await Containers.SqsAsync(AwsClientConfig.LongPollTimeout(options));
            var (queue, dlq) = await Containers.QueueAsync(sqs, "consumer", 3);
            options.QueueUrl = queue;
            var consumer = new SqsConsumer(sqs, db, new ContractValidator(Repo.ContractsDir),
                new Dictionary<string, IMessageHandler> { ["OrderCreated"] = handler }, options, NullLogger<SqsConsumer>.Instance);
            return new Fixture { Db = db, Sqs = sqs, QueueUrl = queue, DlqUrl = dlq, Consumer = consumer };
        }

        public Task<SendMessageResponse> SendAsync(string body) => Sqs.SendMessageAsync(QueueUrl, body);

        public async Task<List<ConsumeOutcome>> ReceiveAndProcessAsync()
        {
            var resp = await Sqs.ReceiveMessageAsync(new ReceiveMessageRequest
            {
                QueueUrl = QueueUrl,
                MaxNumberOfMessages = 10,
                WaitTimeSeconds = 1,
                MessageAttributeNames = ["All"],
                MessageSystemAttributeNames = ["ApproximateReceiveCount"],
            });
            var outcomes = new List<ConsumeOutcome>();
            foreach (var m in resp.Messages ?? [])
            {
                outcomes.Add(await Consumer.ProcessAsync(m, CancellationToken.None));
            }
            return outcomes;
        }

        public async ValueTask DisposeAsync()
        {
            Sqs.Dispose();
            await Db.DisposeAsync();
        }
    }

    private static string Example(string name, string messageId)
    {
        var node = JsonNode.Parse(File.ReadAllText(Path.Combine(Repo.ContractsDir, "examples", name + ".json")))!;
        node["messageId"] = messageId;
        return node.ToJsonString();
    }

    [Fact]
    public async Task P09_DuplicateFiveTimesHasOneEffect()
    {
        await using var f = await Fixture.CreateAsync(new EffectHandler());
        var body = Example("OrderCreated.v1", MsgId);
        for (var i = 0; i < 5; i++)
        {
            await f.SendAsync(body);
        }
        var outcomes = new List<ConsumeOutcome>();
        for (var i = 0; i < 10 && outcomes.Count < 5; i++)
        {
            outcomes.AddRange(await f.ReceiveAndProcessAsync());
        }
        Assert.Equal(1, outcomes.Count(o => o == ConsumeOutcome.Succeeded));
        Assert.Equal(4, outcomes.Count(o => o == ConsumeOutcome.Duplicate));
        Assert.Equal(1, await Containers.CountAsync(f.Db, "SELECT count(*) FROM effects"));
        Assert.Equal(1, await Containers.CountAsync(f.Db, "SELECT count(*) FROM processed_messages"));
        Assert.Equal(0, await Containers.QueueDepthAsync(f.Sqs, f.QueueUrl));
    }

    [Fact]
    public async Task P10_CrashBeforeCommitIsRedelivered()
    {
        await using var f = await Fixture.CreateAsync(new EffectHandler(failFirst: true));
        await f.SendAsync(Example("OrderCreated.v1", MsgId));
        var outcomes = new List<ConsumeOutcome>();
        var deadline = DateTime.UtcNow.AddSeconds(20);
        while (!outcomes.Contains(ConsumeOutcome.Succeeded) && DateTime.UtcNow < deadline)
        {
            outcomes.AddRange(await f.ReceiveAndProcessAsync());
        }
        Assert.Equal(ConsumeOutcome.Transient, outcomes[0]);
        Assert.Equal(ConsumeOutcome.Succeeded, outcomes[^1]);
        Assert.Equal(1, await Containers.CountAsync(f.Db, "SELECT count(*) FROM effects"));
    }

    [Fact]
    public async Task P11_PoisonGoesToDlq()
    {
        await using var f = await Fixture.CreateAsync(new EffectHandler());
        await f.SendAsync(Example("OrderCreated.v1", MsgId).Replace("\"quantity\":2", "\"quantity\":99", StringComparison.Ordinal));
        for (var i = 0; i < 6 && await Containers.QueueDepthAsync(f.Sqs, f.DlqUrl) == 0; i++)
        {
            Assert.All(await f.ReceiveAndProcessAsync(), o => Assert.Equal(ConsumeOutcome.Poison, o));
        }
        Assert.Equal(1, await Containers.QueueDepthAsync(f.Sqs, f.DlqUrl));
        Assert.Equal(0, await Containers.CountAsync(f.Db, "SELECT count(*) FROM effects"));

        // A valid message whose type has no handler is poison too, and is not acknowledged.
        await f.SendAsync(Example("PaymentFailed.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a56"));
        Assert.Equal([ConsumeOutcome.Poison], await f.ReceiveAndProcessAsync());
        Assert.Equal(1, await Containers.QueueDepthAsync(f.Sqs, f.QueueUrl));
    }

    [Fact]
    public async Task P14_StaleEventIsRecordedAndAcknowledged()
    {
        await using var f = await Fixture.CreateAsync(new EffectHandler(result: new StaleEventException("order already final")));
        await f.SendAsync(Example("OrderCreated.v1", MsgId));
        Assert.Equal([ConsumeOutcome.Stale], await f.ReceiveAndProcessAsync());
        Assert.Equal(1, await Containers.CountAsync(f.Db, "SELECT count(*) FROM processed_messages"));
        Assert.Equal(0, await Containers.QueueDepthAsync(f.Sqs, f.QueueUrl));
    }

    [Fact]
    public async Task P15_RawAndSnsFormatsAreBothConsumed()
    {
        await using var f = await Fixture.CreateAsync(new EffectHandler());
        using var sns = await Containers.SnsAsync();
        var topic = await Containers.TopicAsync(sns, f.Sqs, "formats", f.QueueUrl);

        await new SnsOutboxPublisher(sns).PublishAsync(topic, EnvelopeJson.Unwrap(Example("OrderCreated.v1", MsgId)), null, CancellationToken.None);
        var quoted = JsonSerializer.Serialize(Example("OrderCreated.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a57"));
        await f.SendAsync($$"""{"Type":"Notification","MessageId":"n-1","TopicArn":"{{topic}}","Message":{{quoted}}}""");

        var outcomes = new List<ConsumeOutcome>();
        for (var i = 0; i < 10 && outcomes.Count < 2; i++)
        {
            outcomes.AddRange(await f.ReceiveAndProcessAsync());
        }
        Assert.Equal(2, outcomes.Count(o => o == ConsumeOutcome.Succeeded));
    }

    [Fact]
    public async Task RunLongPollLongerThanClientTimeout()
    {
        // SQS_WAIT_TIME_S (10) is longer than HTTP_CLIENT_TIMEOUT_MS (3000): a consumer whose client would
        // cut the long poll is rejected at construction, and the long-polling client consumes normally.
        await using var db = await Containers.PostgresAsync();
        var options = new SqsConsumerOptions
        {
            Name = "long-poll",
            Concurrency = 4,
            WaitTimeSeconds = (int)Containers.ClientTimeout.TotalSeconds + 2,
            RequestTimeout = Containers.ClientTimeout,
        };
        using var shortClient = await Containers.SqsAsync();
        var (queue, _) = await Containers.QueueAsync(shortClient, "long-poll", 3);
        options.QueueUrl = queue;
        var handlers = new Dictionary<string, IMessageHandler> { ["OrderCreated"] = new EffectHandler() };
        var validator = new ContractValidator(Repo.ContractsDir);
        Assert.Throws<ArgumentException>(() => new SqsConsumer(shortClient, db, validator, handlers, options, NullLogger<SqsConsumer>.Instance));

        using var longPollClient = await Containers.SqsAsync(AwsClientConfig.LongPollTimeout(options));
        var consumer = new SqsConsumer(longPollClient, db, validator, handlers, options, NullLogger<SqsConsumer>.Instance);
        using var cts = new CancellationTokenSource();
        var run = consumer.RunAsync(cts.Token);
        await Task.Delay(Containers.ClientTimeout + TimeSpan.FromSeconds(1));
        await shortClient.SendMessageAsync(queue, Example("OrderCreated.v1", MsgId));
        var deadline = DateTime.UtcNow.AddSeconds(15);
        while (await Containers.CountAsync(db, "SELECT count(*) FROM effects") == 0)
        {
            Assert.True(DateTime.UtcNow < deadline, "message not consumed by RunAsync");
            await Task.Delay(200);
        }
        Assert.True(consumer.BrokerUp);
        await cts.CancelAsync();
        await run;
    }
}

[CollectionDefinition("integration", DisableParallelization = true)]
public sealed class IntegrationGroup;
