-- CreateTable
CREATE TABLE "users" (
    "id" UUID NOT NULL,
    "slot" SMALLINT NOT NULL,
    "username" TEXT NOT NULL,
    "password_hash" TEXT NOT NULL,
    "password_changed_at" TIMESTAMPTZ(3) NOT NULL,
    "must_change_password" BOOLEAN NOT NULL DEFAULT true,
    "status" TEXT NOT NULL DEFAULT 'active',
    "failed_login_count" INTEGER NOT NULL DEFAULT 0,
    "throttled_until" TIMESTAMPTZ(3),
    "last_seen_at" TIMESTAMPTZ(3),
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "admin_accounts" (
    "id" UUID NOT NULL,
    "singleton" BOOLEAN NOT NULL DEFAULT true,
    "username" TEXT NOT NULL,
    "password_hash" TEXT NOT NULL,
    "totp_secret_enc" BYTEA NOT NULL,
    "totp_last_used_step" BIGINT,
    "failed_login_count" INTEGER NOT NULL DEFAULT 0,
    "throttled_until" TIMESTAMPTZ(3),
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "admin_accounts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sessions" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "access_token_hash" BYTEA NOT NULL,
    "access_expires_at" TIMESTAMPTZ(3) NOT NULL,
    "refresh_token_hash" BYTEA NOT NULL,
    "previous_refresh_token_hash" BYTEA,
    "idle_expires_at" TIMESTAMPTZ(3) NOT NULL,
    "absolute_expires_at" TIMESTAMPTZ(3) NOT NULL,
    "restricted" BOOLEAN NOT NULL DEFAULT false,
    "fcm_token" TEXT,
    "fcm_token_updated_at" TIMESTAMPTZ(3),
    "app_version_code" INTEGER,
    "device_model" TEXT,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "last_used_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revoked_at" TIMESTAMPTZ(3),
    "revoke_reason" TEXT,

    CONSTRAINT "sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "admin_sessions" (
    "id" UUID NOT NULL,
    "admin_id" UUID NOT NULL,
    "token_hash" BYTEA NOT NULL,
    "csrf_token" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "last_used_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "idle_expires_at" TIMESTAMPTZ(3) NOT NULL,
    "absolute_expires_at" TIMESTAMPTZ(3) NOT NULL,
    "revoked_at" TIMESTAMPTZ(3),

    CONSTRAINT "admin_sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "audit_events" (
    "id" BIGSERIAL NOT NULL,
    "occurred_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "actor_type" TEXT NOT NULL,
    "actor_id" UUID,
    "action" TEXT NOT NULL,
    "target_type" TEXT,
    "target_id" UUID,
    "ip_truncated" INET,
    "metadata" JSONB,

    CONSTRAINT "audit_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "users_slot_key" ON "users"("slot");

-- CreateIndex
CREATE UNIQUE INDEX "users_username_key" ON "users"("username");

-- CreateIndex
CREATE UNIQUE INDEX "admin_accounts_singleton_key" ON "admin_accounts"("singleton");

-- CreateIndex
CREATE UNIQUE INDEX "admin_accounts_username_key" ON "admin_accounts"("username");

-- CreateIndex
CREATE UNIQUE INDEX "sessions_access_token_hash_key" ON "sessions"("access_token_hash");

-- CreateIndex
CREATE UNIQUE INDEX "sessions_refresh_token_hash_key" ON "sessions"("refresh_token_hash");

-- CreateIndex
CREATE INDEX "sessions_previous_refresh_token_hash_idx" ON "sessions"("previous_refresh_token_hash");

-- CreateIndex
CREATE UNIQUE INDEX "admin_sessions_token_hash_key" ON "admin_sessions"("token_hash");

-- CreateIndex
CREATE INDEX "audit_events_occurred_at_idx" ON "audit_events"("occurred_at" DESC);

-- CreateIndex
CREATE INDEX "audit_events_action_occurred_at_idx" ON "audit_events"("action", "occurred_at" DESC);

-- AddForeignKey
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "admin_sessions" ADD CONSTRAINT "admin_sessions_admin_id_fkey" FOREIGN KEY ("admin_id") REFERENCES "admin_accounts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------
-- Constraints Prisma cannot express (docs/13-database-design.md §3).
-- ---------------------------------------------------------------------------------------------

-- Exactly two app users at most: slot is 1 or 2 and unique.
ALTER TABLE "users" ADD CONSTRAINT "users_slot_check" CHECK ("slot" IN (1, 2));
ALTER TABLE "users" ADD CONSTRAINT "users_status_check" CHECK ("status" IN ('active', 'disabled'));
ALTER TABLE "users" ADD CONSTRAINT "users_username_check"
  CHECK ("username" = lower("username") AND "username" ~ '^[a-z0-9._-]{3,32}$');

-- Exactly one administrator.
ALTER TABLE "admin_accounts" ADD CONSTRAINT "admin_accounts_singleton_check" CHECK ("singleton");
ALTER TABLE "admin_accounts" ADD CONSTRAINT "admin_accounts_username_check"
  CHECK ("username" = lower("username") AND "username" ~ '^[a-z0-9._-]{3,32}$');

ALTER TABLE "sessions" ADD CONSTRAINT "sessions_revoke_reason_check" CHECK (
  "revoke_reason" IS NULL OR "revoke_reason" IN
  ('logout', 'new_login', 'password_change', 'admin_reset', 'admin_revoke', 'refresh_reuse', 'disabled', 'expired'));
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_device_model_length" CHECK (char_length("device_model") <= 64);

-- Active sessions per user, and one push token per session.
CREATE INDEX "sessions_user_active_idx" ON "sessions" ("user_id") WHERE "revoked_at" IS NULL;
CREATE UNIQUE INDEX "sessions_fcm_token_key" ON "sessions" ("fcm_token") WHERE "fcm_token" IS NOT NULL;

ALTER TABLE "audit_events" ADD CONSTRAINT "audit_events_actor_type_check"
  CHECK ("actor_type" IN ('user', 'admin', 'system'));
