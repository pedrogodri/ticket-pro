using UserService.Application.Paging;
using UserService.Application.Profiles;
using UserService.Application.Queries;
using UserService.Domain.Profiles;
using UserService.Domain.Tickets;

namespace UserService.Tests.Unit;

public sealed class RegisterProfileTests
{
    private const string Correlation = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";
    private readonly RegisterProfile _useCase = new(new HashSet<string> { "bloqueado.test" });
    private readonly FakeProfileStore _store = new();
    private readonly FakeEventPublisher _events = new();

    private Task<RegisterProfileResult> RunAsync(Guid userId, string email, string name = "Ana Souza") =>
        _useCase.ExecuteAsync(new RegisterProfileCommand(userId, email, name, Correlation), _store, _events, CancellationToken.None);

    [Fact]
    public async Task Valid_registration_creates_the_profile_and_publishes_nothing()
    {
        var user = Guid.CreateVersion7();

        var result = await RunAsync(user, "ana@exemplo.com");

        Assert.Equal(RegisterProfileOutcome.Created, result.Outcome);
        Assert.Equal(user, Assert.Single(_store.Profiles).UserId);
        Assert.Empty(_events.Failures);
    }

    [Fact]
    public async Task Invalid_profile_publishes_INVALID_PROFILE_DATA_with_the_saga_correlation()
    {
        var user = Guid.CreateVersion7();

        var result = await RunAsync(user, "x@bloqueado.test");

        Assert.Equal(RegisterProfileOutcome.RejectedInvalid, result.Outcome);
        Assert.Empty(_store.Profiles);
        Assert.Equal((user, UserCreationFailureReason.InvalidProfileData, Correlation), Assert.Single(_events.Failures));
    }

    [Fact]
    public async Task Email_of_another_user_publishes_DUPLICATE_PROFILE()
    {
        await RunAsync(Guid.CreateVersion7(), "ana@exemplo.com");
        var second = Guid.CreateVersion7();

        var result = await RunAsync(second, "ANA@exemplo.com");

        Assert.Equal(RegisterProfileOutcome.RejectedDuplicate, result.Outcome);
        Assert.Single(_store.Profiles);
        Assert.Equal((second, UserCreationFailureReason.DuplicateProfile, Correlation), Assert.Single(_events.Failures));
    }

    [Fact]
    public async Task Same_user_again_is_a_no_op_and_never_compensates()
    {
        var user = Guid.CreateVersion7();
        await RunAsync(user, "ana@exemplo.com");

        var result = await RunAsync(user, "ana@exemplo.com");

        Assert.Equal(RegisterProfileOutcome.AlreadyExists, result.Outcome);
        Assert.Single(_store.Profiles);
        Assert.Empty(_events.Failures); // compensating would delete a legitimate account
    }
}

public sealed class PagingTests
{
    [Fact]
    public void Cursor_round_trips_with_microsecond_precision()
    {
        var cursor = new PageCursor(new DateTime(2026, 11, 20, 18, 30, 12, DateTimeKind.Utc).AddTicks(3_456_780), Guid.CreateVersion7());

        Assert.Equal(cursor, PageCursor.TryDecode(cursor.Encode()));
    }

    [Theory]
    [InlineData("lixo")]
    [InlineData("")]
    [InlineData("MTIz")] // "123": no id
    [InlineData("!!!")]
    public void Garbage_cursor_is_rejected(string value)
    {
        Assert.Null(PageCursor.TryDecode(value));
    }

    [Fact]
    public void Missing_limit_uses_the_default()
    {
        Assert.Equal(new PageRequest(20, null), PageRequest.Parse(null, null, 20, 50));
    }

    [Theory]
    [InlineData("0")]
    [InlineData("51")]
    [InlineData("-1")]
    [InlineData("dez")]
    [InlineData("1.5")]
    public void Limit_outside_1_to_max_is_a_validation_error(string limit)
    {
        var e = Assert.Throws<QueryValidationException>(() => PageRequest.Parse(limit, null, 20, 50));

        Assert.Equal("limit", e.Field);
    }

    [Fact]
    public void Invalid_cursor_is_a_validation_error()
    {
        Assert.Equal("cursor", Assert.Throws<QueryValidationException>(() => PageRequest.Parse(null, "lixo", 20, 50)).Field);
    }
}

public sealed class UserQueriesTests
{
    private static MyTicket Ticket(Guid user, int minute) => new(Guid.CreateVersion7(), $"AAAA-BBBB-{minute:0000}", user,
        Guid.CreateVersion7(), Guid.CreateVersion7(), "Festival", DateTime.UtcNow, "Pista",
        new DateTime(2026, 11, 20, 18, minute, 0, DateTimeKind.Utc));

    [Fact]
    public async Task Pages_walk_every_ticket_once_newest_first()
    {
        var user = Guid.CreateVersion7();
        var store = new FakeReadStore();
        store.Tickets.AddRange(Enumerable.Range(1, 5).Select(m => Ticket(user, m)));
        var queries = new UserQueries(store);

        var seen = new List<MyTicket>();
        PageCursor? after = null;
        string? next;
        do
        {
            var page = await queries.ListTicketsAsync(user, new PageRequest(2, after), CancellationToken.None);
            seen.AddRange(page.Items);
            next = page.NextCursor;
            after = next is null ? null : PageCursor.TryDecode(next);
        }
        while (next is not null);

        Assert.Equal(store.Tickets.OrderByDescending(t => t.IssuedAt).Select(t => t.TicketId), seen.Select(t => t.TicketId));
    }

    [Fact]
    public async Task Last_page_has_no_next_cursor()
    {
        var user = Guid.CreateVersion7();
        var store = new FakeReadStore();
        store.Tickets.AddRange([Ticket(user, 1), Ticket(user, 2)]);

        var page = await new UserQueries(store).ListTicketsAsync(user, new PageRequest(2, null), CancellationToken.None);

        Assert.Equal(2, page.Items.Count);
        Assert.Null(page.NextCursor);
    }
}
