import type { FastifyInstance } from "fastify";
import type { Pool } from "pg";
import { problem } from "../../../app/errors.js";
import { correlationIdOf, requireUser } from "../../../app/identity.js";
import { createOrder } from "../application/createOrder.js";
import { getOrder, InvalidCursorError, listOrders } from "../application/queries.js";
import { createOrderBody, idempotencyKey, listQuery, orderId } from "./schemas.js";

export interface OrderRoutesDeps {
  pool: Pool; // API role (RLS)
  topicArn: string; // ORDERS_TOPIC_ARN
  maxQuantity: number; // ORDER_MAX_QUANTITY
}

/** POST /orders (202, R3), GET /orders/{id} (owner only), GET /orders (paginated). */
export function registerOrderRoutes(app: FastifyInstance, deps: OrderRoutesDeps): void {
  const body = createOrderBody(deps.maxQuantity);

  app.post("/orders", async (request, reply) => {
    const userId = requireUser(request, reply);
    if (!userId) return reply;
    const key = idempotencyKey.safeParse(request.headers["idempotency-key"]);
    if (!key.success)
      return problem(
        request,
        reply,
        400,
        "VALIDATION_ERROR",
        "Idempotency-Key header must be a UUID",
      );
    const input = body.safeParse(request.body);
    if (!input.success) {
      return problem(
        request,
        reply,
        400,
        "VALIDATION_ERROR",
        input.error.issues.map((i) => `${i.path.join(".") || "body"}: ${i.message}`).join("; "),
      );
    }
    const { order, created } = await createOrder(
      deps.pool,
      userId,
      correlationIdOf(request),
      input.data,
      key.data,
      deps.topicArn,
    );
    if (!created) return reply.code(200).send(order);
    return reply
      .code(202)
      .header("location", `/orders/${order.orderId}`)
      .send({ orderId: order.orderId, status: order.status });
  });

  app.get<{ Params: { id: string } }>("/orders/:id", async (request, reply) => {
    const userId = requireUser(request, reply);
    if (!userId) return reply;
    if (!orderId.safeParse(request.params.id).success)
      return problem(request, reply, 404, "NOT_FOUND");
    const order = await getOrder(deps.pool, userId, request.params.id);
    return order ? reply.send(order) : problem(request, reply, 404, "NOT_FOUND");
  });

  app.get("/orders", async (request, reply) => {
    const userId = requireUser(request, reply);
    if (!userId) return reply;
    const query = listQuery.safeParse(request.query);
    if (!query.success)
      return problem(request, reply, 400, "VALIDATION_ERROR", "limit must be between 1 and 50");
    try {
      return await reply.send(
        await listOrders(deps.pool, userId, query.data.limit, query.data.cursor),
      );
    } catch (error) {
      if (error instanceof InvalidCursorError)
        return problem(request, reply, 400, "VALIDATION_ERROR", "cursor is invalid");
      throw error;
    }
  });
}
