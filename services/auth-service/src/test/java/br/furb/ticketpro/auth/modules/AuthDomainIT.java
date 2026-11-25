package br.furb.ticketpro.auth.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.auth.modules.keys.Jwks;
import br.furb.ticketpro.auth.modules.keys.JwtIssuer;
import br.furb.ticketpro.auth.modules.keys.SecretsManagerSigningKeys;
import br.furb.ticketpro.auth.modules.registration.EmailAlreadyRegisteredException;
import br.furb.ticketpro.auth.modules.registration.PasswordHasher;
import br.furb.ticketpro.auth.modules.registration.RegistrationService;
import br.furb.ticketpro.auth.modules.session.LoginResult;
import br.furb.ticketpro.auth.modules.session.LoginService;
import br.furb.ticketpro.auth.platform.envelope.ContractValidator;
import br.furb.ticketpro.auth.platform.messaging.AwsClients;
import br.furb.ticketpro.auth.support.Migrations;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Register, login and JWKS against Postgres (the service's migrations) and MiniStack Secrets
 * Manager.
 */
class AuthDomainIT {

  private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-auth";
  private static final String SECRET_ID = "ticketpro/auth/jwt-signing-key";
  private static PostgreSQLContainer pg;
  private static GenericContainer<?> ministack;
  private static PGSimpleDataSource db;
  private static SecretsManagerClient secrets;

  @BeforeAll
  static void start() throws Exception {
    pg = new PostgreSQLContainer("postgres:16-alpine");
    pg.start();
    db = new PGSimpleDataSource();
    db.setUrl(pg.getJdbcUrl());
    db.setUser(pg.getUsername());
    db.setPassword(pg.getPassword());
    Migrations.apply(db);

    ministack =
        new GenericContainer<>("ministackorg/ministack:1.5.40")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_ministack/health").forPort(4566));
    ministack.start();
    secrets =
        SecretsManagerClient.builder()
            .endpointOverride(
                URI.create("http://" + ministack.getHost() + ":" + ministack.getMappedPort(4566)))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .overrideConfiguration(AwsClients.overrideConfiguration(Duration.ofSeconds(5)))
            .build();
    String pem = Files.readString(Path.of("src/test/resources/keys/test-rsa-pkcs1.pem"));
    String secret =
        JsonMapper.builder()
            .build()
            .writeValueAsString(Map.of("current", Map.of("kid", "it-kid", "privatePem", pem)));
    secrets.createSecret(
        b -> b.name(SECRET_ID).secretString(secret.replace("}}", "},\"previous\":null}")));
  }

  @AfterAll
  static void stop() {
    secrets.close();
    ministack.stop();
    pg.stop();
  }

  private static final PasswordHasher HASHER = new PasswordHasher(19456, 2, 1);

  @Test
  void registerStoresCredentialAndUserRegisteredInTheSameTransaction() throws Exception {
    RegistrationService registration = new RegistrationService(db, HASHER, TOPIC);
    String userId =
        registration.register(
            "ana@exemplo.com",
            "correct horse",
            "Ana Souza",
            "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c");

    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT type, payload::text FROM outbox WHERE payload->>'userId' = ?")) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("UserRegistered");
        String payload = rs.getString(2);
        assertThat(payload).contains(userId).contains("ana@exemplo.com").contains("Ana Souza");
        String envelope =
            "{\"messageId\":\"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55\",\"correlationId\":\"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c\","
                + "\"type\":\"UserRegistered\",\"version\":1,\"occurredAt\":\"2026-11-20T18:30:12.345Z\",\"payload\":"
                + payload
                + "}";
        new ContractValidator(Path.of("contracts")).validate(envelope);
      }
    }
    assertThatThrownBy(() ->
            registration.register(
                "ANA@exemplo.com".toLowerCase(),
                "other pass 1",
                "Outra",
                "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c"))
        .isInstanceOf(EmailAlreadyRegisteredException.class);
  }

  @Test
  void loginStoresOnlyTheRefreshTokenHashAndJwksExposesTheSecretKid() throws Exception {
    new RegistrationService(db, HASHER, TOPIC)
        .register(
            "bia@exemplo.com", "correct horse", "Bia", "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c");
    SecretsManagerSigningKeys keys =
        new SecretsManagerSigningKeys(secrets, SECRET_ID, Duration.ofMinutes(5));
    JwtIssuer issuer =
        new JwtIssuer(keys, "ticketpro-auth", "ticketpro-api", 900, Clock.systemUTC());
    LoginResult result =
        new LoginService(db, HASHER, issuer, 604800, Clock.systemUTC())
            .login("bia@exemplo.com", "correct horse");

    assertThat(result.accessToken().split("\\.")).hasSize(3);
    try (Connection c = db.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT token_hash FROM refresh_tokens")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1))
            .isEqualTo(LoginService.sha256(result.refreshToken()))
            .isNotEqualTo(result.refreshToken());
      }
    }
    @SuppressWarnings("unchecked")
    List<Map<String, String>> jwks =
        (List<Map<String, String>>) Jwks.of(keys.get(), keys.previous()).get("keys");
    assertThat(jwks).extracting(k -> k.get("kid")).containsExactly("it-kid");
  }
}
