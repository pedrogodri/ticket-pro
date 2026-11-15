using System.Text.Json.Nodes;
using Npgsql;
using TicketPro.Platform;
using UserService.Modules.MyTickets;
using UserService.Modules.Profiles;

namespace UserService.Tests.Integration;

/// <summary>Profiles and the "my tickets" read model are built only from events (CQRS, arquitetura §5).</summary>
[Trait("Category", "Integration")]
[Collection("users-db")]
public sealed class ReadModelTests
{
    private static Envelope Example(string name, Action<JsonObject> edit)
    {
        var node = JsonNode.Parse(File.ReadAllText(Path.Combine(ContractsDir(), "examples", name + ".json")))!.AsObject();
        edit((JsonObject)node["payload"]!);
        return EnvelopeJson.Unwrap(node.ToJsonString());
    }

    private static async Task Handle(NpgsqlDataSource ds, IMessageHandler handler, Envelope env)
    {
        await using var conn = await ds.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        await handler.HandleAsync(env, conn, tx, CancellationToken.None);
        await tx.CommitAsync();
    }

    private static Envelope Issued(string userId, string orderId, params string[] codes) => Example("TicketsIssued.v1", p =>
    {
        p["userId"] = userId;
        p["orderId"] = orderId;
        var tickets = new JsonArray();
        foreach (var code in codes)
        {
            tickets.Add(new JsonObject { ["ticketId"] = Guid.NewGuid().ToString(), ["code"] = code });
        }
        p["tickets"] = tickets;
    });

    [Fact]
    public async Task UserRegisteredCreatesTheProfileOnce()
    {
        await using var ds = await TestDatabase.CreateAsync();
        await using var app = await TestDatabase.CreateAppRoleAsync();
        var user = Guid.NewGuid().ToString();
        var env = Example("UserRegistered.v1", p => { p["userId"] = user; p["email"] = $"{user}@exemplo.com"; });
        await Handle(ds, new UserRegisteredHandler(), env);
        await Handle(ds, new UserRegisteredHandler(), env);

        var me = await new ProfileQueries(app).GetAsync(user, CancellationToken.None);
        Assert.NotNull(me);
        Assert.Equal($"{user}@exemplo.com", me.Email);
        Assert.Null(await new ProfileQueries(app).GetAsync(Guid.NewGuid().ToString(), CancellationToken.None));
    }

    [Fact]
    public async Task TicketsIssuedProjectsTicketsOnceAndOnlyForTheirOwner()
    {
        await using var ds = await TestDatabase.CreateAsync();
        await using var app = await TestDatabase.CreateAppRoleAsync();
        var (alice, bob) = (Guid.NewGuid().ToString(), Guid.NewGuid().ToString());
        var env = Issued(alice, Guid.NewGuid().ToString(), $"A{alice[..3].ToUpperInvariant()}-0001-AAAA", $"A{alice[..3].ToUpperInvariant()}-0002-AAAA");
        await Handle(ds, new TicketsIssuedHandler(), env);
        await Handle(ds, new TicketsIssuedHandler(), env); // redelivery: no duplicate rows

        var queries = new MyTicketsQueries(app);
        var page = await queries.ListAsync(alice, 50, null, CancellationToken.None);
        Assert.Equal(2, page.Items.Count);
        Assert.All(page.Items, t => Assert.Equal("Festival de Verão 2026", t.EventName));
        Assert.Empty((await queries.ListAsync(bob, 50, null, CancellationToken.None)).Items);
    }

    [Fact]
    public async Task MyTicketsPagesWithACursor()
    {
        await using var ds = await TestDatabase.CreateAsync();
        await using var app = await TestDatabase.CreateAppRoleAsync();
        var user = Guid.NewGuid().ToString();
        var prefix = user[..4].ToUpperInvariant();
        await Handle(ds, new TicketsIssuedHandler(), Issued(user, Guid.NewGuid().ToString(), $"{prefix}-0001-BBBB", $"{prefix}-0002-BBBB", $"{prefix}-0003-BBBB"));
        var queries = new MyTicketsQueries(app);
        var first = await queries.ListAsync(user, 2, null, CancellationToken.None);
        Assert.Equal(2, first.Items.Count);
        Assert.NotNull(first.NextCursor);
        var second = await queries.ListAsync(user, 2, first.NextCursor, CancellationToken.None);
        Assert.Single(second.Items);
        Assert.Null(second.NextCursor);
    }

    [Fact]
    public async Task OrderConfirmedIsAcceptedWithoutEffect()
    {
        await using var ds = await TestDatabase.CreateAsync();
        await Handle(ds, new OrderConfirmedHandler(), Example("OrderConfirmed.v1", _ => { }));
    }

    private static string ContractsDir()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "contracts");
            if (File.Exists(Path.Combine(candidate, ".vendored.json")))
            {
                return candidate;
            }
        }
        throw new DirectoryNotFoundException("contracts/");
    }
}

[CollectionDefinition("users-db", DisableParallelization = true)]
public sealed class UsersDbGroup;
