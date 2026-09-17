# Microcord Development Plan

This document tracks architecture, compatibility decisions, feature status and
remaining work. [README.md](README.md) covers installation and building;
[AGENTS.md](AGENTS.md) contains the detailed development rules.

Update the relevant section when behavior or evidence changes. Do not append
session transcripts, tool output, repeated build summaries or agent journals.
Implementation history and older audit reports are available in Git history.

## Project Contract

| Area | Decision |
| --- | --- |
| Reference | Discord Android 126.21 Stable; use newer client/runtime evidence for intentional backports |
| Goal | Preserve the native Android experience and reconstruct complete feature behavior |
| Language and UI | Readable Kotlin, Android Views and XML, generated view bindings |
| Application ID | `dev.mantikafasi.manticord` |
| Namespace | `com.discord`; JNI-facing names remain exact |
| Current build | Minimum SDK 23, target SDK 36, compile SDK 37; see `app/build.gradle.kts` |
| State | Existing store/dispatcher/fragment architecture |
| Resources | Retain recovered assets, layouts and qualifiers |
| Dependencies | Install public libraries normally; versions live in `gradle/libs.versions.toml` |
| Native runtime | Preserve the packaged binaries' JNI contracts and documented WebRTC compatibility |
| Reference files | Read-only; never edit the decompiled reference to make the target build |

The original API 21 goal differs from the current API 23 minimum. API 21 support
must not be claimed without reconciling the build configuration and validating
that compatibility. This documentation cleanup does not change the SDK settings.

## Architecture

### Application and state

- `App` initializes the process; `AppActivity` hosts fragments and dedicated
  entry points provide call, share and Android task behavior.
- `StoreStream` owns the store graph. All `StoreV2` mutations run on its single
  registered dispatcher; `ObservationDeck` publishes post-dispatch snapshots.
- Stores include their complete semantic state: caches, indexes, persistence,
  loading/error state and update sources, not just fields required by one screen.
- Models preserve types, nullability, constructor order, JSON names, enum wire
  values and Kotlin value semantics. Secret-bearing string output is redacted.
- SharedPreferences and Kryo-backed `Persister` retain settings and store data.
  Account-specific state must not leak across logout or account switching.

### Networking and startup

- Discord REST endpoints use `RestAPI.api` and `ClientMetadataInterceptor`.
  Do not restore the former legacy/RN client split.
- The Gateway uses typed dispatch payloads, Gson streaming, `zstd-stream`,
  identify/resume, sequence tracking, heartbeats and reconnect/backoff.
- Fast Connect opens one early socket and buffers events until store listeners
  are ready. `CLIENT_STATE_V2` versions support full/partial READY hydration.
- Cached authenticated UI opens before READY; known required actions still take
  precedence. Network work must not block first-frame rendering.
- Captcha and MFA challenges use the shared transport/coordinator paths rather
  than separate implementations in every screen. Cancellation remains distinct
  from a transport failure.
- The public updater and Activity bearer-token authentication use separate
  transports where Discord account credentials must not be attached.

### UI and media

- Use existing Discord-themed components, generated bindings and lifecycle-aware
  subscriptions. Dynamic profile children consume the resolved profile palette.
- Preserve toolbar Up/Back behavior, touch targets, RTL, large fonts and recycled
  view state. Decorations should not alter existing geometry without a concrete
  requirement and recorded evidence.
- Images use the shared Vito/Fresco pipeline. APNG stickers use `ApngDrawable`,
  GIFs use the image pipeline, and Lottie stickers use the native renderer.
- Voice and streams share the owning media engine. Voice uses the `default`
  native context; streams use separate stream contexts to avoid replacing the
  voice/camera sender. Preserve exact JNI descriptors and callback lifetimes.
- Native provenance and verification are documented under
  [third_party/webrtc-discord-333.5](third_party/webrtc-discord-333.5/README.md).
  The obsolete 126.21 bridge and extractor have been removed; their provenance
  remains in Git history.

### Plugins

Bundled and installed-APK plugins use the typed host API, deterministic dependency
and priority handling, lifecycle-scoped contributions and host-rendered settings.
Asynchronous plugins do not gate first UI; explicitly blocking dependency chains
have bounded startup waits. The master loading switch gates discovery and loading.
External plugins execute in-process and are not sandboxed.

See [docs/plugin-api.md](docs/plugin-api.md) for the current API, signer approval,
resource loading and contribution surfaces.

## Feature Status

These are delivery statuses, not percentage-complete estimates. Structural
coverage and passing builds do not establish complete behavioral parity.

