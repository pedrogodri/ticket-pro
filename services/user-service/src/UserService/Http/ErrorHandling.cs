using Microsoft.AspNetCore.Diagnostics;

namespace UserService.Http;

/// <summary>Correlation scope for request logs and the catch-all 500 (docs/padroes.md §4.1, §5): the
/// response never carries a stack trace, the log does.</summary>
public static partial class ErrorHandling
{
    public static void Use(WebApplication app)
    {
        ArgumentNullException.ThrowIfNull(app);
        var logger = app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("UserService.Http");

        app.UseExceptionHandler(errorApp => errorApp.Run(async context =>
        {
            var error = context.Features.Get<IExceptionHandlerFeature>()?.Error;
            LogFailed(logger, error, context.Request.Path, RequestContext.CorrelationId(context));
            var result = Problems.Of(context, Errors.Internal, Errors.Unexpected);
            await result.ExecuteAsync(context);
        }));

        app.Use(async (context, next) =>
        {
            using var scope = logger.BeginScope(new Dictionary<string, object?>
            {
                ["correlationId"] = RequestContext.CorrelationId(context),
            });
            await next(context);
        });
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "request_failed path={Path} correlationId={CorrelationId}")]
    private static partial void LogFailed(ILogger logger, Exception? error, string path, string? correlationId);
}
