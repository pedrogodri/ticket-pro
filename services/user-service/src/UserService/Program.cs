using Amazon.SimpleNotificationService;
using Npgsql;
using UserService.App;
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

var app = builder.Build();
var relay = app.Services.GetRequiredService<OutboxRelay>();
app.MapHealthEndpoints(async ct =>
{
    try
    {
        await using var connection = await appDb.OpenConnectionAsync(ct);
        return true;
    }
    catch (Exception e) when (e is NpgsqlException or OperationCanceledException or TimeoutException or System.Net.Sockets.SocketException)
    {
        return false;
    }
}, () => relay.BrokerUp);
app.Lifetime.ApplicationStopped.Register(() =>
{
    appDb.Dispose();
    workerDb.Dispose();
});
app.Run();

/// <summary>Exposed for WebApplicationFactory in tests.</summary>
public partial class Program;
