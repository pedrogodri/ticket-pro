package br.furb.ticketpro.order.support;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres 16 with the service's real migrations (roles, RLS), started once per JVM. Each call
 * truncates the tables and returns the three connections a test needs.
 */
public final class OrdersDb {

  /**
   * @param owner superuser (bypasses RLS): setup and assertions only
   * @param app orders_app, subject to RLS (API)
   * @param worker orders_worker, BYPASSRLS (consumers, jobs)
   */
  public record Pools(DataSource owner, DataSource app, DataSource worker) {}

  private static PostgreSQLContainer postgres;

  private OrdersDb() {}

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
      s.execute("TRUNCATE orders, outbox, processed_messages RESTART IDENTITY");
    }
    return new Pools(
        owner,
        dataSource(postgres, "orders_app", "app_test"),
        dataSource(postgres, "orders_worker", "worker_test"));
  }

  private static DataSource dataSource(PostgreSQLContainer pg, String user, String password) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(pg.getJdbcUrl());
    ds.setUser(user);
    ds.setPassword(password);
    return ds;
  }
}
