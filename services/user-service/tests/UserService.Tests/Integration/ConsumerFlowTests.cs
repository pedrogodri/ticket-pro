using System.Text.Json;
using Amazon.Runtime;
using Amazon.SQS;
using Amazon.SQS.Model;
using Microsoft.Extensions.Logging.Abstractions;
using TicketPro.Platform;
using UserService.App;
using UserService.Application.Orders;
using UserService.Application.Profiles;
using UserService.Application.Tickets;
using UserService.Consumers;
using UserService.Domain.Profiles;
using UserService.Infrastructure.Messaging;
using UserService.Infrastructure.Persistence;

namespace UserService.Tests.Integration;

/// <summary>The vendored SqsConsumer (RFC 0003 §5.4: validate → TX → processed_messages → effect + outbox →
/// commit → ack) with this service's handlers, against a real database. Messages are handed to ProcessAsync
/// directly; ack and visibility calls go to an unreachable SQS and are only logged, as the consumer does
/// when the broker is down.</summary>
[Trait("Category", "Integration")]
[Collection("users-db")]
public sealed class ConsumerFlowTests(UsersDatabase db) : IAsyncLifetime
{
    private const string Correlation = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";
    private static readonly ContractValidator Validator = new(TestEnv.ContractsDir);
    private readonly AppConfig _config = TestEnv.Config();

    public Task InitializeAsync() => db.ResetAsync();

    public Task DisposeAsync() => Task.CompletedTask;

    // ---------- helpers ----------

    /// <summary>One consumer "replica": its own SQS client and handlers, sharing only the database.</summary>
    private SqsConsumer Replica()
    {
        var options = new SqsConsumerOptions { QueueUrl = "http://127.0.0.1:9/000000000000/ticketpro-user-service", Name = _config.ServiceName, WaitTimeSeconds = 0 };
        var sqsConfig = AwsClientConfig.Apply(new AmazonSQSConfig { ServiceURL = "http://127.0.0.1:9", AuthenticationRegion = "us-east-1" },
            AwsClientConfig.LongPollTimeout(options));
        var sqs = new AmazonSQSClient(new BasicAWSCredentials("test", "test"), sqsConfig);
        return new SqsConsumer(sqs, db.Worker, Validator, new Dictionary<string, IMessageHandler>
        {
            [Messages.UserRegistered] = new UserRegisteredHandler(_config, new RegisterProfile(_config.ProfileBlockedEmailDomains), NullLogger<UserRegisteredHandler>.Instance),
            [Messages.OrderConfirmed] = new OrderConfirmedHandler(new ProjectOrderConfirmed(), NullLogger<OrderConfirmedHandler>.Instance),
            [Messages.TicketsIssued] = new TicketsIssuedHandler(new ProjectTicketsIssued(), NullLogger<TicketsIssuedHandler>.Instance),
        }, options, NullLogger<SqsConsumer>.Instance);
    }

    private static Message Sqs(Envelope envelope) => Sqs(EnvelopeJson.Serialize(envelope));

    private static Message Sqs(string body) => new()
    {
        MessageId = Guid.NewGuid().ToString(),
        ReceiptHandle = "receipt-" + Guid.NewGuid(),
        Body = body,
        Attributes = new() { ["ApproximateReceiveCount"] = "1" },
    };

    private static Envelope Registered(Guid userId, string email, string name = "Ana Souza") =>
        Messages.Create(Correlation, Messages.UserRegistered, new UserRegisteredV1(userId, email, name));

    private static Envelope Confirmed(Guid orderId, Guid userId) =>
        Messages.Create(Correlation, Messages.OrderConfirmed, new OrderConfirmedV1(orderId, userId, Guid.CreateVersion7(),
            Guid.CreateVersion7(), 2, 50000, "BRL", "2026-11-20T18:30:14.120Z"));

    private static Envelope Issued(Guid orderId, Guid userId, params string[] codes) =>
        Messages.Create(Correlation, Messages.TicketsIssued, new TicketsIssuedV1(orderId, userId, Guid.CreateVersion7(),
            "Festival de Verão 2026", "2026-12-12T21:00:00.000Z", Guid.CreateVersion7(), "Pista",
            codes.Select(c => new TicketV1(Guid.CreateVersion7(), c)).ToList()));

    private async Task<List<(string Type, string Topic, Guid Correlation, string Raw)>> OutboxAsync()
    {
        await using var cmd = db.Worker.CreateCommand("""
            SELECT type, topic_arn, correlation_id, message_id::text, version, occurred_at, payload::text FROM outbox ORDER BY id
            """);
        await using var reader = await cmd.ExecuteReaderAsync();
        var rows = new List<(string, string, Guid, string)>();
        while (await reader.ReadAsync())
        {
            var envelope = new Envelope(reader.GetString(3), reader.GetGuid(2).ToString(), reader.GetString(0), reader.GetInt32(4),
                new DateTimeOffset(reader.GetDateTime(5)), JsonElement.Parse(reader.GetString(6)));
            rows.Add((reader.GetString(0), reader.GetString(1), reader.GetGuid(2), EnvelopeJson.Serialize(envelope)));
        }
        return rows;
    }

