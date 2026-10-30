import { AccessToken, RoomServiceClient, TrackSource, WebhookReceiver } from "livekit-server-sdk";

/** The LiveKit operations the backend needs. Lets tests substitute a fake. */
export interface MediaGateway {
  /** Public signalling URL handed to the app (wss:// in production). */
  readonly publicUrl: string;
  createRoom(name: string, emptyTimeoutSeconds: number): Promise<void>;
  deleteRoom(name: string): Promise<void>;
  /** Room-scoped, short-lived join token (docs/08-calling.md §6). */
  joinToken(identity: string, displayName: string, room: string): Promise<string>;
  /** Verifies a webhook's signature and returns the event, or throws. */
  verifyWebhook(body: string, authHeader: string | undefined): Promise<MediaWebhookEvent>;
  /** Throws if the media server's API cannot be reached (Admin Console health). */
  ping?(): Promise<void>;
}

export interface MediaWebhookEvent {
  event: string;
  roomName?: string;
  participantIdentity?: string;
  numParticipants?: number;
}

export const JOIN_TOKEN_TTL_SECONDS = 600;

export class LiveKitGateway implements MediaGateway {
  private readonly rooms: RoomServiceClient;
  private readonly webhooks: WebhookReceiver;

  constructor(
    readonly publicUrl: string,
    apiUrl: string,
    private readonly apiKey: string,
    private readonly apiSecret: string,
  ) {
    this.rooms = new RoomServiceClient(apiUrl, apiKey, apiSecret);
    this.webhooks = new WebhookReceiver(apiKey, apiSecret);
  }

  async createRoom(name: string, emptyTimeoutSeconds: number): Promise<void> {
    // Only the backend creates rooms (LiveKit `room.auto_create: false`), each for exactly two people.
    await this.rooms.createRoom({ name, maxParticipants: 2, emptyTimeout: emptyTimeoutSeconds, departureTimeout: 20 });
  }

  async deleteRoom(name: string): Promise<void> {
    await this.rooms.deleteRoom(name);
  }

  async ping(): Promise<void> {
    await this.rooms.listRooms();
  }

  async joinToken(identity: string, displayName: string, room: string): Promise<string> {
    const token = new AccessToken(this.apiKey, this.apiSecret, { identity, name: displayName, ttl: JOIN_TOKEN_TTL_SECONDS });
    // Least privilege: join this one room, publish camera and microphone only, no screen share, no
    // room creation or admin. Data messages are allowed for in-call controls only ("switch their
    // camera", docs/08 §10.4); chat never goes over them. The room holds exactly two people.
    token.addGrant({
      roomJoin: true,
      room,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
      canPublishSources: [TrackSource.CAMERA, TrackSource.MICROPHONE],
      canUpdateOwnMetadata: false,
      roomCreate: false,
      roomAdmin: false,
      hidden: false,
    });
    return token.toJwt();
  }

  async verifyWebhook(body: string, authHeader: string | undefined): Promise<MediaWebhookEvent> {
    const e = await this.webhooks.receive(body, authHeader);
    return {
      event: e.event,
      roomName: e.room?.name,
      participantIdentity: e.participant?.identity,
      numParticipants: e.room?.numParticipants,
    };
  }
}
