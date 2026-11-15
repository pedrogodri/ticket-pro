package br.furb.ticketpro.auth.platform.config;

import br.furb.ticketpro.auth.modules.keys.JwtIssuer;
import br.furb.ticketpro.auth.modules.keys.SecretsManagerSigningKeys;
import br.furb.ticketpro.auth.modules.keys.SigningKeys;
import br.furb.ticketpro.auth.modules.registration.PasswordHasher;
import br.furb.ticketpro.auth.modules.registration.RegistrationService;
import br.furb.ticketpro.auth.modules.session.LoginService;
import br.furb.ticketpro.auth.platform.health.BrokerProbe;
import br.furb.ticketpro.auth.platform.health.DatabaseProbe;
import br.furb.ticketpro.auth.platform.messaging.AwsClients;
import br.furb.ticketpro.auth.platform.messaging.SnsOutboxPublisher;
import br.furb.ticketpro.auth.platform.outbox.OutboxRelay;
import br.furb.ticketpro.auth.platform.outbox.OutboxRelayRunner;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;

/**
 * Validates environment configuration at boot (an invalid variable aborts startup) and wires the
 * platform.
 */
@Configuration(proxyBeanMethods = false)
public class AppConfigConfiguration {

  @Bean
  public AppConfig appConfig(Environment environment) {
    return AppConfig.from(environment::getProperty);
  }

  /** API role (RLS applies). Starts even if the database is down: readiness reports it. */
  @Bean
  @Primary
  public HikariDataSource appDataSource(AppConfig config) {
    return dataSource(config.database(), "auth-app");
  }

  /** Worker role (BYPASSRLS): outbox relay, consumers and jobs. */
  @Bean
  @Qualifier("worker")
  public HikariDataSource workerDataSource(AppConfig config) {
    return dataSource(config.databaseWorker(), "auth-worker");
  }

  private static HikariDataSource dataSource(DatabaseUrl url, String pool) {
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName(pool);
    ds.setJdbcUrl(url.jdbcUrl());
    ds.setUsername(url.user());
    ds.setPassword(url.password());
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

  @Bean
  public OutboxRelay outboxRelay(
      @Qualifier("worker") DataSource workerDataSource, SnsClient sns, AppConfig config) {
    return new OutboxRelay(workerDataSource, new SnsOutboxPublisher(sns), config.outbox());
  }

  @Bean
  public OutboxRelayRunner outboxRelayRunner(OutboxRelay relay) {
    return new OutboxRelayRunner(relay);
  }

  @Bean(destroyMethod = "close")
  public SecretsManagerClient secretsManagerClient(AppConfig config) {
    SecretsManagerClientBuilder builder =
        SecretsManagerClient.builder()
            .region(Region.of(config.awsRegion()))
            .overrideConfiguration(AwsClients.overrideConfiguration(config.httpClientTimeout()));
    config.awsEndpointUrl().map(URI::create).ifPresent(builder::endpointOverride);
    return builder.build();
  }

  @Bean
  public SigningKeys signingKeys(SecretsManagerClient client, AppConfig config) {
    return new SecretsManagerSigningKeys(
        client, config.jwt().secretId(), config.jwt().keyRefresh());
  }

  @Bean
  public PasswordHasher passwordHasher(AppConfig config) {
    return new PasswordHasher(
        config.argon2().memoryKb(), config.argon2().iterations(), config.argon2().parallelism());
  }

  @Bean
  public JwtIssuer jwtIssuer(SigningKeys keys, AppConfig config) {
    return new JwtIssuer(
        keys,
        config.jwt().issuer(),
        config.jwt().audience(),
        config.jwt().accessTtlSeconds(),
        Clock.systemUTC());
  }

  @Bean
  public RegistrationService registrationService(
      @Qualifier("worker") DataSource worker, PasswordHasher hasher, AppConfig config) {
    return new RegistrationService(worker, hasher, config.topicArn());
  }

  @Bean
  public LoginService loginService(
      @Qualifier("worker") DataSource worker,
      PasswordHasher hasher,
      JwtIssuer issuer,
      AppConfig config) {
    return new LoginService(
        worker, hasher, issuer, config.jwt().refreshTtlSeconds(), Clock.systemUTC());
  }

  @Bean
  public BrokerProbe brokerProbe(OutboxRelay relay) {
    return relay::brokerUp;
  }
}
