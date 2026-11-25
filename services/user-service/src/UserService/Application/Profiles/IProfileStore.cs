using UserService.Domain.Profiles;

namespace UserService.Application.Profiles;

/// <summary>What happened to an insert into profiles.</summary>
public enum ProfileInsertResult
{
    Inserted = 1,

    /// <summary>A profile with this user_id already exists (redelivery with a new messageId): no-op.</summary>
    SameUserExists,

    /// <summary>The e-mail belongs to another user_id's profile.</summary>
    EmailTaken,
}

/// <summary>Profile persistence inside the consumer's transaction.</summary>
public interface IProfileStore
{
    Task<ProfileInsertResult> TryInsertAsync(Profile profile, CancellationToken ct);
}
