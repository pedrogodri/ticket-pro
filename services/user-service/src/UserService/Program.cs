using Amazon;
using Amazon.SimpleNotificationService;
using Amazon.SQS;
using Microsoft.Extensions.Logging.Console;
using Npgsql;
using TicketPro.Platform;
using UserService.App;
using UserService.Application.Orders;
using UserService.Application.Profiles;
using UserService.Application.Queries;
using UserService.Application.Tickets;
using UserService.Consumers;
using UserService.Http;
using UserService.Infrastructure.Messaging;
using UserService.Infrastructure.Persistence;

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

// 2. Structured JSON logs (docs/padroes.md §5), same shape as the other services.
builder.Logging.ClearProviders();
builder.Logging.SetMinimumLevel(config.LogLevel);
// ASP.NET logs several info lines per request; with a healthcheck every 5 s per replica they would drown the
// demo's events (message_consumed, compensation_requested...). Its warnings and errors still show.
builder.Logging.AddFilter("Microsoft.AspNetCore", LogLevel.Warning);
builder.Logging
    .AddConsole(o => o.FormatterName = StandardJsonConsoleFormatter.FormatterName)
    .AddConsoleFormatter<StandardJsonConsoleFormatter, StandardJsonFormatterOptions>(o => o.ServiceName = config.ServiceName);

// 3. Databases (docs/padroes.md §6.1): users_app (RLS) serves /me* and readiness; users_worker (BYPASSRLS)
//    runs the consumers and the outbox relay, which handle events of every user.
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
services.AddSingleton<IAmazonSQS>(_ => new AmazonSQSClient(sqsConfig));
services.AddSingleton<IAmazonSimpleNotificationService>(_ => new AmazonSimpleNotificationServiceClient(snsConfig));
services.AddSingleton(new ContractValidator(config.ContractsDir));

services.AddSingleton(new RegisterProfile(config.ProfileBlockedEmailDomains));
services.AddSingleton<ProjectOrderConfirmed>();
services.AddSingleton<ProjectTicketsIssued>();
services.AddSingleton<IUserReadStore>(new UserReadStore(appDb));
services.AddSingleton<UserQueries>();
services.AddSingleton<UserRegisteredHandler>();
services.AddSingleton<OrderConfirmedHandler>();
services.AddSingleton<TicketsIssuedHandler>();

services.AddSingleton(sp => new SqsConsumer(
    sp.GetRequiredService<IAmazonSQS>(), workerDb, sp.GetRequiredService<ContractValidator>(),
    new Dictionary<string, IMessageHandler>
    {
        [Messages.UserRegistered] = sp.GetRequiredService<UserRegisteredHandler>(),
        [Messages.OrderConfirmed] = sp.GetRequiredService<OrderConfirmedHandler>(),
        [Messages.TicketsIssued] = sp.GetRequiredService<TicketsIssuedHandler>(),
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

services.AddHostedService<ConsumerLoop>();
services.AddHostedService<OutboxRelayLoop>();

// 6. HTTP: probes + /me, /me/tickets, /me/orders.
var app = builder.Build();
ErrorHandling.Use(app);
var consumer = app.Services.GetRequiredService<SqsConsumer>();
var relay = app.Services.GetRequiredService<OutboxRelay>();
HealthEndpoints.Map(app, appDb, () => consumer.BrokerUp && relay.BrokerUp);
UserEndpoints.Map(app);

await app.RunAsync();
await appDb.DisposeAsync();
await workerDb.DisposeAsync();
return 0;
