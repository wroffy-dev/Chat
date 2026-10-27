-- CreateTable
CREATE TABLE "app_settings" (
    "key" TEXT NOT NULL,
    "value" JSONB NOT NULL,
    "updated_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_by" UUID,

    CONSTRAINT "app_settings_pkey" PRIMARY KEY ("key")
);

-- Only allow-listed keys (docs/05 §5); values are validated by the backend before writing.
ALTER TABLE "app_settings" ADD CONSTRAINT "app_settings_known_key"
  CHECK ("key" IN ('call.max_video_quality', 'call.ring_timeout_seconds', 'chat.unread_backstop_hours', 'push.enabled'));
