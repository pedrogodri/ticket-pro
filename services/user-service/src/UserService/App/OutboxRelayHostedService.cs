using TicketPro.Platform;

namespace UserService.App;

/// <summary>Runs the outbox relay for the lifetime of the host (ADR-0005).</summary>
public sealed class OutboxRelayHostedService(OutboxRelay relay) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => relay.RunAsync(stoppingToken);
}
