-- WhatsApp-style reactions (owner decision D-25, 2026-10-30): one emoji per person per message.
-- Kept on the message row, so a reaction is deleted together with its message (D-01 option D,
-- D-02 backstop) and never outlives it.
ALTER TABLE "messages"
  ADD COLUMN "sender_reaction" TEXT,
  ADD COLUMN "recipient_reaction" TEXT;

ALTER TABLE "messages"
  ADD CONSTRAINT "messages_sender_reaction_check" CHECK (char_length("sender_reaction") BETWEEN 1 AND 32),
  ADD CONSTRAINT "messages_recipient_reaction_check" CHECK (char_length("recipient_reaction") BETWEEN 1 AND 32);