| Phase | Area | Status | Remaining verification/work |
| --- | --- | --- | --- |
| 0 | Build and XML shell | DONE | Maintain build and launch regressions |
| 1 | Resources, themes and custom views | IN PROGRESS | Qualifiers, accessibility, animations and full view behavior |
| 2 | Original native media spike | DONE | Historical x86_64 lifecycle evidence; not a blanket voice-parity claim |
| 3 | Core runtime and stores | IN PROGRESS | Reconcile remaining graph/init/reset seams and full semantic state |
| 4 | REST and Gateway | IN PROGRESS | Destination-store behavior, malformed payloads and live compatibility |
| 5 | Authentication and navigation | IN PROGRESS | Provider flows, required actions and full route/device matrix |
| 6 | Guilds and channels | IN PROGRESS | Reorder, permissions, lazy members, persistence and large-guild behavior |
| 7 | Messages and uploads | IN PROGRESS | Offline/reconnect reconciliation, retry, uploads and media regressions |
| 8 | Users and social features | IN PROGRESS | Profile/presence consistency and provider/device behavior |
| 9 | Notifications and background work | IN PROGRESS | Cold-process delivery, suppression and notification-action matrix |
| 10 | Settings and accounts | IN PROGRESS | Nullable updates, account/session flows and persistence regressions |
| 11 | Voice, calls and video | IN PROGRESS | Join/move/reconnect, audio routes, backgrounding, overlays and teardown |
| 12 | Extended features | IN PROGRESS | Activities, moderation, integrations and remaining behavioral parity |
| 13 | Billing and external integrations | IN PROGRESS | Project-owned products, OAuth/App Links and provider configuration |
| 14 | Release hardening | IN PROGRESS | Device/layout matrix, shrinking/keep rules and full regression boundary |
| 15 | Generated view bindings | IN PROGRESS | Remaining static view lookups and justified dynamic-lookup exceptions |

### Recorded evidence

- Historical structural checkpoints recorded 1,228 retained layout variants,
  76/76 first-party custom-view classes and 83/83 typed reference Gateway events.
- The recorded REST ledger reached 264 effective historical routes; the modern
  generalized MFA route intentionally replaces the exact legacy TOTP template.
- Store, widget and DTO counts used different audit scopes and dates. No fresh
  whole-project behavioral coverage percentage is established here.
- A previous full verification boundary recorded 1,678 JVM tests and 59 connected
  tests, plus debug/release builds and lint. Those historical results are not a
  claim that the current worktree passes every check.
- Device observations have confirmed concurrent incoming/outgoing voice, camera,
  screen sharing and remote playback. The broader lifecycle/device matrix remains
  open, so Phase 11 remains in progress.
- The September 13 updater changes passed 21 focused JVM tests and 32 worker tests.
  The user confirmed an automatic update banner after an explicitly approved
  in-place downgrade to build 191 and resetting only `next_check`.

## Compatibility Decisions

| Area | Retained decision |
| --- | --- |
| Modern features | Backport confirmed modern usernames, profile cosmetics, polls, forwarding, bot components, Apps/Activities and other explicitly requested features without replacing the native UI architecture |
| Display names | Use the shared guild nickname/global name/username resolution; do not invent precedence separately per screen |
| Gateway | Use zstd only, streaming Gson and CLIENT_STATE_V2; the Fastjson2 experiment and zlib fallback were removed |
| REST identity | One default Discord client-metadata transport; endpoint-specific parallel RN clients are retired |
| Captcha | Shared bounded challenge handling; hCaptcha is supported, unsupported challenge providers fail explicitly |
| Activity OAuth | Explicit user authorization; bearer authentication must not receive the Discord account token or cookies |
| Native binaries | Compiled bridge preservation was approved where public artifacts could not satisfy the exact JNI contract; keep provenance and lifecycle checks |
| Firebase | Reuse of Discord's client-side FCM configuration was explicitly approved on 2026-08-11; Admin/service-account credentials are excluded |
| Giphy | The embedded API key is a public client key, confirmed by the user; retain it |
| External services | Missing project-owned OAuth, products or signing relationships must degrade safely; Samsung OAuth is out of scope |
| Signing | Local and CI debug/release APKs use the same repository development identity for in-place updates; it is not a production trust identity |
| Releases | Pushes produce debug artifacts; manual workflow runs publish a signed APK and matching update manifest |
| Version identity | Source SHA and commit timestamp identify builds; versionCode is commit timestamp minus the 2020 epoch, not the CI build number |
| Bookmarks | Synced viewing, jumping and removal are retained; local bookmark creation was withdrawn by user request |
| Diagnostics | Redact tokens, cookies, passwords, MFA/captcha values and message content, including exception text |
| Resource compatibility | Restore first-party attributes; let dependencies own their attributes. Retain decoded-layout compatibility repairs and the inherited lint baseline |
| Observer errors | Preserve the DEX-proven `ObservationDeck` exception/log-level behavior; do not remove it based on incomplete JADX output |

Exact dependency versions belong in the version catalog, not in historical
installation logs. Reference-specific behavioral deviations remain documented
beside the implementation; investigate their evidence before changing them.

