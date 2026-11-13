-- Owner requests 2026-11-13: a "Missed call" line in the chat and replying to a message.
-- `kind`: 'text' (a person's message) or 'missed_call' (written by the server; body = 'video' | 'voice').
-- `reply_to_id`: the message this one answers; cleared when that message is deleted (D-01/D-02).
-- Existing rows become 'text' with no reply. No data is removed.
ALTER TABLE "messages" ADD COLUMN "kind" TEXT NOT NULL DEFAULT 'text';
ALTER TABLE "messages" ADD CONSTRAINT "messages_kind_check" CHECK ("kind" IN ('text', 'missed_call'));
ALTER TABLE "messages" ADD COLUMN "reply_to_id" UUID;
ALTER TABLE "messages" ADD CONSTRAINT "messages_reply_to_id_fkey"
  FOREIGN KEY ("reply_to_id") REFERENCES "messages"("id") ON DELETE SET NULL ON UPDATE CASCADE;
