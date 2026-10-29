-- Up to ten app users (owner decision D-21, 2026-10-29); previously exactly two.
ALTER TABLE "users" DROP CONSTRAINT "users_slot_check";
ALTER TABLE "users" ADD CONSTRAINT "users_slot_check" CHECK ("slot" BETWEEN 1 AND 10);
