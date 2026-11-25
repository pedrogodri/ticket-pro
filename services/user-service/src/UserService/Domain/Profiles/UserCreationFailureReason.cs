namespace UserService.Domain.Profiles;

/// <summary>Values of UserCreationFailed.reason (contracts/events/UserCreationFailed/v1.schema.json).</summary>
public static class UserCreationFailureReason
{
    /// <summary>The profile data breaks a profile rule (blank name, blocked e-mail domain...).</summary>
    public const string InvalidProfileData = "INVALID_PROFILE_DATA";

    /// <summary>The e-mail already belongs to the profile of another user.</summary>
    public const string DuplicateProfile = "DUPLICATE_PROFILE";
}
