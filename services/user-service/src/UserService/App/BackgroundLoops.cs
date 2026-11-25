using TicketPro.Platform;

namespace UserService.App;

/// <summary>Runs the vendored SQS consumer (RFC 0003 §5.4) for as long as the host lives.</summary>
public sealed class ConsumerLoop(SqsConsumer consumer) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => consumer.RunAsync(stoppingToken);
}

/// <summary>Runs the vendored outbox relay (RFC 0003 §5.3): drains outbox to SNS with backoff.</summary>
public sealed class OutboxRelayLoop(OutboxRelay relay) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => relay.RunAsync(stoppingToken);
}
