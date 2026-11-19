-- Snaps and streaks (owner decision D-27, 2026-11-19). A snap is a photo or a short video taken with the
-- camera; its file lives on the server only until it is opened (then it is deleted) or for at most 24 h.
-- Only these metadata rows are in the database; the media bytes are never stored here.
CREATE TABLE "snaps" (
    "id" UUID NOT NULL,
    "connection_id" UUID NOT NULL,
    "sender_user_id" UUID NOT NULL,
    "recipient_user_id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "size_bytes" INTEGER NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "snaps_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "snaps_kind_check" CHECK ("kind" IN ('photo', 'video'))
);
CREATE INDEX "snaps_recipient_user_id_idx" ON "snaps"("recipient_user_id");
CREATE INDEX "snaps_created_at_idx" ON "snaps"("created_at");
ALTER TABLE "snaps" ADD CONSTRAINT "snaps_connection_id_fkey" FOREIGN KEY ("connection_id") REFERENCES "connections"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "snaps" ADD CONSTRAINT "snaps_sender_user_id_fkey" FOREIGN KEY ("sender_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "snaps" ADD CONSTRAINT "snaps_recipient_user_id_fkey" FOREIGN KEY ("recipient_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- One streak per connection: days (India time, YYYY-MM-DD) on which each person last sent a snap.
CREATE TABLE "snap_streaks" (
    "connection_id" UUID NOT NULL,
    "count" INTEGER NOT NULL DEFAULT 0,
    "last_mutual_day" TEXT,
    "user_a_day" TEXT,
    "user_b_day" TEXT,
    CONSTRAINT "snap_streaks_pkey" PRIMARY KEY ("connection_id")
);
ALTER TABLE "snap_streaks" ADD CONSTRAINT "snap_streaks_connection_id_fkey" FOREIGN KEY ("connection_id") REFERENCES "connections"("id") ON DELETE CASCADE ON UPDATE CASCADE;
