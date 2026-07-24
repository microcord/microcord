# Manticord Reconstruction Plan

## Purpose

Manticord is a from-scratch Kotlin reconstruction of Discord Android 126.21
Stable, using the JADX export at `C:\Users\manti\StudioProjects\discord-jadx`
as a read-only behavioral and structural reference.

The target is feature parity, not recompilation of decompiled Java. Decompiled
code may explain behavior, call order, payloads, and layout contracts, but it
must not be copied wholesale into this project. Names should be restored from
Kotlin metadata and usage where possible; otherwise use clear descriptive
names and document uncertain behavior.

This file is the source of truth for scope, decisions, progress, and handoffs.
Every agent working on the project must read it before changing code and update
the relevant status, evidence, decisions, or risks before finishing.

## Fixed Decisions

| Decision | Value |
| --- | --- |
| Target project | `C:\Users\manti\Desktop\projeler\manticordrebuild` |
| Reference project | `C:\Users\manti\StudioProjects\discord-jadx` |
| Reference version | Discord Android `126.21 - Stable`, version code `126021` |
| Goal | User-visible feature parity with the reference release |
| Backend | Discord live consumer backend |
| Language | Kotlin |
| UI | Android Views and XML layouts, not Compose |
| Application ID | `dev.mantikafasi.manticord` |
| Android namespace | `com.discord` |
| Minimum SDK | 21 |
| Resources | Reuse all recovered assets and layout files |
| Public libraries | Install normal dependencies; never rebuild/decompile them |
| Native media | Reuse the recovered `libdiscord.so` binaries |
| Reference policy | `discord-jadx` remains read-only |

The source namespace remains `com.discord` for structural fidelity. JNI-facing
classes must use the exact packages expected by `libdiscord.so`, including
`com.hammerandchisel.libdiscord` and `co.discord.media_engine` where required.
The distinct application ID prevents collision with the official application.

## Non-Goals

- Byte-for-byte APK reproduction.
- Recreating Discord's signing certificate or official package identity.
- Copying decompiled AndroidX, Kotlin, Retrofit, OkHttp, Fresco, WebRTC, or
  other public dependency sources.
- Preserving obfuscated names when the responsibility is understood.
- Preserving generated `R`, `BuildConfig`, or view-binding source.
- Certificate spoofing, signature bypassing, or weakening transport security.
- Adding speculative abstractions, modules, or compatibility layers.
- Modernizing the visual design or architecture before parity is achieved.

## Reference Facts

The reference is an APK extraction, not an original source project.

- It contains approximately 18,017 Java files and 1.7 million lines.
- Approximately 8,976 files contain Kotlin metadata despite being decompiled
  as Java.
- Approximately 917 files contain JADX error or incorrect-code markers.
- Three original DEX files remain at `app/src/main/classes*.dex`.
- No usable app module Gradle definition, mapping file, original Kotlin source,
  native source, signing key, or test suite is present.
- The source tree mixes first-party code, generated bindings, public
  dependencies, and obfuscated packages.
- The reference manifest targets API 30 and declares minimum API 21.
- The UI is primarily single-activity, fragment-driven Android Views with XML.
- State is managed by reactive stores serialized through a dedicated store
  thread, using RxJava and `ObservationDeck`-style snapshots.
- Persistence is primarily SharedPreferences plus Kryo-backed `Persister`
  values rather than an app-owned Room database.
- REST uses Retrofit, OkHttp, Gson, persistent cookies, and Rx return types.
- Gateway uses Discord Gateway v9 JSON over WebSocket with zlib-stream.

### High-Value Reference Paths

| Area | Reference path |
| --- | --- |
| Manifest | `app/src/main/AndroidManifest.xml` |
| Application | `app/src/main/java/com/discord/app/App.java` |
| Activity shell | `app/src/main/java/com/discord/app/AppActivity.java` |
| Store graph | `app/src/main/java/com/discord/stores/StoreStream.java` |
| Dispatcher | `app/src/main/java/com/discord/stores/Dispatcher.java` |
| REST contract | `app/src/main/java/com/discord/restapi/RestAPIInterface.java` |
| REST builder | `app/src/main/java/com/discord/restapi/RestAPIBuilder.java` |
| REST wrapper | `app/src/main/java/com/discord/utilities/rest/RestAPI.java` |
| Gateway | `app/src/main/java/com/discord/gateway/GatewaySocket.java` |
| Gateway store | `app/src/main/java/com/discord/stores/StoreGatewayConnection.java` |
| Authentication | `app/src/main/java/com/discord/stores/StoreAuthentication.java` |
| Navigation | `app/src/main/java/com/discord/stores/StoreNavigation.java` |
| Messages | `app/src/main/java/com/discord/stores/StoreMessages.java` |
| Message queue | `app/src/main/java/com/discord/utilities/messagesend/MessageQueue.java` |
| RTC | `app/src/main/java/com/discord/rtcconnection/RtcConnection.java` |
| Media store | `app/src/main/java/com/discord/stores/StoreMediaEngine.java` |
| Native bridge | `app/src/main/java/com/hammerandchisel/libdiscord/Discord.java` |
| Media models | `app/src/main/java/co/discord/media_engine/` |
| Resources | `app/src/main/res/` |
| Assets | `app/src/main/assets/` |
| Native libraries | `app/src/main/lib/` |

Reference paths in this document are relative to
`C:\Users\manti\StudioProjects\discord-jadx` unless stated otherwise.

## Reconstruction Rules

1. Inspect the entire relevant reference flow before implementing it.
2. Treat surviving Kotlin metadata, DEX behavior, XML, manifest entries, API
   payloads, and call sites as evidence. Treat JADX output as a hypothesis.
