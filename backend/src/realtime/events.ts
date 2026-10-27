/**
 * Live "something changed" signals to the apps (docs/14-api-contracts.md §10). They carry IDs only:
 * the app fetches the details over the REST API, so every rule (authorization, epochs, deletion)
 * stays in one place and no message text travels over the socket.
 */
export type RealtimeEvent =
  | { type: "chat.changed"; conversationId: string }
  | { type: "call.changed"; callId: string };

export interface EventPublisher {
  /** Best effort and immediate: users without an open socket simply miss it (they poll or get a push). */
  publish(userIds: readonly string[], event: RealtimeEvent): void;
}
