using Amazon;
using Amazon.SimpleNotificationService;
using Amazon.SQS;
using Microsoft.Extensions.Logging.Console;
using Npgsql;
using PaymentService.App;
using PaymentService.Modules.Payments;
using PaymentService.Modules.Payments.Consumers;
using PaymentService.Modules.Payments.Provider;
using PaymentService.Modules.Payments.Workers;
using TicketPro.Platform;

// 1. Configuration first: an invalid value stops the boot and names the variable (docs/padroes.md §8).
AppConfig config;
try
{
    config = AppConfig.Load(Environment.GetEnvironmentVariable);
}
catch (ConfigException e)
{
    await Console.Error.WriteLineAsync($"invalid configuration: {e.Message}");
    return 1;
}

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls($"http://0.0.0.0:{config.Port}");

// 2. Structured JSON logs (docs/padroes.md §5), same shape as the Go services.
builder.Logging.ClearProviders();
builder.Logging.SetMinimumLevel(config.LogLevel);
builder.Logging
    .AddConsole(o => o.FormatterName = StandardJsonConsoleFormatter.FormatterName)
    .AddConsoleFormatter<StandardJsonConsoleFormatter, StandardJsonFormatterOptions>(o => o.ServiceName = config.ServiceName);

// 3. Databases (docs/padroes.md §6.1): the app role only answers readiness; everything that processes
//    events runs as the worker role.
var appDb = NpgsqlDataSource.Create(config.DatabaseUrl);
var workerDb = NpgsqlDataSource.Create(config.DatabaseWorkerUrl);

// 4. AWS clients with SDK retries off (our backoff owns retrying, RFC 0003 §5.5).
var consumerOptions = new SqsConsumerOptions
{
    QueueUrl = config.QueueUrl,
    Name = config.ServiceName,
    Concurrency = config.ConsumerConcurrency,
    WaitTimeSeconds = config.SqsWaitTimeSeconds,
    RequestTimeout = config.HttpClientTimeout,
};
var sqsConfig = AwsClientConfig.Apply(new AmazonSQSConfig(), AwsClientConfig.LongPollTimeout(consumerOptions));
var snsConfig = AwsClientConfig.Apply(new AmazonSimpleNotificationServiceConfig(), config.HttpClientTimeout);
if (config.AwsEndpointUrl is { } endpoint)
{
    (sqsConfig.ServiceURL, sqsConfig.AuthenticationRegion) = (endpoint, config.AwsRegion);
    (snsConfig.ServiceURL, snsConfig.AuthenticationRegion) = (endpoint, config.AwsRegion);
}
else
{
    sqsConfig.RegionEndpoint = snsConfig.RegionEndpoint = RegionEndpoint.GetBySystemName(config.AwsRegion);
}

// 5. Wiring.
var services = builder.Services;
services.AddSingleton(config);
services.AddSingleton(TimeProvider.System);
services.AddSingleton(workerDb);
services.AddSingleton<IAmazonSQS>(_ => new AmazonSQSClient(sqsConfig));
services.AddSingleton<IAmazonSimpleNotificationService>(_ => new AmazonSimpleNotificationServiceClient(snsConfig));
services.AddSingleton(new ContractValidator(config.ContractsDir));

services.AddSingleton(new SimulationOptions(config.PaymentLatency, config.PaymentLatencyJitter, config.PaymentFailureRate));
services.AddSingleton<IPaymentProvider>(sp => new SimulatedPaymentProvider(
    workerDb, sp.GetRequiredService<SimulationOptions>(), sp.GetRequiredService<TimeProvider>(), Random.Shared.NextDouble,
    sp.GetRequiredService<ILogger<SimulatedPaymentProvider>>()));
services.AddSingleton<PaymentRequestedHandler>();
services.AddSingleton<RefundRequestedHandler>();

services.AddSingleton(sp => new SqsConsumer(
    sp.GetRequiredService<IAmazonSQS>(), workerDb, sp.GetRequiredService<ContractValidator>(),
    new Dictionary<string, IMessageHandler>
    {
        [PaymentEvents.PaymentRequested] = sp.GetRequiredService<PaymentRequestedHandler>(),
        [PaymentEvents.RefundRequested] = sp.GetRequiredService<RefundRequestedHandler>(),
    },
    consumerOptions, sp.GetRequiredService<ILogger<SqsConsumer>>()));
services.AddSingleton(sp => new OutboxRelay(
    workerDb, new SnsOutboxPublisher(sp.GetRequiredService<IAmazonSimpleNotificationService>()),
    new OutboxRelayOptions
    {
        BatchSize = config.OutboxBatchSize,
        PollInterval = config.OutboxPollInterval,
        MaxBackoff = config.OutboxMaxBackoff,
        ClaimLease = config.OutboxClaimLease,
    },
    sp.GetRequiredService<ILogger<OutboxRelay>>()));

// Tracing (ADR-0012): only with OTEL_EXPORTER_OTLP_ENDPOINT; polling stays out of Jaeger.
services.AddTracing(config);

services.AddHostedService<ConsumerLoop>();
services.AddHostedService<OutboxRelayLoop>();
services.AddHostedService<PaymentWorker>();

// 6. HTTP: only the probes; this service has no business API, only events.
var app = builder.Build();
var consumer = app.Services.GetRequiredService<SqsConsumer>();
var relay = app.Services.GetRequiredService<OutboxRelay>();
HealthEndpoints.Map(app, appDb, () => consumer.BrokerUp && relay.BrokerUp);

await app.RunAsync();
await appDb.DisposeAsync();
await workerDb.DisposeAsync();
return 0;
