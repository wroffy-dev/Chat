-- CreateTable
CREATE TABLE "calls" (
    "id" UUID NOT NULL,
    "connection_id" UUID NOT NULL,
    "caller_user_id" UUID NOT NULL,
    "callee_user_id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "livekit_room" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'ringing',
    "end_reason" TEXT,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "accepted_at" TIMESTAMPTZ(3),
    "active_at" TIMESTAMPTZ(3),
    "ended_at" TIMESTAMPTZ(3),

    CONSTRAINT "calls_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "call_diagnostics" (
    "id" UUID NOT NULL,
    "call_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "summary" JSONB NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "call_diagnostics_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "calls_livekit_room_key" ON "calls"("livekit_room");

-- CreateIndex
CREATE INDEX "calls_created_at_idx" ON "calls"("created_at");

-- CreateIndex
CREATE UNIQUE INDEX "call_diagnostics_call_id_user_id_key" ON "call_diagnostics"("call_id", "user_id");

-- AddForeignKey
ALTER TABLE "calls" ADD CONSTRAINT "calls_connection_id_fkey" FOREIGN KEY ("connection_id") REFERENCES "connections"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "calls" ADD CONSTRAINT "calls_caller_user_id_fkey" FOREIGN KEY ("caller_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "calls" ADD CONSTRAINT "calls_callee_user_id_fkey" FOREIGN KEY ("callee_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "call_diagnostics" ADD CONSTRAINT "call_diagnostics_call_id_fkey" FOREIGN KEY ("call_id") REFERENCES "calls"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "call_diagnostics" ADD CONSTRAINT "call_diagnostics_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Constraints Prisma cannot express (docs/08-calling.md §4.1).
ALTER TABLE "calls" ADD CONSTRAINT "calls_kind_check" CHECK ("kind" IN ('video', 'voice'));
ALTER TABLE "calls" ADD CONSTRAINT "calls_status_check"
  CHECK ("status" IN ('ringing', 'accepted', 'active', 'ended', 'declined', 'cancelled', 'missed', 'failed'));
ALTER TABLE "calls" ADD CONSTRAINT "calls_distinct_parties" CHECK ("caller_user_id" <> "callee_user_id");
-- At most one live call per connection: a second "call" while one is ringing/active is refused.
CREATE UNIQUE INDEX "calls_one_live_per_connection"
  ON "calls" ("connection_id") WHERE "status" IN ('ringing', 'accepted', 'active');
ALTER TABLE "call_diagnostics" ADD CONSTRAINT "call_diagnostics_size" CHECK (octet_length("summary"::text) <= 8192);
