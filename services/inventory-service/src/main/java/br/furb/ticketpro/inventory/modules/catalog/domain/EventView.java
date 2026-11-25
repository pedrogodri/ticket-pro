package br.furb.ticketpro.inventory.modules.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** An event of the catalog (no sectors): what GET /events lists. */
public record EventView(UUID id, String name, Instant startsAt, String venue) {}
