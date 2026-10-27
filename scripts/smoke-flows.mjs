// Drives the app's main flows through the HTTP API against a running backend with an EMPTY
// database: two users, password change, pairing, chat (send, read, leave → deletion), call start,
// push token, logout. Exits non-zero on the first unexpected answer. Never use on real data.
//
//   cd backend && DATABASE_URL=… API=http://127.0.0.1:3000 node ../scripts/smoke-flows.mjs
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";

const API = process.env.API ?? "http://127.0.0.1:3000";
let step = "start";
const fail = (msg) => {
  console.error(`  FAILED  ${step}: ${msg}`);
  process.exit(1);
};
const api = async (method, path, body, token, expect) => {
  const r = await fetch(`${API}/api/v1${path}`, {
    method,
    headers: { ...(body ? { "content-type": "application/json" } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (r.status !== expect) fail(`${method} ${path} → ${r.status}, expected ${expect}`);
  const text = await r.text();
  return text ? JSON.parse(text) : null;
};
const ok = (label) => console.log(`  ok      ${label}`);

const users = {};
for (const name of ["smoke-a", "smoke-b"]) {
  step = `create ${name}`;
  const out = execFileSync("node", ["dist/cli.js", "user:create", "--username", name], { encoding: "utf8" });
  const temp = /shown once\): (\S+)/.exec(out)?.[1] ?? fail("no temporary password");
  step = `login ${name}`;
  const first = await api("POST", "/auth/login", { username: name, password: temp }, null, 200);
  await api("GET", "/me", null, first.accessToken, 403);
  const changed = await api("POST", "/auth/change-password", { currentPassword: temp, newPassword: `${name} smoke passphrase 2026` }, first.accessToken, 200);
  users[name] = changed.accessToken;
}
ok("two users, forced password change");
const [a, b] = [users["smoke-a"], users["smoke-b"]];

step = "pairing";
const qr = await api("POST", "/connection-qr", {}, a, 201);
const req = await api("POST", "/connection-requests", { token: qr.token }, b, 201);
const conn = await api("POST", `/connection-requests/${req.id}/accept`, {}, a, 200);
await api("POST", "/connection-requests", { token: qr.token }, b, 410);
ok("QR pairing (code single-use)");

step = "chat";
const conv = conn.conversationId;
await api("POST", `/conversations/${conv}/enter`, {}, b, 200);
const sent = await api("POST", `/conversations/${conv}/messages`, { clientMessageId: randomUUID(), epoch: 0, text: "smoke message" }, a, 201);
const seen = await api("GET", `/conversations/${conv}/messages`, null, b, 200);
if (seen.items.length !== 1) fail("message not delivered");
await api("POST", `/conversations/${conv}/read`, { seq: sent.seq }, b, 204);
await api("POST", `/conversations/${conv}/leave`, { leaveId: randomUUID() }, b, 200);
const after = await api("GET", `/conversations/${conv}/messages`, null, a, 200);
if (after.items.length !== 0 || after.epoch !== 1) fail("seen message not deleted on exit");
ok("chat send, read, exit deletes the seen message");

step = "call";
const r = await fetch(`${API}/api/v1/calls`, {
  method: "POST",
  headers: { "content-type": "application/json", authorization: `Bearer ${a}` },
  body: JSON.stringify({ connectionId: conn.id, kind: "voice" }),
});
if (r.status !== 201 && r.status !== 503) fail(`POST /calls → ${r.status}`);
ok(r.status === 201 ? "call started" : "call refused cleanly without LiveKit (503 MEDIA_UNAVAILABLE)");

step = "push token and logout";
await api("PUT", "/devices/push-token", { fcmToken: "smoke-fcm-token-0123456789abcdef" }, b, 204);
await api("POST", "/auth/logout", {}, b, 204);
await api("GET", "/me", null, b, 401);
ok("push token, logout ends the session");
