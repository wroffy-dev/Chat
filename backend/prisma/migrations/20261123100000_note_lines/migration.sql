-- Shared notes (owner, 2026-11-23, D-31 changed): a user's notes are also shown to the people they are connected
-- with, who can add lines under a note but not change its title or text. One row per added line; deleted with the
-- note or with the user who wrote it.
CREATE TABLE "note_lines" (
    "id" UUID NOT NULL,
    "note_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "body" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "note_lines_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "note_lines_body_length_check" CHECK (char_length("body") BETWEEN 1 AND 2000)
);
CREATE INDEX "note_lines_note_id_created_at_idx" ON "note_lines"("note_id", "created_at");
CREATE INDEX "note_lines_user_id_idx" ON "note_lines"("user_id");
ALTER TABLE "note_lines" ADD CONSTRAINT "note_lines_note_id_fkey" FOREIGN KEY ("note_id") REFERENCES "notes"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "note_lines" ADD CONSTRAINT "note_lines_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
