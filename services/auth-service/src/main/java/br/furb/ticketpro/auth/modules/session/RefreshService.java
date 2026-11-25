package br.furb.ticketpro.auth.modules.session;

import br.furb.ticketpro.auth.modules.keys.IssuedToken;
import br.furb.ticketpro.auth.modules.keys.JwtIssuer;
import br.furb.ticketpro.auth.platform.envelope.Envelope;
import br.furb.ticketpro.auth.platform.outbox.OutboxWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Rotating refresh (RFC 0007 §5.2, RFC 0002 §5.3). One transaction on the worker role: the row is
 * locked with FOR UPDATE, so two concurrent refreshes of the same token serialize and the second
 * one is a reuse. A reuse revokes the whole family and publishes TokenRevoked(REFRESH_REUSE) for
 * the access tokens still alive.
 */
public class RefreshService {

  private final DataSource worker;
  private final JwtIssuer issuer;
  private final long refreshTtlSeconds;
  private final Clock clock;
  private final String topicArn;

  public RefreshService(
      DataSource worker, JwtIssuer issuer, long refreshTtlSeconds, Clock clock, String topicArn) {
    this.worker = worker;
    this.issuer = issuer;
    this.refreshTtlSeconds = refreshTtlSeconds;
    this.clock = clock;
    this.topicArn = topicArn;
  }

  /**
   * @throws InvalidCredentialsException for an unknown, expired, used or revoked token (401)
   */
  public LoginResult refresh(String refreshToken, String correlationId) {
    try (Connection c = worker.getConnection()) {
      c.setAutoCommit(false);
      try {
        LoginResult result = rotate(c, refreshToken, correlationId);
        c.commit();
        return result;
      } catch (InvalidCredentialsException e) {
        // The reuse path wrote revocations before failing; the other paths wrote nothing.
        c.commit();
        throw e;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("refresh failed", e);
    }
  }

  private LoginResult rotate(Connection c, String refreshToken, String correlationId)
      throws SQLException {
    UUID id;
    UUID userId;
    UUID familyId;
    Timestamp expiresAt;
    boolean spent;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, user_id, family_id, expires_at, used_at IS NOT NULL OR revoked_at IS NOT NULL"
                + " FROM refresh_tokens WHERE token_hash = ? FOR UPDATE")) {
      ps.setString(1, LoginService.sha256(refreshToken));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new InvalidCredentialsException();
        }
        id = rs.getObject(1, UUID.class);
        userId = rs.getObject(2, UUID.class);
        familyId = rs.getObject(3, UUID.class);
        expiresAt = rs.getTimestamp(4);
        spent = rs.getBoolean(5);
      }
    }
    Instant now = clock.instant();
    if (expiresAt.toInstant().isBefore(now)) {
      throw new InvalidCredentialsException();
    }
    if (spent) {
      revokeFamily(c, familyId, userId, now, correlationId);
      throw new InvalidCredentialsException();
    }
    try (PreparedStatement ps =
        c.prepareStatement("UPDATE refresh_tokens SET used_at = ? WHERE id = ?")) {
      ps.setTimestamp(1, Timestamp.from(now));
      ps.setObject(2, id);
      ps.executeUpdate();
    }
    String email = activeEmail(c, userId);
    IssuedToken access = issuer.issue(userId.toString(), email);
    String child = LoginService.newRefreshToken();
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, userId);
      ps.setObject(3, familyId);
      ps.setString(4, LoginService.sha256(child));
      ps.setTimestamp(5, Timestamp.from(now.plusSeconds(refreshTtlSeconds)));
      ps.executeUpdate();
    }
    LoginService.recordIssued(c, access, userId, familyId, clock);
    return new LoginResult(access.value(), child, access.expiresIn());
  }

  private static String activeEmail(Connection c, UUID userId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT email FROM credentials WHERE user_id = ? AND status = 'ACTIVE'")) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new InvalidCredentialsException();
        }
        return rs.getString(1);
      }
    }
  }

  private void revokeFamily(
      Connection c, UUID familyId, UUID userId, Instant now, String correlationId)
      throws SQLException {
    List<Object[]> alive = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT jti, expires_at FROM issued_access_tokens"
                + " WHERE family_id = ? AND revoked_at IS NULL AND expires_at > ?")) {
      ps.setObject(1, familyId);
      ps.setTimestamp(2, Timestamp.from(now));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          alive.add(new Object[] {rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()});
        }
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE issued_access_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")) {
      ps.setTimestamp(1, Timestamp.from(now));
      ps.setObject(2, familyId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")) {
      ps.setTimestamp(1, Timestamp.from(now));
      ps.setObject(2, familyId);
      ps.executeUpdate();
    }
    for (Object[] token : alive) {
      OutboxWriter.enqueue(
          c,
          topicArn,
          Envelope.create(
              correlationId,
              "TokenRevoked",
              1,
              Map.of(
                  "jti", token[0].toString(),
                  "userId", userId.toString(),
                  "expiresAt", ((Instant) token[1]).truncatedTo(ChronoUnit.SECONDS).toString(),
                  "reason", "REFRESH_REUSE")));
    }
  }
}
