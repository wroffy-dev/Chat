# 07 — Text Chat and Disappearing Messages

Status: **Implemented (slice 1)** — see docs/IMPLEMENTATION_PLAN.md, Phase 5. Not yet tried on phones. Decisions D-01 (option D) and D-02 were made on 2026-10-24 — see §6 and §7.

> **Transport:** the app reads messages with `GET /messages` and renews its lease with
> `POST /conversations/{id}/heartbeat`. A live `chat.changed` signal over the WebSocket
> ([14 §10](14-api-contracts.md)) makes it re-read at once; without one it polls every 2 s (every 10 s
> while the socket is connected). The server rules below are the same either way.

## 1. Scope

Text only. Each message is plain Unicode text, 1–4,000 characters (counted as Unicode code points),
trimmed of leading/trailing whitespace.

Explicitly **not** supported, and no endpoint will exist for: images, video, files, attachments, voice
notes, stickers/GIFs, link previews (would make the server or app fetch URLs), forwarding, reactions,
editing, media storage, public media links, profile pictures.

## 2. Required behaviour

When a user leaves the chat through the Back button or another supported exit action, **all messages
in the conversation disappear for both users**. The deletion is performed by the backend, so it does
not depend on the other device being online, and deleted messages can never be returned again.

## 3. Server-side model

Tables (full definitions in [13](13-database-design.md)):

- `conversations` — one per connection. `last_seq` (last message sequence number allocated),
  `clear_epoch` (incremented whenever messages are deleted), `cleared_at`.
- `conversation_participants` — per user: `last_read_seq`, chat presence lease (`lease_id`,
  `lease_expires_at`), `last_leave_id`.
- `messages` — `seq` (per-conversation, strictly increasing), `epoch`, `sender_user_id`,
  `client_message_id`, `body`, `created_at`. **Hard-deleted** on clear (no soft delete — the content must
  actually be removed).

### 3.1 Sending a message — `POST /api/v1/conversations/{id}/messages`

Request carries `clientMessageId` (UUID generated on the device), `epoch` (the clear epoch the client
last synced), and `text`.

```
BEGIN
  conv = SELECT … FROM conversations WHERE id = :id FOR UPDATE      -- serialises with clears
  require :me is a participant                                      -- else 404
  existing = SELECT … FROM messages WHERE conversation_id = :id
               AND sender_user_id = :me AND client_message_id = :cmid
  if existing → COMMIT; return existing                             -- idempotent retry (still exists)
  if :epoch <> conv.clear_epoch → ROLLBACK; 409 CONVERSATION_CLEARED
  seq = conv.last_seq + 1
  INSERT messages (conversation_id, seq, epoch = conv.clear_epoch, sender_user_id = :me,
                   client_message_id = :cmid, body = :text)
  UPDATE conversations SET last_seq = seq
COMMIT
publish message.created {conversationId, seq, epoch, …} to both users' sockets
```

**Why the epoch check:** if a message was committed but the response was lost, and the chat was cleared
before the retry, a naive retry would re-insert it — the message would "come back". The epoch check
rejects any send composed before a clear. A retry of a message that **still exists** (under option D, an
unseen message survives a clear) returns it instead, so it is neither refused nor duplicated. The client
learns the current epoch from `GET /messages`. The app then shows: "The chat was cleared. Your message was
not sent." and puts the text back in the input box so the user can decide to resend it.

### 3.2 Leaving the chat (the clear) — `POST /api/v1/conversations/{id}/leave`

Request carries `leaveId` (UUID per exit action, for idempotent retries). Response: `{ "epoch" }`.

```
BEGIN
  conv = SELECT … FROM conversations WHERE id = :id FOR UPDATE
  require :me is a participant
  if conversation_participants(:me).last_leave_id = :leaveId → COMMIT; return 200 (already done)
  DELETE FROM messages WHERE conversation_id = :id AND <clear scope>        -- see D-01
  if any rows were deleted:
    UPDATE conversations SET clear_epoch = clear_epoch + 1, cleared_at = now()
  UPDATE conversation_participants SET lease_id = NULL, lease_expires_at = NULL,
                                       last_leave_id = :leaveId
     WHERE conversation_id = :id AND user_id = :me
COMMIT
(clients see the new epoch on their next `GET /messages`)
```

`<clear scope>` under D-01 option D (implemented): every message whose **recipient** has displayed it —
`seq <= last_read_seq` of the participant who is not the sender. Both directions are covered by one
statement. The epoch moves only when something was deleted, so an exit with nothing to delete does not
refuse the other person's next message. The backstop job (§5) uses the same lock and also moves the epoch.

