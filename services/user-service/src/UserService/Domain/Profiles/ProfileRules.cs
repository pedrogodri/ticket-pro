namespace UserService.Domain.Profiles;

/// <summary>Outcome of <see cref="ProfileRules.Validate"/>: either a normalized profile or the rule it broke.</summary>
public sealed record ProfileValidation(Profile? Profile, string? Problem)
{
    public bool IsValid => Profile is not null;
}

/// <summary>Business rules a profile must satisfy, on top of the UserRegistered schema (which already
/// limits lengths and the e-mail format). Breaking one is not a poison message: the registration saga
/// is compensated with UserCreationFailed(INVALID_PROFILE_DATA) (arquitetura §5).</summary>
public static class ProfileRules
{
    /// <param name="blockedDomains">Lower-case domains; a listed domain also blocks its subdomains.</param>
    public static ProfileValidation Validate(Guid userId, string email, string name, IReadOnlySet<string> blockedDomains)
    {
        ArgumentNullException.ThrowIfNull(blockedDomains);
        if (userId == Guid.Empty)
        {
            return Reject("userId is empty");
        }

        var normalizedName = NormalizeName(name);
        if (normalizedName.Length == 0)
        {
            return Reject("name is blank");
        }
        if (normalizedName.Any(char.IsControl))
        {
            return Reject("name contains control characters");
        }

        var normalizedEmail = (email ?? "").Trim().ToLowerInvariant();
        var at = normalizedEmail.LastIndexOf('@');
        if (at <= 0 || at == normalizedEmail.Length - 1)
        {
            return Reject("email has no local part or domain");
        }
        var domain = normalizedEmail[(at + 1)..];
        if (IsBlocked(domain, blockedDomains))
        {
            return Reject("email domain is blocked");
        }

        return new ProfileValidation(new Profile(userId, normalizedEmail, normalizedName), null);
    }

    /// <summary>True when <paramref name="domain"/> is a blocked domain or one of its subdomains.</summary>
    public static bool IsBlocked(string domain, IReadOnlySet<string> blockedDomains)
    {
        ArgumentNullException.ThrowIfNull(domain);
        ArgumentNullException.ThrowIfNull(blockedDomains);
        var d = domain.ToLowerInvariant();
        return blockedDomains.Any(b => d == b || d.EndsWith("." + b, StringComparison.Ordinal));
    }

    // Trims and collapses runs of spaces ("  Ana   Souza " → "Ana Souza"). Other control characters are
    // kept so that the rule above can reject them.
    private static string NormalizeName(string? name) =>
        string.Join(' ', (name ?? "").Split([' ', '\t'], StringSplitOptions.RemoveEmptyEntries));

    private static ProfileValidation Reject(string problem) => new(null, problem);
}
