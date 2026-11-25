package br.furb.ticketpro.inventory.support;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres 16 with the service's real migrations (roles, RLS), started once per JVM. Each call
 * truncates the tables and returns the three connections a test needs.
 */
public final class InventoryDb {

  /**
   * @param owner superuser (bypasses RLS): setup and assertions only
   * @param app inventory_app, subject to RLS (API)
   * @param worker inventory_worker, BYPASSRLS (consumers, jobs)
   */
  public record Pools(DataSource owner, DataSource app, DataSource worker) {}

  private static PostgreSQLContainer postgres;

  private InventoryDb() {}

  public static synchronized Pools fresh() throws Exception {
    if (postgres == null) {
      PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");
      pg.start();
      Migrations.apply(dataSource(pg, pg.getUsername(), pg.getPassword()));
      postgres = pg;
    }
    DataSource owner = dataSource(postgres, postgres.getUsername(), postgres.getPassword());
    try (Connection c = owner.getConnection();
        Statement s = c.createStatement()) {
      s.execute("TRUNCATE tickets, reservations, outbox, processed_messages RESTART IDENTITY");
      s.execute("UPDATE sectors SET available = capacity");
    }
    return new Pools(
        owner,
        dataSource(postgres, "inventory_app", "app_test"),
        dataSource(postgres, "inventory_worker", "worker_test"));
  }

  private static DataSource dataSource(PostgreSQLContainer pg, String user, String password) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(pg.getJdbcUrl());
    ds.setUser(user);
    ds.setPassword(password);
    return ds;
  }
}
