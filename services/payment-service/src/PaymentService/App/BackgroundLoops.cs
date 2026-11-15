using PaymentService.Modules.Payments.Workers;
using TicketPro.Platform;

namespace PaymentService.App;

/// <summary>Runs the SQS consumer for the lifetime of the host (RFC 0003 §5.4).</summary>
public sealed class ConsumerHostedService(SqsConsumer consumer) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => consumer.RunAsync(stoppingToken);
}

/// <summary>Polls PENDING payments every PAYMENT_WORKER_POLL_MS; drains while there is work.</summary>
public sealed partial class PaymentWorkerHostedService(PaymentWorker worker, AppConfig config, ILogger<PaymentWorkerHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (await worker.ProcessOnceAsync(stoppingToken))
                {
                    continue;
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e) when (e is Npgsql.NpgsqlException or TimeoutException or InvalidOperationException)
            {
                LogWorkerError(logger, e.Message); // the lease returns the payment to the queue
            }
            try
            {
                await Task.Delay(config.Payment.WorkerPoll, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "payment_worker_error error={Error}")]
    private static partial void LogWorkerError(ILogger logger, string error);
}
