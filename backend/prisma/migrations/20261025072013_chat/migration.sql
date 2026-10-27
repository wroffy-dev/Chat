-- CreateTable
CREATE TABLE "conversations" (
    "id" UUID NOT NULL,
    "connection_id" UUID NOT NULL,
    "last_seq" BIGINT NOT NULL DEFAULT 0,
    "clear_epoch" BIGINT NOT NULL DEFAULT 0,
    "cleared_at" TIMESTAMPTZ(3),

    CONSTRAINT "conversations_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "conversation_participants" (
    "conversation_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "last_read_seq" BIGINT NOT NULL DEFAULT 0,
    "lease_id" UUID,
    "lease_expires_at" TIMESTAMPTZ(3),
    "last_leave_id" UUID,

    CONSTRAINT "conversation_participants_pkey" PRIMARY KEY ("conversation_id","user_id")
);

-- CreateTable
CREATE TABLE "messages" (
    "id" UUID NOT NULL,
    "conversation_id" UUID NOT NULL,
    "seq" BIGINT NOT NULL,
    "epoch" BIGINT NOT NULL,
    "sender_user_id" UUID NOT NULL,
    "client_message_id" UUID NOT NULL,
    "body" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "messages_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "conversations_connection_id_key" ON "conversations"("connection_id");

-- CreateIndex
CREATE INDEX "messages_created_at_idx" ON "messages"("created_at");

-- CreateIndex
CREATE UNIQUE INDEX "messages_conversation_id_seq_key" ON "messages"("conversation_id", "seq");

-- CreateIndex
CREATE UNIQUE INDEX "messages_conversation_id_sender_user_id_client_message_id_key" ON "messages"("conversation_id", "sender_user_id", "client_message_id");

-- AddForeignKey
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_connection_id_fkey" FOREIGN KEY ("connection_id") REFERENCES "connections"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversation_participants" ADD CONSTRAINT "conversation_participants_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversation_participants" ADD CONSTRAINT "conversation_participants_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "messages" ADD CONSTRAINT "messages_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "messages" ADD CONSTRAINT "messages_sender_user_id_fkey" FOREIGN KEY ("sender_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Constraints Prisma cannot express (docs/07-chat-and-disappearing-messages.md, docs/13 §3.8–3.10).
ALTER TABLE "messages" ADD CONSTRAINT "messages_body_length" CHECK (char_length("body") BETWEEN 1 AND 4000);
ALTER TABLE "messages" ADD CONSTRAINT "messages_seq_positive" CHECK ("seq" > 0);
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_counters_nonnegative" CHECK ("last_seq" >= 0 AND "clear_epoch" >= 0);
ALTER TABLE "conversation_participants" ADD CONSTRAINT "conversation_participants_lease_pair"
  CHECK (("lease_id" IS NULL) = ("lease_expires_at" IS NULL));
-- The lease-expiry job scans only active leases.
CREATE INDEX "conversation_participants_active_leases"
  ON "conversation_participants" ("lease_expires_at") WHERE "lease_expires_at" IS NOT NULL;

-- Connections made before the chat phase get their conversation now (one per connection).
INSERT INTO "conversations" ("id", "connection_id")
  SELECT gen_random_uuid(), c."id" FROM "connections" c;
INSERT INTO "conversation_participants" ("conversation_id", "user_id")
  SELECT v."id", c."user_a_id" FROM "conversations" v JOIN "connections" c ON c."id" = v."connection_id"
  UNION ALL
  SELECT v."id", c."user_b_id" FROM "conversations" v JOIN "connections" c ON c."id" = v."connection_id";
