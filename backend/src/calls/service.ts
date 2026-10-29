import { randomUUID } from "node:crypto";
import type { Call, PrismaClient, Prisma } from "../generated/prisma/client.js";
import { type AppError, appError } from "../errors.js";
import type { MediaGateway, MediaWebhookEvent } from "./media.js";
import type { PushService } from "../push/service.js";
import type { EventPublisher } from "../realtime/events.js";
import type { SettingsService } from "../settings/service.js";

const SECOND = 1000;

export type CallKind = "video" | "voice";
export type EndReason = "hangup" | "network_lost" | "error";
const LIVE = ["ringing", "accepted", "active"] as const;

/** Caller and callee must join within this time after acceptance, or the call fails (docs/08 §4.1). */
export const JOIN_TIMEOUT_MS = 30 * SECOND;

export interface CallView {
  id: string;
  kind: CallKind;
  status: string;
  role: "caller" | "callee";
  peer: { id: string; username: string };
  createdAt: Date;
  acceptedAt: Date | null;
  endedAt: Date | null;
  endReason: string | null;
}

export interface JoinInfo {
  callId: string;
  livekitUrl: string;
  livekitToken: string;
  maxVideoQuality: string;
  ringTimeoutSeconds: number;
}

export class CallService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly media: MediaGateway | null,
    private readonly ringTimeoutSeconds: number,
    private readonly now: () => Date = () => new Date(),
    private readonly push: PushService | null = null,
    private readonly events: EventPublisher | null = null,
    /** Admin Console settings; without it the constructor's ring timeout and 1080p apply. */
    private readonly settings: SettingsService | null = null,
  ) {}

  private async ringSeconds(): Promise<number> {
    return this.settings ? this.settings.get("call.ring_timeout_seconds") : this.ringTimeoutSeconds;
  }

  /** Tells both parties' open apps that this call changed; they re-read it over the API. */
  private changed(call: Pick<Call, "id" | "callerUserId" | "calleeUserId">) {
    this.events?.publish([call.callerUserId, call.calleeUserId], { type: "call.changed", callId: call.id });
  }

  /** Fire-and-forget push; failures are logged inside PushService and never affect the call. */
  private notify(userId: string, payload: { t: "call" | "call_end"; id: string }) {
    if (!this.push) return;
    const push = this.push;
    void this.ringSeconds()
      .then((ttlSeconds) => push.notifyUser(userId, payload, { highPriority: true, ttlSeconds }))
      .catch(() => undefined);
  }

  /** Starts a call to the connected peer. The caller joins the room while it rings (faster setup). */
  async start(userId: string, connectionId: string, kind: CallKind): Promise<JoinInfo> {
    const media = this.requireMedia();
    const connection = await this.prisma.connection.findUnique({ where: { id: connectionId } });
    if (!connection || (connection.userAId !== userId && connection.userBId !== userId)) throw appError(404, "NOT_FOUND");
    const calleeUserId = connection.userAId === userId ? connection.userBId : connection.userAId;
    const callee = await this.prisma.user.findUnique({ where: { id: calleeUserId } });
    if (!callee || callee.status !== "active") throw appError(404, "NOT_FOUND");

    const existing = await this.liveCallFor(connectionId);
    if (existing) throw this.inProgress(existing.id);
    // With several contacts (D-21) either person may already be in a call with someone else.
    if (await this.liveCallOfUser(userId)) throw this.inProgress("");
    if (await this.liveCallOfUser(calleeUserId)) throw appError(409, "PEER_BUSY");

    const room = `call_${randomUUID()}`;
    try {
      await media.createRoom(room, (await this.ringSeconds()) + 30);
    } catch {
      throw appError(503, "MEDIA_UNAVAILABLE");
    }

    let call: Call;
    try {
      call = await this.prisma.call.create({
        data: { connectionId, callerUserId: userId, calleeUserId, kind, livekitRoom: room, status: "ringing", createdAt: this.now() },
      });
    } catch (err) {
      await media.deleteRoom(room).catch(() => undefined);
      // The partial unique index refused a second live call (both users called at once).
      if ((err as { code?: string }).code === "P2002") {
        const live = await this.liveCallFor(connectionId);
        if (live) throw this.inProgress(live.id);
      }
      throw err;
    }
    this.notify(calleeUserId, { t: "call", id: call.id });
    this.changed(call);
    return this.joinInfo(call, userId);
  }

  /** The user's ringing/accepted/active call, if any. Polled by the app until live push exists. */
  async current(userId: string): Promise<CallView | null> {
    const call = await this.prisma.call.findFirst({
      where: { status: { in: [...LIVE] }, OR: [{ callerUserId: userId }, { calleeUserId: userId }] },
      include: { caller: true, callee: true },
      orderBy: { createdAt: "desc" },
    });
    return call ? this.view(call, userId) : null;
  }

  async get(userId: string, callId: string): Promise<CallView> {
    const call = await this.prisma.call.findUnique({ where: { id: callId }, include: { caller: true, callee: true } });
    if (!call || (call.callerUserId !== userId && call.calleeUserId !== userId)) throw appError(404, "NOT_FOUND");
    return this.view(call, userId);
  }

  async accept(userId: string, callId: string): Promise<JoinInfo> {
    this.requireMedia();
    const call = await this.transition(callId, userId, "callee", ["ringing"], { status: "accepted", acceptedAt: this.now() });
    this.changed(call);
    return this.joinInfo(call, userId);
  }

  async decline(userId: string, callId: string): Promise<void> {
    const call = await this.transition(callId, userId, "callee", ["ringing"], { status: "declined", endedAt: this.now() });
    this.changed(call);
    await this.closeRoom(call);
  }

  async cancel(userId: string, callId: string): Promise<void> {
    const call = await this.transition(callId, userId, "caller", ["ringing"], { status: "cancelled", endedAt: this.now() });
    this.changed(call);
    await this.closeRoom(call);
    this.notify(call.calleeUserId, { t: "call_end", id: call.id });
  }

  /** Either participant hangs up. Idempotent: ending an already-ended call is a no-op. */
  async end(userId: string, callId: string, reason: EndReason): Promise<void> {
    const call = await this.prisma.call.findUnique({ where: { id: callId } });
    if (!call || (call.callerUserId !== userId && call.calleeUserId !== userId)) throw appError(404, "NOT_FOUND");
    if (!LIVE.includes(call.status as (typeof LIVE)[number])) return;
    const updated = await this.prisma.call.updateMany({
      where: { id: callId, status: { in: [...LIVE] } },
      // Hanging up before the other side answered is a cancel/decline, not a completed call.
      data: { status: call.status === "ringing" ? (call.callerUserId === userId ? "cancelled" : "declined") : "ended", endReason: reason, endedAt: this.now() },
    });
    if (updated.count === 1) {
      this.changed(call);
      await this.closeRoom(call);
    }
  }

  /** Fresh join token for an ongoing call (e.g. after the app process restarted). */
  async token(userId: string, callId: string): Promise<JoinInfo> {
    this.requireMedia();
    const call = await this.prisma.call.findUnique({ where: { id: callId } });
    if (!call || (call.callerUserId !== userId && call.calleeUserId !== userId)) throw appError(404, "NOT_FOUND");
    const allowed = call.status === "accepted" || call.status === "active" || (call.status === "ringing" && call.callerUserId === userId);
    if (!allowed) throw appError(409, "CALL_NOT_ACTIVE");
    return this.joinInfo(call, userId);
  }

  async saveDiagnostics(userId: string, callId: string, summary: Prisma.InputJsonValue): Promise<void> {
    const call = await this.prisma.call.findUnique({ where: { id: callId } });
    if (!call || (call.callerUserId !== userId && call.calleeUserId !== userId)) throw appError(404, "NOT_FOUND");
    await this.prisma.callDiagnostic.upsert({
      where: { callId_userId: { callId, userId } },
      create: { callId, userId, summary },
      update: { summary },
    });
  }

  /** LiveKit webhooks keep the server's view honest when apps cannot report (docs/08 §6). */
  async handleWebhook(e: MediaWebhookEvent): Promise<void> {
    if (!e.roomName) return;
    const call = await this.prisma.call.findUnique({ where: { livekitRoom: e.roomName } });
    if (!call) return;
    const now = this.now();
    if (e.event === "participant_joined" && call.status === "accepted" && e.participantIdentity === call.calleeUserId) {
      const r = await this.prisma.call.updateMany({ where: { id: call.id, status: "accepted" }, data: { status: "active", activeAt: now } });
      if (r.count === 1) this.changed(call);
    } else if (e.event === "room_finished") {
      const r = await this.prisma.call.updateMany({
        where: { id: call.id, status: { in: [...LIVE] } },
        data: { status: call.status === "ringing" ? "missed" : "ended", endReason: "room_finished", endedAt: now },
      });
      if (r.count === 1) this.changed(call);
    }
  }

  /** Ends calls nobody answered and accepted calls nobody joined. Run every few seconds. */
  async sweep(): Promise<number> {
    const now = this.now().getTime();
    const ringingBefore = new Date(now - (await this.ringSeconds()) * SECOND);
    const acceptedBefore = new Date(now - JOIN_TIMEOUT_MS);
    const stale = await this.prisma.call.findMany({
      where: {
        OR: [
          { status: "ringing", createdAt: { lt: ringingBefore } },
          { status: "accepted", acceptedAt: { lt: acceptedBefore } },
        ],
      },
    });
    for (const call of stale) {
      const missed = call.status === "ringing";
      const r = await this.prisma.call.updateMany({
        where: { id: call.id, status: call.status },
        data: { status: missed ? "missed" : "failed", endReason: "timeout", endedAt: new Date(now) },
      });
      if (r.count === 1) {
        this.changed(call);
        await this.closeRoom(call);
        if (missed) this.notify(call.calleeUserId, { t: "call_end", id: call.id });
      }
    }
    return stale.length;
  }

  // ---------------------------------------------------------------------------------------------

  private async transition(
    callId: string,
    userId: string,
    role: "caller" | "callee",
    from: string[],
    data: Prisma.CallUpdateManyMutationInput,
  ): Promise<Call> {
    const call = await this.prisma.call.findUnique({ where: { id: callId } });
    const owner = role === "caller" ? call?.callerUserId : call?.calleeUserId;
    if (!call || owner !== userId) throw appError(404, "NOT_FOUND");
    // Conditional update: a cancel racing an accept cannot both win.
    const r = await this.prisma.call.updateMany({ where: { id: callId, status: { in: from } }, data });
    if (r.count !== 1) throw appError(409, "CALL_NOT_ACTIVE");
    return this.prisma.call.findUniqueOrThrow({ where: { id: callId } });
  }

  private async closeRoom(call: Call) {
    // Best effort: LiveKit also closes empty rooms by itself after a short timeout.
    await this.media?.deleteRoom(call.livekitRoom).catch(() => undefined);
  }

  private async joinInfo(call: Call, userId: string): Promise<JoinInfo> {
    const media = this.requireMedia();
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    let livekitToken: string;
    try {
      livekitToken = await media.joinToken(userId, user.username, call.livekitRoom);
    } catch {
      throw appError(503, "MEDIA_UNAVAILABLE");
    }
    return {
      callId: call.id,
      livekitUrl: media.publicUrl,
      livekitToken,
      maxVideoQuality: this.settings ? await this.settings.get("call.max_video_quality") : "1080p",
      ringTimeoutSeconds: await this.ringSeconds(),
    };
  }

  private liveCallOfUser(userId: string) {
    return this.prisma.call.findFirst({ where: { status: { in: [...LIVE] }, OR: [{ callerUserId: userId }, { calleeUserId: userId }] } });
  }

  private liveCallFor(connectionId: string) {
    return this.prisma.call.findFirst({ where: { connectionId, status: { in: [...LIVE] } } });
  }

  /** The app then fetches GET /calls/current to show the existing call. */
  private inProgress(_callId: string): AppError {
    return appError(409, "CALL_IN_PROGRESS");
  }

  private requireMedia(): MediaGateway {
    if (!this.media) throw appError(503, "MEDIA_UNAVAILABLE");
    return this.media;
  }

  private view(call: Call & { caller: { id: string; username: string }; callee: { id: string; username: string } }, userId: string): CallView {
    const isCaller = call.callerUserId === userId;
    const peer = isCaller ? call.callee : call.caller;
    return {
      id: call.id,
      kind: call.kind as CallKind,
      status: call.status,
      role: isCaller ? "caller" : "callee",
      peer: { id: peer.id, username: peer.username },
      createdAt: call.createdAt,
      acceptedAt: call.acceptedAt,
      endedAt: call.endedAt,
      endReason: call.endReason,
    };
  }
}
