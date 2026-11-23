-- Notes (owner decision D-31, 2026-11-23): the app opens on the user's own notes ("Just Notes"). Each note
-- belongs to one user, is visible only to that user and is kept until the user deletes it (or the account is
-- removed). Notes are ordinary text and are included in backups.
CREATE TABLE "notes" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "title" TEXT NOT NULL DEFAULT '',
    "body" TEXT NOT NULL DEFAULT '',
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "notes_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "notes_title_length_check" CHECK (char_length("title") <= 200),
    CONSTRAINT "notes_body_length_check" CHECK (char_length("body") <= 20000)
);
CREATE INDEX "notes_user_id_updated_at_idx" ON "notes"("user_id", "updated_at");
ALTER TABLE "notes" ADD CONSTRAINT "notes_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
