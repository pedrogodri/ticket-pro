/** Saga states (arquitetura §6, state machine). */
export type OrderStatus = "PENDING" | "AWAITING_PAYMENT" | "CONFIRMED" | "CANCELLED";

export type CancelReason =
  "OUT_OF_STOCK" | "PAYMENT_FAILED" | "PAYMENT_TIMEOUT" | "RESERVATION_EXPIRED";

/** What the API returns for an order (explicit DTO, never the row: OWASP API3). */
export interface OrderView {
  orderId: string;
  status: OrderStatus;
  eventId: string;
  sectorId: string;
  quantity: number;
  totalCents: number | null;
  currency: string | null;
  cancelReason: CancelReason | null;
  createdAt: string;
}

export interface CreateOrderInput {
  eventId: string;
  sectorId: string;
  quantity: number;
}
