-- Avatars and nicknames (owner, 2026-11-23, D-32). An avatar is one of the app's built-in drawings, chosen by the
-- user; it is stored as its short name, never as an uploaded picture. A nickname is the name one user gives one of
-- the people they are connected with, seen only by that user; the chat gets a line saying the name was changed.
ALTER TABLE "users" ADD COLUMN "avatar" TEXT;
ALTER TABLE "users" ADD CONSTRAINT "users_avatar_check" CHECK ("avatar" IS NULL OR "avatar" ~ '^[a-z]{2,16}$');

CREATE TABLE "nicknames" (
    "user_id" UUID NOT NULL,
    "connection_id" UUID NOT NULL,
    "nickname" TEXT NOT NULL,
    "updated_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "nicknames_pkey" PRIMARY KEY ("user_id", "connection_id"),
    CONSTRAINT "nicknames_length_check" CHECK (char_length("nickname") BETWEEN 1 AND 40)
);
ALTER TABLE "nicknames" ADD CONSTRAINT "nicknames_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "nicknames" ADD CONSTRAINT "nicknames_connection_id_fkey" FOREIGN KEY ("connection_id") REFERENCES "connections"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- A chat line "… changed your name from A to B" (kind `nickname`, body JSON {"from","to"}).
ALTER TABLE "messages" DROP CONSTRAINT "messages_kind_check";
ALTER TABLE "messages" ADD CONSTRAINT "messages_kind_check" CHECK ("kind" IN ('text', 'missed_call', 'disappear', 'nickname'));
