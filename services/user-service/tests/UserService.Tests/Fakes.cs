using UserService.Application;
using UserService.Application.Paging;
using UserService.Application.Profiles;
using UserService.Application.Queries;
using UserService.Domain.Profiles;
using UserService.Domain.Tickets;

namespace UserService.Tests;

/// <summary>In-memory IProfileStore: answers like the SQL one (user_id PK + unique lower(email)).</summary>
internal sealed class FakeProfileStore : IProfileStore
{
    public List<Profile> Profiles { get; } = [];

    public Task<ProfileInsertResult> TryInsertAsync(Profile profile, CancellationToken ct)
    {
        if (Profiles.Any(p => p.UserId == profile.UserId))
        {
            return Task.FromResult(ProfileInsertResult.SameUserExists);
        }
        if (Profiles.Any(p => string.Equals(p.Email, profile.Email, StringComparison.OrdinalIgnoreCase)))
        {
            return Task.FromResult(ProfileInsertResult.EmailTaken);
        }
        Profiles.Add(profile);
        return Task.FromResult(ProfileInsertResult.Inserted);
    }
}

internal sealed class FakeEventPublisher : IUserEventPublisher
{
    public List<(Guid UserId, string Reason, string CorrelationId)> Failures { get; } = [];

    public Task UserCreationFailedAsync(Guid userId, string reason, string correlationId, CancellationToken ct)
    {
        Failures.Add((userId, reason, correlationId));
        return Task.CompletedTask;
    }
}

/// <summary>In-memory IUserReadStore that records which user each read was made for.</summary>
internal sealed class FakeReadStore : IUserReadStore
{
    public Dictionary<Guid, ProfileView> Profiles { get; } = [];
    public List<MyTicket> Tickets { get; } = [];
    public List<OrderView> Orders { get; } = [];
    public List<Guid> ReadsFor { get; } = [];
    public Exception? Fail { get; set; }

    public Task<ProfileView?> GetProfileAsync(Guid userId, CancellationToken ct)
    {
        Record(userId);
        return Task.FromResult(Profiles.GetValueOrDefault(userId));
    }

    public Task<IReadOnlyList<MyTicket>> ListTicketsAsync(Guid userId, int take, PageCursor? after, CancellationToken ct)
    {
        Record(userId);
        IReadOnlyList<MyTicket> rows = Tickets.Where(t => t.UserId == userId)
            .OrderByDescending(t => t.IssuedAt).ThenByDescending(t => t.TicketId)
            .Where(t => after is null || t.IssuedAt < after.At || (t.IssuedAt == after.At && t.TicketId.CompareTo(after.Id) < 0))
            .Take(take).ToList();
        return Task.FromResult(rows);
    }

    public Task<IReadOnlyList<OrderView>> ListOrdersAsync(Guid userId, int take, PageCursor? after, CancellationToken ct)
    {
        Record(userId);
        IReadOnlyList<OrderView> rows = Orders.Where(o => o.Order.UserId == userId).Take(take).ToList();
        return Task.FromResult(rows);
    }

    private void Record(Guid userId)
    {
        ReadsFor.Add(userId);
        if (Fail is not null)
        {
            throw Fail;
        }
    }
}
