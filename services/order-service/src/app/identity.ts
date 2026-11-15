import type { FastifyReply, FastifyRequest } from "fastify";
import { problem } from "./errors.js";

/**
 * The user injected by the gateway (X-User-Id, RFC 0002 §5.5). Without it the request is answered 401 and
 * undefined is returned: identity never comes from the body or query.
 */
export function requireUser(request: FastifyRequest, reply: FastifyReply): string | undefined {
  const user = request.headers["x-user-id"];
  if (typeof user !== "string" || user === "") {
    void problem(request, reply, 401, "UNAUTHENTICATED");
    return undefined;
  }
  return user;
}

/** The correlation id from the gateway, or a fresh one for direct calls. */
export function correlationIdOf(request: FastifyRequest): string {
  const value = request.headers["x-correlation-id"];
  return typeof value === "string" && value !== "" ? value : crypto.randomUUID();
}
