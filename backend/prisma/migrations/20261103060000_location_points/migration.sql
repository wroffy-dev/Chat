-- The last ten shared points per user (owner decision 2026-11-03, D-06; consent version 2). Older ones
-- are deleted as new ones arrive; all are deleted when sharing is turned off. Backups exclude the data.
CREATE TABLE "location_points" (
    "id" BIGSERIAL NOT NULL,
    "user_id" UUID NOT NULL,
    "latitude" DOUBLE PRECISION NOT NULL,
    "longitude" DOUBLE PRECISION NOT NULL,
    "accuracy_m" REAL NOT NULL,
    "precision" TEXT NOT NULL,
    "captured_at" TIMESTAMPTZ(3) NOT NULL,
    "received_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "location_points_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "location_points_user_id_captured_at_idx" ON "location_points"("user_id", "captured_at");

ALTER TABLE "location_points" ADD CONSTRAINT "location_points_user_id_fkey"
  FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "location_points"
  ADD CONSTRAINT "location_points_precision_check" CHECK ("precision" IN ('approximate', 'precise')),
  ADD CONSTRAINT "location_points_latitude_check" CHECK ("latitude" BETWEEN -90 AND 90),
  ADD CONSTRAINT "location_points_longitude_check" CHECK ("longitude" BETWEEN -180 AND 180),
  ADD CONSTRAINT "location_points_accuracy_check" CHECK ("accuracy_m" > 0 AND "accuracy_m" <= 100000);
