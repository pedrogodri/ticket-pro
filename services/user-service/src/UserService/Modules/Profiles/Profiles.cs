using Npgsql;
using TicketPro.Platform;
using UserService.App;

namespace UserService.Modules.Profiles;

/// <summary>GET /me response.</summary>
public sealed record ProfileView(string UserId, string Email, string Name);

/// <summary>UserRegistered → profile (idempotent: the same user twice is a no-op).</summary>
public sealed class UserRegisteredHandler : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var p = envelope.Payload;
        await using var cmd = new NpgsqlCommand(
            "INSERT INTO profiles (user_id, email, name) VALUES ($1, $2, $3) ON CONFLICT (user_id) DO NOTHING", connection, transaction);
        cmd.Parameters.Add(new NpgsqlParameter { Value = Guid.Parse(p.GetProperty("userId").GetString()!) });
        cmd.Parameters.Add(new NpgsqlParameter { Value = p.GetProperty("email").GetString()! });
        cmd.Parameters.Add(new NpgsqlParameter { Value = p.GetProperty("name").GetString()! });
        await cmd.ExecuteNonQueryAsync(ct);
    }
}

/// <summary>Reads the caller's profile through the API role (RLS).</summary>
public sealed class ProfileQueries(NpgsqlDataSource app)
{
    /// <summary>The profile, or null while it is not projected yet (eventual consistency, arquitetura §5).</summary>
    public Task<ProfileView?> GetAsync(string userId, CancellationToken ct) =>
        UserScope.RunAsync<ProfileView?>(app, userId, async (conn, tx) =>
        {
            await using var cmd = new NpgsqlCommand("SELECT user_id::text, email, name FROM profiles WHERE user_id = $1::uuid", conn, tx);
            cmd.Parameters.Add(new NpgsqlParameter { Value = userId });
            await using var r = await cmd.ExecuteReaderAsync(ct);
            return await r.ReadAsync(ct) ? new ProfileView(r.GetString(0), r.GetString(1), r.GetString(2)) : null;
        }, ct);
}
