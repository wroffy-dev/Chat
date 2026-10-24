-- CreateTable
CREATE TABLE "qr_tokens" (
    "id" UUID NOT NULL,
    "owner_user_id" UUID NOT NULL,
    "token_hash" BYTEA NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expires_at" TIMESTAMPTZ(3) NOT NULL,
    "superseded_at" TIMESTAMPTZ(3),
    "consumed_at" TIMESTAMPTZ(3),
    "consumed_by_user_id" UUID,

    CONSTRAINT "qr_tokens_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "connection_requests" (
    "id" UUID NOT NULL,
    "qr_token_id" UUID,
    "requester_user_id" UUID NOT NULL,
    "owner_user_id" UUID NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expires_at" TIMESTAMPTZ(3) NOT NULL,
    "decided_at" TIMESTAMPTZ(3),

    CONSTRAINT "connection_requests_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "connections" (
    "id" UUID NOT NULL,
    "user_a_id" UUID NOT NULL,
    "user_b_id" UUID NOT NULL,
    "created_from_request_id" UUID,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "connections_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "qr_tokens_token_hash_key" ON "qr_tokens"("token_hash");

-- CreateIndex
CREATE UNIQUE INDEX "connection_requests_qr_token_id_key" ON "connection_requests"("qr_token_id");

-- CreateIndex
CREATE UNIQUE INDEX "connections_created_from_request_id_key" ON "connections"("created_from_request_id");

-- CreateIndex
CREATE UNIQUE INDEX "connections_user_a_id_user_b_id_key" ON "connections"("user_a_id", "user_b_id");

-- AddForeignKey
ALTER TABLE "qr_tokens" ADD CONSTRAINT "qr_tokens_owner_user_id_fkey" FOREIGN KEY ("owner_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "qr_tokens" ADD CONSTRAINT "qr_tokens_consumed_by_user_id_fkey" FOREIGN KEY ("consumed_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "connection_requests" ADD CONSTRAINT "connection_requests_qr_token_id_fkey" FOREIGN KEY ("qr_token_id") REFERENCES "qr_tokens"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "connection_requests" ADD CONSTRAINT "connection_requests_requester_user_id_fkey" FOREIGN KEY ("requester_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "connection_requests" ADD CONSTRAINT "connection_requests_owner_user_id_fkey" FOREIGN KEY ("owner_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "connections" ADD CONSTRAINT "connections_user_a_id_fkey" FOREIGN KEY ("user_a_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "connections" ADD CONSTRAINT "connections_user_b_id_fkey" FOREIGN KEY ("user_b_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "connections" ADD CONSTRAINT "connections_created_from_request_id_fkey" FOREIGN KEY ("created_from_request_id") REFERENCES "connection_requests"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------
-- Constraints Prisma cannot express (docs/06-qr-connection.md §5).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE "qr_tokens" ADD CONSTRAINT "qr_tokens_not_self_consumed"
  CHECK ("consumed_by_user_id" IS NULL OR "consumed_by_user_id" <> "owner_user_id");
CREATE INDEX "qr_tokens_owner_active_idx" ON "qr_tokens" ("owner_user_id")
  WHERE "consumed_at" IS NULL AND "superseded_at" IS NULL;

ALTER TABLE "connection_requests" ADD CONSTRAINT "connection_requests_status_check"
  CHECK ("status" IN ('pending', 'accepted', 'rejected', 'cancelled', 'expired'));
ALTER TABLE "connection_requests" ADD CONSTRAINT "connection_requests_not_self"
  CHECK ("requester_user_id" <> "owner_user_id");
-- No duplicate pending requests between the same pair, in the same direction.
CREATE UNIQUE INDEX "connection_requests_one_pending_idx"
  ON "connection_requests" ("requester_user_id", "owner_user_id") WHERE "status" = 'pending';

-- One connection per pair regardless of who scanned whom: ids are stored in canonical order.
ALTER TABLE "connections" ADD CONSTRAINT "connections_ordered_pair" CHECK ("user_a_id" < "user_b_id");

-- Connections are permanent in v1: the database refuses deletes and changes to the pair itself.
-- (Only created_from_request_id may change, to NULL, when an old request row is cleaned up.)
CREATE FUNCTION "connections_immutable"() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'connections are permanent and cannot be deleted';
  END IF;
  IF NEW."id" <> OLD."id" OR NEW."user_a_id" <> OLD."user_a_id" OR NEW."user_b_id" <> OLD."user_b_id"
     OR NEW."created_at" <> OLD."created_at" THEN
    RAISE EXCEPTION 'connections are permanent and cannot be changed';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER "connections_immutable_trg"
  BEFORE UPDATE OR DELETE ON "connections"
  FOR EACH ROW EXECUTE FUNCTION "connections_immutable"();
