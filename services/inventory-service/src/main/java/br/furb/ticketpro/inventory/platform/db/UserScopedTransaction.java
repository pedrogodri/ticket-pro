package br.furb.ticketpro.inventory.platform.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * One transaction on the API pool with {@code app.user_id} set, so row-level security filters every
 * query by owner (ADR-0020, RFC 0004 §5.5). Use only the app data source here.
 */
public final class UserScopedTransaction {

  /** Work done inside the transaction. */
  @FunctionalInterface
  public interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
  }

  private UserScopedTransaction() {}

  /** BEGIN; set_config('app.user_id', userId, true); fn; COMMIT. Rolls back on any error. */
  public static <T> T run(DataSource app, UUID userId, SqlFunction<T> fn) throws SQLException {
    try (Connection c = app.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (PreparedStatement ps =
            c.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
          ps.setString(1, userId.toString());
          ps.execute();
        }
        T result = fn.apply(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          c.rollback();
        } catch (SQLException ignored) {
          // Connection already broken: the server discards the transaction.
        }
        throw e;
      }
    }
  }
}
