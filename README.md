# Manticord

A native Android Discord client reconstructed in **Kotlin with Android Views and
XML**, based on Discord Android **126.21 Stable**. Manticord keeps the classic
Android interface while adding compatibility with newer Discord features and
local customization.

The project is under active development. Core messaging, social and media flows
are implemented, but complete behavioral parity is still a work in progress.
See [the development plan](PLAN.md) for status and remaining work.

## Download

**[Download the latest APK](https://microcord.mantikafasi.dev/latest.apk)**

- Requires **Android 6.0 (API 23) or newer**.
- Install the APK with Android's package installer. Updates can be installed over
  the existing app without clearing its data.
- Manticord uses the package `dev.mantikafasi.manticord`, separate from Discord's
  official app.

The **Updates** screen can check for new builds, show release notes and commit
history, download an update and open the installer. Automatic checks run when
the app enters the foreground, at most once every six hours per installed build.
Downloads do not start automatically.

## Features

- Guilds, channels, DMs, group DMs, threads and forums.
- Messages, replies, reactions, attachments, search, pins and forwarding.
- Emoji, stickers, GIFs, rich embeds, image galleries and video playback.
- User profiles, presence, modern display names and profile cosmetics.
- Voice calls, video, screen sharing and game-activity icons in voice-member rows.
- Modern polls, bot components, application commands and embedded Activities.
- Theme, chat-input and header customization, plus optional feature flags.
- Bundled and external APK plugins through the [Manticord Plugin API](docs/plugin-api.md).

Some provider integrations and device-specific behavior remain incomplete; the
[feature status table](PLAN.md#feature-status) distinguishes implementation from
full verification.

## Build from Source

### Requirements

- A Git checkout, including the checked-in resources and `third_party` artifacts.
  Build identity is derived from Git, so a source archive without `.git` is not sufficient.
- A **JDK 21** installation. The Gradle daemon toolchain is pinned to Java 21.
- Android SDK **Platform 37** and **Build Tools 36.0.0**, matching the current build/CI setup.
- Bash and standard Unix command-line tools; use **Git Bash on Windows**.

Configure the Android SDK through Android Studio, `ANDROID_HOME`, or `sdk.dir`
in your local `local.properties`. Dependency versions are maintained in
[gradle/libs.versions.toml](gradle/libs.versions.toml).

Run all commands below from the repository root. Use `scripts/build.sh` rather
than invoking Gradle directly: it serializes builds that share the checkout.

```bash
# Build a debug APK
bash scripts/build.sh :app:assembleDebug

# Install it on a connected Android device
bash scripts/build.sh :app:installDebug

# Build a release APK and its updater manifest
bash scripts/build.sh :app:assembleRelease :app:generateReleaseUpdateManifest
```

Outputs:

| Artifact | Path |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| Update manifest | `app/build/outputs/update/release/update-manifest.json` |

The first build needs network access to resolve dependencies. Add `--offline`
only after they are cached. `app/google-services.json` is optional for building;
push notifications are inactive when that configuration is absent.

Local and CI builds use a shared development signing identity so they can update
one another in place. Build numbers are release labels; Android version codes
are derived from the source commit timestamp.

### Tests

```bash
# JVM tests, or add --tests '<class or pattern>' for a focused run
bash scripts/build.sh :app:testDebugUnitTest
bash scripts/build.sh :app:lintDebug

# Release download/changelog worker tests (Node.js required)
node --test tools/update-worker/worker.test.mjs
```

Run instrumentation tests only on a dedicated test emulator or device, since
test installation and cleanup can affect app data.

## Project Layout

| Directory | Purpose |
| --- | --- |
| `app/` | Android application, stores, screens, resources and tests |
| `baselineprofile/` | Startup/profile generation |
| `plugin-api/` | Plugin contracts and typed host services |
| `plugin-gradle/` | Plugin packaging and manifest generation |
| `third_party/` | Native/WebRTC artifacts, provenance and licenses |
| `tools/update-worker/` | Release feed, APK downloads and changelogs |
| `scripts/` | Build coordination and development tooling |

## Contributing

Read [PLAN.md](PLAN.md) for architecture and current work, and
[AGENTS.md](AGENTS.md) for repository conventions. Keep changes focused, reuse
existing components, and add a targeted check for non-trivial behavior. Bug
reports should include the build number, Android/device version and reproduction
steps; redact account credentials and private message content from diagnostics.

- [Plugin development](docs/plugin-api.md)
- [Update service and release manifests](tools/update-worker/README.md)
- [Native voice runtime provenance](third_party/webrtc-discord-333.5/README.md)

## Credits

- [Discord Userdoccers](https://github.com/discord-userdoccers/discord-userdoccers)
- [Aliucord](https://github.com/Aliucord/Aliucord/)
- [zt64/aliucord-plugins](https://github.com/zt64/aliucord-plugins)
- [BetterChatBox](https://github.com/wingio/plugins/tree/main/BetterChatbox)

Manticord is not affiliated with or endorsed by Discord Inc. Recovered Discord
assets and third-party components retain their respective ownership and licenses.
