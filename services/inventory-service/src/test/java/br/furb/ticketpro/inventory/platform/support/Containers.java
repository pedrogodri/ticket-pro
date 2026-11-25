package br.furb.ticketpro.inventory.platform.support;

import br.furb.ticketpro.inventory.platform.messaging.AwsClients;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Shared Postgres 16 (platform tables) and MiniStack containers for the platform integration tests.
 * Started once per JVM; tables are truncated on every call.
 */
public final class Containers {

  public static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(2);

  private static PostgreSQLContainer postgres;
  private static GenericContainer<?> ministack;

  private Containers() {}

  public static synchronized DataSource postgres() throws Exception {
    if (postgres == null) {
      PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");
      pg.start();
      PGSimpleDataSource ds = dataSource(pg);
      try (Connection c = ds.getConnection();
          Statement s = c.createStatement()) {
        s.execute(Files.readString(Path.of("src/test/resources/platform.sql")));
      }
      postgres = pg;
    }
    PGSimpleDataSource ds = dataSource(postgres);
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.execute("TRUNCATE outbox, processed_messages, effects RESTART IDENTITY");
    }
    return ds;
  }

  private static PGSimpleDataSource dataSource(PostgreSQLContainer pg) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(pg.getJdbcUrl());
    ds.setUser(pg.getUsername());
    ds.setPassword(pg.getPassword());
    return ds;
  }

  public static synchronized GenericContainer<?> ministack() {
    if (ministack == null) {
      GenericContainer<?> ms =
          new GenericContainer<>("ministackorg/ministack:1.5.40")
              .withEnv("MINISTACK_REGION", "us-east-1")
              .withExposedPorts(4566)
              .waitingFor(Wait.forHttp("/_ministack/health").forPort(4566));
      ms.start();
      ministack = ms;
    }
    return ministack;
  }

  private static URI endpoint() {
    GenericContainer<?> ms = ministack();
    return URI.create("http://" + ms.getHost() + ":" + ms.getMappedPort(4566));
  }

  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

  public static SqsClient sqs() {
    return SqsClient.builder()
        .endpointOverride(endpoint())
        .region(Region.US_EAST_1)
        .credentialsProvider(CREDENTIALS)
        .overrideConfiguration(AwsClients.overrideConfiguration(CLIENT_TIMEOUT))
        .build();
  }

  public static SnsClient sns() {
    return SnsClient.builder()
        .endpointOverride(endpoint())
        .region(Region.US_EAST_1)
        .credentialsProvider(CREDENTIALS)
        .overrideConfiguration(AwsClients.overrideConfiguration(CLIENT_TIMEOUT))
        .build();
  }

  /** Freezes MiniStack (same as `docker pause`, the F6 scenario) through the Docker API. */
  public static void pause() {
    DockerClientFactory.instance().client().pauseContainerCmd(ministack().getContainerId()).exec();
  }

  public static void unpause() {
    DockerClientFactory.instance()
        .client()
        .unpauseContainerCmd(ministack().getContainerId())
        .exec();
  }

  /** Creates a queue with a DLQ (redrive after maxReceiveCount); returns {queueUrl, dlqUrl}. */
  public static String[] queue(SqsClient sqs, String name, int maxReceiveCount) {
    String unique = name + "-" + UUID.randomUUID();
    String dlq = sqs.createQueue(b -> b.queueName(unique + "-dlq")).queueUrl();
    String redrive =
        "{\"deadLetterTargetArn\":\""
            + queueArn(sqs, dlq)
            + "\",\"maxReceiveCount\":\""
            + maxReceiveCount
            + "\"}";
    String queue =
        sqs.createQueue(b ->
                b.queueName(unique)
                    .attributes(
                        Map.of(
                            QueueAttributeName.VISIBILITY_TIMEOUT,
                            "30",
                            QueueAttributeName.REDRIVE_POLICY,
                            redrive)))
            .queueUrl();
    return new String[] {queue, dlq};
  }

  public static String queueArn(SqsClient sqs, String url) {
    return sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN))
        .attributes()
        .get(QueueAttributeName.QUEUE_ARN);
  }

  /** Topic subscribed by the queue with raw message delivery; returns the topic ARN. */
  public static String topic(SnsClient sns, SqsClient sqs, String name, String queueUrl) {
    String arn = sns.createTopic(b -> b.name(name + "-" + UUID.randomUUID())).topicArn();
    sns.subscribe(b ->
        b.topicArn(arn)
            .protocol("sqs")
            .endpoint(queueArn(sqs, queueUrl))
            .attributes(Map.of("RawMessageDelivery", "true")));
    return arn;
  }

  public static int queueDepth(SqsClient sqs, String url) {
    Map<QueueAttributeName, String> a =
        sqs.getQueueAttributes(b ->
                b.queueUrl(url)
                    .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
            .attributes();
    return Integer.parseInt(a.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
        + Integer.parseInt(a.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
  }

  public static long count(DataSource ds, String sql, Object... args) throws SQLException {
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
