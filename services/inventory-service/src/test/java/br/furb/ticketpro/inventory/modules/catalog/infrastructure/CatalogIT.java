package br.furb.ticketpro.inventory.modules.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import br.furb.ticketpro.inventory.modules.catalog.application.CatalogQueries;
import br.furb.ticketpro.inventory.modules.catalog.domain.EventDetail;
import br.furb.ticketpro.inventory.modules.catalog.domain.SectorView;
import br.furb.ticketpro.inventory.support.InventoryDb;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The catalog against the real schema and the seed of migrations/V0005 (RFC 0006 §5.2). */
class CatalogIT {

  private static final UUID FESTIVAL = UUID.fromString("0193a6f0-3333-7000-8000-000000000003");
  private static final UUID ACOUSTIC = UUID.fromString("0193a6f0-3333-7000-8000-000000000013");

  private CatalogQueries queries;

  @BeforeEach
  void setUp() throws Exception {
    InventoryDb.Pools db = InventoryDb.fresh();
    queries = new CatalogQueries(db.app(), new CatalogRepository());
  }

  @Test
  void listsSeededEventsOrderedByStartDate() throws Exception {
    CatalogQueries.Page page = queries.list(null, 20);
    assertThat(page.items()).extracting("id").containsExactly(FESTIVAL, ACOUSTIC);
    assertThat(page.next()).isNull();
  }

  @Test
  void keysetPaginationWalksAllEventsWithoutRepeats() throws Exception {
    CatalogQueries.Page first = queries.list(null, 1);
    assertThat(first.items()).extracting("id").containsExactly(FESTIVAL);
    assertThat(first.next()).isNotNull();

    CatalogQueries.Page second = queries.list(first.next(), 1);
    assertThat(second.items()).extracting("id").containsExactly(ACOUSTIC);
    assertThat(second.next()).isNull();
  }

  @Test
  void detailListsSectorsByPriceDescendingThenName() throws Exception {
    EventDetail detail = queries.get(FESTIVAL).orElseThrow();
    assertThat(detail.event().name()).isEqualTo("Festival de Verão 2026");
    List<SectorView> sectors = detail.sectors();
    assertThat(sectors).extracting(SectorView::name).containsExactly("VIP", "Pista", "F5");
    SectorView f5 = sectors.get(2);
    assertThat(f5.capacity()).isEqualTo(10);
    assertThat(f5.available()).isEqualTo(10);
    assertThat(f5.unitPriceCents()).isEqualTo(15000L);
    assertThat(f5.currency()).isEqualTo("BRL");
  }

  @Test
  void unknownEventIsEmpty() throws Exception {
    assertThat(queries.get(UUID.randomUUID())).isEmpty();
  }
}
