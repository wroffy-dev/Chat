import type { WebSocket } from "ws";
import type { EventPublisher, RealtimeEvent } from "./events.js";

/** Open app sockets per user (a single backend instance, docs/02 ADR-07: no cross-node fan-out). */
export class RealtimeHub implements EventPublisher {
  private readonly byUser = new Map<string, Set<WebSocket>>();

  add(userId: string, socket: WebSocket) {
    let set = this.byUser.get(userId);
    if (!set) this.byUser.set(userId, (set = new Set()));
    set.add(socket);
  }

  remove(userId: string, socket: WebSocket) {
    const set = this.byUser.get(userId);
    if (!set) return;
    set.delete(socket);
    if (set.size === 0) this.byUser.delete(userId);
  }

  connected(userId: string): number {
    return this.byUser.get(userId)?.size ?? 0;
  }

  publish(userIds: readonly string[], event: RealtimeEvent) {
    const { type, ...data } = event;
    const frame = JSON.stringify({ type, data });
    for (const id of new Set(userIds)) {
      for (const socket of this.byUser.get(id) ?? []) {
        if (socket.readyState === socket.OPEN) socket.send(frame);
      }
    }
  }
}
