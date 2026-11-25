package br.furb.ticketpro.inventory.modules.catalog.web;

import br.furb.ticketpro.inventory.modules.catalog.domain.EventDetail;
import br.furb.ticketpro.inventory.modules.catalog.domain.SectorView;
import java.util.List;

/** An event with its sectors (GET /events/{id}). */
public record EventDetailResponse(
    String id, String name, String startsAt, String venue, List<SectorResponse> sectors) {

  /** A sector with its current stock. */
  public record SectorResponse(
      String id, String name, int capacity, int available, long unitPriceCents, String currency) {

    static SectorResponse from(SectorView s) {
      return new SectorResponse(
          s.id().toString(),
          s.name(),
          s.capacity(),
          s.available(),
          s.unitPriceCents(),
          s.currency());
    }
  }

  static EventDetailResponse from(EventDetail d) {
    return new EventDetailResponse(
        d.event().id().toString(),
        d.event().name(),
        d.event().startsAt().toString(),
        d.event().venue(),
        d.sectors().stream().map(SectorResponse::from).toList());
  }
}
