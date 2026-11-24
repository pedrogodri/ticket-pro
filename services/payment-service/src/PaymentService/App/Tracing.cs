using System.Diagnostics;
using OpenTelemetry;
using OpenTelemetry.Exporter;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using TicketPro.Platform;

namespace PaymentService.App;

/// <summary>Parent-based sampling that drops root Client spans. Those are the worker's and relay's polling
/// queries (Npgsql spans with no message around them), which would otherwise flood Jaeger several times a
/// second. Message work always has a consumer, producer or internal span above it and is recorded.</summary>
public sealed class QuietPollingSampler : Sampler
{
    private static readonly Sampler Inner = new ParentBasedSampler(new AlwaysOnSampler());
    private static readonly SamplingResult Dropped = new(SamplingDecision.Drop);

    public override SamplingResult ShouldSample(in SamplingParameters samplingParameters) =>
        samplingParameters.ParentContext.TraceId == default && samplingParameters.Kind == ActivityKind.Client
            ? Dropped
            : Inner.ShouldSample(samplingParameters);
}

/// <summary>OpenTelemetry tracing (ADR-0012), exported over OTLP gRPC to the collector: the platform's
/// messaging spans (including the worker's "charge payment") and the Npgsql queries inside them.</summary>
public static class Tracing
{
    /// <summary>Registers the SDK when OTEL_EXPORTER_OTLP_ENDPOINT is set; without it nothing is exported
    /// and the platform still propagates the traceparent.</summary>
    public static void AddTracing(this IServiceCollection services, AppConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        if (config.OtlpEndpoint is not { } endpoint)
        {
            return;
        }
        services.AddOpenTelemetry()
            .ConfigureResource(r => r.AddService(config.ServiceName))
            .WithTracing(t => t
                .AddSource(Spans.SourceName, "Npgsql")
                .SetSampler(new QuietPollingSampler())
                .AddOtlpExporter(o =>
                {
                    o.Endpoint = endpoint;
                    o.Protocol = OtlpExportProtocol.Grpc;
                }));
    }
}
