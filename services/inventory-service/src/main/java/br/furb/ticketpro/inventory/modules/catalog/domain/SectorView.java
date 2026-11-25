package br.furb.ticketpro.inventory.modules.catalog.domain;

import java.util.UUID;

/** A sector with its current stock, as GET /events/{id} shows it. */
public record SectorView(
    UUID id, String name, int capacity, int available, long unitPriceCents, String currency) {}
