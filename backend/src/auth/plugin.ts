import type { FastifyReply, FastifyRequest } from "fastify";
import { appError } from "../errors.js";
import type { AuthContext, AuthService, RequestMeta } from "./service.js";
import { truncateIp } from "./tokens.js";

declare module "fastify" {
  interface FastifyRequest {
    auth?: AuthContext;
  }
}

function bearerToken(request: FastifyRequest): string | null {
  const header = request.headers.authorization;
  if (!header) return null;
  const match = /^Bearer ([A-Za-z0-9_-]{10,200})$/.exec(header);
  return match ? match[1]! : null;
}

export function requestMeta(request: FastifyRequest): RequestMeta {
  return { ip: truncateIp(request.ip) };
}

/**
 * preHandler factory. The authenticated user always comes from the session, never from the
 * request body or path (docs/04-accounts-and-authentication.md §9).
 */
export function requireUser(auth: AuthService, opts: { allowRestricted?: boolean } = {}) {
  return async (request: FastifyRequest, _reply: FastifyReply) => {
    const token = bearerToken(request);
    if (!token) throw appError(401, "UNAUTHENTICATED");
    const ctx = await auth.authenticate(token);
    if (ctx.session.restricted && !opts.allowRestricted) throw appError(403, "PASSWORD_CHANGE_REQUIRED");
    request.auth = ctx;
  };
}

export function authOf(request: FastifyRequest): AuthContext {
  if (!request.auth) throw appError(401, "UNAUTHENTICATED");
  return request.auth;
}