### 3.3 Reading history — `GET /api/v1/conversations/{id}/messages?afterSeq=&epoch=`

Returns the current `epoch` and the messages that still exist with `seq > afterSeq`, read in one
snapshot. Deleted rows no longer exist, so they **cannot** be returned. If the client's `epoch` is older
than the server's, the response says `cleared: true`, `afterSeq` is ignored and the items start from the
beginning: the client replaces everything it holds with them.

### 3.5 Seen — `POST /api/v1/conversations/{id}/read`

`{ "seq" }`: the highest sequence number this user has **displayed**. Monotonic and capped at the last
allocated sequence, so it never covers a message sent later. The app reports it only while the chat is
on screen; this is what decides which messages an exit deletes.

### 3.4 Race conditions

| Situation | Outcome |
|---|---|
| A sends while B leaves at the same instant | Both transactions lock the conversation row; they run one after the other. If the clear runs first, A's send gets `409 CONVERSATION_CLEARED`. If the send runs first, the clear deletes it. Either way no message survives "before" a clear |
| A and B both leave at the same instant | Two serialised clears. The second deletes whatever exists at that time (usually nothing) and increments the epoch again. Harmless |
| Leave request retried after a timeout | Same `leaveId` → no-op |
| Send retried after a timeout | Same `clientMessageId` and epoch → original message returned, no duplicate |
| Send retried after a clear | Different epoch → rejected; never resurrected |
| WebSocket event lost | Clients reconcile with `GET /messages` on reconnect and whenever the chat screen opens |

## 4. Device-side rules

1. **Message content is never written to device storage.** No Room database, no file cache, no
   DataStore. Messages live only in memory (a repository scoped to the app process). Process death
   = messages gone from the device.
2. On `conversation.cleared`, or a history response showing a newer epoch, the app immediately clears
   its in-memory list and any compose-retry queue for the old epoch.
3. On every chat-screen open and every socket reconnect, the app calls `GET /messages` and replaces
   its state with the server's. A stale cache therefore cannot restore deleted content.
4. Notifications for new messages say only "New message" — no sender text, no preview (see [10](10-notifications.md)).
5. The chat activity calls `setRecentsScreenshotEnabled(false)` (API 33+) so the recent-apps thumbnail
   does not show messages. This does **not** block screenshots, which remain allowed.
6. The text field should request no personalised learning from the keyboard
   (`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`) — the keyboard may ignore this hint. Implemented
   with a platform `EditText` (Compose 1.7 text fields have no API for it). The draft is held in memory
   only; the field never writes its text to saved instance state.

### 4.1 Supported exit actions

| Action | Treated as exit? |
|---|---|
| System Back button / back gesture from the chat screen | Yes → `leave` sent immediately |
| Toolbar back arrow | Yes |
| Logout while in chat | Yes (leave sent before logout) |
| A call screen shown over the chat (incoming call) | **No** — the chat stays open beneath it and the lease is renewed while the app is in front, but nothing new counts as seen until the chat is on screen again. The chat screen has no call buttons in slice 1 |
| App sent to background (Home, app switcher, screen off) | Yes, once the 60 s presence lease lapses (D-02) |
| App killed, crash, phone loses power or network | Handled by the presence lease (§5) |

The app sends `leave` from its own navigation handling, **and** the server enforces exits through the
lease. The design does not rely only on Android navigation callbacks.

## 5. When the exit event never reaches the server

Crashes, power loss, the OS killing the app, or losing network mean the `leave` request may never be sent.

| Mechanism | How it works |
|---|---|
| **Presence lease** | `POST /conversations/{id}/enter` returns a `leaseId` with a TTL of 60 s. While the app is in front with the chat open, it calls `POST /conversations/{id}/heartbeat` every 20 s (the WebSocket `chat.heartbeat` in a later slice). A heartbeat for a lapsed lease applies the exit and returns `409 LEASE_EXPIRED`; the app then drops its list, enters again and says so. A server job runs every 15 s. Any lease whose `lease_expires_at` has passed is treated as an exit and runs the same clear transaction (not audited, like every clear). Leases are stored in PostgreSQL, so a backend restart does not lose them — the first run after startup (within 15 s) processes leases that expired during downtime |
| **Pending-leave flag** | When the user exits, the app writes a small "leave pending" record (conversation ID + `leaveId`, **no message content**) to SharedPreferences before sending. If sending fails, it retries on next launch/reconnect. Idempotent by `leaveId` |
| **Backstop expiry** (D-02: 24 h) | Every message is deleted 24 h after it was sent, even if no exit was recorded |

