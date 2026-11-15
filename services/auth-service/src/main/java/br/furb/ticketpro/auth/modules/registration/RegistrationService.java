package br.furb.ticketpro.auth.modules.registration;

import br.furb.ticketpro.auth.platform.envelope.Envelope;
import br.furb.ticketpro.auth.platform.outbox.OutboxWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Registers a credential (ACTIVE) and enqueues UserRegistered in the same transaction (arquitetura
 * §5). Runs on the worker role: there is no user yet for RLS (padroes §6.1).
 */
public class RegistrationService {

  private static final String UNIQUE_VIOLATION = "23505";
  private final DataSource worker;
  private final PasswordHasher hasher;
  private final String topicArn;

  public RegistrationService(DataSource worker, PasswordHasher hasher, String topicArn) {
    this.worker = worker;
    this.hasher = hasher;
    this.topicArn = topicArn;
  }

  /**
   * @return the new userId; @throws EmailAlreadyRegisteredException for a duplicate e-mail
   */
  public String register(String email, String password, String name, String correlationId) {
    UUID userId = UUID.randomUUID();
    String hash = hasher.hash(password);
    try (Connection c = worker.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO credentials (user_id, email, password_hash, status) VALUES (?, ?, ?, 'ACTIVE')")) {
        ps.setObject(1, userId);
        ps.setString(2, email);
        ps.setString(3, hash);
        ps.executeUpdate();
        OutboxWriter.enqueue(
            c,
            topicArn,
            Envelope.create(
                correlationId,
                "UserRegistered",
                1,
                Map.of("userId", userId.toString(), "email", email, "name", name)));
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
          throw new EmailAlreadyRegisteredException();
        }
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("register failed", e);
    }
    return userId.toString();
  }
}
