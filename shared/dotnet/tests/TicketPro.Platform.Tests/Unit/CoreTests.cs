using System.Diagnostics;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace TicketPro.Platform.Tests.Unit;

public sealed class CoreTests
{
    private static readonly TimeSpan Base = TimeSpan.FromMilliseconds(200);
    private static readonly TimeSpan Cap = TimeSpan.FromSeconds(30);

    [Fact]
    public void P01_DelayIsFullJitterBoundedByCap()
    {
        Assert.Equal(TimeSpan.Zero, Backoff.Delay(5, Base, Cap, () => 0));
        foreach (var (attempt, want) in new[] { (0, 200.0), (3, 1600.0), (10, 30000.0) })
        {
            var got = Backoff.Delay(attempt, Base, Cap, () => 0.999999).TotalMilliseconds;
            Assert.InRange(got, want - (want / 1000), want);
        }
        Assert.True(Backoff.Delay(1000, Base, Cap, () => 0.999999) <= Cap);
        for (var n = 0; n < 12; n++)
        {
            var limit = TimeSpan.FromTicks(Math.Min(Cap.Ticks, Base.Ticks << n));
            for (var i = 0; i < 1000; i++)
            {
                var d = Backoff.Delay(n, Base, Cap, Random.Shared.NextDouble);
                Assert.InRange(d, TimeSpan.Zero, limit);
            }
        }
    }

    [Fact]
    public void P02_CreateSerializesExactlySixKeys()
    {
        var env = Envelope.Create("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "OrderCreated", 1, new { orderId = "o-1" });
        var json = EnvelopeJson.Serialize(env);
        using var doc = JsonDocument.Parse(json);
        var keys = doc.RootElement.EnumerateObject().Select(p => p.Name).ToArray();
        Assert.Equal(["messageId", "correlationId", "type", "version", "occurredAt", "payload"], keys);
        Assert.True(Guid.TryParse(env.MessageId, out _));
        Assert.Matches(@"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$", doc.RootElement.GetProperty("occurredAt").GetString());

        var back = EnvelopeJson.Unwrap(json);
        Assert.Equal(env.MessageId, back.MessageId);
        Assert.Equal(env.CorrelationId, back.CorrelationId);
        Assert.Equal(env.Type, back.Type);
        Assert.Equal(env.Version, back.Version);
        Assert.Equal(env.OccurredAt, back.OccurredAt);
        Assert.Equal(env.Payload.GetRawText(), back.Payload.GetRawText());
    }

    [Fact]
    public void P03_UnwrapAcceptsSnsAndRawAndRejectsGarbage()
    {
        const string raw = """{"messageId":"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55","correlationId":"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c","type":"Ping","version":1,"occurredAt":"2026-11-20T18:30:12.345Z","payload":{"a":1}}""";
        var sns = $$"""{"Type":"Notification","MessageId":"x","Message":{{JsonSerializer.Serialize(raw)}}}""";

        var fromRaw = EnvelopeJson.Unwrap(raw);
        var fromSns = EnvelopeJson.Unwrap(sns);
        Assert.Equal(fromRaw.MessageId, fromSns.MessageId);
        Assert.Equal("Ping", fromSns.Type);
        Assert.Equal("""{"a":1}""", fromSns.Payload.GetRawText());

        foreach (var body in new[] { """{"Type":"Notification","Message":"x"}""", "[]", "not json" })
        {
            Assert.ThrowsAny<PoisonMessageException>(() => EnvelopeJson.Unwrap(body));
        }
    }

    [Fact]
    public void P04_ValidatorAgainstContracts()
    {
        var validator = new ContractValidator(Repo.ContractsDir);
        var valid = Directory.GetFiles(Path.Combine(Repo.ContractsDir, "examples"), "*.json");
        var invalid = Directory.GetFiles(Path.Combine(Repo.ContractsDir, "examples", "invalid"), "*.json");
        Assert.NotEmpty(valid);
        Assert.NotEmpty(invalid);
        foreach (var f in valid)
        {
            var ex = Record.Exception(() => validator.Validate(File.ReadAllText(f)));
            Assert.True(ex is null, $"{Path.GetFileName(f)} should be valid: {ex?.Message}");
        }
        foreach (var f in invalid)
        {
            var ex = Record.Exception(() => validator.Validate(File.ReadAllText(f)));
            Assert.True(ex is PoisonMessageException, $"{Path.GetFileName(f)} should be poison, got {ex?.GetType().Name}");
        }

        var orderCreated = File.ReadAllText(Path.Combine(Repo.ContractsDir, "examples", "OrderCreated.v1.json"));
        Assert.Throws<UnsupportedVersionException>(() => validator.Validate(orderCreated.Replace("\"version\": 1", "\"version\": 3", StringComparison.Ordinal)));
        Assert.Throws<UnsupportedVersionException>(() => validator.Validate(orderCreated.Replace("\"type\": \"OrderCreated\"", "\"type\": \"NoSuchEvent\"", StringComparison.Ordinal)));
        Assert.Throws<InvalidMessageException>(() => validator.Validate(orderCreated.Replace("\"quantity\"", "\"qty\"", StringComparison.Ordinal)));
    }

    [Fact]
    public void P05_TraceparentRoundTrip()
    {
        using var source = new ActivitySource("test");
        using var listener = new ActivityListener
        {
            ShouldListenTo = s => s.Name == "test",
            Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllDataAndRecorded,
        };
        ActivitySource.AddActivityListener(listener);

        using (var activity = source.StartActivity("op"))
        {
            Assert.NotNull(activity);
            var header = TraceContext.Inject();
            Assert.NotNull(header);
            Assert.Matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$", header);
            var extracted = TraceContext.Extract(header);
            Assert.Equal(activity.TraceId, extracted!.Value.TraceId);
        }
        Assert.Null(TraceContext.Inject());
        Assert.Null(TraceContext.Extract(null));
    }

    [Fact]
    public void P06_StandardKeysAndCorrelationId()
    {
        var formatter = new StandardJsonConsoleFormatter(Options.Create(new StandardJsonFormatterOptions { ServiceName = "payment-service" }));
        var scopes = new LoggerExternalScopeProvider();
        using var writer = new StringWriter();
        using (scopes.Push(new Dictionary<string, object?> { ["correlationId"] = "c-1" }))
        {
            var entry = new LogEntry<string>(LogLevel.Information, "cat", default, "message_consumed", null, (s, _) => s);
            formatter.Write(entry, scopes, writer);
        }
        using var line = JsonDocument.Parse(writer.ToString());
        var root = line.RootElement;
        Assert.True(DateTimeOffset.TryParse(root.GetProperty("timestamp").GetString(), out _));
        Assert.Equal("info", root.GetProperty("level").GetString());
        Assert.Equal("payment-service", root.GetProperty("service").GetString());
        Assert.Equal("message_consumed", root.GetProperty("message").GetString());
        Assert.Equal("c-1", root.GetProperty("correlationId").GetString());

        using var plain = new StringWriter();
        formatter.Write(new LogEntry<string>(LogLevel.Information, "cat", default, "no_context", null, (s, _) => s), scopes, plain);
        Assert.DoesNotContain("correlationId", plain.ToString(), StringComparison.Ordinal);
    }
}
