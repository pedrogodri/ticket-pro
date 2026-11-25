package br.furb.ticketpro.inventory.modules.catalog.web;

import br.furb.ticketpro.inventory.modules.catalog.application.CatalogQueries;
import br.furb.ticketpro.inventory.platform.web.NotFoundException;
import br.furb.ticketpro.inventory.platform.web.UnauthenticatedException;
import br.furb.ticketpro.inventory.platform.web.ValidationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event catalog (RFC 0006 §5.2). Public to logged-in users: the gateway validates the JWT and
 * injects X-User-Id (RFC 0002); without it the answer is 401. Uses only the app pool.
 */
@RestController
@RequestMapping(path = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 50;

  private final CatalogQueries queries;

  public CatalogController(CatalogQueries queries) {
    this.queries = queries;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @RequestParam(value = "limit", required = false) String limit,
      @RequestParam(value = "cursor", required = false) String cursor)
      throws SQLException {
    requireIdentity(userId);
    CatalogQueries.Page page = queries.list(CursorCodec.decode(cursor), limit(limit));
    List<EventResponse> items = page.items().stream().map(EventResponse::from).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("nextCursor", CursorCodec.encode(page.next()));
    return body;
  }

  @GetMapping("/{id}")
  public EventDetailResponse get(
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @PathVariable("id") String id)
      throws SQLException {
    requireIdentity(userId);
    UUID eventId;
    try {
      eventId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException("event not found");
    }
    return queries
        .get(eventId)
        .map(EventDetailResponse::from)
        .orElseThrow(() -> new NotFoundException("event not found"));
  }

  private static void requireIdentity(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new UnauthenticatedException("missing identity");
    }
    try {
      UUID.fromString(userId.trim());
    } catch (IllegalArgumentException e) {
      throw new UnauthenticatedException("invalid identity");
    }
  }

  private static int limit(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_LIMIT;
    }
    try {
      int n = Integer.parseInt(raw.trim());
      if (n >= 1 && n <= MAX_LIMIT) {
        return n;
      }
    } catch (NumberFormatException ignored) {
      // falls through to the validation error
    }
    throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
  }
}