**Honest limits:**
- Between the crash and lease expiry (up to ~75 s), messages still exist on the server and on the other
  user's screen.
- A long network outage while chatting (e.g. a lift or tunnel) will expire the lease and clear the chat.
  This is a product trade-off tied to D-02.
- If the server itself is down, nothing is deleted until it comes back; the job then processes expired leases.

## 6. Unresolved product decision D-01 — messages when the recipient is not in the chat

**Decided 2026-10-24: option D.** On any exit, delete only messages the recipient has already displayed
(`seq <= recipient.last_read_seq`); unseen messages wait for the recipient and are deleted on the next exit
after they are seen; and every message is deleted at most 24 hours after it was sent (D-02 backstop).
The options considered are kept below for the record.

Question: User A sends messages while B is **not** inside the chat (app closed, or on another screen).
Then A leaves the chat. What happens to those messages?

| Option | Behaviour | Implications |
|---|---|---|
| **A. Strict clear on any exit** | Every exit deletes **all** messages, read or unread | Simplest and most private. A message sent while B is away is deleted when A leaves — B may never see it. The UI should warn A ("B is not in the chat. Messages are deleted when you leave."). Chat effectively works only when both are present |
| **B. Clear only seen messages** | An exit deletes messages the recipient has already displayed (`seq <= recipient.last_read_seq`). Unseen messages wait on the server until the recipient opens the chat, sees them, and someone exits | Works like a normal inbox for offline delivery. Unread messages can sit on the server for a long time, so a backstop expiry (D-02) is strongly advisable. Slightly more complex clear scope and read-receipt handling |
| **C. Live-only chat** | Sending is disabled unless both users hold an active presence lease. No offline messages exist | Very simple deletion and minimal storage. Less useful: the sender must first get the other person into the chat (e.g. via "Ask to open chat" push or a call) |
| **D. B + short backstop** | As B, plus unread messages are deleted after N hours (e.g. 24 h) | Bounded server-side retention with offline delivery. The recipient may miss messages if they do not open the app within N hours |

What each option changes technically:
- A: `<clear scope>` = all rows.
- B/D: `<clear scope>` = rows whose recipient's `last_read_seq >= seq`; requires `POST /read` before leave; D adds a scheduled delete by `created_at`.
- C: `POST /messages` additionally requires both leases active (`409 PEER_NOT_IN_CHAT`).

All options use the same tables, epoch logic and lease mechanism, so the decision can be made late
without changing the schema.

## 7. Decision D-02 — backgrounding and backstop

**Decided 2026-10-24:** leaving the app counts as exiting the chat **after the presence lease lapses**
(60 s lease, renewed every 20 s while the chat is visible). The same rule covers crashes, power loss and
network loss. **Backstop: 24 hours** — a scheduled job deletes any message older than 24 h, seen or not.

The original question, for the record:

1. Does moving the app to the background count as leaving the chat? Options: (a) yes, immediately;
   (b) yes, after the lease expires (≈ 60–75 s) — the natural result of the lease design; (c) no, only
   explicit exits and lease expiry while the app is killed (would require keeping heartbeats alive in
   the background, which Android restricts).
2. Should there be a maximum message lifetime (backstop), and how long?

## 8. Limits of deletion (stated honestly)

Deletion from the server and the two apps **cannot** remove copies made outside the app's control:

- Screenshots and screen recordings (allowed by requirement).
- Photos of the screen taken with another device.
- Content on rooted or modified devices, or modified APKs that save messages.
- Text the keyboard app may have learned or stored, and the clipboard if the user copied text.
- Notification history/logs kept by the OS or third-party apps (mitigated by not putting text in notifications).
- Data still physically on the server's disk after `DELETE` until PostgreSQL vacuums and reuses the
  space; it is not securely wiped. Backups are protected by excluding `messages` data from them (§9).

The app will describe this as "messages are deleted from the server and both apps", never as
"guaranteed unrecoverable".

## 9. Storage, backups and logs

- Database backups use `pg_dump --exclude-table-data=messages` (proposed ADR-05). Restoring a backup
  therefore never brings old messages back. The table structure is still backed up.
- The backend logger redacts `body`/`text` fields; request bodies are never logged for chat routes.
- Error events and audit events never include message text, only IDs and counts.
- No analytics on chat activity.

## 10. Limits and abuse protection

| Limit | Value (proposed) |
|---|---|
| Message length | 4,000 code points (`413`/`422 MESSAGE_TOO_LONG`) |
| Send rate | 30 messages/minute per user (`429`) |
| Request body size | 16 KB for chat routes |
| History page size | 200 messages per call |