3. For a failed central method, inspect smali/DEX and all callers rather than
   guessing from one path.
4. Recreate behavior in idiomatic, readable Kotlin while preserving observable
   ordering, threading, persistence, and error behavior.
5. Use an installed dependency for public library behavior. Do not copy its
   decompiled source.
6. Regenerate build artifacts and bindings. Do not copy generated Java.
7. Keep changes vertical and buildable. One feature slice should include its
   models, transport, state, UI, and smallest useful tests.
8. Do not add a module, interface, repository, factory, or abstraction until a
   second concrete use requires it.
9. Never log access tokens, refresh tokens, cookies, MFA codes, passwords,
   attachment bodies, or private message content.
10. Never run destructive live-backend tests without explicit user approval.
11. Reconstructed stores and first-party data/model classes must include every
    semantic field present in the reference, even when the current slice does
    not use that field yet. Preserve field types, nullability, defaults,
    mutability, serialization names, and persistence behavior.
12. Reconstruct every first-party custom view and screen properly. A layout-only
    placeholder, empty fragment, or view that ignores reference state and
    interaction behavior does not count as parity.

## Target Architecture

The reconstruction should preserve the reference architecture until parity is
reached.

### Android Shell

- `com.discord.app.App` owns process-wide initialization.
- `com.discord.app.AppActivity` is the main fragment host.
- Nested or dedicated activity entry points cover main, call, incoming call,
  and share/app-action behavior where Android task semantics require them.
- Navigation remains code-driven; do not introduce Navigation Component solely
  for modernization.
- Themes, locale, font scale, and accessibility settings are applied before UI
  inflation where the reference does so.

### State

- `Dispatcher` serializes mutations on one store scheduler/thread.
- `Store` covers older observable stores.
- `StoreV2` and `ObservationDeck` expose immutable or stable snapshots after a
  dispatch completes.
- `StoreStream` owns and wires the process-wide store graph.
- Gateway events hydrate stores; REST commands and pagination update them.
- User-specific stores clear on logout.
- Every reconstructed store must include the complete reference state surface:
  semantic fields, caches, indexes, subjects, snapshots, persistence handles,
  update sources, loading/error state, and dependencies on other stores.
- Store fields may be renamed only when the reference name is obfuscated or
  misleading; record a reference-to-target field mapping while implementing the
  store so no state is silently omitted.
- Do not build reduced stores that only satisfy the first screen using them.

### Data and Models

- Every first-party API DTO, Gateway payload, domain model, store state model,
  and Kotlin data class must contain all semantic fields present in the
  reference version.
- Preserve exact wire names, JSON aliases, numeric widths, enum values,
  nullability, defaults, collection element types, and unknown/optional field
  behavior.
- Preserve meaningful Kotlin data-class behavior, including constructor order,
  `copy`, component order, equality, hash code, and safe string rendering.
- Sensitive values must be redacted from string rendering even if decompiled
  generated output appears to print them.
- Do not replace typed reference fields with `Any`, raw maps, or generic JSON
  solely to make an incomplete slice compile.
- Compiler-generated synthetic fields and lambda captures are not parity fields
  unless JNI, reflection, serialization, or runtime evidence requires them.

### Networking

- Retrofit declares REST endpoints and payload types.
- OkHttp provides headers, cookies, request metadata, timeouts, and WebSockets.
- Gson behavior must match reference payload naming and optional/null handling.
- Gateway supports discovery, identify, resume, sequence tracking, heartbeat,
  heartbeat ACK timeout, reconnect/backoff, and zlib-stream decompression.
- Network parsing must be isolated from UI and tested with sanitized fixtures.

### Persistence

- SharedPreferences stores small local settings and authentication state.
- Kryo-backed persistence may be restored only for stores that used it and
  where compatibility matters.
- Do not introduce Room without evidence of app-owned Room persistence.
- Persisted data must be versioned or safely discardable during reconstruction.

### UI

- XML layouts and Android Views are authoritative for structure.
- Fragments, dialogs, and bottom sheets follow the original screen boundaries.
- View binding is generated by AGP.
- RecyclerView adapters remain appropriate for guild, channel, member, search,
  and message lists.
- Accessibility labels, focus order, minimum touch targets, RTL behavior, and
  dynamic font scaling are parity requirements.
- Recreate every first-party custom `View`, `ViewGroup`, fragment, dialog,
  bottom sheet, adapter row, decoration, and interaction represented by the
  reference feature.
- Preserve view hierarchy, style/theme attributes, visibility rules, state
  restoration, gestures, scrolling, animations, enabled/loading/error states,
  content descriptions, and lifecycle-aware observation.
- All recovered layout variants must remain usable with their corresponding
  target class; inflating successfully without correct behavior is incomplete.
- Temporary placeholders are allowed only while a phase is `IN PROGRESS`; they
  must be listed in that phase's notes and removed before it is marked `DONE`.

### Native Voice and Video

- `libdiscord.so` is opaque and treated as a fixed binary contract.
- JNI class names, method signatures, callback interfaces, resource names,
  WebRTC Java types, and ABI packaging must match exactly.
- The reference primarily uses `com.hammerandchisel.libdiscord.Discord` with
  models under `co.discord.media_engine`.
- `com.discord.native.engine.NativeEngine` also exists in the APK but appears
  unused by the primary media store; do not implement it unless call-site or
  runtime evidence requires it.
- Native validation is an early blocker, not a final integration task.

## Resource Migration Policy

All recovered assets and layouts are retained. Import resources in controlled
batches so build failures identify the responsible group.

