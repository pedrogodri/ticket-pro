package br.furb.ticketpro.inventory.modules.reservations.domain;

import java.util.UUID;

/** Internal model of OrderCreated: always v2 (RFC 0006 §5.4). */
public record OrderCreatedV2(
    UUID orderId, UUID userId, UUID eventId, UUID sectorId, int quantity, String channel) {}
