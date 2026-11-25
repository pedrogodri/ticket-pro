package br.furb.ticketpro.inventory.modules.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.furb.ticketpro.inventory.modules.catalog.application.CatalogQueries;
import br.furb.ticketpro.inventory.modules.catalog.domain.EventDetail;
import br.furb.ticketpro.inventory.modules.catalog.domain.EventView;
import br.furb.ticketpro.inventory.modules.catalog.domain.SectorView;
import br.furb.ticketpro.inventory.modules.catalog.infrastructure.CatalogRepository.Position;
import br.furb.ticketpro.inventory.platform.web.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the catalog (RFC 0006 §5.2) with the queries mocked. */
@WebMvcTest(CatalogController.class)
@Import(ApiExceptionHandler.class)
class CatalogControllerTest {

  private static final String USER = "0193a6f0-1111-7000-8000-000000000001";
  private static final UUID EVENT = UUID.fromString("0193a6f0-3333-7000-8000-000000000003");
  private static final UUID SECTOR = UUID.fromString("0193a6f0-4444-7000-8000-000000000004");
  private static final Instant STARTS = Instant.parse("2026-12-12T21:00:00Z");

  @Autowired private MockMvc mvc;
  @MockitoBean private CatalogQueries queries;

  private static EventView event() {
    return new EventView(EVENT, "Festival de Verão 2026", STARTS, "Arena Blumenau");
  }

  @Test
  void listReturnsItemsAndNextCursor() throws Exception {
    when(queries.list(isNull(), eq(20)))
        .thenReturn(new CatalogQueries.Page(List.of(event()), new Position(STARTS, EVENT)));
    mvc.perform(get("/events").header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(EVENT.toString()))
        .andExpect(jsonPath("$.items[0].name").value("Festival de Verão 2026"))
        .andExpect(jsonPath("$.items[0].startsAt").value("2026-12-12T21:00:00Z"))
        .andExpect(jsonPath("$.items[0].venue").value("Arena Blumenau"))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
  }

  @Test
  void lastPageHasNullCursor() throws Exception {
    when(queries.list(isNull(), eq(5))).thenReturn(new CatalogQueries.Page(List.of(event()), null));
    mvc.perform(get("/events?limit=5").header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void cursorIsDecodedAndPassedOn() throws Exception {
    String cursor = CursorCodec.encode(new Position(STARTS, EVENT));
    when(queries.list(eq(new Position(STARTS, EVENT)), eq(20)))
        .thenReturn(new CatalogQueries.Page(List.of(), null));
    mvc.perform(get("/events?cursor=" + cursor).header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void invalidLimitOrCursorIs400() throws Exception {
    for (String query : List.of("limit=51", "limit=0", "limit=abc", "cursor=%25%25%25")) {
      mvc.perform(get("/events?" + query).header("X-User-Id", USER))
          .andExpect(status().isBadRequest())
          .andExpect(header().string("Content-Type", "application/problem+json"))
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    verify(queries, never()).list(any(), anyInt());
  }

  @Test
  void missingOrInvalidIdentityIs401() throws Exception {
    mvc.perform(get("/events"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/events/" + EVENT).header("X-User-Id", "x"))
        .andExpect(status().isUnauthorized());
    verify(queries, never()).list(any(), anyInt());
  }

  @Test
  void getReturnsEventWithSectors() throws Exception {
    when(queries.get(EVENT))
        .thenReturn(
            Optional.of(
                new EventDetail(
                    event(), List.of(new SectorView(SECTOR, "Pista", 500, 498, 25000L, "BRL")))));
    mvc.perform(get("/events/" + EVENT).header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(EVENT.toString()))
        .andExpect(jsonPath("$.sectors[0].id").value(SECTOR.toString()))
        .andExpect(jsonPath("$.sectors[0].name").value("Pista"))
        .andExpect(jsonPath("$.sectors[0].capacity").value(500))
        .andExpect(jsonPath("$.sectors[0].available").value(498))
        .andExpect(jsonPath("$.sectors[0].unitPriceCents").value(25000))
        .andExpect(jsonPath("$.sectors[0].currency").value("BRL"));
  }

  @Test
  void unknownOrMalformedIdIs404() throws Exception {
    UUID missing = UUID.randomUUID();
    when(queries.get(missing)).thenReturn(Optional.empty());
    mvc.perform(get("/events/" + missing).header("X-User-Id", USER))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(404));
    mvc.perform(get("/events/not-a-uuid").header("X-User-Id", USER))
        .andExpect(status().isNotFound());
  }
}
