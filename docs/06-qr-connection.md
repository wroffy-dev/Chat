# 06 — QR-Based Connection

Status: **Proposed**. Not implemented.

## 1. User flow

1. User A opens **Inbox → Show my QR**.
2. The app requests a QR token from the backend and displays it. It refreshes automatically before expiry.
3. User B opens **Inbox → Scan QR** and scans A's screen.
4. B's app sends a connection request with the scanned token.
5. A sees "B wants to connect" (live over WebSocket, or via push if A left the screen) and taps
   **Accept** or **Reject**.
6. On accept, A and B are permanently connected. Chat and calls become available.

Because only two users exist, there is at most one connection. Once connected, the QR screens show
"You are already connected" and the generate/scan actions are disabled.

## 2. QR token design

| Property | Design |
|---|---|
| Generation | 32 bytes from a CSPRNG (`crypto.randomBytes(32)`), encoded base64url (43 chars) |
| Storage | Only `SHA-256(token)` is stored (`qr_tokens.token_hash`, unique). The plaintext exists only in the QR image and in transit |
| QR content | `vaultchat://connect?v=1&t=<token>` — no username, no user ID, no password, nothing else |
| Lifetime | 60 seconds (`expires_at`) |
| Rotation | The app requests a new token at ~45 s. Creating a new token **invalidates** the owner's previous unconsumed token (one active token per owner) |
| Single use | Consumed atomically on first valid scan (`consumed_at`, `consumed_by_user_id`) |
| Rate limit | Generation: 6 per minute per user, 60 per hour. Scan/redeem: 10 per minute per user |

Why opaque random tokens and not signed tokens: single-use and "previous token invalidated" both need
server-side state anyway, and an opaque token reveals nothing if photographed.

## 3. Lifecycle

```
qr_tokens:            ACTIVE ──scan──► CONSUMED
                         │
                         ├─expires_at passed──► EXPIRED (derived, not stored)
                         └─owner generated a new token──► SUPERSEDED (superseded_at set)

connection_requests:  PENDING ──owner accepts──► ACCEPTED  (creates connections row)
                         │──owner rejects──► REJECTED
                         │──requester cancels──► CANCELLED
                         └─5 minutes pass──► EXPIRED

connections:          created once, never deleted in v1
```

## 4. Server logic (transactions)

### 4.1 `POST /api/v1/connection-qr` (generate)

```
BEGIN
  if a row exists in connections for this user → 409 ALREADY_CONNECTED
  UPDATE qr_tokens SET superseded_at = now()
    WHERE owner_user_id = :me AND consumed_at IS NULL AND superseded_at IS NULL
  INSERT qr_tokens (owner_user_id, token_hash, expires_at = now() + 60 s)
COMMIT
return { token, expiresAt }
```

### 4.2 `POST /api/v1/connection-requests` (redeem a scanned token)

```
BEGIN
  row = UPDATE qr_tokens
          SET consumed_at = now(), consumed_by_user_id = :me
        WHERE token_hash = sha256(:token)
          AND consumed_at IS NULL AND superseded_at IS NULL
          AND expires_at > now()
          AND owner_user_id <> :me
        RETURNING id, owner_user_id
  if no row → 410 QR_INVALID_OR_EXPIRED   (same error for unknown, expired, used, superseded, own token)
  if connection already exists between :me and owner → ROLLBACK; 409 ALREADY_CONNECTED
  UPDATE connection_requests SET status = 'expired'          -- lazy expiry, keeps the partial
    WHERE status = 'pending' AND expires_at <= now()         -- unique index accurate
      AND requester_user_id = :me AND owner_user_id = owner
  if a PENDING request already exists for (requester=:me, owner) → ROLLBACK; 409 REQUEST_ALREADY_PENDING
  INSERT connection_requests (qr_token_id, requester_user_id = :me, owner_user_id, status = 'pending',
                              expires_at = now() + 5 min)
COMMIT
notify owner (WS event connection_request.created; FCM if no socket)
```

The single `UPDATE … WHERE consumed_at IS NULL … RETURNING` is atomic in PostgreSQL: if two scans of
the same token arrive simultaneously, exactly one gets the row; the other gets `410`.

