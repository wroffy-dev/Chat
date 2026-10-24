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
