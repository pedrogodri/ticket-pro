using UserService.App;

namespace UserService.Tests.Unit;

public sealed class AppConfigTests
{
    [Fact]
    public void Env_example_is_a_valid_configuration()
    {
        var config = TestEnv.Config();

        Assert.Equal("user-service", config.ServiceName);
        Assert.Equal("arn:aws:sns:us-east-1:000000000000:ticketpro-users", config.UsersTopicArn);
        Assert.Equal(["bloqueado.test"], config.ProfileBlockedEmailDomains);
        Assert.Equal(20, config.PageDefaultLimit);
        Assert.Equal(50, config.PageMaxLimit);
    }

    [Theory]
    [InlineData("SERVICE_NAME")]
    [InlineData("DATABASE_URL")]
    [InlineData("DATABASE_WORKER_URL")]
    [InlineData("USERS_TOPIC_ARN")]
    [InlineData("QUEUE_URL")]
    [InlineData("CONTRACTS_DIR")]
    public void Missing_required_variable_names_it(string name)
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { [name] = null }));

        Assert.Equal($"{name}: required", e.Message);
    }

    [Fact]
    public void Blocked_domains_are_optional_trimmed_and_lower_cased()
    {
        Assert.Empty(TestEnv.Config(new() { ["PROFILE_BLOCKED_EMAIL_DOMAINS"] = null }).ProfileBlockedEmailDomains);

        var config = TestEnv.Config(new() { ["PROFILE_BLOCKED_EMAIL_DOMAINS"] = " Bloqueado.Test , spam.example ," });

        Assert.Equal(new HashSet<string> { "bloqueado.test", "spam.example" }, config.ProfileBlockedEmailDomains);
    }

    [Theory]
    [InlineData("nodot")]
    [InlineData("bad domain.test")]
    [InlineData("@x.test")]
    [InlineData(".x.test")]
    public void Invalid_blocked_domain_stops_the_boot(string value)
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { ["PROFILE_BLOCKED_EMAIL_DOMAINS"] = value }));

        Assert.StartsWith("PROFILE_BLOCKED_EMAIL_DOMAINS:", e.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("PAGE_MAX_LIMIT", "51")]
    [InlineData("PAGE_MAX_LIMIT", "0")]
    [InlineData("PAGE_DEFAULT_LIMIT", "0")]
    [InlineData("PAGE_DEFAULT_LIMIT", "abc")]
    public void Page_limits_are_validated(string name, string value)
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { [name] = value }));

        Assert.StartsWith(name + ":", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Default_page_size_cannot_exceed_the_maximum()
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { ["PAGE_MAX_LIMIT"] = "10", ["PAGE_DEFAULT_LIMIT"] = "11" }));

        Assert.StartsWith("PAGE_DEFAULT_LIMIT:", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Default_page_size_follows_a_smaller_maximum()
    {
        Assert.Equal(5, TestEnv.Config(new() { ["PAGE_MAX_LIMIT"] = "5", ["PAGE_DEFAULT_LIMIT"] = null }).PageDefaultLimit);
    }

    [Fact]
    public void Topic_must_be_an_arn()
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { ["USERS_TOPIC_ARN"] = "ticketpro-users" }));

        Assert.StartsWith("USERS_TOPIC_ARN:", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Invalid_log_level_names_the_variable()
    {
        var e = Assert.Throws<ConfigException>(() => TestEnv.Config(new() { ["LOG_LEVEL"] = "verbose" }));

        Assert.StartsWith("LOG_LEVEL:", e.Message, StringComparison.Ordinal);
    }
}

public sealed class HealthTests
{
    [Fact]
    public async Task Ready_is_200_with_broker_degraded_when_only_the_broker_is_down()
    {
        var (code, body) = await HealthEndpoints.ReadinessAsync(_ => Task.FromResult(true), () => false, CancellationToken.None);

        Assert.Equal(200, code);
        Assert.Equal("""{"status":"up","checks":{"db":"up","broker":"degraded"}}""", body);
    }

    [Fact]
    public async Task Ready_is_503_when_the_database_is_down()
    {
        var (code, body) = await HealthEndpoints.ReadinessAsync(_ => Task.FromResult(false), () => true, CancellationToken.None);

        Assert.Equal(503, code);
        Assert.Equal("""{"status":"down","checks":{"db":"down","broker":"up"}}""", body);
    }
}