### 4.3 `POST /api/v1/connection-requests/{id}/accept`

```
BEGIN
  req = SELECT … FROM connection_requests WHERE id = :id FOR UPDATE
  if req.owner_user_id <> :me → 404 NOT_FOUND   (do not reveal existence)
  if req.status <> 'pending' or req.expires_at <= now() → 409 REQUEST_NOT_PENDING
  (a, b) = order(req.requester_user_id, req.owner_user_id)   -- canonical ordering
  INSERT connections (user_a_id = a, user_b_id = b, created_from_request_id = req.id)
     ON CONFLICT (user_a_id, user_b_id) DO NOTHING
  INSERT conversations (connection_id) ON CONFLICT DO NOTHING
  UPDATE connection_requests SET status = 'accepted', decided_at = now() WHERE id = :id
  UPDATE connection_requests SET status = 'cancelled', decided_at = now()
     WHERE status = 'pending' AND id <> :id AND both users involved
COMMIT
notify both (connection.created)
audit connection.accepted
```

Reject is the same shape with `status = 'rejected'` and no connection insert.

## 5. Database constraints

Defined in [13-database-design.md](13-database-design.md):

- `qr_tokens.token_hash` UNIQUE.
- `connection_requests.qr_token_id` UNIQUE — one request per token, ever.
- Partial unique index on `connection_requests (requester_user_id, owner_user_id) WHERE status = 'pending'`
  — no duplicate pending requests.
- `connections`: `CHECK (user_a_id < user_b_id)` and UNIQUE `(user_a_id, user_b_id)` — one connection per
  pair regardless of who scanned whom.
- The application database role has **no DELETE privilege** on `connections` (permanence enforced by the
  database, not only by the absence of an endpoint).

## 6. Edge cases

| Case | Behaviour |
|---|---|
| QR refreshed after B scanned but before A accepted | Accept still works — the request references the consumed token, not the current one |
| QR refreshed after connection accepted | No effect; generation is refused with `ALREADY_CONNECTED` |
| B scans an expired QR | `410 QR_INVALID_OR_EXPIRED`; B is told to ask A to show a fresh code |
| B scans the same QR twice (double tap, retry) | Second call gets `410` (token consumed) — or `409 REQUEST_ALREADY_PENDING`. The app treats both as "request already sent" if it has a pending request |
| Network fails after server committed but before B got the response | B retries with the same token → `410`; app then calls `GET /connection-requests?direction=outgoing&status=pending` to discover the existing request |
| A and B scan each other's codes at the same moment | Two pending requests (one each direction). When either is accepted, the transaction cancels the other pending one. If both accept simultaneously, `ON CONFLICT DO NOTHING` keeps a single connection |
| A scans their own QR | `410` (same generic error; `owner_user_id <> :me` check) |
| Photo of QR taken and used later | Expired after 60 s, single-use, and superseded on refresh. Even if redeemed in time, A must still explicitly accept |
| Unknown third party gets a QR token | They cannot redeem it: redeeming requires an authenticated app session, and only two accounts exist |
| A ignores the request | Expires after 5 minutes; B can ask for a new QR |
| A is offline when B scans | Request waits (pending) up to 5 minutes; A sees it on next Inbox open if still pending |
| Account disabled during pending request | Requests involving a disabled user cannot be accepted (`403 ACCOUNT_DISABLED`) |

## 7. Security controls summary

- CSPRNG, 256-bit tokens; hash-only storage; 60 s expiry; single-use; superseded on refresh.
- Generic error for every invalid-token reason (no oracle for "expired vs used vs unknown").
- Owner must explicitly accept — scanning alone never connects.
- Rate limits on generation and redemption; audit events `connection.request_created`, `connection.accepted`,
  `connection.rejected`.
- Expired/consumed `qr_tokens` rows are deleted by a cleanup job after 24 hours; `connection_requests`
  older than 30 days in a final state are deleted.
- The QR screen sets no `FLAG_SECURE` (screenshots are allowed by requirement); the short lifetime is the protection.
