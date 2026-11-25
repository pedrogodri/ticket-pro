package br.furb.ticketpro.auth.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.auth.modules.keys.JwtIssuer;
import br.furb.ticketpro.auth.modules.keys.SigningKey;
import br.furb.ticketpro.auth.modules.registration.PasswordHasher;
import br.furb.ticketpro.auth.modules.registration.RegistrationService;
import br.furb.ticketpro.auth.modules.session.InvalidCredentialsException;
import br.furb.ticketpro.auth.modules.session.LoginResult;
import br.furb.ticketpro.auth.modules.session.LoginService;
import br.furb.ticketpro.auth.modules.session.RefreshService;
import br.furb.ticketpro.auth.platform.envelope.ContractValidator;
import br.furb.ticketpro.auth.support.Migrations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** RFC 0007 §5.2 / §5.4 against Postgres: rotation, reuse, family revocation and concurrency. */
class RefreshIT {

  private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-auth";
  private static final String CORRELATION = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";
  private static final PasswordHasher HASHER = new PasswordHasher(19456, 2, 1);
  private static PostgreSQLContainer pg;
  private static PGSimpleDataSource db;
  private static JwtIssuer issuer;
  private static int users;

  @BeforeAll
  static void start() throws Exception {
    pg = new PostgreSQLContainer("postgres:16-alpine");
    pg.start();
    db = new PGSimpleDataSource();
    db.setUrl(pg.getJdbcUrl());
    db.setUser(pg.getUsername());
    db.setPassword(pg.getPassword());
    Migrations.apply(db);
    SigningKey key =
        SigningKey.fromPem(
            "it-kid", Files.readString(Path.of("src/test/resources/keys/test-rsa-pkcs1.pem")));
    issuer = new JwtIssuer(() -> key, "ticketpro-auth", "ticketpro-api", 900, Clock.systemUTC());
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  private static LoginResult newSession() {
    String email = "user" + (++users) + "@exemplo.com";
    new RegistrationService(db, HASHER, TOPIC)
        .register(email, "correct horse", "User", CORRELATION);
    return new LoginService(db, HASHER, issuer, 604800, Clock.systemUTC())
        .login(email, "correct horse");
  }

  private static RefreshService refreshService() {
    return new RefreshService(db, issuer, 604800, Clock.systemUTC(), TOPIC);
  }

  private static String familyOf(String refreshToken) throws Exception {
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT family_id::text FROM refresh_tokens WHERE token_hash = ?")) {
      ps.setString(1, LoginService.sha256(refreshToken));
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private static long count(String sql, String family) throws Exception {
    try (Connection c = db.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, family);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  @Test
  void refreshRotatesWithinTheSameFamilyAndMarksTheOldTokenUsed() throws Exception {
    LoginResult first = newSession();
    LoginResult second = refreshService().refresh(first.refreshToken(), CORRELATION);

    assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
    assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
    String family = familyOf(first.refreshToken());
    assertThat(familyOf(second.refreshToken())).isEqualTo(family);
    assertThat(
            count(
                "SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid AND used_at IS NOT NULL",
                family))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM issued_access_tokens WHERE family_id = ?::uuid", family))
        .isEqualTo(2);
  }

  @Test
  void unknownAndExpiredTokensAreTheGeneric401() throws Exception {
    assertThatThrownBy(() -> refreshService().refresh("not-a-real-token", CORRELATION))
        .isInstanceOf(InvalidCredentialsException.class);

    String email = "expired@exemplo.com";
    new RegistrationService(db, HASHER, TOPIC).register(email, "correct horse", "E", CORRELATION);
    Clock past = Clock.offset(Clock.systemUTC(), Duration.ofDays(-2));
    LoginResult old =
        new LoginService(db, HASHER, issuer, 3600, past).login(email, "correct horse");
    assertThatThrownBy(() -> refreshService().refresh(old.refreshToken(), CORRELATION))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void reuseRevokesTheFamilyAndPublishesTokenRevokedOncePerLiveAccessToken() throws Exception {
    LoginResult first = newSession();
    String family = familyOf(first.refreshToken());
    LoginResult second = refreshService().refresh(first.refreshToken(), CORRELATION);

    assertThatThrownBy(() -> refreshService().refresh(first.refreshToken(), CORRELATION))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(
            count(
                "SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid AND revoked_at IS NULL",
                family))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM issued_access_tokens WHERE family_id = ?::uuid AND revoked_at IS NULL",
                family))
        .isZero();
    List<String> payloads = revokedPayloads(familyUser(family));
    assertThat(payloads).hasSize(2).allMatch(p -> p.contains("REFRESH_REUSE"));
    for (String payload : payloads) {
      new ContractValidator(Path.of("contracts"))
          .validate(
              "{\"messageId\":\"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55\",\"correlationId\":\""
                  + CORRELATION
                  + "\",\"type\":\"TokenRevoked\",\"version\":1,\"occurredAt\":\"2026-11-20T18:30:12.345Z\",\"payload\":"
                  + payload
                  + "}");
    }

    // The child of the revoked family is dead too, and nothing is published a second time.
    assertThatThrownBy(() -> refreshService().refresh(second.refreshToken(), CORRELATION))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(revokedPayloads(familyUser(family))).hasSize(2);
  }

  @Test
  void twoConcurrentRefreshesOfTheSameTokenLetExactlyOnePass() throws Exception {
    LoginResult first = newSession();
    RefreshService service = refreshService();
    int threads = 2;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Callable<Void> task =
          () -> {
            go.await();
            try {
              service.refresh(first.refreshToken(), CORRELATION);
              ok.incrementAndGet();
            } catch (InvalidCredentialsException e) {
              rejected.incrementAndGet();
            }
            return null;
          };
      futures.add(pool.submit(task));
    }
    go.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();
    assertThat(ok.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(1);
  }

  private static String familyUser(String family) throws Exception {
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT user_id::text FROM refresh_tokens WHERE family_id = ?::uuid LIMIT 1")) {
      ps.setString(1, family);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static List<String> revokedPayloads(String userId) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT payload::text FROM outbox WHERE type = 'TokenRevoked' AND payload->>'userId' = ?")) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }
}
