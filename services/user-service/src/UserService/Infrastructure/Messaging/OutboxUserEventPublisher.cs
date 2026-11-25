using Npgsql;
using TicketPro.Platform;
using UserService.Application;

namespace UserService.Infrastructure.Messaging;

/// <summary>Writes this service's events to the outbox in the consumer's transaction (ADR-0005); the vendored
/// OutboxRelay publishes them to USERS_TOPIC_ARN after the commit.</summary>
public sealed class OutboxUserEventPublisher(NpgsqlConnection connection, NpgsqlTransaction transaction, string usersTopicArn)
    : IUserEventPublisher
{
    public Task UserCreationFailedAsync(Guid userId, string reason, string correlationId, CancellationToken ct) =>
        OutboxWriter.EnqueueAsync(connection, transaction, usersTopicArn,
            Messages.Create(correlationId, Messages.UserCreationFailed, new UserCreationFailedV1(userId, reason)), ct);
}
