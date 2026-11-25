package br.furb.ticketpro.inventory.platform.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * JDBC coordinates parsed from postgres://user:password@host[:port]/database (docs/padroes.md
 * §3.1).
 */
public record DatabaseUrl(String jdbcUrl, String user, String password) {

  /**
   * @throws IllegalStateException naming {@code variable} when the URL is not a postgres URL
   */
  public static DatabaseUrl parse(String url, String variable) {
    try {
      URI uri = new URI(url);
      String userInfo = uri.getRawUserInfo();
      if (!("postgres".equals(uri.getScheme()) || "postgresql".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getPath() == null
          || uri.getPath().length() <= 1
          || userInfo == null
          || !userInfo.contains(":")) {
        throw new IllegalStateException(
            variable + " must be postgres://user:password@host:port/database");
      }
      String[] credentials = userInfo.split(":", 2);
      int port = uri.getPort() > 0 ? uri.getPort() : 5432;
      return new DatabaseUrl(
          "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath(),
          URLDecoder.decode(credentials[0], StandardCharsets.UTF_8),
          URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
    } catch (URISyntaxException e) {
      throw new IllegalStateException(
          variable + " must be postgres://user:password@host:port/database", e);
    }
  }
}