## Updater and Distribution

- Stable download: https://microcord.mantikafasi.dev/latest.apk.
- The worker resolves the latest published APK and returns an uncached redirect
  to its version-pinned URL. Latest-release metadata may be cached for 60 seconds.
- Each release manifest binds source identity, package/version, APK size and
  SHA-256. The app verifies downloaded bytes and package/signing identity before
  handing the APK to Android's installer.
- Changelogs compare the installed SHA with the release manifest SHA. Valid
  diverged histories are supported after rebases; patch-equivalent commits can
  reappear under new SHAs. Unknown bases remain unavailable rather than guessed.
- Automatic checks run on foreground entry, at most once every six hours for the
  same installed source build. Changing builds makes a fresh check eligible.
- Manual discoveries can queue an update banner without postponing automatic
  checks. Banners wait for Home/main Settings and are not cleared as ordinary
  message notifications. This is not background polling or an OS update alert.
- DownloadManager owns background downloads. Downloads and installation start
  only through the user's actions.

Endpoint contracts, deployment instructions and cache limits live in
[tools/update-worker/README.md](tools/update-worker/README.md).

## Open Work and Risks

1. **Runtime parity:** verify remaining store dependencies, screen actions and
   lifecycle behavior against the reference and controlled runtime observations.
   Do not carry obsolete missing-class lists forward as current facts.
2. **Messaging reliability:** exercise offline sends, reconnects, upload retries,
   optimistic replies, pagination and recycled media under poor connectivity.
3. **Voice and notifications:** complete background/cold-process, device-switch,
   reconnect and teardown checks on dedicated test hardware.
4. **Integrations:** project-owned OAuth/App Links and billing products remain
   separate from reconstructing the UI and transport. New Spotify account linking
   and unsupported captcha providers require their own confirmed integration work.
5. **UI coverage:** finish static view-binding migration and validate RTL, large
   fonts, tablet/landscape layouts, accessibility and dynamic profile palettes.
6. **Release checks:** reconcile the known channel-list MFA-row test expectation
   with the existing events separator before declaring that entire suite green.
7. **Minimum Android version:** resolve the historical API 21 contract against the
   current API 23 build; do not silently claim support for devices the APK excludes.
8. **Backend drift:** use sanitized runtime traffic to resolve uncertain HTTP and
   Gateway behavior. Source presence alone is not proof that a route still works.

## Reference Evidence

| Source | Use |
| --- | --- |
| Controlled runtime observations | Actual client, backend and device behavior |
| DEX/smali, `javap`, Vineflower output | Exact signatures, instructions, declaration order and missing JADX bodies |
| Manifest, resources and payloads | UI structure, platform contracts and serialization |
| Kotlin metadata and callers | Nullability, names and corroboration |
| Decompiled JADX Java | Navigation aid; verify incomplete or suspicious bodies against stronger evidence |

The local reference locations and detailed interpretation rules are in
[AGENTS.md](AGENTS.md). A broken decompile is an unresolved evidence gap, not
permission to invent behavior. Enum ordinals come from bytecode, and apparent
constructor defaults must be checked against synthetic constructor masks.

## Reconstruction Campaign

Work in complete vertical slices: dependencies and state first, then UI and
verification. When contributors work concurrently:

- Assign exclusive file ownership and agree on cross-package API names.
- Use `scripts/build.sh` for every Gradle invocation and wait for its lock.
- Coordinate Git operations; never stage or overwrite another contributor's work.
- Defer cross-package cleanup to its owner and leave files self-consistent.

### Definition of done

A slice needs reference/runtime evidence, complete state and UI behavior, a
meaningful regression check and the relevant successful build checks. Loading,
empty, error, lifecycle and accessibility behavior are part of the feature.
List anything unverified explicitly; placeholders do not count as parity.

Handoffs should name the changed paths, behavior, checks and unresolved issues.
For remaining work, give the target path, reference source and next action.
Keep durable conclusions in the relevant section of this plan, not a session log.

## Verification

Run from the repository root; use Git Bash on Windows:

```bash
bash scripts/build.sh :app:assembleDebug
bash scripts/build.sh :app:testDebugUnitTest --tests 'com.discord.utilities.updates.AppUpdateTest'
bash scripts/build.sh :app:lintDebug
bash scripts/build.sh :app:assembleRelease :app:generateReleaseUpdateManifest
node --test tools/update-worker/worker.test.mjs
```

Use `--offline` only when the required dependencies are cached. Broader tests
and release checks are required before closing their corresponding phases, not
after every small edit.

Preserve the user's installed app and login. Never uninstall, clear app data or
run connected/Orchestrator tests on the daily-use A065 (`188b1209`). Use a
dedicated emulator/device for instrumentation. Device updates are in-place;
an explicitly approved downgrade must also retain data. Stop on signing/version
conflicts rather than falling back to uninstalling.
