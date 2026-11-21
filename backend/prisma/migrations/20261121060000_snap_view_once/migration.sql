-- View-once snaps (owner, 2026-11-21; changes D-27): the sender may mark a snap "view once". The
-- recipient's app then shows it a single time with no replay. Snaps (camera or, since the same day, from the
-- phone's gallery) are still deleted on the server when opened or after 24 h. No data is removed.
ALTER TABLE "snaps" ADD COLUMN "view_once" BOOLEAN NOT NULL DEFAULT false;