    // ---------- UserRegistered: registration saga ----------

    [Fact]
    public async Task UserRegistered_creates_the_profile_without_publishing()
    {
        var user = Guid.CreateVersion7();

        Assert.Equal(ConsumeOutcome.Succeeded, await Replica().ProcessAsync(Sqs(Registered(user, "Ana@Exemplo.com")), CancellationToken.None));

        Assert.Equal("ana@exemplo.com", await db.ScalarAsync<string>("SELECT email FROM profiles WHERE user_id = $1", user));
        Assert.Empty(await OutboxAsync());
    }

    [Fact]
    public async Task F3_same_message_five_times_has_a_single_effect()
    {
        var consumer = Replica();
        var message = Registered(Guid.CreateVersion7(), "ana@exemplo.com");

        var outcomes = new List<ConsumeOutcome>();
        for (var i = 0; i < 5; i++)
        {
            outcomes.Add(await consumer.ProcessAsync(Sqs(message), CancellationToken.None));
        }

        Assert.Equal([ConsumeOutcome.Succeeded, .. Enumerable.Repeat(ConsumeOutcome.Duplicate, 4)], outcomes);
        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM profiles"));
        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM processed_messages"));
    }

    [Fact]
    public async Task Same_user_with_a_new_message_id_changes_nothing_and_never_compensates()
    {
        var user = Guid.CreateVersion7();
        await Replica().ProcessAsync(Sqs(Registered(user, "ana@exemplo.com")), CancellationToken.None);

        Assert.Equal(ConsumeOutcome.Succeeded, await Replica().ProcessAsync(Sqs(Registered(user, "ana@exemplo.com", "Outro Nome")), CancellationToken.None));

        Assert.Equal("Ana Souza", await db.ScalarAsync<string>("SELECT name FROM profiles WHERE user_id = $1", user));
        Assert.Empty(await OutboxAsync());
    }

