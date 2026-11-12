using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using PaymentService.App;

namespace PaymentService.Tests;

// docs/padroes.md §5: one JSON line with timestamp, level (lowercase), service, message.
public class StandardJsonConsoleFormatterTests
{
    private static JsonElement Format(LogLevel level, string message, Exception? exception = null)
    {
        var formatter = new StandardJsonConsoleFormatter(
            Options.Create(new StandardJsonFormatterOptions { ServiceName = "payment-service" }));
        using var writer = new StringWriter();
        var entry = new LogEntry<string>(level, "PaymentService.Test", new EventId(0), message, exception, (s, _) => s);

        formatter.Write(in entry, scopeProvider: null, writer);

        var text = writer.ToString();
        Assert.EndsWith(Environment.NewLine, text, StringComparison.Ordinal);
        return JsonDocument.Parse(text).RootElement.Clone();
    }

    [Fact]
    public void Write_EmitsStandardKeys()
    {
        var line = Format(LogLevel.Warning, "http_server_started");

        Assert.Equal("warn", line.GetProperty("level").GetString());
        Assert.Equal("http_server_started", line.GetProperty("message").GetString());
        Assert.Equal("payment-service", line.GetProperty("service").GetString());
        Assert.True(DateTimeOffset.TryParse(line.GetProperty("timestamp").GetString(), out _));
    }

    [Theory]
    [InlineData(LogLevel.Trace, "debug")]
    [InlineData(LogLevel.Debug, "debug")]
    [InlineData(LogLevel.Information, "info")]
    [InlineData(LogLevel.Warning, "warn")]
    [InlineData(LogLevel.Error, "error")]
    [InlineData(LogLevel.Critical, "error")]
    public void Write_MapsLevelsToStandardNames(LogLevel level, string expected)
    {
        Assert.Equal(expected, Format(level, "x").GetProperty("level").GetString());
    }

    [Fact]
    public void Write_IncludesErrorKindAndMessage()
    {
        var line = Format(LogLevel.Error, "server_failed", new InvalidOperationException("boom"));

        var error = line.GetProperty("error");
        Assert.Equal("InvalidOperationException", error.GetProperty("kind").GetString());
        Assert.Equal("boom", error.GetProperty("message").GetString());
    }
}
