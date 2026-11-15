import type { FastifyReply, FastifyRequest } from "fastify";

/** Error codes (padroes §4.1) with PT-BR titles. */
const titles = {
  VALIDATION_ERROR: "Requisição inválida",
  UNAUTHENTICATED: "Não autenticado",
  NOT_FOUND: "Não encontrado",
  CONFLICT: "Conflito",
  INTERNAL_ERROR: "Erro interno",
} as const;

export type ErrorCode = keyof typeof titles;

/** Sends application/problem+json (RFC 9457) with the request's correlation id. */
export function problem(
  request: FastifyRequest,
  reply: FastifyReply,
  status: number,
  code: ErrorCode,
  detail?: string,
): FastifyReply {
  const correlationId = request.headers["x-correlation-id"];
  return reply
    .code(status)
    .type("application/problem+json")
    .send({
      type: `https://ticketpro.local/errors/${code.toLowerCase()}`,
      title: titles[code],
      status,
      ...(detail ? { detail } : {}),
      instance: request.url.split("?")[0],
      code,
      correlationId: typeof correlationId === "string" ? correlationId : null,
    });
}
