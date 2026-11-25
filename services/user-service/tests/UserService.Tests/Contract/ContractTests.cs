using System.Text.Json;
using TicketPro.Platform;
using UserService.Consumers;
using UserService.Domain.Profiles;
using UserService.Infrastructure.Messaging;

namespace UserService.Tests.Contract;

/// <summary>docs/padroes.md §11.1: produced events validate against the vendored contracts, and the contract
/// examples deserialize into the payload records the handlers read.</summary>
public sealed class ContractTests
{
    private static readonly ContractValidator Validator = new(TestEnv.ContractsDir);

    private static Envelope Example(string type)
    {
        var raw = File.ReadAllText(Path.Combine(TestEnv.ContractsDir, "examples", $"{type}.v1.json"));
        Validator.Validate(raw);
        return EnvelopeJson.Unwrap(raw);
    }

    [Theory]
    [InlineData(UserCreationFailureReason.InvalidProfileData)]
    [InlineData(UserCreationFailureReason.DuplicateProfile)]
    public void Produced_UserCreationFailed_matches_its_schema(string reason)
    {
        var envelope = Messages.Create("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", Messages.UserCreationFailed,
            new UserCreationFailedV1(Guid.CreateVersion7(), reason));

        Validator.Validate(EnvelopeJson.Serialize(envelope)); // throws on any violation
        Assert.Equal(1, envelope.Version);
    }

    [Fact]
    public void UserRegistered_example_feeds_the_handler_record()
    {
        var m = Messages.Read<UserRegisteredV1>(Example(Messages.UserRegistered));

        Assert.Equal(Guid.Parse("0193a6f0-1111-7000-8000-000000000001"), m.UserId);
        Assert.Equal("ana@exemplo.com", m.Email);
        Assert.Equal("Ana Souza", m.Name);
    }

    [Fact]
    public void OrderConfirmed_example_feeds_the_handler_record()
    {
        var m = Messages.Read<OrderConfirmedV1>(Example(Messages.OrderConfirmed));

        Assert.Equal(2, m.Quantity);
        Assert.Equal(50000, m.TotalCents);
        Assert.Equal(new DateTime(2026, 11, 20, 18, 30, 14, 120, DateTimeKind.Utc), Messages.ParseTimestamp(m.ConfirmedAt));
    }

    [Fact]
    public void TicketsIssued_example_feeds_the_handler_record()
    {
        var m = Messages.Read<TicketsIssuedV1>(Example(Messages.TicketsIssued));

        Assert.Equal("Festival de Verão 2026", m.EventName);
        Assert.Equal("Pista", m.SectorName);
        Assert.Equal(["K7Q2-9XMA-41PZ", "B3RT-77NC-0QWE"], m.Tickets.Select(t => t.Code));
    }

    [Fact]
    public void UserCreationFailed_example_matches_the_published_record()
    {
        var m = Messages.Read<UserCreationFailedV1>(Example(Messages.UserCreationFailed));

        Assert.Equal(UserCreationFailureReason.InvalidProfileData, m.Reason);
    }

    [Fact]
    public void Handlers_accept_only_v1_so_an_unknown_version_goes_to_the_DLQ()
    {
        IMessageHandler[] handlers =
        [
            new UserRegisteredHandler(TestEnv.Config(), new(new HashSet<string>()), Microsoft.Extensions.Logging.Abstractions.NullLogger<UserRegisteredHandler>.Instance),
            new OrderConfirmedHandler(new(), Microsoft.Extensions.Logging.Abstractions.NullLogger<OrderConfirmedHandler>.Instance),
            new TicketsIssuedHandler(new(), Microsoft.Extensions.Logging.Abstractions.NullLogger<TicketsIssuedHandler>.Instance),
        ];

        Assert.All(handlers, h => Assert.Equal(new HashSet<int> { 1 }, h.Versions));
    }

    [Fact]
    public void Schema_violation_is_poison()
    {
        var bad = """
            {"messageId":"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55","correlationId":"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c",
             "type":"UserRegistered","version":1,"occurredAt":"2026-11-20T18:30:12.345Z",
             "payload":{"userId":"0193a6f0-1111-7000-8000-000000000001","email":"nao-e-email"}}
            """;

        Assert.ThrowsAny<PoisonMessageException>(() => Validator.Validate(bad));
        _ = JsonDocument.Parse(bad); // the JSON itself is fine: only the contract rejects it
    }
}
