using PaymentService.App;

var builder = WebApplication.CreateBuilder(args);

// Fail fast on invalid configuration; the exception message names the offending variable.
var config = AppConfig.FromEnvironment(new Dictionary<string, string?>
{
    ["SERVICE_NAME"] = builder.Configuration["SERVICE_NAME"],
    ["PORT"] = builder.Configuration["PORT"],
    ["LOG_LEVEL"] = builder.Configuration["LOG_LEVEL"],
});

builder.WebHost.UseUrls($"http://0.0.0.0:{config.Port}");
builder.Logging.ClearProviders();
builder.Logging.AddJsonConsole(o =>
{
    o.IncludeScopes = true;
    o.UseUtcTimestamp = true;
    o.TimestampFormat = "yyyy-MM-ddTHH:mm:ss.fffZ";
});
builder.Logging.SetMinimumLevel(config.LogLevel switch
{
    "debug" => LogLevel.Debug,
    "warn" => LogLevel.Warning,
    "error" => LogLevel.Error,
    _ => LogLevel.Information,
});
builder.Services.AddSingleton(config);

var app = builder.Build();
app.MapHealthEndpoints();
app.Run();

/// <summary>Exposed for WebApplicationFactory in tests.</summary>
public partial class Program;