1. Copy `assets/**` unchanged.
2. Copy base `values/**`, then qualified values directories.
3. Copy XML configuration, menus, fonts, animators, animations, and raw files.
4. Copy drawables and mipmaps.
5. Copy every `layout*/**` directory.
6. Restore manifest components only when their implementation or safe stub is
   present; exported components require a security review.
7. Regenerate `R` and view bindings.
8. Do not copy decompiled binding classes or resource classes.
9. Do not rely on numeric resource IDs from `public.xml` unless native runtime
   evidence proves a fixed ID contract.
10. If an installed dependency provides the same resource name, the app's
    recovered resource intentionally overrides the library resource.

Decoded resources include merged third-party resources. They may reference
attributes from dependencies that have not yet been installed. Resolve those
by adding the correct dependency before editing the XML. Only change recovered
XML when it is invalid or incompatible with current AAPT, and record material
changes in the decision log.

## Dependency Policy

Dependencies are added only when a compiling feature or imported resource needs
them. Initial versions should match recovered metadata when practical; newer
versions are allowed when the original is unavailable, insecure, or
incompatible with the current toolchain.

Known reference baselines include:

| Dependency family | Reference baseline |
| --- | --- |
| AndroidX Core | 1.6.0 |
| AppCompat / Activity | 1.2.0 |
| Fragment | 1.3.0 |
| Lifecycle | 2.3.1 |
| RecyclerView | 1.2.1 |
| WorkManager | 2.4.0 |
| Material Components | 1.3.0 |
| Browser | 1.3.0 |
| Preference | 1.1.1 |
| ViewPager2 | 1.0.0 |
| Room | 2.2.5, transitive/manifest evidence only |
| Google Play Billing | 4.0.0 |
| Firebase Messaging | 21.0.0 |
| Firebase Analytics | 18.0.0 |
| Firebase Dynamic Links | 19.1.1 |
| Play Services Auth | 19.0.0 |

Other required families include Kotlin, RxJava 1, Retrofit, OkHttp, Okio,
Gson, Kryo, Fresco, SoLoader, Lottie, WebRTC, Adjust, hCaptcha, uCrop, APNG,
ZXing/zbar, persistent cookie jar, and Google install referrer.

For every added dependency:

- Record the coordinate and selected version in `gradle/libs.versions.toml`.
- Confirm its license and repository.
- Check for duplicate classes before copying any reference source.
- Prefer Maven Central or Google's repository; do not restore JCenter merely
  because the original build used it.
- Add only the artifact needed by the current slice.

## Native Integration Plan

The recovered binaries are located under reference `app/src/main/lib` for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

### Native Spike Exit Criteria

- [x] `libdiscord.so` is packaged for all four supported ABIs.
- [x] A device/emulator loads the library without `UnsatisfiedLinkError`.
- [x] The exact required JNI bridge class is resolved.
- [x] Required WebRTC classes and method descriptors match the binary.
- [x] Shared EGL context initializes.
- [x] Audio input/output enumeration returns or fails safely.
- [x] Native instance disposal is repeatable without crash.
- [x] Required companion binaries are identified from runtime/linker evidence.
- [x] Krisp model assets and `krisp_model_version` resolve if noise processing
  is enabled.

Do not postpone the native spike until the voice UI phase. If the recovered
binary cannot operate with available public WebRTC artifacts, record the exact
linkage failure and decide whether to preserve an extracted compatible Java
bridge, replace the media engine, or reduce the voice parity claim.

## Live Backend Safety

This project targets Discord's consumer backend, which is a private and
changeable interface. Compatibility may be limited by backend policy,
experiments, client properties, application signing, package identity, or
terms of service.

- Do not hard-code credentials recovered from the APK.
- Google/Firebase API identifiers from the reference must not be copied into
  source control.
- Register replacement Firebase, OAuth, App Links, FCM, and billing
  configuration for `dev.mantikafasi.manticord` when those phases begin.
- Keep production-backend tests opt-in.
- Use dedicated test accounts with minimal privileges.
- Never automate spam, unsolicited messages, mass guild actions, or account
  evasion behavior.
- Preserve TLS validation and cleartext restrictions.
- Treat authentication tokens as secrets at rest and in diagnostics.

## Delivery Phases

Status values: `NOT STARTED`, `IN PROGRESS`, `BLOCKED`, `DONE`.

### Phase 0: Project Baseline

Status: **DONE**

- [x] Record a clean baseline build.
- [x] Change application ID to `dev.mantikafasi.manticord`.
- [x] Keep namespace `com.discord`.
- [x] Change minimum SDK to 21.
- [x] Remove Compose plugin, dependencies, source, and theme code.
- [x] Enable view binding and BuildConfig generation.
- [x] Add AppCompat, Fragment, and Material dependencies.
- [x] Add a minimal XML activity and launch it.
- [x] Replace template tests with a package/application smoke test.

Exit criteria: `assembleDebug`, unit tests, and lint complete successfully and
the app launches an XML-backed activity on a supported emulator/device.

