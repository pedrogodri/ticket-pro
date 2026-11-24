package br.furb.ticketpro.order.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Applies the service's own migrations/ in order, as Flyway would (placeholders filled with test
 * values).
 */
public final class Migrations {

  private Migrations() {}

  public static void apply(DataSource ds) throws Exception {
    List<Path> files;
    try (Stream<Path> s = Files.list(Path.of("migrations"))) {
      files =
          s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList();
    }
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      for (Path f : files) {
        st.execute(
            Files.readString(f)
                .replace("${app_password}", "app_test")
                .replace("${worker_password}", "worker_test"));
      }
    }
  }
}
