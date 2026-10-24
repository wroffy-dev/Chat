import { randomUUID } from "node:crypto";
import cookie from "@fastify/cookie";
import formbody from "@fastify/formbody";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest, type FastifyServerOptions } from "fastify";
import type { PrismaClient } from "../generated/prisma/client.js";
import type { Config } from "../config/env.js";
import type { Database } from "../db/database.js";
import { AppError } from "../errors.js";
import type { AuthService } from "../auth/service.js";
import { MAX_USERS } from "../auth/service.js";
import { safeEqual } from "../auth/tokens.js";
import { buildLoggerOptions } from "../logger.js";
import { ADMIN_SESSION_POLICY, AdminLoginError, type AdminContext, type AdminService } from "./service.js";
import { csrfField, esc, fmt, page } from "./views.js";

const COOKIE = "vc_admin";

export interface AdminAppDeps {
  config: Config;
  prisma: PrismaClient;
  database: Database;
  auth: AuthService;
  admin: AdminService;
  logger?: FastifyServerOptions["logger"];
}

declare module "fastify" {
  interface FastifyRequest {
    adminCtx?: AdminContext;
  }
}

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
const idParams = { type: "object", required: ["id"], properties: { id: { type: "string", pattern: UUID } } } as const;
const csrfBody = {
  type: "object",
  required: ["csrf"],
  additionalProperties: false,
  properties: { csrf: { type: "string", maxLength: 100 } },
} as const;

/**
 * The Admin Console (docs/05-admin-console.md). A separate Fastify instance on its own port, bound
 * to loopback and reached through an SSH tunnel. It cannot read messages, tokens or passwords.
 */