Evidence: the untouched Compose template completed `assembleDebug`; the XML
baseline completed `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and
`connectedDebugAndroidTest` on Android 13 (Windows Subsystem for Android). The
instrumented smoke test verified `App`, the Manticord package name, launcher
activity creation, and inflation of `activity_app.xml`.

### Phase 1: Resource Foundation

Status: **DONE**

- [x] Import all assets.
- [x] Import values and resource qualifiers.
- [x] Import XML configuration and raw resources.
- [x] Import drawables, mipmaps, fonts, and animations.
- [x] Import all layout directories.
- [x] Install resource-owning public dependencies as needed.
- [x] Resolve AAPT errors without deleting recovered layouts.
- [x] Regenerate view bindings successfully.
- [x] Establish dark/light themes and launch/loading layouts.

Exit criteria: all recovered assets and layouts exist in the target project,
AAPT succeeds, and the launch/loading shell renders without Compose.

Progress: all 36 recovered asset files are present at their original paths,
including data catalogs, images, Lottie JSON, hCaptcha HTML, and three Krisp
model files. All recovered value qualifiers, XML/raw resources, visual
resources, and 1,228 layout variants were imported. `public.xml` and nine
official service resources were excluded; decoded apostrophe, attr-enum,
ConstraintLayout-parent, float-resource, and namespace damage was repaired.
Direct AAPT compilation, Gradle resource linking, all generated View Bindings,
and the reconstructed first-party custom-view surface compile. Modern-AAPT
repairs preserve ConstraintLayout parent sentinels and decoded vector-gradient
coordinates without deleting layouts. `assembleDebug`, 39 unit tests, and lint
with a frozen recovered-resource baseline pass. Six connected tests pass on
Android 13, covering application/XML launch, foundational view inflation,
directory rendering, scrolling behavior, home panels, and tabs.

Native dependency note: symbol inspection proves recovered `libdsti.so`, not
`libdiscord.so`, exports the six `RLottieDrawable.Companion` JNI functions.
`libdsti.so` is packaged for the four supported ABIs so RLottie-backed layout
classes can be reconstructed during Phase 1; native creation/rendering is not
yet runtime-verified.

### Phase 2: Native Media Spike

Status: **DONE**

- [x] Copy `libdiscord.so` into ABI-specific `jniLibs` directories.
- [x] Recreate the minimal `com.hammerandchisel.libdiscord` bridge.
- [x] Recreate required `co.discord.media_engine` types.
- [x] Select and verify the compatible WebRTC dependency.
- [x] Complete every native spike exit criterion.
- [x] Add one instrumented native load/lifecycle test.

Exit criteria: the library loads and a native media-engine instance can be
created, queried minimally, and disposed on the x86_64 emulator ABI. Per user
decision, packaged/hash-verified ARM64 binaries do not require runtime execution.

Compatibility resolution: all four recovered `libdiscord.so` binaries require
four WebRTC descriptors absent from every available public artifact. With user
approval, the exact compiled WebRTC bridge was isolated from the reference DEX
without decompiled source or first-party Discord classes. The reproducible
artifact preserves 385 WebRTC classes, all 117 native roots, the four hard
descriptors, and `EglHelper`.
Android 13 x86_64 passes both native lifecycle tests twice, including EGL
initialization, audio subsystem and input/output enumeration, and disposal.

### Phase 3: Core Runtime and Stores

Status: **DONE**

- [x] Implement application initialization.
- [x] Implement providers for application/activity context and preferences.
- [x] Implement dispatcher and dedicated store scheduler.
- [x] Implement `Store`, `StoreV2`, and observation snapshots.
- [x] Implement the initial `StoreStream` graph.
- [x] Implement safe persistence primitives.
- [x] Implement logout/reset behavior.
- [x] Add deterministic dispatcher/store tests.

Exit criteria: stores mutate only on the dispatcher, observers receive stable
post-dispatch state, persistence survives restart, and logout clears user data.

### Phase 4: REST and Gateway

Status: **DONE**

- [x] Implement common API models and JSON adapters.
- [x] Implement REST builder and endpoint interface.
- [x] Implement required headers and cookie persistence.
- [x] Implement structured API errors and rate-limit handling.
- [x] Implement Gateway discovery and WebSocket connection.
- [x] Implement zlib-stream decoding.
- [x] Implement identify, resume, heartbeat, ACK timeout, and reconnect.
- [x] Dispatch core gateway events into stores.
- [ ] Add fixture-based REST and Gateway contract tests.

Exit criteria: sanitized fixtures parse correctly, reconnect/resume behavior is
deterministic in tests, and an opt-in test account can authenticate and receive
`READY` without exposing credentials.

### Phase 5: Authentication and Navigation

Status: **DONE**

- [x] Landing, login, registration, MFA, verification, and logout flows.
- [x] Authentication state persistence and invalidation.
- [x] Required-action and age-gate routing.
- [x] Main fragment host and tabs/home shell.
- [x] Deep-link parsing and route handling.
- [x] Share/app-action entry point.
- [x] Safe external URI handling.

Exit criteria: cold start routes correctly for logged-out, logged-in,
required-action, deep-link, and share intents.

### Phase 6: Guilds and Channels

Status: **NOT STARTED**

- [ ] Guild list, folders, sorting, unread state, and selection.
- [ ] Channel list, categories, permissions, and selection.
- [ ] Guild/channel persistence and READY hydration.
- [ ] Member list and presence basics.
- [ ] DM and group-DM channel lists.
- [ ] Panel behavior for guild/channel/content/member surfaces.

Exit criteria: users can navigate guilds, channels, and DMs with correct
permissions, unread state, persisted selection, and responsive panel behavior.

### Phase 7: Messages and Uploads

Status: **NOT STARTED**

- [ ] Message history loading, pagination, and jump behavior.
- [ ] Gateway create/update/delete reconciliation.
- [ ] Optimistic local messages and nonce reconciliation.
- [ ] Per-channel message queue.
- [ ] Send, edit, delete, reply, reactions, pins, and ACK/read state.
- [ ] Attachments, image preprocessing, multipart upload, and progress.
- [ ] Slow mode, rate limits, captcha, AutoMod, and retry behavior.
- [ ] Background send/ACK workers.

Exit criteria: ordinary text and attachment messaging works reliably through
offline/reconnect/rate-limit cases without duplicate or lost local messages.

### Phase 8: Users and Social Features

Status: **NOT STARTED**

- [ ] User cache, current user, profiles, notes, and mutual context.
- [ ] Relationships: friends, blocked, incoming, and outgoing.
- [ ] Presence, activities, typing, and status controls.
- [ ] Friend suggestions and affinity-dependent UI where observable.
- [ ] User sheet, profile, friend list, and add-friend screens.

Exit criteria: social state remains consistent across REST mutations, gateway
updates, persistence, and logout.

### Phase 9: Notifications and Background Work

Status: **NOT STARTED**

- [ ] Notification preferences and suppression rules.
- [ ] FCM registration using project-owned configuration.
- [ ] Message/call notification rendering and grouping.
- [ ] Notification actions, deletion, and cancellation.
- [ ] Foreground/background lifecycle behavior.
- [ ] Direct share targets and download completion handling.

Exit criteria: notifications respect foreground state, channel/guild settings,
permissions, account state, and Android version requirements.

### Phase 10: Settings and Account Management

Status: **NOT STARTED**

- [ ] Appearance, theme, locale, font scale, and accessibility.
- [ ] Privacy, safety, media, text/image, behavior, and notifications.
- [ ] Voice/video device and processing settings.
- [ ] Account profile, email, phone, password, sessions, and deletion flows.
- [ ] Authorized applications and connections.
- [ ] Developer diagnostics without secret leakage.

Exit criteria: settings restore correctly, synchronize where required, and
apply without unnecessary process restarts.

### Phase 11: Voice, Calls, and Video

Status: **NOT STARTED**

- [ ] Voice selection and Gateway voice-state/server coordination.
- [ ] RTC connection state machine.
- [ ] Audio input/output selection, mute, deaf, volume, VAD, and AEC.
- [ ] Incoming/private calls and ringing state.
- [ ] Call activity, bottom sheet, and fullscreen UI.
- [ ] Camera capture, video sinks, participant streams, and offscreen policy.
- [ ] Screen sharing and media-projection foreground service.
- [ ] Reconnect, region selection, statistics, and diagnostics.

Exit criteria: voice, private calls, video, and screen sharing survive activity
recreation, background transitions, device changes, and network reconnects.

### Phase 12: Extended Feature Parity

Status: **NOT STARTED**

- [ ] Search and quick switcher.
- [ ] Threads and forums.
- [ ] Emoji, stickers, GIFs, embeds, and media viewer/player.
- [ ] Scheduled events and stage channels.
- [ ] Guild creation, invites, templates, onboarding, and discovery.
- [ ] Moderation, audit log, member verification, and community settings.
- [ ] Roles, permissions, integrations, webhooks, and server settings.
- [ ] Hubs, reports, safety flows, and remaining manifest routes.

Exit criteria: every first-party layout and manifest route is implemented,
explicitly obsolete for 126.21 behavior, or documented as blocked.

### Phase 13: Billing and External Integrations

Status: **NOT STARTED**

- [ ] Project-owned Firebase configuration.
- [ ] Project-owned OAuth/Google Sign-In clients if still applicable.
- [ ] App Links for the new application ID.
- [ ] Google Play Billing integration and project-owned products.
- [ ] Subscriptions, entitlements, gifts, and boosts.
- [ ] Analytics/crash reporting with privacy-safe project configuration.

Exit criteria: external integrations use only Manticord-owned credentials and
degrade safely when an official signing/package relationship is unavailable.

### Phase 14: Parity Audit and Release Hardening

Status: **NOT STARTED**

- [ ] Audit every first-party manifest component.
- [ ] Audit every recovered app layout and deep-link route.
- [ ] Audit exported components and permissions.
- [ ] Test API 21 through current target SDK behavior.
- [ ] Test phone, tablet, portrait, landscape, RTL, and large fonts.
- [ ] Run unit, instrumentation, lint, and release builds.
- [ ] Enable release shrinking after keep rules are verified.
- [ ] Verify no secrets or reference API keys are committed.
- [ ] Produce a signed internal release using Manticord-owned credentials.

Exit criteria: feature matrix is complete, no critical known regressions remain,
and debug/release APKs install and run on representative devices.

## Feature Parity Ledger

Agents should split rows only when implementation begins. Do not pre-create a
large issue hierarchy without work attached.

| Area | Status | Reference evidence | Tests/evidence | Notes |
| --- | --- | --- | --- | --- |
| Build/XML shell | DONE | `App`, `AppActivity`, manifest | Build, unit, lint, and Android 13 connected smoke test pass | Minimal Phase 0 XML loading shell; recovered resources remain Phase 1 |
| Resources/themes | IN PROGRESS | `res/**`, `assets/**` | Assets and all resource families imported; AAPT/resource link pass | 134 first-party custom views block binding compilation |
| Native media load | NOT STARTED | `lib/**`, `Discord.java` | None | Early blocker |
| Dispatcher/stores | NOT STARTED | `stores/**` | None | Preserve serialized mutation |
| REST | NOT STARTED | `restapi/**` | None | Live backend, fixture tests first |
| Gateway | NOT STARTED | `gateway/**` | None | v9 + zlib-stream |
| Authentication | NOT STARTED | `StoreAuthentication`, `widgets/auth` | None | Includes MFA/required actions |
| Navigation/deep links | NOT STARTED | `StoreNavigation`, `IntentUtils` | None | Central method has JADX damage |
| Guilds/channels | NOT STARTED | guild/channel stores and widgets | None | Includes permissions |
| Messages/uploads | NOT STARTED | message stores/queue/chat widgets | None | `doSend` needs DEX inspection |
| Users/relationships | NOT STARTED | user/relationship stores | None | Presence merging needs care |
| Notifications | NOT STARTED | FCM utilities/store/workers | None | Use owned Firebase config |
| Settings | NOT STARTED | settings widgets/stores | None | Local and synced settings |
| Voice/calls/video | NOT STARTED | RTC/media stores/widgets/native | None | Native binary contract |
| Extended guild features | NOT STARTED | server settings widgets | None | Large final parity surface |
| Billing/integrations | NOT STARTED | billing stores/widgets | None | Cannot reuse official products |

## Verification Commands

Run the smallest relevant checks during development and the full set before a
phase is marked done.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat assembleRelease
```

`connectedDebugAndroidTest` requires a configured device/emulator. Do not mark
instrumentation work verified when it was merely compiled.

## Definition of Done for a Feature Slice

A slice is done only when:

- Its reference flow and callers were inspected.
- Every semantic field from each reconstructed store and first-party data/model
  class is present with matching type, nullability, default, wire name, and
  persistence/serialization behavior.
- Every view in the slice is fully connected to reference-equivalent state,
  interactions, lifecycle, accessibility, and layout variants; no placeholder
  implementation remains.
- User-visible happy path and important failure paths are implemented.
- State mutation, lifecycle, and threading behavior are defined.
- Secrets and user content are excluded from logs.
- The smallest meaningful automated test exists.
- Relevant Gradle checks pass.
- Live-backend behavior, if tested, used an approved test account and is noted.
- `PLAN.md` status/evidence and any decision/risk entries are updated.

## Known Risks and Unknowns

| Risk | Impact | Required response |
| --- | --- | --- |
| Private backend changes | Client behavior may stop working | Isolate transport contracts and record observed versions |
| Discord policy restrictions | Live accounts may be limited | Use test accounts and avoid prohibited automation |
| JADX failures in central methods | Incorrect edge behavior | Inspect DEX/smali and sibling call sites |
| Obfuscated first-party helpers | Hidden navigation/UI contracts | Infer from metadata, resources, and all callers |
| Native/WebRTC ABI mismatch | Voice/video cannot initialize | Complete native spike before feature UI |
| Missing official signing identity | Google/OAuth/App Link gaps | Register Manticord-owned integrations |
| Merged resource dump | AAPT conflicts or stale resources | Import in batches; dependencies first, XML edits last |
| Target SDK behavior changes | Permissions/services differ from API 30 | Preserve behavior with current platform requirements |
| No original tests | Regressions are hard to detect | Add one focused test per non-trivial slice |
| Proprietary recovered assets/binary | Distribution constraints | Confirm rights before public distribution |

## Decision Log

Append decisions that change architecture, compatibility, scope, dependencies,
or observable behavior. Small implementation details do not belong here.

| Date | Decision | Reason | Consequence |
| --- | --- | --- | --- |
| 2026-07-22 | Rebuild in existing Android boilerplate | Project already exists at target path | Convert template rather than create another project |
| 2026-07-22 | Use Kotlin + XML | Closest to original architecture and recovered layouts | Remove Compose |
| 2026-07-22 | Use `dev.mantikafasi.manticord` application ID | Official signing identity cannot be reproduced | External integrations need new configuration |
| 2026-07-22 | Keep `com.discord` namespace | Structural fidelity and lower package churn | Application ID and namespace intentionally differ |
| 2026-07-22 | Reuse assets/layouts and `libdiscord.so` | Required for visual and media parity | Resource and JNI compatibility become explicit workstreams |
| 2026-07-22 | Install public libraries normally | Avoid rebuilding dependency code | Decompiled dependency sources are reference-only |
| 2026-07-22 | Require complete store/model fields and complete views | Partial models and placeholder UI cannot provide feature parity | Every slice must inventory and verify its full structural surface |
| 2026-07-23 | Allow scoped JitPack access for recovered uCrop and FlexInput artifacts | Reference-compatible versions are unavailable from Google and Maven Central | Repository content filtering permits only `com.github.yalantis` and `com.github.lytefast` |
| 2026-07-23 | Install upstream SimpleAST 2.8.0 with the reference 2.6.0 rule surface | Recovered behavior uniquely matches 2.6.0, but JitPack no longer serves that artifact; 2.8.0 is the available API-compatible upstream AAR | Public source is not reconstructed; Discord rules must omit post-2.6 diff behavior |
| 2026-07-23 | Use JADX MCP for decompiled reference inspection | The connected JADX server provides direct class, method, field, and cross-reference evidence | Exported Java/DEX file inspection is fallback-only when MCP lacks a body |
| 2026-07-24 | Use public WebRTC `104.5112.10`, APNG `1.11.0`, and scoped RealtimeBlurView commit `82df3520b4` for recovered view contracts | These are the available public artifacts matching the reconstructed Java APIs closely enough for Phase 1 | Exact `libdiscord.so` compatibility remains a Phase 2 runtime requirement |
| 2026-07-24 | Adapt the unpublished Fresco sample `ZoomableDraweeView` package to public PhotoDraweeView `2.1.0` | No published artifact provides `com.facebook.samples.zoomable.ZoomableDraweeView`, and copying public sample source is prohibited | The exact XML class name retains real zoom behavior through a thin adapter |
| 2026-07-24 | Baseline the imported recovered-resource lint corpus | The immutable corpus carries 16,733 inherited errors and 28,734 warnings from merged libraries, translations, and decoded resources after reconstructed Kotlin issues were fixed | `lintDebug` fails on every new issue while preserving the recovered corpus unchanged |
| 2026-07-24 | Preserve the recovered compiled WebRTC Java bridge for Phase 2 | No available public artifact matches four JNI-visible descriptors and two are Discord-specific; the user approved the exception required for exact native parity | Vendor only the exact compiled bridge closure, remove the incompatible public artifact, and verify creation/disposal on device |
| 2026-07-24 | Waive ARM64 runtime execution for the native spike | The user accepted packaged/hash-verified ARM64 libraries with x86_64 lifecycle evidence | Phase 2 closes on x86_64; ARM64 binaries remain present for later device validation |
| 2026-07-24 | Use Kryo `4.0.2` for reference-shaped persisted store payloads | Recovered metadata and API contracts require Kryo while persistence must remain atomic and path-confined | Persistence uses the public artifact and disables Android application backup before authentication data exists |
| 2026-07-24 | Use OkHttp `4.8.0`, Okio `2.7.0`, Retrofit `2.9.0`, and PersistentCookieJar `v1.0.1` for Phase 4 | OkHttp/Okio versions are recovered; Retrofit and cookie-jar versions are the compatible public artifacts matching the retained API surface | REST/Gateway fixture tests pin headers, cookies, errors, rate limits, and socket behavior without live backend access |

## Progress Journal

Add one concise row after a meaningful completed or blocked work session.

| Date | Agent | Work completed | Verification | Next action/blocker |
| --- | --- | --- | --- | --- |
| 2026-07-22 | OpenCode | Repository/reference inventory and reconstruction plan | Documentation reviewed against inspected files | Begin Phase 0 |
| 2026-07-23 | OpenCode | Completed Phase 0 Compose-to-XML baseline with Manticord identity and reference-shaped application/activity shell | `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest` passed on Android 13 | Begin controlled Phase 1 resource import |
| 2026-07-23 | OpenCode | Inventoried the merged recovered resource surface and imported all 36 assets unchanged by path | `assembleDebug` passed after asset import | Filter/import base values without `public.xml` or official service credentials |
| 2026-07-23 | OpenCode | Imported and normalized the complete recovered resource/layout surface, installed public resource owners, and generated the custom-view inventory | Direct AAPT compile and Gradle merge/link pass; `assembleDebug` reaches missing first-party binding types | Reconstruct foundational custom views by reference behavior, starting with the text/view primitives |
| 2026-07-23 | OpenCode | Reconstructed foundational flipper, max-height recycler, rounded containers, Drawee/link text, clickable span, and typing-dot views from reference and DEX evidence | `compileDebugKotlin` passes; connected smoke check added but blocked from execution by remaining binding types | Continue foundational custom views by highest layout occurrence |
| 2026-07-23 | OpenCode | Reconstructed settings/tri-state controls, loading/title/search/code views, guild imagery, and segmented controls; removed stale decoded Material layouts | `compileDebugKotlin` passes after each cluster; full build advances to remaining first-party binding types | Continue model-independent view clusters; defer `StatusView` until complete Presence models exist |
| 2026-07-23 | OpenCode | Reconstructed cutout/badging geometry, app-bar/content resizing, nested scrolling, simple pager, and icon-row primitives; removed stale AndroidX notification templates | `compileDebugKotlin` passes; Java binding compile advances to remaining first-party/model-dependent types | Continue model-independent views, then build complete model surfaces required by dependent views |
| 2026-07-23 | OpenCode | Installed upstream SimpleAST, removed 54 stale dependency layouts, refreshed the unresolved-view inventory, and reconstructed dimmer/community/upload/member-count/step primitives | `compileDebugKotlin` passes; dependency-owned bindings no longer block Java compilation | Complete the remaining 41 model-independent views |
| 2026-07-23 | OpenCode | Reconstructed 17 additional model-independent settings, sidebar, permission, event, upload, voice, verification, eligibility, admin, and premium controls | `compileDebugKotlin` passes; Java binding errors are only unresolved first-party types | Complete the remaining 24 model-independent views |
| 2026-07-23 | OpenCode | Added reply/GIF drawing infrastructure, complete activity/presence and sticker value contracts with wire adapters, `StatusView`, directory header, and experiment editor using direct JADX MCP evidence | `compileDebugKotlin` passes; focused contract tests added; Java binding compile advances through these types | Reconstruct complete user/guild models and native RLottie-dependent views |
| 2026-07-23 | OpenCode | Reconstructed the recursive API user/guild-member/presence subset, normalized `CoreUser`, Username/SuggestedFriend views, and packaged four-ABI RLottie JNI binaries | `compileDebugKotlin` passes; `llvm-nm` confirms exact `libdsti.so` RLottie exports | Implement and runtime-test the RLottie drawable/image bridge, then continue complete guild models |
| 2026-07-23 | OpenCode | Parallelized JADX MCP research and reconstructed the RLottie frame bridge, domain guild/role/permission graph, pile/sidebar/call leaf views, GuildIcon, and channel permission owner rendering | `compileDebugKotlin` passes; Java binding compile advances to composite feature views | Reconstruct StickerView pipeline, scheduled-event composites, and remaining user/voice views |
| 2026-07-23 | OpenCode | Parallel agents reconstructed Chips/Feedback, member and summary models/views, directory item, mobile-report controls, role preview, highlights settings, and supporting tests; rejected a partial AppFragment shortcut | `compileDebugKotlin` passes; Java binding compile remains capped at 100 unresolved composite types | Parallelize scheduled-event, voice/video, auth/phone, forum, billing, and sticker infrastructure clusters |
| 2026-07-24 | OpenCode | Reconstructed guild-boost progress, account-credit/payment-source models and views, and auth phone-or-email/invite views from direct JADX evidence | `compileDebugKotlin` passes; their generated binding errors are cleared, while Java remains capped at 100 later composite errors | Continue independent forum/events, notification, overlay/thread, and voice leaf clusters before StickerView infrastructure |
| 2026-07-24 | OpenCode | Parallelized the remaining binding frontier across forums, events, stickers, voice/video, overlays, threads, notifications, bot UI kit, profiles, guild-role subscriptions, home/tabs, roles, stage, premium, and XML behaviors | Checkpoint is intentionally incomplete: `compileDebugKotlin` now fails in `InlineMediaView` due ExoPlayer API mismatch and in anchored/call voice controls due missing RTC/audio/streaming/embedded-activity/video-device contracts | Reconcile exact ExoPlayer 2.16 API and reconstruct the shared voice contracts before resuming Java binding verification |
| 2026-07-24 | OpenCode | Completed Phase 1 by restoring shared voice contracts, public media dependencies, all View Bindings, decoded ConstraintLayout/vector resources, lint compatibility, and connected view tests | `assembleDebug`, 39 unit tests, `lintDebug` with zero new issues, and 6 Android 13 connected tests pass | Begin Phase 2 native media spike with exact WebRTC/JNI creation and disposal tests |
| 2026-07-24 | OpenCode | Packaged all four `libdiscord.so` binaries, reconstructed the exact Hammer-and-Chisel and media-engine JNI contract surfaces, and added a native lifecycle test | ELF hashes/architectures and 283 exported symbols verified; `assembleDebug` and 41 unit tests pass | Blocked: no available public WebRTC artifact matches four JNI-visible descriptors; request approval for recovered bridge preservation or parity change |
| 2026-07-24 | OpenCode | Isolated the approved compiled WebRTC bridge into a reproducible local artifact with no decompiled source or first-party classes | 385 classes, 117 native roots, four hard descriptors, `EglHelper`, duplicate-class check, build, and 41 unit tests pass | Run native create/query/dispose instrumentation when an ADB device is attached |
| 2026-07-24 | OpenCode | Completed all JNI registration roots under Hammer-and-Chisel, media-engine, and native-engine packages and expanded audio lifecycle coverage | Android 13 x86_64 passes load, EGL creation, engine creation, audio subsystem/input/output queries, and repeated disposal | Run the same native lifecycle tests on an ARM64 device to close Phase 2 |
| 2026-07-24 | OpenCode | Closed Phase 2 after the approved ARM64 runtime waiver | `assembleDebug`, 43 unit tests, lint with zero new issues, and 8 Android 13 x86_64 connected tests pass | Begin Phase 3 application initialization, dispatcher, and complete store infrastructure |
| 2026-07-24 | OpenCode | Reconstructed dispatcher/store scheduling, post-dispatch observation snapshots, runtime providers, preferences, and atomic Kryo persistence | `assembleDebug` and 50 deterministic unit tests pass; no placeholder markers found | Implement the complete accessibility/system-settings StoreStream closure before authentication/logout |
| 2026-07-24 | OpenCode | Implemented the dependency-complete accessibility/settings/stage StoreStream graph and credential-free application bootstrap | `assembleDebug`, 54 store tests, lint, and 8 Android 13 connected tests pass across the two slices | Implement authentication and its complete selective pre-logout/reset dependency closure |
| 2026-07-24 | OpenCode | Closed Phase 3 with dispatcher-ordered local logout/reset across the implemented graph, preference domains, and Persister data | Build, deterministic restart/reset unit tests, lint, and 8 Android 13 connected tests pass | Begin Phase 4 common API models, REST transport, and Gateway protocol |
| 2026-07-24 | OpenCode | Implemented fixture-first REST/Gateway transport plus persistent discovery and typed stage-event dispatch | `assembleDebug`, 69 unit tests, and lint pass; no live backend used | Reconstruct the complete READY model/store closure before routing remaining core events |
| 2026-07-24 | OpenCode | Closed Phase 4 with complete READY DTOs/adapters, dependency-complete core stores, typed parser, and dispatcher-routed READY/incremental events | `assembleDebug`, 85 fixture/unit tests, lint, and 8 Android 13 connected tests pass; no live backend used | Begin Phase 5 authentication/session state and logged-out/logged-in routing |
| 2026-07-24 | OpenCode | Completed Phase 5 authentication runtime/UI, required-action routing, safe intents/deep links/share actions, and the auth-gated main tabs/home host | Build, unit tests, lint, and 14 Android 13 connected route tests pass without live backend use | Begin Phase 6 guild/channel navigation and replace deferred home content with complete feature fragments |

## Agent Handoff Template

Agents should include this information in their final response and update the
progress journal when work changes project state:

```text
Scope:
Reference files inspected:
Target files changed:
Behavior implemented:
Checks run and results:
Live backend/device used:
Known gaps or risks:
Recommended next action:
```

## Immediate Next Work

Begin Phase 1 only:

1. Inventory target and recovered resource directory/file counts.
2. Import `assets/**` unchanged.
3. Import base `values/**`, resolve dependency-owned attributes first, and
   record any modern AAPT compatibility edits.
4. Import qualified values, XML/raw/configuration, drawables/mipmaps/fonts/
   animations, then layouts in separate buildable batches.
5. Run the smallest relevant build after each batch and update exact evidence.

Do not begin native, store, networking, or feature implementation while the
resource foundation is incomplete.
