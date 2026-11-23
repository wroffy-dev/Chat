import type { PrismaClient } from "../generated/prisma/client.js";
import { appError } from "../errors.js";
import type { ChatService } from "../chat/service.js";

/**
 * The built-in avatars a user can pick (owner, 2026-11-23, D-32). Only these short names are stored; the drawings
 * live in the app. There are no uploaded pictures.
 */
export const AVATARS = [
  "fox", "panda", "koala", "lion", "tiger", "bear", "bunny", "cat", "dog", "frog",
  "monkey", "penguin", "owl", "unicorn", "octopus", "whale", "turtle", "bee", "butterfly", "flower",
  "sun", "moon", "star", "heart",
] as const;

/** Longest nickname, in characters (CHECK in SQL). */
export const NICKNAME_MAX = 40;

/**
 * Avatars and nicknames (D-32). A nickname is the name a user gives one of the people they are connected with; only
 * that user sees it, and the chat gets a line ("… changed your name from A to B") that both see.
 */
export class ProfileService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly chat: ChatService | null = null,
  ) {}

  async setAvatar(userId: string, avatar: string | null): Promise<{ avatar: string | null }> {
    if (avatar !== null && !(AVATARS as readonly string[]).includes(avatar)) {
      throw appError(400, "VALIDATION_FAILED", [{ path: "/avatar", rule: "choice" }]);
    }
    await this.prisma.user.update({ where: { id: userId }, data: { avatar } });
    return { avatar };
  }

  /** Sets (or, with null or blank, removes) the user's name for the person on [connectionId]. */
  async setNickname(userId: string, connectionId: string, nickname: string | null): Promise<{ nickname: string | null }> {
    const name = nickname?.trim() || null;
    if (name !== null && name.length > NICKNAME_MAX) throw appError(400, "VALIDATION_FAILED", [{ path: "/nickname", rule: "maxLength" }]);
    const connection = await this.prisma.connection.findFirst({
      where: { id: connectionId, OR: [{ userAId: userId }, { userBId: userId }] },
      include: { userA: { select: { username: true } }, userB: { select: { username: true } }, conversation: { select: { id: true } } },
    });
    if (!connection) throw appError(404, "NOT_FOUND");
    const peerUsername = connection.userAId === userId ? connection.userB.username : connection.userA.username;
    const key = { userId_connectionId: { userId, connectionId } };
    const current = (await this.prisma.nickname.findUnique({ where: key }))?.nickname ?? null;
    if (current === name) return { nickname: name };
    if (name === null) {
      await this.prisma.nickname.delete({ where: key });
    } else {
      await this.prisma.nickname.upsert({ where: key, create: { userId, connectionId, nickname: name }, update: { nickname: name } });
    }
    if (connection.conversation && this.chat) {
      await this.chat.recordNicknameChange(connection.conversation.id, userId, current ?? peerUsername, name ?? peerUsername);
    }
    return { nickname: name };
  }

  /** The user's nicknames, by the connected person's user id. */
  async nicknamesByPeer(userId: string): Promise<Map<string, string>> {
    const rows = await this.prisma.nickname.findMany({
      where: { userId },
      include: { connection: { select: { userAId: true, userBId: true } } },
    });
    return new Map(rows.map((r) => [r.connection.userAId === userId ? r.connection.userBId : r.connection.userAId, r.nickname]));
  }
}
