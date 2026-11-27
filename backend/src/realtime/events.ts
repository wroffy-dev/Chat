/**
 * Live "something changed" signals to the apps (docs/14-api-contracts.md §10). They carry IDs only:
 * the app fetches the details over the REST API, so every rule (authorization, epochs, deletion)
 * stays in one place and no message text travels over the socket.
 */
export type RealtimeEvent =
  | { type: "chat.changed"; conversationId: string }
  /** The other person is typing right now (sent to them only; nothing is stored). */
  | { type: "chat.typing"; conversationId: string }
  | { type: "call.changed"; callId: string }
  /** A snap was sent to or opened by this user in this connection (D-27). */
  | { type: "snap.changed"; connectionId: string }
  | { type: "connection.changed"; requestId: string }
  /** A note was written, changed, deleted or got a line (owner 2026-11-26: live notes). To its writer and their connections. */
  | { type: "notes.changed"; noteId: string };

export interface EventPublisher {
  /** Best effort and immediate: users without an open socket simply miss it (they poll or get a push). */
  publish(userIds: readonly string[], event: RealtimeEvent): void;
}
