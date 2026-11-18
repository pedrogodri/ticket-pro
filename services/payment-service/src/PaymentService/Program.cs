using Amazon.SimpleNotificationService;
using Amazon.SQS;
using Npgsql;
using PaymentService.App;
using PaymentService.Modules.Payments.Consumers;
using PaymentService.Modules.Payments.Provider;
using PaymentService.Modules.Payments.Workers;
using TicketPro.Platform;

var builder = WebApplication.CreateBuilder(args);

// Fail fast on invalid configuration; the exception message names the offending variable.
var config = AppConfig.FromEnvironment(AppConfig.Variables.ToDictionary(v => v, v => builder.Configuration[v]));

builder.WebHost.UseUrls($"http://0.0.0.0:{config.Port}");
builder.Logging.ClearProviders();
builder.Logging
    .AddConsole(o => o.FormatterName = StandardJsonConsoleFormatter.FormatterName)
    .AddConsoleFormatter<StandardJsonConsoleFormatter, StandardJsonFormatterOptions>(o =>
    {
        o.ServiceName = config.ServiceName;
        o.IncludeScopes = true;
    });
builder.Logging.SetMinimumLevel(config.LogLevel switch
{
    "debug" => LogLevel.Debug,
    "warn" => LogLevel.Warning,
    "error" => LogLevel.Error,
    _ => LogLevel.Information,
});

// API role (RLS) serves readiness and requests; the worker role runs the relay and, in F2, the consumers.
var appDb = NpgsqlDataSource.Create(config.DatabaseUrl);
var workerDb = NpgsqlDataSource.Create(config.DatabaseWorkerUrl);
var snsConfig = AwsClientConfig.Apply(new AmazonSimpleNotificationServiceConfig(), config.HttpClientTimeout);
if (builder.Configuration["AWS_ENDPOINT_URL"] is { Length: > 0 } endpoint)
{
    snsConfig.ServiceURL = endpoint;
    snsConfig.AuthenticationRegion = builder.Configuration["AWS_REGION"];
}

builder.Services.AddSingleton(config);
builder.Services.AddSingleton<IAmazonSimpleNotificationService>(_ => new AmazonSimpleNotificationServiceClient(snsConfig));
builder.Services.AddSingleton<IOutboxPublisher, SnsOutboxPublisher>();
builder.Services.AddSingleton(sp => new OutboxRelay(workerDb, sp.GetRequiredService<IOutboxPublisher>(), config.Outbox,
    sp.GetRequiredService<ILogger<OutboxRelay>>()));
builder.Services.AddHostedService<OutboxRelayHostedService>();

// Consumer: its own SQS client, whose timeout allows the long poll (AwsClientConfig.LongPollTimeout).
var consumerOptions = new SqsConsumerOptions
{
    QueueUrl = config.QueueUrl,
    Name = config.ServiceName,
    Concurrency = config.ConsumerConcurrency,
    WaitTimeSeconds = config.SqsWaitTimeSeconds,
    RequestTimeout = config.HttpClientTimeout,
};
var sqsConfig = AwsClientConfig.Apply(new AmazonSQSConfig(), AwsClientConfig.LongPollTimeout(consumerOptions));
if (builder.Configuration["AWS_ENDPOINT_URL"] is { Length: > 0 } sqsEndpoint)
{
    sqsConfig.ServiceURL = sqsEndpoint;
    sqsConfig.AuthenticationRegion = builder.Configuration["AWS_REGION"];
}
builder.Services.AddSingleton(sp => new SqsConsumer(
    new AmazonSQSClient(sqsConfig), workerDb, new ContractValidator(config.ContractsDir),
    new Dictionary<string, IMessageHandler>
    {
        ["PaymentRequested"] = new PaymentRequestedHandler(),
        ["RefundRequested"] = new RefundRequestedHandler(config.TopicArn),
    },
    consumerOptions, sp.GetRequiredService<ILogger<SqsConsumer>>()));
builder.Services.AddHostedService<ConsumerHostedService>();

// Worker: leased claim + simulated provider with a hard timeout (RFC 0003 §5.6).
builder.Services.AddSingleton<IPaymentProvider>(_ => new SimulatedPaymentProvider(
    new ProviderOptions(config.Payment.Latency, config.Payment.Jitter, config.Payment.FailureRate),
    new PostgresChargeStore(workerDb), Random.Shared.NextDouble, Task.Delay));
builder.Services.AddSingleton(sp => new PaymentWorker(workerDb, sp.GetRequiredService<IPaymentProvider>(),
    new WorkerOptions(config.Payment.WorkerLease, config.Payment.ProviderTimeout, config.TopicArn),
    sp.GetRequiredService<ILogger<PaymentWorker>>()));
builder.Services.AddHostedService<PaymentWorkerHostedService>();

var app = builder.Build();
var relay = app.Services.GetRequiredService<OutboxRelay>();
var consumer = app.Services.GetRequiredService<SqsConsumer>();
app.MapHealthEndpoints(async ct =>
{
    try
    {
        // A pooled connection can be returned without a round trip; SELECT 1 proves the database answers.
        await using var command = appDb.CreateCommand("SELECT 1");
        await command.ExecuteScalarAsync(ct);
        return true;
    }
    catch (Exception e) when (e is NpgsqlException or OperationCanceledException or TimeoutException or System.Net.Sockets.SocketException)
    {
        return false;
    }
}, () => relay.BrokerUp && consumer.BrokerUp);
app.Lifetime.ApplicationStopped.Register(() =>
{
    appDb.Dispose();
    workerDb.Dispose();
});
app.Run();

/// <summary>Exposed for WebApplicationFactory in tests.</summary>
public partial class Program;
