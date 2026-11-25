namespace UserService.Application;

/// <summary>Events this service publishes. Implementations write to the outbox in the caller's transaction
/// (ADR-0005): the event is published only if the transaction that decided it commits.</summary>
public interface IUserEventPublisher
{
    Task UserCreationFailedAsync(Guid userId, string reason, string correlationId, CancellationToken ct);
}
