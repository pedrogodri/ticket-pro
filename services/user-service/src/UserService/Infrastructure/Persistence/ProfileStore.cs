using Npgsql;
using UserService.Application.Profiles;
using UserService.Domain.Profiles;

namespace UserService.Infrastructure.Persistence;

/// <summary>profiles, inside the consumer's transaction (worker role).</summary>
public sealed class ProfileStore(NpgsqlConnection connection, NpgsqlTransaction transaction) : IProfileStore
{
    public async Task<ProfileInsertResult> TryInsertAsync(Profile profile, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(profile);
        // No conflict target: both the user_id PK and the lower(email) unique index (V0006) end in DO NOTHING.
        // A concurrent insert of the same key on another replica waits here for that transaction to finish.
        await using (var insert = new NpgsqlCommand(
            "INSERT INTO profiles (user_id, email, name) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING", connection, transaction))
        {
            insert.Parameters.Add(new NpgsqlParameter { Value = profile.UserId });
            insert.Parameters.Add(new NpgsqlParameter { Value = profile.Email });
            insert.Parameters.Add(new NpgsqlParameter { Value = profile.Name });
            if (await insert.ExecuteNonQueryAsync(ct) == 1)
            {
                return ProfileInsertResult.Inserted;
            }
        }

        await using var exists = new NpgsqlCommand(
            "SELECT EXISTS (SELECT 1 FROM profiles WHERE user_id = $1)", connection, transaction);
        exists.Parameters.Add(new NpgsqlParameter { Value = profile.UserId });
        return (bool)(await exists.ExecuteScalarAsync(ct))!
            ? ProfileInsertResult.SameUserExists
            : ProfileInsertResult.EmailTaken;
    }
}
