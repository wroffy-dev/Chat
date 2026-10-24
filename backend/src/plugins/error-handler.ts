import type { FastifyError, FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  AppError,
  DEFAULT_MESSAGES,
  codeForStatus,
  type ErrorBody,
  type ErrorCode,
  type ErrorDetail,
} from "../errors.js";

function send(
  reply: FastifyReply,
  request: FastifyRequest,
  statusCode: number,
  code: ErrorCode,
  message: string,
  details?: ErrorDetail[],
) {
  const body: ErrorBody = { error: { code, message, requestId: request.id } };
  if (details && details.length > 0) body.error.details = details;
  return reply.status(statusCode).send(body);
}

function validationDetails(error: FastifyError): ErrorDetail[] {
  return (error.validation ?? []).map((v) => {
    const missing = (v.params as { missingProperty?: string; additionalProperty?: string } | undefined) ?? {};
    const field = missing.missingProperty ?? missing.additionalProperty;
    const base = `${error.validationContext ?? "body"}${v.instancePath ?? ""}`;
    return { path: field ? `${base}/${field}` : base, rule: v.keyword };
  });
}

/**
 * Uniform error responses (docs/14-api-contracts.md §1). Internal messages and stack traces are
 * logged server-side and never returned to the client.
 */
export function registerErrorHandling(app: FastifyInstance) {
  app.setErrorHandler((error: FastifyError, request, reply) => {
    if (error instanceof AppError) {
      return send(reply, request, error.statusCode, error.code, error.message, error.details);
    }

    if (error.validation) {
      return send(reply, request, 400, "VALIDATION_FAILED", DEFAULT_MESSAGES.VALIDATION_FAILED, validationDetails(error));
    }

    const status = error.statusCode ?? 500;
    if (status >= 400 && status < 500) {
      request.log.info({ errCode: error.code, statusCode: status }, "client error");
      const code = codeForStatus(status);
      return send(reply, request, status, code, DEFAULT_MESSAGES[code]);
    }

    request.log.error({ err: error }, "unhandled error");
    const code = status === 503 ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR";
    return send(reply, request, status === 503 ? 503 : 500, code, DEFAULT_MESSAGES[code]);
  });

  app.setNotFoundHandler((request, reply) =>
    send(reply, request, 404, "NOT_FOUND", DEFAULT_MESSAGES.NOT_FOUND),
  );
}