    [Fact]
    public async Task Invalid_profile_is_compensated_with_UserCreationFailed_in_the_same_transaction()
    {
        var user = Guid.CreateVersion7();

        Assert.Equal(ConsumeOutcome.Succeeded, await Replica().ProcessAsync(Sqs(Registered(user, "x@bloqueado.test")), CancellationToken.None));

        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM profiles"));
        var (type, topic, correlation, raw) = Assert.Single(await OutboxAsync());
        Assert.Equal(Messages.UserCreationFailed, type);
        Assert.Equal(_config.UsersTopicArn, topic);
        Assert.Equal(Guid.Parse(Correlation), correlation); // same saga as the UserRegistered
        Validator.Validate(raw); // what the relay will publish matches the contract
        var payload = EnvelopeJson.Unwrap(raw).Payload;
        Assert.Equal(user, payload.GetProperty("userId").GetGuid());
        Assert.Equal(UserCreationFailureReason.InvalidProfileData, payload.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Email_of_another_user_is_compensated_with_DUPLICATE_PROFILE()
    {
        await Replica().ProcessAsync(Sqs(Registered(Guid.CreateVersion7(), "ana@exemplo.com")), CancellationToken.None);
        var second = Guid.CreateVersion7();

        await Replica().ProcessAsync(Sqs(Registered(second, "ANA@exemplo.com")), CancellationToken.None);

        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM profiles"));
        var (_, _, _, raw) = Assert.Single(await OutboxAsync());
        var payload = EnvelopeJson.Unwrap(raw).Payload;
        Assert.Equal(second, payload.GetProperty("userId").GetGuid());
        Assert.Equal(UserCreationFailureReason.DuplicateProfile, payload.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task SNS_notification_body_is_unwrapped()
    {
        var user = Guid.CreateVersion7();
        var body = JsonSerializer.Serialize(new { Type = "Notification", Message = EnvelopeJson.Serialize(Registered(user, "ana@exemplo.com")) });

        Assert.Equal(ConsumeOutcome.Succeeded, await Replica().ProcessAsync(Sqs(body), CancellationToken.None));
        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM profiles WHERE user_id = $1", user));
    }

    // ---------- F4: poison ----------

    [Fact]
    public async Task F4_payload_breaking_the_schema_is_poison_and_leaves_no_trace()
    {
        var body = """
            {"messageId":"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55","correlationId":"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c",
             "type":"UserRegistered","version":1,"occurredAt":"2026-11-20T18:30:12.345Z",
             "payload":{"userId":"0193a6f0-1111-7000-8000-000000000001","email":"ana@exemplo.com"}}
            """;

        Assert.Equal(ConsumeOutcome.Poison, await Replica().ProcessAsync(Sqs(body), CancellationToken.None));

        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM processed_messages"));
        Assert.Equal(0L, await db.ScalarAsync<long>("SELECT count(*) FROM profiles"));
    }

    [Theory]
    [InlineData("isto não é json")]
    [InlineData("""{"type":"UserRegistered","version":2}""")]
    [InlineData("""{"type":"PaymentApproved","version":1}""")]
    public async Task F4_garbage_unknown_version_or_unknown_type_is_poison(string body)
    {
        Assert.Equal(ConsumeOutcome.Poison, await Replica().ProcessAsync(Sqs(body), CancellationToken.None));
    }

    // ---------- read models ----------

    [Fact]
    public async Task TicketsIssued_projects_one_row_per_ticket_even_if_republished()
    {
        var (order, user) = (Guid.CreateVersion7(), Guid.CreateVersion7());
        var issued = Issued(order, user, "K7Q2-9XMA-41PZ", "B3RT-77NC-0QWE");

        for (var i = 0; i < 5; i++)
        {
            await Replica().ProcessAsync(Sqs(issued), CancellationToken.None);
        }
        // Same tickets under a new messageId (producer retried): processed_messages does not catch it, the key does.
        var republished = issued with { MessageId = Guid.CreateVersion7().ToString() };
        Assert.Equal(ConsumeOutcome.Succeeded, await Replica().ProcessAsync(Sqs(republished), CancellationToken.None));

        Assert.Equal(2L, await db.ScalarAsync<long>("SELECT count(*) FROM my_tickets WHERE order_id = $1", order));
        Assert.Equal(issued.OccurredAt.UtcDateTime, await db.ScalarAsync<DateTime>("SELECT min(issued_at) FROM my_tickets"));
    }

    [Fact]
    public async Task OrderConfirmed_projects_the_purchase_once()
    {
        var (order, user) = (Guid.CreateVersion7(), Guid.CreateVersion7());
        var confirmed = Confirmed(order, user);

        await Replica().ProcessAsync(Sqs(confirmed), CancellationToken.None);
        await Replica().ProcessAsync(Sqs(confirmed with { MessageId = Guid.CreateVersion7().ToString() }), CancellationToken.None);

        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM my_orders WHERE order_id = $1", order));
        Assert.Equal(50000L, await db.ScalarAsync<long>("SELECT total_cents FROM my_orders WHERE order_id = $1", order));
    }

    [Fact]
    public async Task Arrival_order_of_OrderConfirmed_and_TicketsIssued_does_not_change_the_result()
    {
        var store = new UserReadStore(db.App);
        var (userA, orderA) = (Guid.CreateVersion7(), Guid.CreateVersion7());
        var (userB, orderB) = (Guid.CreateVersion7(), Guid.CreateVersion7());

        await Replica().ProcessAsync(Sqs(Confirmed(orderA, userA)), CancellationToken.None);   // usual order
        await Replica().ProcessAsync(Sqs(Issued(orderA, userA, "AAAA-AAAA-0001", "AAAA-AAAA-0002")), CancellationToken.None);
        await Replica().ProcessAsync(Sqs(Issued(orderB, userB, "BBBB-BBBB-0001", "BBBB-BBBB-0002")), CancellationToken.None); // reversed
        await Replica().ProcessAsync(Sqs(Confirmed(orderB, userB)), CancellationToken.None);

        var a = Assert.Single(await store.ListOrdersAsync(userA, 10, null, CancellationToken.None));
        var b = Assert.Single(await store.ListOrdersAsync(userB, 10, null, CancellationToken.None));
        Assert.Equal((2, 2), (a.Order.Quantity, a.TicketsIssued));
        Assert.Equal((2, 2), (b.Order.Quantity, b.TicketsIssued));
    }

    // ---------- R4 / F8: competing replicas ----------

    [Fact]
    public async Task R4_two_replicas_racing_on_the_same_message_apply_it_once()
    {
        var (replica1, replica2) = (Replica(), Replica());
        var message = Issued(Guid.CreateVersion7(), Guid.CreateVersion7(), "RACE-RACE-0001", "RACE-RACE-0002");

        // Redelivery after a visibility timeout can hand the same message to both replicas at once.
        var outcomes = await Task.WhenAll(Enumerable.Range(0, 10)
            .Select(i => Task.Run(() => (i % 2 == 0 ? replica1 : replica2).ProcessAsync(Sqs(message), CancellationToken.None))));

        Assert.Equal(1, outcomes.Count(o => o == ConsumeOutcome.Succeeded));
        Assert.Equal(9, outcomes.Count(o => o == ConsumeOutcome.Duplicate));
        Assert.Equal(2L, await db.ScalarAsync<long>("SELECT count(*) FROM my_tickets"));
    }

    [Fact]
    public async Task R4_concurrent_registrations_of_one_user_with_different_message_ids_create_one_profile_and_no_compensation()
    {
        var user = Guid.CreateVersion7();
        var (replica1, replica2) = (Replica(), Replica());

        var outcomes = await Task.WhenAll(Enumerable.Range(0, 8)
            .Select(i => Task.Run(() => (i % 2 == 0 ? replica1 : replica2).ProcessAsync(Sqs(Registered(user, "ana@exemplo.com")), CancellationToken.None))));

        Assert.All(outcomes, o => Assert.Equal(ConsumeOutcome.Succeeded, o));
        Assert.Equal(1L, await db.ScalarAsync<long>("SELECT count(*) FROM profiles"));
        Assert.Empty(await OutboxAsync());
    }
}
