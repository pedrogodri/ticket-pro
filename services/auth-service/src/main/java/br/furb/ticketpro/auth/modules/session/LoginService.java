package br.furb.ticketpro.auth.modules.session;

import br.furb.ticketpro.auth.modules.keys.IssuedToken;
import br.furb.ticketpro.auth.modules.keys.JwtIssuer;
import br.furb.ticketpro.auth.modules.registration.PasswordHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Verifies credentials and issues an access token + a refresh token (RFC 0002 §5.1–5.3). The
 * refresh token is stored only as its SHA-256 hash. Runs on the worker role (no user context yet).
 */
public class LoginService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final DataSource worker;
  private final PasswordHasher hasher;
  private final JwtIssuer issuer;
  private final long refreshTtlSeconds;
  private final Clock clock;
  // Verified when the e-mail does not exist, so both failures cost the same time.
  private final String dummyHash;

  public LoginService(
      DataSource worker,
      PasswordHasher hasher,
      JwtIssuer issuer,
      long refreshTtlSeconds,
      Clock clock) {
    this.worker = worker;
    this.hasher = hasher;
    this.issuer = issuer;
    this.refreshTtlSeconds = refreshTtlSeconds;
    this.clock = clock;
    this.dummyHash = hasher.hash(UUID.randomUUID().toString());
  }

  public LoginResult login(String email, String password) {
    try (Connection c = worker.getConnection()) {
      UUID userId = null;
      String hash = dummyHash;
      String storedEmail = null;
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT user_id, password_hash, email FROM credentials WHERE email = ? AND status = 'ACTIVE'")) {
        ps.setString(1, email.toLowerCase(Locale.ROOT));
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            userId = rs.getObject(1, UUID.class);
            hash = rs.getString(2);
            storedEmail = rs.getString(3);
          }
        }
      }
      boolean ok = hasher.matches(password, hash);
      if (userId == null || !ok) {
        throw new InvalidCredentialsException();
      }
      c.setAutoCommit(false);
      UUID familyId = UUID.randomUUID();
      IssuedToken access = issuer.issue(userId.toString(), storedEmail);
      String refresh = newRefreshToken();
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, userId);
        ps.setObject(3, familyId);
        ps.setString(4, sha256(refresh));
        ps.setTimestamp(5, Timestamp.from(clock.instant().plusSeconds(refreshTtlSeconds)));
        ps.executeUpdate();
      }
      recordIssued(c, access, userId, familyId, clock);
      c.commit();
      return new LoginResult(access.value(), refresh, access.expiresIn());
    } catch (SQLException e) {
      throw new IllegalStateException("login failed", e);
    }
  }

  /** Registers the access token's jti so it can be revoked later (RFC 0007 §5.4). */
  static void recordIssued(
      Connection c, IssuedToken access, UUID userId, UUID familyId, Clock clock)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO issued_access_tokens (jti, user_id, family_id, expires_at) VALUES (?, ?, ?, ?)")) {
      ps.setObject(1, UUID.fromString(access.jti()));
      ps.setObject(2, userId);
      ps.setObject(3, familyId);
      ps.setTimestamp(4, Timestamp.from(clock.instant().plusSeconds(access.expiresIn())));
      ps.executeUpdate();
    }
  }

  /** 256 random bits, base64url (RFC 0002 §5.3). */
  static String newRefreshToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
