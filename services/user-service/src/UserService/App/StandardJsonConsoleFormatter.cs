using System.Globalization;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Logging.Console;
using Microsoft.Extensions.Options;

namespace UserService.App;

/// <summary>Options for <see cref="StandardJsonConsoleFormatter"/>.</summary>
public sealed class StandardJsonFormatterOptions : ConsoleFormatterOptions
{
    public string ServiceName { get; set; } = string.Empty;
}

/// <summary>
/// Console formatter following docs/padroes.md §5: one JSON line with
/// timestamp (RFC 3339 UTC), level (debug|info|warn|error), service, message and optional error.
/// </summary>
public sealed class StandardJsonConsoleFormatter : ConsoleFormatter
{
    public const string FormatterName = "standard-json";

    private readonly StandardJsonFormatterOptions _options;

    public StandardJsonConsoleFormatter(IOptions<StandardJsonFormatterOptions> options)
        : base(FormatterName)
    {
        ArgumentNullException.ThrowIfNull(options);
        _options = options.Value;
    }

    public override void Write<TState>(in LogEntry<TState> logEntry, IExternalScopeProvider? scopeProvider, TextWriter textWriter)
    {
        ArgumentNullException.ThrowIfNull(textWriter);
        var message = logEntry.Formatter(logEntry.State, logEntry.Exception);

        using var buffer = new MemoryStream();
        using (var json = new Utf8JsonWriter(buffer))
        {
            json.WriteStartObject();
            json.WriteString("timestamp", DateTimeOffset.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ", CultureInfo.InvariantCulture));
            json.WriteString("level", ToStandardLevel(logEntry.LogLevel));
            json.WriteString("service", _options.ServiceName);
            json.WriteString("message", message);
            json.WriteString("category", logEntry.Category);
            if (logEntry.Exception is { } ex)
            {
                json.WriteStartObject("error");
                json.WriteString("kind", ex.GetType().Name);
                json.WriteString("message", ex.Message);
                json.WriteString("stack", ex.StackTrace);
                json.WriteEndObject();
            }
            json.WriteEndObject();
        }

        textWriter.Write(Encoding.UTF8.GetString(buffer.ToArray()));
        textWriter.Write(Environment.NewLine);
    }

    private static string ToStandardLevel(LogLevel level) => level switch
    {
        LogLevel.Trace or LogLevel.Debug => "debug",
        LogLevel.Information => "info",
        LogLevel.Warning => "warn",
        _ => "error",
    };
}