export function buildAdminApp(deps: AdminAppDeps): FastifyInstance {
  const { config, prisma, database, auth, admin } = deps;
  const app = Fastify({
    logger: deps.logger ?? buildLoggerOptions(config),
    genReqId: () => randomUUID(),
    requestIdHeader: false,
    bodyLimit: 16_384,
    ajv: { customOptions: { removeAdditional: false } },
  });

  app.register(cookie);
  app.register(formbody);

  app.addHook("onSend", async (request, reply) => {
    reply.header("X-Request-Id", request.id);
    reply.header("Cache-Control", "no-store");
    reply.header("X-Content-Type-Options", "nosniff");
    reply.header("X-Frame-Options", "DENY");
    reply.header("Referrer-Policy", "no-referrer");
    reply.header(
      "Content-Security-Policy",
      "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    );
  });

  const html = (reply: FastifyReply, body: string, status = 200) =>
    reply.status(status).type("text/html; charset=utf-8").send(body);

  app.setErrorHandler((error: Error & { statusCode?: number; validation?: unknown }, request, reply) => {
    if (error instanceof AppError) {
      return html(reply, page("Error", `<p class="error">${esc(error.message)}</p><p><a href="/admin/users">Back</a></p>`), error.statusCode);
    }
    if (error.validation || (error.statusCode && error.statusCode < 500)) {
      return html(reply, page("Bad request", `<p class="error">The request was not valid.</p><p><a href="/admin/users">Back</a></p>`), error.statusCode ?? 400);
    }
    request.log.error({ err: error }, "admin console error");
    return html(reply, page("Error", `<p class="error">Something went wrong. Request ID: ${esc(request.id)}</p>`), 500);
  });
  app.setNotFoundHandler((_req, reply) => html(reply, page("Not found", "<p>Not found.</p>"), 404));

  const setSessionCookie = (reply: FastifyReply, token: string) =>
    reply.setCookie(COOKIE, token, {
      httpOnly: true,
      // Browsers treat http://localhost as a secure context, so Secure cookies work through the tunnel.
      secure: true,
      sameSite: "strict",
      path: "/admin",
      maxAge: Math.floor(ADMIN_SESSION_POLICY.absoluteTtlMs / 1000),
    });

  /** Authenticated pages; state-changing requests must also carry the session's CSRF token. */
  const requireAdmin = async (request: FastifyRequest, reply: FastifyReply) => {
    const ctx = await admin.authenticate(request.cookies[COOKIE]);
    if (!ctx) return reply.redirect("/admin/login", 303);
    if (request.method === "POST") {
      const sent = (request.body as { csrf?: unknown } | undefined)?.csrf;
      if (typeof sent !== "string" || !safeEqual(sent, ctx.session.csrfToken)) {
        request.log.warn("admin csrf check failed");
        return html(reply, page("Forbidden", `<p class="error">The form expired. Go back and try again.</p>`), 403);
      }
    }
    request.adminCtx = ctx;
  };
  const ctxOf = (request: FastifyRequest) => request.adminCtx!;
  const actor = (request: FastifyRequest) => ({ type: "admin" as const, id: ctxOf(request).admin.id });
  const nav = (request: FastifyRequest) => ({ csrf: ctxOf(request).session.csrfToken, username: ctxOf(request).admin.username });

  // ------------------------------------------------------------------ login / logout

  const loginForm = (message?: string) =>
    page(
      "Log in",
      `<div class="card" style="max-width:420px">
        <h1>Administrator login</h1>
        ${message ? `<p class="error">${esc(message)}</p>` : ""}
        <form method="post" action="/admin/login">
          <label for="u">Username</label><input id="u" name="username" autocomplete="username" required maxlength="64">
          <label for="p">Password</label><input id="p" name="password" type="password" autocomplete="current-password" required maxlength="512">
          <label for="t">Authenticator code</label><input id="t" name="totp" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required maxlength="6">
          <p><button class="primary">Log in</button></p>
        </form></div>`,
    );

  app.get("/admin/login", async (_request, reply) => {
    if (!(await admin.isInitialised())) {
      return html(reply, page("Not initialised", `<h1>Not initialised</h1>
        <p>No administrator exists yet. Run the setup command on the server:</p>
        <pre>node dist/cli.js admin:create --username &lt;name&gt;</pre>
        <p class="muted">There is deliberately no web-based admin registration.</p>`));
    }
    return html(reply, loginForm());
  });

  app.post<{ Body: { username: string; password: string; totp: string } }>(
    "/admin/login",
    {
      schema: {
        body: {
          type: "object",
          required: ["username", "password", "totp"],
          additionalProperties: false,
          properties: {
            username: { type: "string", maxLength: 64 },
            password: { type: "string", maxLength: 512 },
            totp: { type: "string", maxLength: 10 },
          },
        },
      },
    },
    async (request, reply) => {
      try {
        const { token } = await admin.login(request.body.username, request.body.password, request.body.totp);
        setSessionCookie(reply, token);
        return reply.redirect("/admin/users", 303);
      } catch (err) {
        if (err instanceof AdminLoginError) {
          const msg = err.reason === "throttled"
            ? `Too many failed attempts. Try again in ${Math.ceil(err.retryAfterMs / 60_000)} minutes.`
            : "Username, password or authenticator code is incorrect.";
          return html(reply, loginForm(msg), err.reason === "throttled" ? 429 : 401);
        }
        throw err;
      }
    },
  );

  app.post("/admin/logout", { preHandler: requireAdmin, schema: { body: csrfBody } }, async (request, reply) => {
    await admin.logout(ctxOf(request));
    reply.clearCookie(COOKIE, { path: "/admin" });
    return reply.redirect("/admin/login", 303);
  });

  app.get("/admin", async (_request, reply) => reply.redirect("/admin/users", 303));

  // ------------------------------------------------------------------ users

  app.get("/admin/users", { preHandler: requireAdmin }, async (request, reply) => {
    const csrf = ctxOf(request).session.csrfToken;
    const users = await prisma.user.findMany({
      orderBy: { slot: "asc" },
      include: { sessions: { where: { revokedAt: null }, orderBy: { lastUsedAt: "desc" } } },
    });
    const rows = users.map((u) => {
      const sessions = u.sessions
        .map((s) => `${fmt(s.lastUsedAt)} · ${esc(s.deviceModel ?? "unknown device")} · v${esc(s.appVersionCode ?? "?")}${s.restricted ? " · must change password" : ""}`)
        .join("<br>") || "—";
      const toggle = u.status === "active" ? "disable" : "enable";
      return `<tr>
        <td>${u.slot}</td><td><strong>${esc(u.username)}</strong></td>
        <td>${esc(u.status)}${u.mustChangePassword ? "<br><span class=muted>must change password</span>" : ""}</td>
        <td>${fmt(u.lastSeenAt)}</td><td>${sessions}</td>
        <td>
          <form class="inline" method="post" action="/admin/users/${u.id}/reset-password">${csrfField(csrf)}<button>Reset password</button></form>
          <form class="inline" method="post" action="/admin/users/${u.id}/revoke-sessions">${csrfField(csrf)}<button>Revoke sessions</button></form>
          <form class="inline" method="post" action="/admin/users/${u.id}/${toggle}">${csrfField(csrf)}<button class="danger">${toggle === "disable" ? "Disable" : "Enable"}</button></form>
        </td></tr>`;
    });
    const create = users.length < MAX_USERS
      ? `<div class="card"><h2>Create user (${users.length} of ${MAX_USERS})</h2>
          <form method="post" action="/admin/users">${csrfField(csrf)}
            <label for="nu">Username (3–32: a-z, 0-9, dot, dash, underscore)</label>
            <input id="nu" name="username" required maxlength="32" pattern="[a-zA-Z0-9._-]{3,32}">
            <p><button class="primary">Create with temporary password</button></p></form></div>`
      : `<p class="muted">Both user accounts exist. No more can be created.</p>`;
    const connection = await prisma.connection.findFirst({ include: { userA: true, userB: true } });
    const connectionLine = connection
      ? `<p>Connected: <strong>${esc(connection.userA.username)}</strong> ↔ <strong>${esc(connection.userB.username)}</strong> since ${fmt(connection.createdAt)}. Connections are permanent in this version.</p>`
      : `<p class="muted">The users are not connected yet (they pair by scanning a QR code in the app).</p>`;
    return html(reply, page("Users", `<h1>Users</h1>${connectionLine}
      <table><thead><tr><th>Slot</th><th>Username</th><th>Status</th><th>Last seen</th><th>Active sessions</th><th>Actions</th></tr></thead>
      <tbody>${rows.join("") || `<tr><td colspan="6" class="muted">No users yet.</td></tr>`}</tbody></table>${create}`, nav(request)));
  });

  const showTemporaryPassword = (request: FastifyRequest, reply: FastifyReply, username: string, password: string, what: string) =>
    html(reply, page("Temporary password", `<h1>${esc(what)}</h1>
      <p>Temporary password for <strong>${esc(username)}</strong>. It is shown <strong>only once</strong> and is not stored.
      Give it to the user in person or over another trusted channel. They must choose a new password at first login.</p>
      <p><code class="secret">${esc(password)}</code></p>
      <p><a href="/admin/users">Back to users</a></p>`, nav(request)));

  app.post<{ Body: { csrf: string; username: string } }>(
    "/admin/users",
    {
      preHandler: requireAdmin,
      schema: {
        body: {
          type: "object",
          required: ["csrf", "username"],
          additionalProperties: false,
          properties: { csrf: { type: "string", maxLength: 100 }, username: { type: "string", maxLength: 64 } },
        },
      },
    },
    async (request, reply) => {
      const { user, temporaryPassword } = await auth.createUser(request.body.username, actor(request));
      return showTemporaryPassword(request, reply, user.username, temporaryPassword, "User created");
    },
  );

  app.post<{ Params: { id: string } }>(
    "/admin/users/:id/reset-password",
    { preHandler: requireAdmin, schema: { params: idParams, body: csrfBody } },
    async (request, reply) => {
      const user = await prisma.user.findUnique({ where: { id: request.params.id } });
      if (!user) throw new AppError(404, "NOT_FOUND", "User not found.");
      const password = await auth.resetPassword(user.id, actor(request));
      return showTemporaryPassword(request, reply, user.username, password, "Password reset — all sessions ended");
    },
  );

  app.post<{ Params: { id: string } }>(
    "/admin/users/:id/revoke-sessions",
    { preHandler: requireAdmin, schema: { params: idParams, body: csrfBody } },
    async (request, reply) => {
      await auth.revokeUserSessions(request.params.id, "admin_revoke", actor(request));
      return reply.redirect("/admin/users", 303);
    },
  );

  for (const [path, status] of [["disable", "disabled"], ["enable", "active"]] as const) {
    app.post<{ Params: { id: string } }>(
      `/admin/users/:id/${path}`,
      { preHandler: requireAdmin, schema: { params: idParams, body: csrfBody } },
      async (request, reply) => {
        await auth.setUserStatus(request.params.id, status, actor(request));
        return reply.redirect("/admin/users", 303);
      },
    );
  }

  // ------------------------------------------------------------------ health & audit

  app.get("/admin/health", { preHandler: requireAdmin }, async (request, reply) => {
    const readiness = await database.checkReadiness(config.READINESS_DB_TIMEOUT_MS);
    const mem = process.memoryUsage();
    const rows: [string, string][] = [
      ["Database", readiness.ready ? "ready" : `not ready (${readiness.problems.join(", ")})`],
      ["Pending migrations", readiness.pendingMigrations.join(", ") || "none"],
      ["Process uptime", `${Math.floor(process.uptime() / 60)} min`],
      ["Memory (RSS)", `${Math.round(mem.rss / 1_048_576)} MB`],
      ["Node.js", process.version],
      ["LiveKit", "not configured yet (calling phase)"],
      ["Push notifications", "not configured yet"],
    ];
    return html(reply, page("Health", `<h1>Health</h1><table><tbody>${rows
      .map(([k, v]) => `<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`).join("")}</tbody></table>`, nav(request)));
  });

  app.get<{ Querystring: { before?: string } }>(
    "/admin/audit",
    {
      preHandler: requireAdmin,
      schema: { querystring: { type: "object", additionalProperties: false, properties: { before: { type: "string", pattern: "^[0-9]{1,19}$" } } } },
    },
    async (request, reply) => {
      const before = request.query.before ? BigInt(request.query.before) : undefined;
      const events = await prisma.auditEvent.findMany({
        where: before ? { id: { lt: before } } : {},
        orderBy: { id: "desc" },
        take: 100,
      });
      const rows = events.map((e) => `<tr><td>${fmt(e.occurredAt)}</td><td>${esc(e.action)}</td>
        <td>${esc(e.actorType)}${e.actorId ? ` <span class=muted>${esc(e.actorId.slice(0, 8))}</span>` : ""}</td>
        <td>${e.targetId ? `${esc(e.targetType)} <span class=muted>${esc(e.targetId.slice(0, 8))}</span>` : "—"}</td>
        <td>${esc(e.ipTruncated ?? "—")}</td><td class="muted">${esc(e.metadata ? JSON.stringify(e.metadata) : "")}</td></tr>`);
      const last = events.at(-1);
      const more = events.length === 100 && last ? `<p><a href="/admin/audit?before=${last.id}">Older events</a></p>` : "";
      return html(reply, page("Audit log", `<h1>Audit log</h1><p class="muted">Read-only. Events cannot be edited or deleted here.</p>
        <table><thead><tr><th>Time</th><th>Action</th><th>Actor</th><th>Target</th><th>IP (truncated)</th><th>Details</th></tr></thead>
        <tbody>${rows.join("") || `<tr><td colspan="6" class="muted">No events.</td></tr>`}</tbody></table>${more}`, nav(request)));
    },
  );

  return app;
}
