using TicketPro.Platform;

namespace UserService.App;

/// <summary>Runs the SQS consumer for the lifetime of the host (RFC 0003 §5.4).</summary>
public sealed class ConsumerHostedService(SqsConsumer consumer) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => consumer.RunAsync(stoppingToken);
}
