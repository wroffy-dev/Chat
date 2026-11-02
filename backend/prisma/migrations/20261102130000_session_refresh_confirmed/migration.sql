-- Whether the phone has used the tokens of the latest refresh (docs/04 §4). Until it has, the refresh
-- token it held before may be presented again (its answer was lost on a weak network) and is renewed
-- instead of being treated as stolen. Existing sessions count as confirmed.
ALTER TABLE "sessions" ADD COLUMN "refresh_confirmed" BOOLEAN NOT NULL DEFAULT true;
