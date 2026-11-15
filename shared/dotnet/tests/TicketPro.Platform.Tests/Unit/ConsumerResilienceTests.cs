using Amazon.Runtime;
using Amazon.SQS;
using Amazon.SQS.Model;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;

namespace TicketPro.Platform.Tests.Unit;

/// <summary>Review finding: transport failures on ack/visibility must not escape ProcessAsync (they would
/// fault RunAsync and stop the host).</summary>
public sealed class ConsumerResilienceTests
{
    [Fact]
    public async Task PoisonWithUnreachableSqs_ReturnsOutcomeInsteadOfThrowing()
    {
        var options = new SqsConsumerOptions { QueueUrl = "http://127.0.0.1:1/q", Name = "c", WaitTimeSeconds = 1, RequestTimeout = TimeSpan.FromMilliseconds(300) };
        var config = AwsClientConfig.Apply(new AmazonSQSConfig { ServiceURL = "http://127.0.0.1:1", AuthenticationRegion = "us-east-1" },
            AwsClientConfig.LongPollTimeout(options));
        using var sqs = new AmazonSQSClient(new BasicAWSCredentials("test", "test"), config);
        await using var db = NpgsqlDataSource.Create("Host=127.0.0.1;Port=1;Username=x;Password=x;Database=x;Timeout=1");
        var consumer = new SqsConsumer(sqs, db, new ContractValidator(Repo.ContractsDir),
            new Dictionary<string, IMessageHandler>(), options, NullLogger<SqsConsumer>.Instance);

        var outcome = await consumer.ProcessAsync(new Message { Body = "not json", ReceiptHandle = "r" }, CancellationToken.None);

        Assert.Equal(ConsumeOutcome.Poison, outcome);
    }
}
