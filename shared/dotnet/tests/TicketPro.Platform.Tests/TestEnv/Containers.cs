using Amazon.Runtime;
using Amazon.SimpleNotificationService;
using Amazon.SimpleNotificationService.Model;
using Amazon.SQS;
using Amazon.SQS.Model;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Containers;
using Npgsql;
using Testcontainers.PostgreSql;

namespace TicketPro.Platform.Tests.TestEnv;

/// <summary>
/// Shared Postgres 16 (platform tables) and MiniStack containers for the integration tests.
/// One container of each per test run; tables are truncated per test. Not vendored into services.
/// </summary>
internal static class Containers
{
    public static readonly TimeSpan ClientTimeout = TimeSpan.FromSeconds(2);
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static PostgreSqlContainer? _postgres;
    private static IContainer? _ministack;

    public static async Task<NpgsqlDataSource> PostgresAsync()
    {
        await Gate.WaitAsync();
        try
        {
            if (_postgres is null)
            {
                var pg = new PostgreSqlBuilder("postgres:16-alpine").Build();
                await pg.StartAsync();
                await using var setup = NpgsqlDataSource.Create(pg.GetConnectionString());
                await using var cmd = setup.CreateCommand(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "TestEnv", "platform.sql")));
                await cmd.ExecuteNonQueryAsync();
                _postgres = pg;
            }
        }
        finally
        {
            Gate.Release();
        }
        var ds = NpgsqlDataSource.Create(_postgres.GetConnectionString());
        await using var truncate = ds.CreateCommand("TRUNCATE outbox, processed_messages, effects RESTART IDENTITY");
        await truncate.ExecuteNonQueryAsync();
        return ds;
    }

    public static async Task<IContainer> MiniStackAsync()
    {
        await Gate.WaitAsync();
        try
        {
            if (_ministack is null)
            {
                var ms = new ContainerBuilder("ministackorg/ministack:1.5.40")
                    .WithEnvironment("MINISTACK_REGION", "us-east-1")
                    .WithPortBinding(4566, true)
                    .WithWaitStrategy(Wait.ForUnixContainer().UntilHttpRequestIsSucceeded(r => r.ForPort(4566).ForPath("/_ministack/health")))
                    .Build();
                await ms.StartAsync();
                _ministack = ms;
            }
            return _ministack;
        }
        finally
        {
            Gate.Release();
        }
    }

    private static string Endpoint(IContainer ms) => $"http://{ms.Hostname}:{ms.GetMappedPublicPort(4566)}";

    private static readonly BasicAWSCredentials Credentials = new("test", "test");

    public static async Task<IAmazonSQS> SqsAsync(TimeSpan? timeout = null)
    {
        var ms = await MiniStackAsync();
        var config = new AmazonSQSConfig { ServiceURL = Endpoint(ms), AuthenticationRegion = "us-east-1" };
        AwsClientConfig.Apply(config, timeout ?? ClientTimeout);
        return new AmazonSQSClient(Credentials, config);
    }

    public static async Task<IAmazonSimpleNotificationService> SnsAsync()
    {
        var ms = await MiniStackAsync();
        var config = new AmazonSimpleNotificationServiceConfig { ServiceURL = Endpoint(ms), AuthenticationRegion = "us-east-1" };
        AwsClientConfig.Apply(config, ClientTimeout);
        return new AmazonSimpleNotificationServiceClient(Credentials, config);
    }

    /// <summary>Creates a queue with a DLQ (redrive after maxReceiveCount); returns both URLs.</summary>
    public static async Task<(string Queue, string Dlq)> QueueAsync(IAmazonSQS sqs, string name, int maxReceiveCount)
    {
        name = $"{name}-{Guid.NewGuid():N}";
        var dlq = await sqs.CreateQueueAsync(name + "-dlq");
        var dlqArn = await QueueArnAsync(sqs, dlq.QueueUrl);
        var queue = await sqs.CreateQueueAsync(new CreateQueueRequest
        {
            QueueName = name,
            Attributes = new()
            {
                ["VisibilityTimeout"] = "30",
                ["RedrivePolicy"] = $$"""{"deadLetterTargetArn":"{{dlqArn}}","maxReceiveCount":"{{maxReceiveCount}}"}""",
            },
        });
        return (queue.QueueUrl, dlq.QueueUrl);
    }

    public static async Task<string> QueueArnAsync(IAmazonSQS sqs, string url) =>
        (await sqs.GetQueueAttributesAsync(url, ["QueueArn"])).Attributes["QueueArn"];

    /// <summary>Creates a topic subscribed by the queue with raw message delivery; returns its ARN.</summary>
    public static async Task<string> TopicAsync(IAmazonSimpleNotificationService sns, IAmazonSQS sqs, string name, string queueUrl)
    {
        var topic = await sns.CreateTopicAsync($"{name}-{Guid.NewGuid():N}");
        await sns.SubscribeAsync(new SubscribeRequest
        {
            TopicArn = topic.TopicArn,
            Protocol = "sqs",
            Endpoint = await QueueArnAsync(sqs, queueUrl),
            Attributes = new() { ["RawMessageDelivery"] = "true" },
        });
        return topic.TopicArn;
    }

    public static async Task<int> QueueDepthAsync(IAmazonSQS sqs, string url)
    {
        var attrs = (await sqs.GetQueueAttributesAsync(url, ["ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"])).Attributes;
        return int.Parse(attrs["ApproximateNumberOfMessages"], System.Globalization.CultureInfo.InvariantCulture)
            + int.Parse(attrs["ApproximateNumberOfMessagesNotVisible"], System.Globalization.CultureInfo.InvariantCulture);
    }

    public static async Task<long> CountAsync(NpgsqlDataSource ds, string sql, params object[] args)
    {
        await using var cmd = ds.CreateCommand(sql);
        foreach (var a in args)
        {
            cmd.Parameters.Add(new NpgsqlParameter { Value = a });
        }
        return Convert.ToInt64(await cmd.ExecuteScalarAsync(), System.Globalization.CultureInfo.InvariantCulture);
    }
}
