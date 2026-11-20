-- Disappearing-message timer per chat (owner, 2026-11-20; D-30, changes D-01/D-02).
-- `disappear_seconds`: NULL = Off (the D-01 rule: seen messages are deleted when either person leaves);
-- otherwise a message is deleted this many seconds after its recipient has seen it. Set by either person
-- and applies to both. The 24 h backstop (D-02) still deletes every message 24 h after it was sent.
-- `seen_at`: when the recipient first displayed the message (set from their read position).
-- `kind` gains 'disappear' (a line saying who changed the timer; body = seconds or 'off').
-- No data is removed.
ALTER TABLE "conversations" ADD COLUMN "disappear_seconds" INTEGER;
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_disappear_seconds_check"
  CHECK ("disappear_seconds" IS NULL OR "disappear_seconds" IN (60, 300, 3600, 86400));
ALTER TABLE "messages" ADD COLUMN "seen_at" TIMESTAMPTZ(3);
CREATE INDEX "messages_seen_at_idx" ON "messages" ("seen_at") WHERE "seen_at" IS NOT NULL;
ALTER TABLE "messages" DROP CONSTRAINT "messages_kind_check";
ALTER TABLE "messages" ADD CONSTRAINT "messages_kind_check" CHECK ("kind" IN ('text', 'missed_call', 'disappear'));
