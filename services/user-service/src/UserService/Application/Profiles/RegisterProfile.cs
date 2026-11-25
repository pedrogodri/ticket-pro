using UserService.Domain.Profiles;

namespace UserService.Application.Profiles;

/// <summary>Data of a UserRegistered event.</summary>
public sealed record RegisterProfileCommand(Guid UserId, string Email, string Name, string CorrelationId);

public enum RegisterProfileOutcome
{
    Created = 1,

    /// <summary>The profile already existed: nothing changed, nothing published.</summary>
    AlreadyExists,

    /// <summary>A profile rule failed: UserCreationFailed(INVALID_PROFILE_DATA) was enqueued.</summary>
    RejectedInvalid,

    /// <summary>The e-mail belongs to another user: UserCreationFailed(DUPLICATE_PROFILE) was enqueued.</summary>
    RejectedDuplicate,
}

public sealed record RegisterProfileResult(RegisterProfileOutcome Outcome, string? Problem = null);

/// <summary>Second step of the registration saga (arquitetura §5): create the profile, or ask auth-service
/// to compensate (remove the credential) by publishing UserCreationFailed. Both effects happen in the same
/// transaction as processed_messages, so a redelivered UserRegistered never runs them twice (R1).</summary>
public sealed class RegisterProfile(IReadOnlySet<string> blockedEmailDomains)
{
    public async Task<RegisterProfileResult> ExecuteAsync(RegisterProfileCommand command, IProfileStore store, IUserEventPublisher events, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(command);
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(events);

        var validation = ProfileRules.Validate(command.UserId, command.Email, command.Name, blockedEmailDomains);
        if (!validation.IsValid)
        {
            await events.UserCreationFailedAsync(command.UserId, UserCreationFailureReason.InvalidProfileData, command.CorrelationId, ct);
            return new RegisterProfileResult(RegisterProfileOutcome.RejectedInvalid, validation.Problem);
        }

        switch (await store.TryInsertAsync(validation.Profile!, ct))
        {
            case ProfileInsertResult.Inserted:
                return new RegisterProfileResult(RegisterProfileOutcome.Created);
            case ProfileInsertResult.SameUserExists:
                return new RegisterProfileResult(RegisterProfileOutcome.AlreadyExists);
            default:
                await events.UserCreationFailedAsync(command.UserId, UserCreationFailureReason.DuplicateProfile, command.CorrelationId, ct);
                return new RegisterProfileResult(RegisterProfileOutcome.RejectedDuplicate, "email belongs to another user");
        }
    }
}
