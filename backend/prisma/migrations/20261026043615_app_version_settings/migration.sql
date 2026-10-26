-- App version settings (docs/05 §5): the allow-list grows by three keys.
ALTER TABLE "app_settings" DROP CONSTRAINT "app_settings_known_key";
ALTER TABLE "app_settings" ADD CONSTRAINT "app_settings_known_key"
  CHECK ("key" IN ('call.max_video_quality', 'call.ring_timeout_seconds', 'chat.unread_backstop_hours', 'push.enabled',
                   'app.min_supported_version_code', 'app.latest_version_code', 'app.latest_apk_sha256'));
