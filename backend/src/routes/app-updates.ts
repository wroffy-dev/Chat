import { createHash, timingSafeEqual } from "node:crypto";
import type { Readable } from "node:stream";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import { appError } from "../errors.js";
import type { AppUpdateStore, PublishedApk } from "../updates/store.js";

const APK_TYPE = "application/vnd.android.package-archive";

export interface AppUpdateRouteOptions {
  auth: AuthService;
  store: AppUpdateStore;
  /** Shared with the APK build workflow; without it uploads are refused. */
  uploadToken?: string;
  onPublished?: (meta: PublishedApk) => Promise<void>;
}

/**
 * In-app updates: the build workflow uploads each new APK, and signed-in users download it.
 * Android installs it over the old app only when both are signed with the same key, so the
 * upload token alone cannot put a foreign app on the phones.
 */
export async function appUpdateRoutes(app: FastifyInstance, opts: AppUpdateRouteOptions) {
  const user = requireUser(opts.auth);
  const expected = opts.uploadToken ? digest(opts.uploadToken) : null;

  // The body is streamed to disk, never buffered; AppUpdateStore enforces the size limit.
  app.addContentTypeParser(APK_TYPE, (_request, payload, done) => done(null, payload));

  app.put<{ Querystring: { versionCode: number } }>(
    "/app/apk",
    {
      onRequest: async (request: FastifyRequest) => {
        const header = request.headers.authorization;
        const token = typeof header === "string" && header.startsWith("Bearer ") ? header.slice(7) : "";
        if (!expected || !token || !timingSafeEqual(digest(token), expected)) throw appError(401, "UNAUTHENTICATED");
        if (request.headers["content-type"] !== APK_TYPE) throw appError(415, "UNSUPPORTED_MEDIA_TYPE");
      },
      config: { rateLimit: { max: 10, timeWindow: 60 * 60_000 } },
      schema: {
        querystring: {
          type: "object",
          additionalProperties: false,
          required: ["versionCode"],
          properties: { versionCode: { type: "integer", minimum: 1, maximum: 2_000_000_000 } },
        },
      },
    },
    async (request) => {
      const meta = await opts.store.publish(request.body as Readable, request.query.versionCode);
      await opts.onPublished?.(meta);
      return meta;
    },
  );

  app.get(
    "/app/apk",
    { onRequest: user, config: { rateLimit: { max: 20, timeWindow: 60 * 60_000 } } },
    async (_request, reply) => {
      const apk = await opts.store.open();
      if (!apk) throw appError(404, "NOT_FOUND");
      return reply
        .type(APK_TYPE)
        .header("Content-Length", apk.meta.size)
        .header("X-Apk-Sha256", apk.meta.sha256)
        .header("X-Apk-Version-Code", apk.meta.versionCode)
        .send(apk.stream);
    },
  );
}

function digest(value: string): Buffer {
  return createHash("sha256").update(value).digest();
}
