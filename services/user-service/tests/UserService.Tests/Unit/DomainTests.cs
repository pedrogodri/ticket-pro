using UserService.Domain.Profiles;

namespace UserService.Tests.Unit;

public sealed class ProfileRulesTests
{
    private static readonly HashSet<string> Blocked = ["bloqueado.test"];
    private static readonly Guid User = Guid.CreateVersion7();

    [Fact]
    public void Valid_profile_is_normalized()
    {
        var result = ProfileRules.Validate(User, "  Ana@Exemplo.COM ", "  Ana   Souza ", Blocked);

        Assert.True(result.IsValid);
        Assert.Equal(new Profile(User, "ana@exemplo.com", "Ana Souza"), result.Profile);
    }

    [Theory]
    [InlineData("   ")]
    [InlineData("")]
    [InlineData("\t \t")]
    public void Blank_name_is_rejected(string name)
    {
        var result = ProfileRules.Validate(User, "ana@exemplo.com", name, Blocked);

        Assert.False(result.IsValid);
        Assert.Equal("name is blank", result.Problem);
    }

    [Fact]
    public void Name_with_control_characters_is_rejected()
    {
        var result = ProfileRules.Validate(User, "ana@exemplo.com", "Ana\u0007Souza", Blocked);

        Assert.Equal("name contains control characters", result.Problem);
    }

    [Theory]
    [InlineData("x@bloqueado.test")]
    [InlineData("x@BLOQUEADO.TEST")]
    [InlineData("x@mail.bloqueado.test")]
    public void Blocked_domain_and_its_subdomains_are_rejected(string email)
    {
        var result = ProfileRules.Validate(User, email, "Ana", Blocked);

        Assert.Equal("email domain is blocked", result.Problem);
    }

    [Theory]
    [InlineData("x@naobloqueado.test")]
    [InlineData("x@bloqueado.test.br")]
    public void Lookalike_domains_are_not_blocked(string email)
    {
        Assert.True(ProfileRules.Validate(User, email, "Ana", Blocked).IsValid);
    }

    [Theory]
    [InlineData("semarroba")]
    [InlineData("@exemplo.com")]
    [InlineData("ana@")]
    public void Email_without_local_part_or_domain_is_rejected(string email)
    {
        Assert.Equal("email has no local part or domain", ProfileRules.Validate(User, email, "Ana", Blocked).Problem);
    }

    [Fact]
    public void Empty_user_id_is_rejected()
    {
        Assert.False(ProfileRules.Validate(Guid.Empty, "ana@exemplo.com", "Ana", Blocked).IsValid);
    }

    [Fact]
    public void Nothing_is_blocked_when_the_list_is_empty()
    {
        Assert.True(ProfileRules.Validate(User, "x@bloqueado.test", "Ana", new HashSet<string>()).IsValid);
    }
}

public sealed class EmailMaskTests
{
    [Theory]
    [InlineData("ana@exemplo.com", "a***@exemplo.com")]
    [InlineData("a@b.c", "a***@b.c")]
    [InlineData("semarroba", "***")]
    [InlineData("", "***")]
    [InlineData(null, "***")]
    public void Masks_the_local_part(string? email, string expected)
    {
        Assert.Equal(expected, EmailMask.Mask(email));
    }
}
