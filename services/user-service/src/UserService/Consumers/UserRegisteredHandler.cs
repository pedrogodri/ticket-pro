using Npgsql;
using TicketPro.Platform;
using UserService.App;
using UserService.Application.Profiles;
using UserService.Domain.Profiles;
using UserService.Infrastructure.Messaging;
using UserService.Infrastructure.Persistence;

namespace UserService.Consumers;

/// <summary>UserRegistered → profile, or UserCreationFailed for auth-service to compensate (arquitetura §5).
/// Runs inside the SqsConsumer transaction that also records processed_messages (RFC 0003 §5.4). A payload
/// that breaks the schema never gets here: the consumer sends it to the DLQ (F4).</summary>
public sealed partial class UserRegisteredHandler(AppConfig config, RegisterProfile registerProfile, ILogger<UserRegisteredHandler> logger)
    : IMessageHandler
{
    public IReadOnlySet<int> Versions { get; } = new HashSet<int> { 1 };

    public async Task HandleAsync(Envelope envelope, NpgsqlConnection connection, NpgsqlTransaction transaction, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var message = Messages.Read<UserRegisteredV1>(envelope);
        var result = await registerProfile.ExecuteAsync(
            new RegisterProfileCommand(message.UserId, message.Email, message.Name, envelope.CorrelationId),
            new ProfileStore(connection, transaction),
            new OutboxUserEventPublisher(connection, transaction, config.UsersTopicArn),
            ct);

        var email = EmailMask.Mask(message.Email);
        switch (result.Outcome)
        {
            case RegisterProfileOutcome.Created:
                LogCreated(logger, message.UserId, email);
                break;
            case RegisterProfileOutcome.AlreadyExists:
                LogAlreadyExists(logger, message.UserId);
                break;
            case RegisterProfileOutcome.RejectedInvalid:
                LogCompensation(logger, message.UserId, email, UserCreationFailureReason.InvalidProfileData, result.Problem ?? "");
                break;
            default:
                LogCompensation(logger, message.UserId, email, UserCreationFailureReason.DuplicateProfile, result.Problem ?? "");
                break;
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "profile_created userId={UserId} email={Email}")]
    private static partial void LogCreated(ILogger logger, Guid userId, string email);

    [LoggerMessage(Level = LogLevel.Information, Message = "profile_already_exists userId={UserId}")]
    private static partial void LogAlreadyExists(ILogger logger, Guid userId);

    [LoggerMessage(Level = LogLevel.Warning, Message = "compensation_requested userId={UserId} email={Email} reason={Reason} problem={Problem}")]
    private static partial void LogCompensation(ILogger logger, Guid userId, string email, string reason, string problem);
}
