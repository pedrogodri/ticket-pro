package br.furb.ticketpro.inventory.modules.catalog.domain;

import java.util.List;

/** An event and its sectors, ordered by price (descending) and name. */
public record EventDetail(EventView event, List<SectorView> sectors) {}
