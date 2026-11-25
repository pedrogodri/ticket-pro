namespace UserService.Domain.Profiles;

/// <summary>E-mail as it may appear in logs (docs/padroes.md §5): "ana@exemplo.com" → "a***@exemplo.com".</summary>
public static class EmailMask
{
    public static string Mask(string? email)
    {
        if (string.IsNullOrEmpty(email))
        {
            return "***";
        }
        var at = email.LastIndexOf('@');
        return at <= 0 ? "***" : email[0] + "***" + email[at..];
    }
}
