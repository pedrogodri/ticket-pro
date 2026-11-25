using UserService.Domain.Orders;

namespace UserService.Application.Orders;

/// <summary>Writes to the "my orders" read model inside the consumer's transaction.</summary>
public interface IOrderProjectionStore
{
    /// <summary>Inserts the order if it is not projected yet; false when it already was.</summary>
    Task<bool> AddAsync(MyOrder order, CancellationToken ct);
}
