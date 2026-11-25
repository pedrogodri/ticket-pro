namespace UserService.Domain.Orders;

/// <summary>One confirmed order in the "my orders" read model, projected from OrderConfirmed. Only confirmed
/// orders exist here; the live order status belongs to order-service.</summary>
public sealed record MyOrder(
    Guid OrderId,
    Guid UserId,
    Guid EventId,
    Guid SectorId,
    int Quantity,
    long TotalCents,
    string Currency,
    DateTime ConfirmedAt);
