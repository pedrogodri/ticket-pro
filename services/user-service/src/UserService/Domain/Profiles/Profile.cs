namespace UserService.Domain.Profiles;

/// <summary>A user's profile, projected from UserRegistered. Email is already normalized (trimmed, lower case).</summary>
public sealed record Profile(Guid UserId, string Email, string Name);
