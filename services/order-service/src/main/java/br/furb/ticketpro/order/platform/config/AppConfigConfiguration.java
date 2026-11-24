package br.furb.ticketpro.order.platform.config;

import br.furb.ticketpro.order.modules.orders.application.CreateOrder;
import br.furb.ticketpro.order.modules.orders.application.DeadlineScanner;
import br.furb.ticketpro.order.modules.orders.application.OrderEvents;
import br.furb.ticketpro.order.modules.orders.application.OrderQueries;
import br.furb.ticketpro.order.modules.orders.application.SagaHandlers;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository;
import br.furb.ticketpro.order.platform.envelope.ContractValidator;
import br.furb.ticketpro.order.platform.health.BrokerProbe;
import br.furb.ticketpro.order.platform.health.DatabaseProbe;
import br.furb.ticketpro.order.platform.jobs.PeriodicJobRunner;
import br.furb.ticketpro.order.platform.messaging.AwsClients;
import br.furb.ticketpro.order.platform.messaging.SnsOutboxPublisher;
import br.furb.ticketpro.order.platform.messaging.SqsConsumer;
import br.furb.ticketpro.order.platform.messaging.SqsConsumerProperties;
import br.furb.ticketpro.order.platform.messaging.SqsConsumerRunner;
import br.furb.ticketpro.order.platform.outbox.OutboxRelay;
import br.furb.ticketpro.order.platform.outbox.OutboxRelayRunner;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Validates environment configuration at boot (an invalid variable aborts startup) and wires the
 * platform and the orders module. Controllers get only the app pool; consumer, relay and jobs get
 * only the worker pool (RFC 0004 decision 4).
 */
@Configuration(proxyBeanMethods = false)
public class AppConfigConfiguration {

  /** Connections beyond the consumer's: relay, deadline scanner and slack (RFC 0004 §5.3). */
  private static final int WORKER_POOL_EXTRA = 4;

  @Bean
  public AppConfig appConfig(Environment environment) {
    return AppConfig.from(environment::getProperty);
  }

  /** API role (RLS applies). Starts even if the database is down: readiness reports it. */
  @Bean
  @Primary
  public HikariDataSource appDataSource(AppConfig config) {
    return dataSource(config.database(), "orders-app", 10);
  }

  /** Worker role (BYPASSRLS): outbox relay, consumer and jobs. */
  @Bean
  @Qualifier("worker")
  public HikariDataSource workerDataSource(AppConfig config) {
    return dataSource(
        config.databaseWorker(),
        "orders-worker",
        config.consumer().concurrency() + WORKER_POOL_EXTRA);
  }

  private static HikariDataSource dataSource(DatabaseUrl url, String pool, int size) {
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName(pool);
    ds.setJdbcUrl(url.jdbcUrl());
    ds.setUsername(url.user());
    ds.setPassword(url.password());
    ds.setMaximumPoolSize(size);
    ds.setInitializationFailTimeout(-1);
    ds.setConnectionTimeout(2000);
    return ds;
  }

  @Bean
  public DatabaseProbe databaseProbe(DataSource appDataSource) {
    return () -> {
      try (Connection c = appDataSource.getConnection()) {
        return c.isValid(2);
      } catch (SQLException e) {
        return false;
      }
    };
  }

  @Bean(destroyMethod = "close")
  public SnsClient snsClient(AppConfig config) {
    SnsClientBuilder builder =
        SnsClient.builder()
            .region(Region.of(config.awsRegion()))
            .overrideConfiguration(AwsClients.overrideConfiguration(config.httpClientTimeout()));
    config.awsEndpointUrl().map(URI::create).ifPresent(builder::endpointOverride);
    return builder.build();
  }

  @Bean(destroyMethod = "close")
  public SqsClient sqsClient(AppConfig config) {
    SqsClientBuilder builder =
        SqsClient.builder()
            .region(Region.of(config.awsRegion()))
            .overrideConfiguration(AwsClients.overrideConfiguration(config.httpClientTimeout()));
    config.awsEndpointUrl().map(URI::create).ifPresent(builder::endpointOverride);
    return builder.build();
  }

  @Bean
  public OutboxRelay outboxRelay(
      @Qualifier("worker") DataSource workerDataSource, SnsClient sns, AppConfig config) {
    return new OutboxRelay(workerDataSource, new SnsOutboxPublisher(sns), config.outbox());
  }

  @Bean
  public OutboxRelayRunner outboxRelayRunner(OutboxRelay relay) {
    return new OutboxRelayRunner(relay);
  }

  @Bean
  public OrderRepository orderRepository() {
    return new OrderRepository();
  }

  @Bean
  public OrderEvents orderEvents(AppConfig config) {
    return new OrderEvents(config.topicArn(), config.orders().createdVersion());
  }

  @Bean
  public CreateOrder createOrder(
      DataSource appDataSource, OrderRepository orders, OrderEvents events) {
    return new CreateOrder(appDataSource, orders, events);
  }

  @Bean
  public OrderQueries orderQueries(DataSource appDataSource, OrderRepository orders) {
    return new OrderQueries(appDataSource, orders);
  }

  @Bean
  public SqsConsumer sqsConsumer(
      SqsClient sqs,
      @Qualifier("worker") DataSource workerDataSource,
      OrderRepository orders,
      OrderEvents events,
      AppConfig config) {
    SagaHandlers saga = new SagaHandlers(orders, events, config.orders().paymentTimeout());
    return new SqsConsumer(
        sqs,
        workerDataSource,
        new ContractValidator(config.contractsDir()),
        saga.all(),
        new SqsConsumerProperties(
            config.consumer().queueUrl(),
            config.serviceName(),
            config.consumer().concurrency(),
            config.consumer().waitTimeSeconds(),
            config.httpClientTimeout()));
  }

  @Bean
  public SqsConsumerRunner sqsConsumerRunner(SqsConsumer consumer, AppConfig config) {
    return new SqsConsumerRunner(consumer, config.shutdownTimeout());
  }

  @Bean
  public PeriodicJobRunner periodicJobRunner(
      @Qualifier("worker") DataSource workerDataSource,
      OrderRepository orders,
      OrderEvents events,
      AppConfig config) {
    return new PeriodicJobRunner(
        List.of(
            new DeadlineScanner(
                workerDataSource,
                orders,
                events,
                config.orders().deadlineScanInterval(),
                config.orders().deadlineScanBatchSize())));
  }

  /** Degraded when the relay cannot publish or the consumer cannot receive (RFC 0003 §5.9). */
  @Bean
  public BrokerProbe brokerProbe(OutboxRelay relay, SqsConsumer consumer) {
    return () -> relay.brokerUp() && consumer.brokerUp();
  }
}
