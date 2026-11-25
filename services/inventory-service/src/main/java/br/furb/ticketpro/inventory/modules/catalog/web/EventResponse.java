package br.furb.ticketpro.inventory.modules.catalog.web;

import br.furb.ticketpro.inventory.modules.catalog.domain.EventView;

/** An event in the list: an explicit DTO, never the database row. */
public record EventResponse(String id, String name, String startsAt, String venue) {

  static EventResponse from(EventView e) {
    return new EventResponse(e.id().toString(), e.name(), e.startsAt().toString(), e.venue());
  }
}
