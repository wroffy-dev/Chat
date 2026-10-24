# Vault Chat Android app

Kotlin + Jetpack Compose. Design: `../docs/15-android-ui-ux.md`, status: `../docs/IMPLEMENTATION_PLAN.md`.

> **Not yet compiled.** The project was written in an environment without Android SDK access. The
> first real build happens in CI (`.github/workflows/ci.yml`) or Android Studio. Library versions in
> `gradle/libs.versions.toml` are older stable releases and can be bumped to current ones.

Current scope (Phase 1): app shell, theme, navigation, login screen (sign-in reports "not available"
until Phase 2 adds accounts), server reachability check, permission-handling helpers.

## Build

Requirements: JDK 17+, Android SDK (API 35). Then:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Backend URL

- Debug: `http://10.0.2.2:3000/` (the development machine as seen from the emulator). For a physical
  phone, pass `-Pvaultchat.apiBaseUrl=http://<lan-ip>:3000/` and add that host to
  `app/src/debug/res/xml/network_security_config.xml`.
- Release: must be `https://` (the build fails otherwise). Set `vaultchat.apiBaseUrl.release` in
  `~/.gradle/gradle.properties` on the release machine.

## Signing

Release signing material is never committed. On the release machine only, add to
`~/.gradle/gradle.properties`: `vaultchat.signing.storeFile`, `vaultchat.signing.storePassword`,
`vaultchat.signing.keyAlias`, `vaultchat.signing.keyPassword`. Without them, release builds are unsigned.

## Push notifications (optional)

Incoming calls ring on a closed app only with Firebase Cloud Messaging. Nothing Firebase-related is
committed. To enable it on the release/build machine:

1. Create a Firebase project (console.firebase.google.com) and add an Android app with the package name
   (`app.vaultchat`, or `app.vaultchat.dev` for debug builds).
2. From the app's settings, copy these values into `~/.gradle/gradle.properties`:
   ```
   vaultchat.fcm.projectId=<project id>
   vaultchat.fcm.appId=<mobilesdk app id, 1:…:android:…>
   vaultchat.fcm.apiKey=<current api key>
   vaultchat.fcm.senderId=<project number>
   ```
3. On the server, create a service-account key with permission to send FCM messages, mount it as a file,
   and set `FCM_CREDENTIALS_FILE` (backend/.env.example).

Without these, the app works but incoming calls ring only while it is open. FCM needs Google Play
services on the phone (open decision D-08).
