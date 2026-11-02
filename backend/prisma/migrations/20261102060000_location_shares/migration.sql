-- Location sharing with the administrator (owner decision D-06, 2026-11-02; docs/11).
-- One row per user, overwritten in place: the latest point only, never a history. Coordinates are
-- cleared in the same statement that turns sharing off.
CREATE TABLE "location_shares" (
    "user_id" UUID NOT NULL,
    "enabled" BOOLEAN NOT NULL DEFAULT false,
    "consent_version" INTEGER,
    "consented_at" TIMESTAMPTZ(3),
    "precision" TEXT,
    "latitude" DOUBLE PRECISION,
    "longitude" DOUBLE PRECISION,
    "accuracy_m" REAL,
    "captured_at" TIMESTAMPTZ(3),
    "received_at" TIMESTAMPTZ(3),
    "last_viewed_by_admin_at" TIMESTAMPTZ(3),
    "updated_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "location_shares_pkey" PRIMARY KEY ("user_id")
);

ALTER TABLE "location_shares" ADD CONSTRAINT "location_shares_user_id_fkey"
  FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "location_shares"
  ADD CONSTRAINT "location_shares_precision_check" CHECK ("precision" IN ('approximate', 'precise')),
  ADD CONSTRAINT "location_shares_latitude_check" CHECK ("latitude" BETWEEN -90 AND 90),
  ADD CONSTRAINT "location_shares_longitude_check" CHECK ("longitude" BETWEEN -180 AND 180),
  ADD CONSTRAINT "location_shares_accuracy_check" CHECK ("accuracy_m" > 0 AND "accuracy_m" <= 100000),
  ADD CONSTRAINT "location_shares_off_has_no_point" CHECK ("enabled" OR ("latitude" IS NULL AND "longitude" IS NULL));
