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
  heartbeat ACK timeout, reconnect/backoff, and zstd-stream decompression.
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

- Do not hard-code account credentials, tokens, cookies, or private
  service-account keys recovered from the APK.
- By explicit user decision, Discord's client-side Firebase configuration may
  be reused for FCM parity. Firebase Admin/service-account credentials remain
  prohibited.
- Register replacement OAuth, App Links, and billing configuration for
  `dev.mantikafasi.manticord` when those phases begin.
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

Status: **IN PROGRESS** (reopened 2026-07-26 by parity audit)

Reopened because the 2026-07-26 audit found two structural resource defects that
inflation success had masked. See `docs/parity-audit/audit-views.md`.

- [ ] Restore the stripped `<enum>`/`<flag>` children in `res/values/attrs.xml`.
      The reference declares 518 `<enum>` and 130 `<flag>` children; the target
      has 34 and 0. AAPT therefore stores the layout value as a raw string and
      `TypedArray.getInt()` returns the default. `cs_view_type` is dead across
      **39 layouts**, so every settings radio/switch renders as a checkbox with
      the wrong accessibility role; `cvv_character_input_type`, `cutout_style`,
      `format_type`, `image_style`, and `cap_style` are also dead.
- [ ] Reconstruct the 7 missing first-party view classes: `ToolbarTitleLayout`,
      `TooltipManager`, `DefaultTooltipCreator`, `RadioManager`,
      `OverlayMenuBubbleDialog`, `OAuthPermissionViews`,
      `UserAvatarPresenceViewController`.
- [ ] Drive the 32 custom views that are inflated by XML but never driven from
      Kotlin, and fix the 13 confirmed view behavior defects.

Confirmed good: all 134 distinct `com.discord.*` element names across every
layout qualifier resolve to a target class with an `AttributeSet` constructor.
There is no inflation crash in the view surface.

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

Status: **IN PROGRESS** (reopened 2026-07-26 by parity audit)

Reopened because the store graph is a quarter of the reference and the shared
store infrastructure is missing. See `docs/parity-audit/audit-stores-core.md`
and `docs/parity-audit/audit-stores-gap-inventory.md`.

- [ ] `StoreStream` constructs **36 of 145** reference stores; `storesV2` holds
      **29 of 106**. 99 reference stores are MISSING and 37 are PARTIAL.
- [ ] `StoreStream.deferredInit(Application)` has no equivalent: ~57 `init(ctx)`
      calls, `NetworkMonitor`, the initialization timer, and the backgrounded
      subscription are all absent.
- [ ] `stores/utilities/` is **100% missing** (9 classes): `RestCallState` and
      its operators, `BatchManager`/`Batched`, and `leadingEdgeThrottle`. Every
      REST-backed store hand-rolls its own loading/error enum, and store
      observables are unthrottled.
- [ ] `StoreV2` thread assertion **throws** where the reference only logs,
      including inside `MarkChangedDelegate.setValue` where the reference asserts
      nothing. Off-thread `markChanged` crashes the app.
- [ ] `ObservationDeck` lost `logger`/`logBreadcrumb`/`logNotifyError` and added
      a try/catch that swallows observer exceptions the reference propagates.
- [ ] `ReadyPayloadHydrator` drops the `HydrateResult` error branch, carries 5 of
      22+ cached guild scalars across re-READY, and has no per-channel merge, so
      `lastMessageId` is lost and unread state is wrong after reconnect.
- [ ] `resetForLogout()` and the `reset()` methods on `StoreAccessibility` /
      `StoreUserSettingsSystem` are target inventions with no reference
      counterpart; `handlePreLogoutInternal` conflates pre-logout with a full
      cache reset the reference does not perform.

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

Status: **IN PROGRESS** (reopened 2026-07-26 by parity audit)

Reopened because the transport surface is a sixth of the reference and 44
gateway events are parsed as untyped JSON. See
`docs/parity-audit/audit-models-api.md` and
`docs/parity-audit/audit-stores-voice-gateway.md`.

- [ ] REST: **42 of 264** unique verb+path endpoints (15.9%). 222 missing —
      guild/channel/role/member CRUD, threads, forums, interactions, commands,
      stage instances, scheduled events, MFA, billing, connections, hubs,
      reporting. Full list in `docs/parity-audit/missing_endpoints.txt`.
- [ ] `RestAPIParams`: **17 of 107** request bodies.
- [ ] Gateway: **44 of 83** dispatch events routed. `IncomingParser.kt:91` has an
      `else -> data.deepCopy()` fallback delivering the other 39 as raw
      `JsonElement`; the reference has no untyped fallback. Voice, threads,
      calls, interactions, streams, bans, scheduled events, and required-action
      updates all arrive and are discarded.
- [ ] `OutgoingPayload` (16 nested types) is missing entirely; 13 `GatewaySocket`
      send methods absent, so 9 of 21 opcodes are dead.
- [ ] No `StoreGatewayConnection` store wrapper: no observable connection state,
      no `ClientState` backgrounded socket closing, no `BatchManager` voice-state
      coalescing, and `dispatchSubscribe` lost `onBackpressureBuffer`.
- [ ] `StoreConnectivity` is absent entirely — there is no network state source
      anywhere in the target.
- [ ] `StoreClientDataState` absent; `StoreStream.kt:442` hardcodes
      `IdentifyClientState(emptyMap(), 0, -1, -1)`, permanently disabling
      guild-hash resume.
- [ ] `TypeAdapterRegistrar` registers 20 of 39 reference adapters. `EmbedType`
      is unregistered, so **every embed's `type` deserialises to `null`**.
- [x] Add fixture-based REST and Gateway contract tests.

- [x] Implement common API models and JSON adapters.
- [x] Implement REST builder and endpoint interface.
- [x] Implement required headers and cookie persistence.
- [x] Implement structured API errors and rate-limit handling.
- [x] Implement Gateway discovery and WebSocket connection.
- [x] Implement zstd-stream decoding.
- [x] Implement identify, resume, heartbeat, ACK timeout, and reconnect.
- [x] Dispatch core gateway events into stores.
- [ ] Add fixture-based REST and Gateway contract tests.

Exit criteria: sanitized fixtures parse correctly, reconnect/resume behavior is
deterministic in tests, and an opt-in test account can authenticate and receive
`READY` without exposing credentials.

### Phase 5: Authentication and Navigation

Status: **IN PROGRESS** (reopened 2026-07-26 by parity audit)

Reopened because only token login actually reaches the store, and the four base
classes every screen should inherit from do not exist. See
`docs/parity-audit/audit-widgets-shell-auth.md` and
`docs/parity-audit/audit-stores-auth-nav.md`.

- [ ] **All non-token authentication is dead.** `AppActivity.kt:188-204` handles
      only `TokenLogin`; every other path returns "Authentication is not
      initialized.". `StoreAuthentication.login/register/authMFA/forgotPassword/
      resendVerificationEmail` have **zero production callers** — `authSubmitter`
      is assigned only by an instrumentation test.
- [ ] `AppFragment`, `AppBottomSheet`, `AppDialog`, `AppLog`/`AppLogger`, the
      screen router, and the priority back-press dispatcher **do not exist**.
      Lost contracts: `onViewBound`, unsubscribe-on-pause, the action bar API,
      keyboard show/hide, priority back handling, permission delegates, and the
      media-picker `onActivityResult` demux. `AppComponent` has zero implementors.
      Back handling is now closed: `BackPressHandlerRegistry` reproduces the
      reference's priority map, and `WidgetTabsHost`/`WidgetHome` register through
      `AppFragment.setOnBackPressed`. Remaining: `WidgetChatInput` and
      `WidgetChannelPinnedMessages` still use bespoke `OnBackPressedCallback`s.
- [ ] `WidgetTos` does not exist, but `AppActivity.kt:152` routes the
      `AGREEMENTS` / `TOS_UPDATE_ACKNOWLEDGMENT` required actions to it by class
      name — **any affected account crashes on the main route transaction**.
- [ ] `StoreUserRequiredActions` absent; `StoreNavigation.kt:78` derives the
      required action from `isUpdatePasswordRequired` only, so TOS, agreements,
      verified email/phone, and captcha gates are unreachable.
- [ ] MFA challenge is treated as a successful login (`StoreAuthentication.kt:83`
      dispatches unconditionally; the reference short-circuits on
      `loginResult.getMfa()`). `StoreMFA` and `StorePhone` are missing.
- [ ] Logout does not wipe prior-account state (`StoreAuthentication.kt:171-174`
      omits the reference's `Persister.reset()` + `prefs.edit().clear()`).
- [ ] Age gate can never fire: `getShouldShowAgeGate()` is absent and
      `AuthNavigationState.ageGate` is `null` at every call site.
- [ ] `StoreNavigation` is missing `ModelGlobalNavigation` (14 fields), the
      activity lifecycle callbacks, `init(Application)`, `launchNotice`, and 9 of
      its 12 navigation handlers.
- [x] OAuth authorize and remote-auth/QR login. `WidgetQRScanner` is reachable
      from the settings `qr_scanner` row and from a `/ra/` link with no usable
      fingerprint; `/ra/<fingerprint>` opens `WidgetRemoteAuth`.
- [ ] `utilities/uri/UriHandler` absent — "safe external URI handling" is
      currently a raw `ACTION_VIEW` at `AppActivity.kt:134`, and the Chrome
      Custom Tabs user setting is read by nothing.
- [ ] `widgets/tos` (5 classes) is at 0%.

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

Status: **IN PROGRESS**

- [x] Guild list, folders, sorting, unread state, and selection.
- [x] Channel list, categories, permissions, and selection.
- [x] Guild/channel persistence and READY hydration.
- [x] Member list and presence basics.
- [x] DM and group-DM channel lists.
- [x] Panel behavior for guild/channel/content/member surfaces.

Implemented: persisted guild/channel selection, folders/order/drag persistence,
effective permissions, unread/mention projection, typed member/role/member-list
Gateway updates, lazy member subscriptions, guild/channel/DM/member fragments,
panel lifecycle, and authenticated production Gateway bootstrap.

The 2026-07-26 audit un-ticked the boxes above. Measured coverage:
`widgets/channels` **31 of 114** reference classes (8 substantially complete),
`widgets/guilds` **7 of 75**. Evidence:
`docs/parity-audit/audit-widgets-channels.md`,
`docs/parity-audit/audit-widgets-guilds.md`,
`docs/parity-audit/audit-stores-guilds.md`,
`docs/parity-audit/audit-stores-channels.md`.

Store-side blockers:
- [ ] `StoreChannelsSelected` is a 107-line rewrite of a 917-line store. Both
      sealed hierarchies (`ResolvedSelectedChannel`, `UserChannelSelection`) are
      absent, so thread drafts, channel peeking, and `Uninitialized`/`Unavailable`
      are unrepresentable. `validateSelectedChannel()` and the reactive
      re-validation pipeline are gone.
- [ ] Four of ten `StoreReadStates.Inputs` fields are never populated
      (`StoreStream.kt:1584-1593`), so **all thread and voice unreads are
      silently discarded**.
- [ ] `markUnread` is inverted: it discards the passed message id and acks
      `mostRecentId`, telling the server the channel is fully read.
- [ ] All 8 `StoreUserGuildSettings` setters are dead no-ops — mute, frequency,
      and suppress-@everyone never reach the API.
- [ ] `StoreMentions` pipeline absent; badges are server-snapshot-only and DMs
      contribute zero mentions.
- [ ] `StorePermissions` uses the wrong algorithm — no stage-instance or
      joined-thread inputs, so stage speakers and private-thread members get
      wrong permissions. 6 gateway handlers missing.
- [ ] `StoreGuildSubscriptions` is a rewrite: typing/activities/threads/member
      subscription state absent, so typing indicators and presence for the
      selected guild are never requested.
- [ ] `StoreLurking`, `StoreGuildMemberRequester`, `StoreGuildMemberCounts`,
      `StoreGuildsNsfw`, `StoreThreadsJoined`, `StoreThreadMembers`,
      `StoreThreadMessages`, `ArchivedThreadsStore`, `StoreVoiceChannelSelected`,
      and `StoreSlowMode` are MISSING.
- [ ] `com.discord.api.channel.ChannelUtils` does not exist; stores re-invent its
      predicates and disagree in at least 4 places.
- [ ] `StoreGuilds.handleGuildMember` preserve flag is INVERTED, clobbering
      member `bio`/`banner`; `initClearCommunicationDisabledObserver` is missing
      so timed-out members stay muted forever.

UI-side blockers:
- [ ] 13 of 20 channel-list row types are never produced; all 14 of their layouts
      already exist unused.
- [ ] Zero long-press handlers in the channel list (reference wires 7), so both
      action bottom sheets are unreachable.
- [ ] 6 of 10 guild-sidebar item types absent, including **create-server**; no
      guild/folder context menus; folder reorder is never persisted.
- [ ] Entire clusters missing: channel settings (6), channel permissions (18),
      thread browser (9), channel-list unreads (1), guild creation (10), join
      guild (13), invite share (22), guild profile sheet (10).
- [ ] No ViewModels anywhere in the slice; store wiring is inlined and routed
      through process-global mutable `@JvmField` callbacks that default to
      `null`, leaving several screens no-ops out of the box.

Exit criteria: users can navigate guilds, channels, and DMs with correct
permissions, unread state, persisted selection, and responsive panel behavior.

### Phase 7: Messages and Uploads

Status: **IN PROGRESS**

- [x] Message history loading, pagination, and jump behavior.
- [x] Gateway create/update/delete reconciliation.
- [x] Optimistic local messages and nonce reconciliation.
- [x] Per-channel message queue.
- [x] Send, edit, delete, reply, reactions, pins, and ACK/read state.
- [x] Attachments, image preprocessing, multipart upload, and progress.
- [x] Slow mode, rate limits, captcha, AutoMod, and retry behavior.
- [x] Background send/ACK workers.

The 2026-07-26 audit un-ticked the boxes above. `widgets/chat` covers **40 of
277** reference classes (14.4%). Evidence:
`docs/parity-audit/audit-widgets-chat.md`,
`docs/parity-audit/audit-stores-messages.md`.

Store-side blockers:
- [ ] `StoreMessages` has **no send/edit/resend/delete pipeline**: `sendMessage`,
      `resendMessage`, `resendAllLocalMessages`, `markMessageFailed`,
      `editMessage`, `deleteMessage`, `cancelMessageSend`, `enqueueRequest`,
      `getOrCreateMessageQueue`, and `messageQueues` are all absent.
- [ ] `StoreMessagesLoader.init()` never subscribes to chat interaction, so
      `loadBefore`/`loadAfter` have zero callers and **infinite scroll is
      unreachable**. `jumpToMessage` is reduced to a single AROUND fetch.
- [ ] `StoreChat` is missing: `InteractionState`, `appendMention`,
      `replaceChatText`, the chat event bus, and blocked-message-group expansion.
      The target's `StoreChatInputEditing` reconstructs ~8% of it.
- [ ] `StoreMessageReactions` (438 lines), `StoreMessageReplies` (374 lines, so
      inline reply previews cannot render), and `StoreSlowMode` (232 lines) have
      no counterparts.
- [ ] `MessageResult.Success` drops the created message.
- [ ] Attachment sends bypass `MessageQueue` entirely and run from
      `StoreStream.kt:1119-1215` with an uncapped `retryWhen` that never handles
      slow-mode error code 20016.
- [ ] Holder cache persists on every mutation instead of the reference's 60s
      throttled+deduped persist.
- [ ] Own-reaction echoes double-count (missing me-guard on add and removal).
- [ ] `StorePinnedMessages.handlePinsUpdate` mutates the published snapshot
      outside `snapshotData()`, breaking StoreV2 confinement.

UI-side blockers:
- [ ] **33 of 46 row types unimplemented** — embeds, stickers, invites, bot
      components, threads, calls, blocked, AutoMod, search, and ephemeral
      messages never render. `ChatListRows.kt:98-112` invents English literals
      (`"[Embed: ...]"`, `"[Interactive content]"`) that mask the gap.
- [ ] `bindAttachment` is a stub that hides `InlineMediaView` and prints
      `"<size> bytes"`, making `InlineMediaView.kt` dead code.
- [ ] Autocomplete/mentions/slash commands (64 classes), sticker picker (23),
      GIF picker (17), and the expression tray (8) are entirely absent.
- [ ] 9 of 17 message-action rows are force-hidden; the quick-reaction emoji
      strip is replaced by a single "Add Reaction" TextView; pinned-message
      long-press opens nothing.
- [ ] Chat-input guards hardcoded — member verification, lurker mode, follow, and
      verification level are all absent.
- [ ] Author rendering loses nickname, role colour, bot tag, role icon, and
      timeout icon; timestamps show a bare clock instead of "Today at 3:42 PM".

Exit criteria: ordinary text and attachment messaging works reliably through
offline/reconnect/rate-limit cases without duplicate or lost local messages.

### Phase 8: Users and Social Features

Status: **IN PROGRESS**

- [x] User cache, current user, profiles, notes, and mutual context.
- [x] Relationships: friends, blocked, incoming, and outgoing.
- [x] Presence, activities, typing, and status controls.
- [x] Friend suggestions and affinity-dependent UI where observable.
- [x] User sheet, profile, friend list, and add-friend screens.

The 2026-07-26 audit un-ticked the boxes above. `widgets/user` + `widgets/friends`
cover **28 of 68** reference classes, with only 6 at parity. Evidence:
`docs/parity-audit/audit-widgets-user-friends.md`,
`docs/parity-audit/audit-stores-users.md`.

- [ ] `StoreUserSettings` has **no server-write half**: every `setIsXxx` REST
      tail, `updateCustomStatus`, `expireCustomStatus`, `observeConsents`,
      `setExplicitContentFilter`, `setFriendSourceFlags`, and the restricted-guild
      setters are absent. Settings changes never reach Discord.
- [ ] `setUserTyping(channelId)` is missing — **the local user never sends typing
      indicators**; the `restAPI` dependency is not even wired.
- [ ] Custom-status emoji resolution is stubbed and expiry is computed from
      `ModelMuteConfig.endTimeMs` instead of `TimeUtils.parseUTCDate`.
- [ ] `WidgetUserSheetViewModel` (2,272 lines) and its `StoreState` do not exist;
      the sheet is driven by nullable static lambdas and is inert by default,
      with 6 sections forced `GONE`.
- [ ] Roles are never bound into the user sheet; rich presence (all 9 classes) is
      absent; badges have no tooltips and the boost badge is pinned to level 1.
- [ ] `PrivateCallLauncher` missing — every call/video button in both packages is
      a no-op. `WidgetBanUser`/`WidgetKickUser` missing, so the admin view's
      ban/kick have no destination.
- [ ] Quick switcher sorts alphabetically, ignores channel permissions, and has
      no scoring or frecency (`ScoreStrategy`, `ChannelFrecencyTracker` absent).
- [ ] Account/email/phone verification (6 classes) and `StoreMaskedLinks`
      (phishing warnings — security relevant) are missing.
- [ ] `StoreUserConnections`, `StoreContactSync`, `StoreSpotify`,
      `StoreGameParty`, and `StoreUserSurvey` are missing; `widgets/contact_sync`
      (9 classes) is at 0% and is unclaimed scope for this phase.
- [ ] `PresenceUtils` and `UserUtils` do not exist, so there is no status text,
      custom-status emoji, mobile indicator, badge predicate, or
      username+discriminator formatting.

Exit criteria: social state remains consistent across REST mutations, gateway
updates, persistence, and logout.

### Phase 9: Notifications and Background Work

Status: **IN PROGRESS**

- [x] Notification preferences and suppression rules.
- [x] FCM registration using the approved Discord client configuration.
- [x] Message/call notification rendering and grouping.
- [x] Notification actions, deletion, and cancellation.
- [x] Foreground/background lifecycle behavior.
- [x] Direct share targets and download completion handling.

Implemented: `StoreNotifications` is live in `StoreStream`; provider-neutral push
payload parsing, local/Gateway rendering, grouping/cache, Android 13 permission
handling, guarded reply/read/delete actions, notice hosting, and the CallAction,
MessageSend, and TimedMute workers are present. Focused store, payload, utility,
worker, and notice-host tests exist. The installed app now acquires and registers a
token from Discord's Firebase project; cold-process delivery and the full
notification/action device matrix remain open.

Exit criteria: notifications respect foreground state, channel/guild settings,
permissions, account state, and Android version requirements.

### Phase 10: Settings and Account Management

Status: **IN PROGRESS**

- [x] Appearance, theme, locale, font scale, and accessibility.
- [x] Privacy, safety, media, text/image, behavior, and notifications.
- [x] Voice/video device and processing settings.
- [ ] Account profile, email, phone, password, sessions, and deletion flows.
- [x] Authorized applications and connections.
- [x] Developer diagnostics without secret leakage.

Implemented: the settings hierarchy and full-screen routing now cover account,
profile, privacy/safety, appearance, accessibility, voice, notifications,
authorized apps, connections, MFA, billing, and diagnostics with real store/REST
flows. Unit coverage includes settings routes, account/MFA contracts, profile,
privacy, connections, billing, and optimistic writes; focused privacy cold-launch
instrumentation passed on device. Account disable/delete now has exact endpoint,
payload, MFA/password error, and generated-binding coverage. Remaining seams include
some nullable-clear/store utility paths, session-management evidence, provider-owned
credential flows, and broad device validation.

Exit criteria: settings restore correctly, synchronize where required, and
apply without unnecessary process restarts.

### Phase 11: Voice, Calls, and Video

Status: **IN PROGRESS**

- [x] Voice selection and Gateway voice-state/server coordination.
- [x] RTC connection state machine.
- [x] Audio input/output selection, mute, deaf, volume, VAD, and AEC.
- [x] Incoming/private calls and ringing state.
- [x] Call activity, bottom sheet, and fullscreen UI.
- [x] Camera capture, video sinks, participant streams, and offscreen policy.
- [x] Screen sharing and media-projection foreground service.
- [x] Reconnect, region selection, statistics, and diagnostics.

Implemented: `VoiceTransport`, RTC/media/audio stores, voice sheets, incoming and
private calls, active/preview fullscreen screens, dedicated call activity,
foreground voice and overlay services, camera/video sinks, stream focus, call
sounds, and screen-share previews/actions are connected. Native lifecycle checks
passed on Android 16 x86_64 before the fullscreen slice; the fullscreen slice has
unit/build/lint evidence but no live-backend or ARM64 end-to-end call exercise.
Voice remains open until join/move/reconnect, device switching, video, screen
sharing, overlay, backgrounding, and teardown are validated on device.

Exit criteria: voice, private calls, video, and screen sharing survive activity
recreation, background transitions, device changes, and network reconnects.

### Phase 12: Extended Feature Parity

Status: **IN PROGRESS**

- [x] Search and quick switcher.
- [x] Threads and forums.
- [x] Emoji, stickers, GIFs, embeds, and media viewer/player.
- [x] Scheduled events and stage channels.
- [x] Guild creation, invites, templates, onboarding, and discovery.
- [x] Moderation, audit log, member verification, and community settings.
- [ ] Roles, permissions, integrations, webhooks, and server settings.
- [ ] Hubs, reports, safety flows, and remaining manifest routes.

Implemented structural and tested slices now cover the major listed clusters,
including the search/GIF/expression stores, thread/forum flows, scheduled-event
location and stage moderation, group invites, hubs/directories, reports, audit
logs, community/member verification, roles, permissions, and server settings.
Physical widget source coverage is 1,150/1,155; four unmatched filenames emit the
exact reference JVM classes from consolidated Kotlin. Structural coverage remains
an upper bound: existing linked Spotify accounts now have local playback/token/track
metadata presence support, while new links still need project OAuth; webhooks/integration and manifest-route
behavior is not fully re-audited, several store/utility seams remain, and the
feature/device regression matrix is incomplete.

Exit criteria: every first-party layout and manifest route is implemented,
explicitly obsolete for 126.21 behavior, or documented as blocked.

### Phase 13: Billing and External Integrations

Status: **IN PROGRESS**

- [x] Approved Discord client-side Firebase configuration for FCM.
- [ ] Project-owned OAuth/Google Sign-In clients if still applicable.
- [ ] App Links for the new application ID.
- [x] Google Play Billing integration; project-owned products remain unconfigured.
- [x] Subscriptions, entitlements, gifts, and boosts.
- [x] Privacy-safe provider-neutral analytics; external sinks remain disabled.

Implemented: Billing 5.2.1 runtime management, purchase/SKU stores, plan
selection, subscriptions, entitlements, gifting, boosts, and checkout handoff are
present and fail closed without Manticord-owned products. Purchase completion now
routes to the reference premium/boost activation dialogs before finishing. Analytics, super
properties, resource monitoring, and legitimate GAID retrieval are implemented
without private service credentials. FCM client configuration is present by explicit
user decision; dynamic links/App
Links, OAuth clients, products, and crash-reporting transport remain open.
Samsung OAuth is explicitly out of scope and is not a blocker.

Exit criteria: external integrations use only Manticord-owned credentials and
degrade safely when an official signing/package relationship is unavailable.

### Phase 14: Parity Audit and Release Hardening

Status: **IN PROGRESS**

- [x] Audit every first-party manifest component.
- [ ] Audit every recovered app layout and deep-link route.
- [x] Audit exported components and permissions.
- [ ] Test API 21 through current target SDK behavior.
- [ ] Test phone, tablet, portrait, landscape, RTL, and large fonts.
- [x] Run unit, instrumentation, lint, and release builds.
- [ ] Enable release shrinking after keep rules are verified.
- [ ] Verify no secrets or reference API keys are committed.
- [ ] Produce a signed internal release using Manticord-owned credentials.

Exit criteria: feature matrix is complete, no critical known regressions remain,
and debug/release APKs install and run on representative devices.

### Phase 15: Generated View Binding Migration

Status: **IN PROGRESS**

- [ ] Inventory first-party `findViewById` calls and map each static layout owner
  to its AGP-generated binding class.
- [ ] Classify and retain lookups that are genuinely dynamic, such as runtime-
  inflated children, polymorphic rows, and views owned by third-party widgets.
- [ ] Migrate fragments, dialogs, bottom sheets, activities, adapters, and custom
  views one file at a time, prioritizing user-reachable screens.
- [ ] Scope fragment/dialog bindings to the view lifecycle and clear nullable
  backing fields in `onDestroyView`.
- [ ] Replace static repeated lookups with binding properties without changing
  layout structure, state behavior, callbacks, or accessibility.
- [ ] Run focused tests and `assembleDebug` after each slice; run the full unit,
  lint, and Android-test compilation boundary before closing the phase.
- [ ] Document the small remaining dynamic-lookup allowlist with justification.

Exit criteria: no first-party static layout view uses `findViewById` when an
AGP-generated binding can represent it, every retained lookup is justified, and
all standard verification boundaries pass.

## Feature Parity Ledger

Agents should split rows only when implementation begins. Do not pre-create a
large issue hierarchy without work attached.

The 2026-07-26 figures are retained in `docs/parity-audit/` as the campaign
baseline, but are superseded here by later measured checkpoints. The latest full
recorded verification is 1,678 unit tests plus debug/release assembly, `lintDebug`,
release lint-vital, `compileDebugAndroidTestKotlin`, and 59/59 connected tests on
Android 16; it did not include a live backend run. Where no fresh full-surface
recount exists, the ledger says so rather than carrying the obsolete baseline
percentage. A row is not `DONE` until its behavioral/runtime blockers are cleared.

| Area | Status | Measured coverage | Notes |
| --- | --- | --- | --- |
| Build/XML shell | DONE | Debug and release APKs assemble; lint and release lint-vital pass | Phase 0 baseline holds |
| Native media load | DONE | — | ARM64 runtime waived by user decision |
| Resources/themes | IN PROGRESS | 1,228 layout variants retained; all first-party attr enums/flags restored | Runtime/qualifier/accessibility matrix not complete |
| Custom views | IN PROGRESS | **76/76** first-party classes present; 13 audited defects fixed | No fresh behavioral recount of formerly inert views |
| Dispatcher/stores | IN PROGRESS | No fresh 187-store recount | Broad graph now includes voice, notifications, analytics, search/GIF/expression, directories, billing, and social/admin stores; residual seams remain |
| REST | IN PROGRESS | **264/264** unique endpoints; **108/108** request bodies | Endpoint set diff has 0 missing; live backend remains opt-in |
| Gateway | IN PROGRESS | **83/83** reference dispatch events typed; 21/21 opcodes represented | Destination-store behavior has not been fully re-audited |
| Models/DTOs | IN PROGRESS | 39 adapters plus 3 factories at the measured data-wave checkpoint | Later DTO/model additions are not freshly recounted |
| Utilities | IN PROGRESS | No fresh full-surface recount | Major text, image, permission, send, recycler, URI, billing, notification, and voice utilities now exist; residual seams are tracked in followups |
| App framework | IN PROGRESS | Base fragment/sheet/dialog, router, back registry, permissions, direct-screen activity stack, reviewed component exports, shortcuts and package queries present | Device regression remains incomplete |
| Authentication | IN PROGRESS | Credential, MFA, captcha, remote-auth, OAuth, verification, and required-action flows present | Samsung OAuth excluded; remaining provider/runtime gaps prevent `DONE` |
| Navigation/deep links | IN PROGRESS | Reference routes represented, including query/search, remote auth, guild templates, discovery, mutuals, pins, and call activities | Full route/device matrix not complete |
| Guilds/channels | IN PROGRESS | Included in the **1,150/1,155** physical widget-path checkpoint | Runtime reorder, permissions, panels, and persistence still need broad device validation |
| Messages/uploads | IN PROGRESS | All 45 reference chat entry types mapped; queue/send/upload/search/GIF/expression surfaces present | Offline/reconnect/live upload and media regressions remain open |
| Users/relationships | IN PROGRESS | User/friends package paths structurally closed in the widget campaign | Provider and device behavior remain incomplete |
| Notifications/background work | IN PROGRESS | Store, renderer/actions, provider-neutral payloads, and 3 first-party workers present | Project-owned FCM and full device/action validation remain open |
| Settings/account | IN PROGRESS | Settings/account source surfaces and focused unit/instrumentation checks present | Provider, residual dialog/seam, and device gaps remain |
| Voice/calls/video | IN PROGRESS | Fullscreen active/preview, call activity, services, overlay, video and screen-share surfaces present | No ARM64/live end-to-end fullscreen call validation |
| Extended features | IN PROGRESS | Widgets **1,150/1,155 physical paths**; 4 unmatched names emitted by consolidated files | Existing linked Spotify accounts supported; new links and behavioral gaps remain |
| Billing/integrations | IN PROGRESS | Billing 5.2.1, purchase/SKU stores, premium/gift/boost flows, analytics and GAID present | Manticord-owned products/providers absent; purchase path fails closed |
| Generated view bindings | IN PROGRESS | Core settings/user plus 15 additional channel, guild, chat, user, settings, and server screens migrated in the Phase 14 wave | Repository-wide static lookup inventory and migration remain |

## Parity Audit (2026-07-26)

A 16-agent read-only audit compared every reconstructed store, widget, view,
model, and utility against the reference. The full reports are committed at
`docs/parity-audit/`, indexed by `docs/parity-audit/README.md`. Read the report
for your area before starting work in it — each finding cites `file:line` in the
target and the reference class/method, and each report lists the regions where
JADX output is broken and the behavior is therefore **unverified**. Do not close
a finding by guessing at an unverified region; inspect DEX/smali instead.

Cross-wave follow-ups — items an agent could not finish because another agent
owned the file or a dependency did not exist yet — accumulate in
`docs/parity-audit/followups.md`. That file also records the deliberate
divergences from the reference that must **not** be "corrected" by a later parity
pass, and the least-corroborated reconstructions that need DEX/smali
re-verification. Append to it; do not delete an item until it is done.

Historical baseline result: the target was roughly **11% of the reference widget
surface**, **27% of stores**, **16% of REST**, and **53% of gateway events** on
2026-07-26. The current measured checkpoints are **1,150/1,155 physical widget
paths** (four unmatched names emitted by consolidated files), **264/264 REST
endpoints**, **108/108 request bodies**, and **83/83 typed gateway events**. No
fresh complete store count or behavioral-parity percentage exists; the build and
test evidence does not replace the remaining runtime audit.

### Historical cross-cutting defects

This table is the 2026-07-26 audit baseline and is retained as historical evidence;
many entries were subsequently fixed. Use the current ledger and recent progress
journal/followups for active status.

| Defect | Effect |
| --- | --- |
| `WidgetTos` route target does not exist (`AppActivity.kt:152`) | Crash for any account with a pending TOS/agreements action |
| `EmbedType` unregistered with Gson | Every embed's `type` deserialises to `null` |
| `IncomingParser.kt:91` untyped fallback | 39 gateway events arrive and are discarded |
| `<enum>`/`<flag>` stripped from `attrs.xml` | Settings radios/switches render as checkboxes across 39 layouts |
| `markUnread` acks `mostRecentId` | Mark-unread marks the channel fully read |
| 4 of 10 `StoreReadStates.Inputs` unpopulated | All thread and voice unreads discarded |
| `ACTIVE_STATES` has `ENDED` not `PAUSED` | `isStreamActive()` inverted both ways |
| @everyone permission fallback returns `NONE` not `DEFAULT` | Channel list can empty during partial hydration |
| MFA challenge dispatched as successful login | Account state corrupted on MFA accounts |
| Logout omits `Persister.reset()` + `prefs.clear()` | Prior-account state survives logout |
| Off-thread `markChanged` throws | Crash where the reference only logs |

### Historical live dead-ends

These were the user-reachable dead ends measured on 2026-07-26: the channel sidebar **Search** button
(`onSearch` never assigned), `Channel.DIRECTORY` rows (open the ordinary chat
surface), the **Blocked users** settings row (force-hidden, callback never
invoked), and the `/template/`, `discord.new`, `/discovery`, `/query/` deep links
(parsed, consumed, then dropped). `res/xml/shortcuts.xml` and
`res/xml/actions.xml` exist but are never referenced from the manifest, so Direct
Share and all Google App Actions are dead.

## Reconstruction Campaign

Parity is being closed in dependency-ordered waves. Each wave is a set of agents
with **disjoint file ownership** running in parallel; the build must be green at
every wave boundary. Work bottom-up: rebuilding widgets before the utilities and
stores beneath them means writing them twice.

### Concurrency protocol

Mandatory when more than one agent is active:

1. Every agent gets an explicit exclusive file-ownership list and touches nothing
   outside it.
2. An agent that needs an API another agent is currently building codes against
   the agreed fully-qualified name and waits for it, rather than defining a
   duplicate or substituting `Any`.
3. **Only the coordinating agent runs Gradle.** Parallel agents must not run
   `assembleDebug`, `compileDebugKotlin`, `testDebugUnitTest`, or any other
   Gradle task. Learned the hard way on 2026-07-26: the Gradle lock serializes
   *task execution* but does not protect `app/build` from concurrent writers, so
   13 agents produced 22 live daemons and repeated failures — `Unable to delete
   directory .../stripped.dir/layout`, `DirectoryNotEmptyException`, and "New
   files were found. This might happen because a process is still writing to the
   target directory." One agent spent most of a 45-minute run fighting this. The
   coordinator runs one serialized build at the wave boundary and dispatches each
   compile error to the agent that owns the file.
4. **Only the coordinating agent runs git.** Parallel agents must not run
   `commit`, `add`, `checkout`, `reset`, or `stash`. On 2026-07-26 an agent
   committed 286 files / 30,766 insertions, sweeping every other agent's
   in-flight work plus the coordinator's documentation into one incoherent
   snapshot. Agents leave changes in the working tree.
5. An agent that cannot finish its slice stops cleanly and reports what remains.
   A partial slice that is honest about its boundary is worth more than a rushed
   one, because the next wave plans from these reports.
6. Cross-package cleanups an agent cannot make (because another agent owns the
   file) go in that agent's handoff report as an explicit item for the next wave.
7. `PLAN.md` and `docs/parity-audit/**` are owned by the coordinating agent only.

### Wave status

| Wave | Scope | Status |
| --- | --- | --- |
| 1 | App framework base classes; `stores/utilities`; gateway 83-event surface + `StoreGatewayConnection`/`StoreConnectivity`/`StoreConnectionOpen`/`StoreClientDataState` | IN PROGRESS |
| 2a | `textprocessing`; `IconUtils`/images/Fresco; permissions + `RoleUtils`/`UserUtils`/`PresenceUtils`/`ChannelUtils`; `messagesend`/`Backoff`/`rx`/`error`; `mg_recycler`/`collections`/`view`/`uri`/`time` | IN PROGRESS |
| 2b | `attrs.xml` enum restoration; 7 missing view classes; 13 view behavior defects | IN PROGRESS |
| 2c | REST 264 endpoints + 107 bodies; `api/**` subpackages + 19 missing type adapters; `models/**` subpackages + streaming parse | IN PROGRESS |
| 3 | Store graph: 99 missing stores, 37 partial stores, `StoreStream.deferredInit`, `ReadyPayloadHydrator` merge semantics, logout/reset correctness | NOT STARTED |
| 4 | Widgets with their ViewModels: chat, channels, guilds, user/friends, settings/servers, voice/stage, auth/home/tabs | NOT STARTED |
| 5 | The 24 zero-coverage widget packages; manifest components and permissions; notifications and FCM; shortcuts/actions wiring | NOT STARTED |
| 6 | Re-audit against the reference, full verification sweep, release hardening | NOT STARTED |

### Wave file-ownership map

Recorded so a resumed or replacement agent can pick up an unfinished wave without
re-deriving the partition.

| Owner | Paths |
| --- | --- |
| App framework | `com/discord/app/**`, `com/discord/panels/**` |
| Store utilities | `stores/utilities/**`, `stores/updates/**`, `StoreV2.kt`, `StoreGuildsSorted.kt`, `StoreUser.kt` |
| Gateway | `gateway/**`, `StoreStream.kt`, `StoreGatewayConnection.kt`, `StoreConnectivity.kt`, `StoreConnectionOpen.kt`, `StoreClientDataState.kt` |
| Text processing | `utilities/textprocessing/**` |
| Icons/images | `utilities/icon/**`, `utilities/images/**`, `utilities/fresco/**` |
| Permissions/entities | `utilities/permissions/**`, `utilities/guilds/**`, `utilities/user/**`, `utilities/presence/**`, `utilities/guildmember/**`, `utilities/channel/**`, `api/permission/Permission.kt`, `api/channel/ChannelUtils.kt` |
| Send pipeline | `utilities/messagesend/**`, `utilities/networking/**`, `utilities/rest/**`, `utilities/error/**`, `utilities/rx/**`, `utilities/captcha/**` |
| Recycler/view utils | `utilities/mg_recycler/**`, `utilities/recycler/**`, `utilities/collections/**`, `utilities/view/**`, `utilities/uri/**`, `utilities/string/**`, `utilities/time/**`, `utilities/locale/**` |
| Resources | `res/values/attrs.xml` |
| New views | the 7 new files under `views/**` and `tooltips/**` |
| Existing views | pre-existing files under `views/**`, `rlottie/**` |
| REST | `restapi/**` |
| API DTOs | `api/**` except `Permission.kt` and `ChannelUtils.kt` |
| Domain models | `models/**` |

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
| `MessageRequest.SendApplicationCommand` is not reconstructed | Slash-command sends cannot use the queue | Blocked on `models/commands/ApplicationCommandLocalSendData`, `RestAPIParams.ApplicationCommand` and the `sendApplicationCommand` endpoint, none of which exist yet; add the variant with the `SendUtils.SendPayload.ReadyToSendCommand` branch once they do |
| Attachment sends still bypass `MessageQueue` | Uploads get no per-channel ordering, timeout or backoff | `StoreStream.startUpload` must move onto `MessageRequest.Send(attachments = ...)`; the multipart-capable surface is in place |
| Only hCaptcha challenges can be rendered | A `recaptcha`/`recaptcha_enterprise`/`turnstile` challenge fails instead of being solved | No Play Services dependency is installed, so `CaptchaRequest.ReCaptcha` resolves to the reference's `captcha_failed_play_services` failure. The published site keys are on `CaptchaService.defaultSiteKey` for whoever adds a renderer; the current mobile client also only implements hCaptcha |
| `com.discord.restapi.utils.RetryWithDelay` is not reconstructed | The REST retry policy lives inline in `restSubscribeOn` | Policy is reproduced faithfully (IOException-only, non-GET and 401/403/404/429/503 excluded, 1s/2s/4s, three retries); move it into `RetryWithDelay` when that package is rebuilt |

## Decision Log

Append decisions that change architecture, compatibility, scope, dependencies,
or observable behavior. Small implementation details do not belong here.

| Date | Decision | Reason | Consequence |
| --- | --- | --- | --- |
| 2026-08-11 | Allow Discord's client-side Firebase configuration for FCM parity | The user explicitly overrode the prior project-owned-Firebase restriction after confirming custom-project tokens cannot be delivered by Discord's sender | The reference `google-services` client identifiers may be used locally; private Firebase Admin/service-account credentials, account tokens, and cookies remain forbidden |
| 2026-08-09 | Publish master builds as debug Actions artifacts and reserve tagged release APKs for manual workflow runs | Continuous tagged releases obscured intentional releases and provided no embedded CI provenance | Settings shows `Dev <build id> <commit hash>`; manual release APKs retain the committed Baseline Profile |
| 2026-08-09 | Sign local and CI debug/release APKs with one repository-owned development certificate | Per-machine Android debug keys made GitHub Actions APKs incompatible with local installs | All Manticord development artifacts are update-compatible; the public key is explicitly not a production trust identity |
| 2026-08-09 | Use Gateway `zstd-stream` exclusively with zstd-jni `1.5.7-12` | Discord documents zstd transport, repeated A65 runs support it, and the user explicitly removed legacy fallback | One persistent native decoder context is scoped to each connection; zlib decoder/fallback code is removed |
| 2026-08-09 | Start an identified Fast Connect socket before constructing the store graph | The modern Android native module proves this is a client-side early-socket handoff, and the user requested it | Cached token/client state opens one zstd socket with `is_fast_connect=true`; typed events buffer until cache restore and store listener attachment |
| 2026-08-09 | Migrate Gateway cache state from v1 hashes to CLIENT_STATE_V2 versions | Current React Native Hermes modules `GatewaySocket`, `GuildVersions`, and `NonGuildVersions` prove the live contract; the backend no longer returns v1 hashes | Persist unsigned entity versions, send `guild_versions` plus non-guild versions, and hydrate typed full/partial READY guild envelopes against restored stores |
| 2026-08-08 | Retain Gson streaming for Gateway metadata and reject Fastjson2 `2.0.64.android5` | A65 A/B tracing found Fastjson2 14% slower on ordinary dispatches, with higher Gateway CPU, heap, and GC count despite a 2.8% faster READY sample | Fastjson2 and its parser selector were removed; the existing Gson adapters remain the single Gateway JSON stack |
| 2026-08-08 | Stream `ModelPayload` directly from Gson's `JsonReader` | The former adapter built the complete READY tree and reparsed each field; the reference also streams READY | Only irregular experiment arrays retain local trees; the large guild/member/presence surfaces parse once |
| 2026-08-08 | Open the authenticated main UI before Gateway READY | The user explicitly requires cached UI to open immediately while Gateway connects in the background | `MainRoute.LoggedIn` depends on initialized persisted auth, not channel hydration; required-action and age gates still take priority when known |
| 2026-08-08 | Generate release Baseline Profiles with AndroidX `1.5.0-beta01` | Stable `1.4.1` rejects AGP `9.2.1`; the current beta configures and generates successfully with that AGP | `:baselineprofile` records non-destructive startup on a connected device; ProfileInstaller remains stable `1.4.1` |
| 2026-07-22 | Rebuild in existing Android boilerplate | Project already exists at target path | Convert template rather than create another project |
| 2026-07-22 | Use Kotlin + XML | Closest to original architecture and recovered layouts | Remove Compose |
| 2026-07-22 | Use `dev.mantikafasi.manticord` application ID | Official signing identity cannot be reproduced | External integrations need new configuration |
| 2026-07-22 | Keep `com.discord` namespace | Structural fidelity and lower package churn | Application ID and namespace intentionally differ |
| 2026-07-22 | Reuse assets/layouts and `libdiscord.so` | Required for visual and media parity | Resource and JNI compatibility become explicit workstreams |
| 2026-07-22 | Install public libraries normally | Avoid rebuilding dependency code | Decompiled dependency sources are reference-only |
| 2026-07-22 | Require complete store/model fields and complete views | Partial models and placeholder UI cannot provide feature parity | Every slice must inventory and verify its full structural surface |
| 2026-07-23 | Allow scoped JitPack access for recovered uCrop and FlexInput artifacts | Reference-compatible versions are unavailable from Google and Maven Central | Repository content filtering permits only `com.github.yalantis` and `com.github.lytefast` |
| 2026-07-31 | Replace FlexInput's unavailable CameraKit `1.0.0-beta3.10` transitive with upstream JitPack `v1.0.0-beta3.11` | The original Bintray artifact is absent from Google and Maven Central and beta3.10 does not build on JitPack; beta3.11 preserves the `CameraKitView.ErrorListener` signature, camera APIs used by FlexInput, and resource attrs | Keep the unavailable CameraKit/JPEGKit transitives excluded, install CameraKit explicitly, and admit `com.github.CameraKit` through the scoped JitPack filter |
| 2026-07-31 | Port Aliucord `DefaultStickers` behavior natively | The user explicitly requested default sticker access beyond stock 126.21 behavior | Standard packs/categories are shown to non-Nitro users, standard stickers are sendable, and only non-premium `sticker_ids` sends use a dedicated RN-metadata REST client; no Aliucord API or dependency is included |
| 2026-08-05 | Port Aliucord poll behavior natively with XML-backed views and current transport | The user requested modern poll creation, rendering, voting, results, and closure on the 126.21 shell | Poll endpoints use `RestAPI.api`; `MESSAGE_POLL_VOTE_*` updates the existing message store; no Aliucord runtime dependency or programmatic view hierarchy is included |
| 2026-08-07 | Use one default `RestAPIInterface` transport | The user confirmed the former legacy/RN split has been retired and every API call is routed through the same client metadata | `RestAPI.api` is the sole default client; the redundant RN alias/interceptor is removed and the null-serializing variant uses the same metadata headers |
| 2026-07-31 | Use 12 Gradle workers with parallel execution and build cache | The host has 12 physical/24 logical CPUs; 24 workers would worsen the compiler heap/disk contention already observed | Builds use physical-core parallelism while Kotlin/Gradle daemons retain 4 GB heap ceilings |
| 2026-07-23 | Install upstream SimpleAST 2.8.0 with the reference 2.6.0 rule surface | Recovered behavior uniquely matches 2.6.0, but JitPack no longer serves that artifact; 2.8.0 is the available API-compatible upstream AAR | Public source is not reconstructed; Discord rules must omit post-2.6 diff behavior |
| 2026-07-23 | Use JADX MCP for decompiled reference inspection | The connected JADX server provides direct class, method, field, and cross-reference evidence | Exported Java/DEX file inspection is fallback-only when MCP lacks a body |
| 2026-07-24 | Use public WebRTC `104.5112.10`, APNG `1.11.0`, and scoped RealtimeBlurView commit `82df3520b4` for recovered view contracts | These are the available public artifacts matching the reconstructed Java APIs closely enough for Phase 1 | Exact `libdiscord.so` compatibility remains a Phase 2 runtime requirement |
| 2026-07-24 | Adapt the unpublished Fresco sample `ZoomableDraweeView` package to public PhotoDraweeView `2.1.0` | No published artifact provides `com.facebook.samples.zoomable.ZoomableDraweeView`, and copying public sample source is prohibited | The exact XML class name retains real zoom behavior through a thin adapter |
| 2026-07-24 | Baseline the imported recovered-resource lint corpus | The immutable corpus carries 16,733 inherited errors and 28,734 warnings from merged libraries, translations, and decoded resources after reconstructed Kotlin issues were fixed | `lintDebug` fails on every new issue while preserving the recovered corpus unchanged |
| 2026-07-24 | Preserve the recovered compiled WebRTC Java bridge for Phase 2 | No available public artifact matches four JNI-visible descriptors and two are Discord-specific; the user approved the exception required for exact native parity | Vendor only the exact compiled bridge closure, remove the incompatible public artifact, and verify creation/disposal on device |
| 2026-07-24 | Waive ARM64 runtime execution for the native spike | The user accepted packaged/hash-verified ARM64 libraries with x86_64 lifecycle evidence | Phase 2 closes on x86_64; ARM64 binaries remain present for later device validation |
| 2026-07-24 | Use Kryo `4.0.2` for reference-shaped persisted store payloads | Recovered metadata and API contracts require Kryo while persistence must remain atomic and path-confined | Persistence uses the public artifact and disables Android application backup before authentication data exists |
| 2026-07-24 | Use OkHttp `4.8.0`, Okio `2.7.0`, Retrofit `2.9.0`, and PersistentCookieJar `v1.0.1` for Phase 4 | OkHttp/Okio versions are recovered; Retrofit and cookie-jar versions are the compatible public artifacts matching the retained API surface | REST/Gateway fixture tests pin headers, cookies, errors, rate limits, and socket behavior without live backend access |
| 2026-07-24 | Add a Manticord-only token login entry point | The user explicitly requested direct token authentication | Tokens use password-style transient input, never enter saved UI state or logs, and persist only through the existing redacted auth-state cache |
| 2026-07-26 | Keep the reference `utilities.error.Error` type named `RestApiError` | `kotlin.Error` is a default import, so a first-party `Error` shadows it at every call site | The reference field names are preserved and a reference-to-target mapping is documented in the class KDoc |
| 2026-07-26 | Install RxAndroid `1.2.1` | `ObservableExtensionsKt.ui()` needs `AndroidSchedulers.mainThread()`; the reference uses the same artifact and it is the RxJava 1 companion release | Avoids hand-reimplementing a public library's main-thread scheduler |
| 2026-07-26 | Keep `FileUpload`/`UploadProgress` top-level in `utilities.rest` instead of nested in `SendUtils` | `StoreMessageUploads` already imports them at those names | Structural deviation from the reference nesting only; fields and semantics match `SendUtils.FileUpload` |
| 2026-07-26 | Surface attachment preprocessing/progress on a separate `MessageSendCoordinator.UploadEvent` stream | `Update` is consumed by an exhaustive `when` in `StoreStream`; upload progress is an orthogonal concern the reference routes straight into `StoreMessageUploads` | Adding upload variants to `Update` would have broken the store's exhaustive branch |
| 2026-07-26 | Reopen Phases 1, 3, 4, and 5 | A full structural audit proved they were closed on compile/inflate evidence rather than behavior; each has BLOCKER-severity gaps listed in its phase section | Phase status now tracks measured coverage; no phase closes while its audit report lists open blockers |
| 2026-07-26 | Commit the parity audit to `docs/parity-audit/` and link it from `PLAN.md` | The evidence is far too large for the plan but must survive agent handoffs and session boundaries | `PLAN.md` stays the single roadmap; the audit is linked supporting evidence, not a competing status document |
| 2026-07-26 | Close parity in dependency-ordered waves with disjoint per-agent file ownership | Rebuilding widgets before the utilities, models, and stores beneath them would mean writing them twice, and parallel agents editing shared files corrupt each other's work | Every agent gets an explicit ownership list; cross-package cleanups are deferred to the owning wave via handoff reports |
| 2026-07-26 | Keep `toString()` redaction on `Message`, `User`, `MeUser`, and `ActivitySecrets` even though the reference does not redact | The project's security contract requires redacting secrets regardless of what the generated reference output printed | A deliberate, documented divergence from the reference; must not be "corrected" by a later parity pass |
| 2026-07-26 | Widen the `orientation` attr (ViewReplySpline) from enum-only to `format="string\|enum"` | Three recovered layouts set `app:orientation="vertical"`, a value absent from the recovered enum set. The reference APK stores it as a raw string, so it was already dead in 126.21 and `getInteger` resolved it to the default 0. Modern AAPT2 rejects the combination | Both recovered enum constants stay live and still resolve to their integers; the three layouts compile unchanged and still yield 0, matching original behavior. Same `format="…\|enum"` convention as the earlier ConstraintLayout-parent repair |
| 2026-07-26 | Restore only first-party `<enum>`/`<flag>` attr children; leave 103 dependency-owned attrs undeclared | Material, AppCompat, ConstraintLayout/Motion, CoordinatorLayout, Fresco, Flexbox, CameraView, and Lottie own those attrs. The app's empty redeclarations were shadowing the library versions out — which is the exact failure being fixed | 146 enums + 16 flags restored across 49 attrs (34→180 enums, 0→16 flags), zero value mismatches; 23 dependency-owned attrs used by recovered layouts verified to resolve from their libraries in the linked resource table |
| 2026-07-26 | Add `androidx.dynamicanimation:dynamicanimation:1.0.0` | `SpringAnimation` is unavoidable for `com.discord.overlay.views.OverlayBubbleWrap` (velocity 50, stiffness 200, damping 0.75) | Version is **inferred** as the only release contemporary with 126.21, not recovered from metadata — re-check if overlay physics look wrong |
| 2026-07-26 | Scope the FileProvider to `Pictures/` rather than pointing it at `file_paths.xml` | Camera capture writes to `getExternalFilesDir(DIRECTORY_PICTURES)/<packageName>`, which `image_share_filepaths.xml` did not cover, so `getUriForFile` threw on every capture. `file_paths.xml` covers it only via `<external-path path="."/>` | Fixes the crash without exposing the whole of external storage through the provider |
| 2026-07-26 | Only the coordinating agent runs Gradle and git during parallel waves | The Gradle lock serializes task execution but does not protect `app/build` from concurrent writers; and an agent commit swept 286 unrelated files into one snapshot | Parallel agents edit files only; the coordinator runs one serialized wave-boundary build and owns all commits |
| 2026-07-26 | Keep `ObservationDeck.notify`'s try/catch, reversing an audit finding | `dexdump` proves `notify` has a 7-entry catch table with a `logLevel >= ERROR` branch; JADX dropped the region entirely, which is what produced the incorrect finding | DEX outranks decompiled Java per the AGENTS.md evidence order. Behavior is unchanged at the default `LogLevel.NONE`, where exceptions still propagate |
| 2026-08-03 | Handle captcha at the OkHttp layer (`CaptchaInterceptor`) and send the solution as `X-Captcha-*` headers, removing the per-screen flows and the deprecated `captcha_key` body fields | The API may challenge almost any request, so per-caller handling left every untaught caller failing on an opaque 400 — and Userdoccers records the body-field form as deprecated. The current mobile client does the same thing in one global response interceptor (`superagentPatch.tsx`) | A challenged response no longer reaches Retrofit: the challenge is solved and the identical request re-sent, so callers see one slow request. `WidgetCaptcha`, `WidgetAuthCaptcha` and `WidgetGuildJoinCaptchaBottomSheet` are deleted (their layouts are retained as recovered assets); `WidgetCaptchaBottomSheet` is now the solver's prompt. Bounded at `MAX_CHALLENGES = 2`; a declined challenge surfaces as `RestApiError.Type.CAPTCHA_CANCELLED` and is silent. The send queue opts out via `CaptchaRequestPolicy` so a backgrounded send cannot park a queue worker on a modal |
| 2026-07-27 | Add production Google Advertising ID support | The user explicitly approved `AD_ID` for replacement-client parity; recovered metadata identifies `play-services-ads-identifier:17.0.0` | GAID is fetched off-main and added to super properties only when nonblank, non-zero, and ad tracking is allowed; unavailable/limited devices omit it and no identifier is fabricated |
| 2026-07-26 | Name the reconstructed obfuscated classes `AppScreenRouter` (`b.a.d.j`) and `BackPressHandlerRegistry` (`b.a.d.d`) | The project forbids carrying obfuscated names into the target; responsibilities were recoverable from call sites | Consumers reference the descriptive names; the mapping is recorded here so reference lookups stay possible |
| 2026-07-26 | `AppLog` redacts every message through a `redactSecrets` pass | The security contract forbids logging tokens, cookies, MFA codes, passwords, and message content; the reference fan-out to Crashlytics/AnalyticsTracker does not exist in the target | Masks `token`/`access_token`/`refresh_token`/`session_id`/`authorization`/`cookie`/`password`/`mfa_code`/`backup_code`/`ticket`/`secret`/`api_key`/`captcha_key`/`fingerprint`/`content` in JSON, `k=v`, `k: v`, and `Bearer\|Bot\|Basic <value>` forms |
| 2026-07-26 | Host `leadingEdgeThrottle`/`LeadingEdgeThrottle` in `com.discord.stores.utilities` instead of the reference's `com.discord.utilities.rx` | `com.discord.utilities.rx` does not exist in the target yet and is outside the owning agent's file scope | The operator must move to `com.discord.utilities.rx` (with `ObservableExtensionsKt`, `computationLatest`, `appSubscribe`) when that package is reconstructed; `com.discord.stores.utilities.StoreUtilities.kt` also carries the reference's real `snapshotDirtyKeys` |
| 2026-07-26 | Route `Logger`/`AppLog` calls in `StoreV2` and `ObservationDeck` through `android.util.Log` | `com.discord.utilities.logging.Logger` and `com.discord.app.AppLog` are not reconstructed yet | Reference log levels and gating are preserved (`LogLevel.VERBOSE` breadcrumbs, `>= ERROR` notify errors); the calls should be redirected to `Logger` once it exists. `android.util.Log` is unmocked in JVM unit tests, so the off-store-thread report path cannot be asserted directly |
| 2026-07-26 | `AppActivity` registers its `OnBackPressedCallback` eagerly in `onCreate` with no `LifecycleOwner` | A lifecycle-bound callback only enters the dispatcher stack on its owner's `ON_START`, and from API 29 the activity's `ON_START` is delivered after its fragments'. That ordering put the activity *above* every screen and is what made Back leave the app | The activity is pinned to the bottom of the stack, so screen-owned callbacks and the `BackPressHandlerRegistry` always get the first look. It is removed with the activity, so no explicit teardown is needed |
| 2026-07-26 | `WidgetTabsHost` and `WidgetHome` both register at `BackPressHandlerRegistry.DEFAULT_PRIORITY`, relying on registration order for their relative precedence | This is what the reference does: every reference `setOnBackPressed` call site uses the default priority, and the tabs-host-before-home order falls out of `HashMap` bucket order for the two `javaClass.name` keys (computed and confirmed). Inventing distinct priority constants would diverge from the reference for no behavioral gain | A parent fragment's view is bound before its child's, so registration order reproduces the reference order deterministically. Pinned by `BackPressHandlerRegistryTest` |
| 2026-07-26 | Keep `ObservationDeck.notify`'s try/catch, contrary to the decompiled Java | `dexdump classes2.dex` shows `notify` has a 7-entry catch table: the loop is guarded, the handler compares `logLevel.compareTo(ERROR)`, calls `logNotifyError` when `>= 0` and rethrows otherwise, with `logBreadcrumb("notify END")` duplicated on all three exits. JADX dropped the whole region | Observer failures still propagate at the default `LogLevel.NONE`; only a deck configured to `ERROR`/`VERBOSE` absorbs and reports them, matching the reference |
| 2026-07-31 | Upgrade Google Play Billing from recovered `4.0.0` to `5.2.1` | `5.2.1` is the earliest public release explicitly compatible with Android 14; its API 33+ billing receiver registration uses `RECEIVER_NOT_EXPORTED` and it retains the reconstructed wrapper's `SkuDetails` and `Purchase` APIs | Avoids the Android 14+ startup `SecurityException` without receiver workarounds or a `ProductDetails` migration |
| 2026-08-01 | Do not use platform Android/AppCompat/Material dialogs in first-party application flows | The user explicitly requires Discord-styled first-party dialog and bottom-sheet UI throughout the app | First-party choices, confirmations, inputs, date/time pickers, and sheets use `AppDialog`, `AppBottomSheet`, selector dialogs, notice dialogs, or feature-owned fragment dialogs; this deliberately replaces the few 126.21 flows that used platform date/time dialogs |

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
| 2026-07-24 | OpenCode | Added secure direct token login through the existing authentication store and route flow | Build, unit tests, lint, and 6 focused Android 16 connected auth/navigation tests pass | Pause before Phase 6 |
| 2026-07-25 | OpenCode | Started Phase 6 with reference-aligned guild ordering/folders, guild/channel selection, permission filtering, typed member/role gateway updates, and real guild/channel/DM browser fragments | Debug assembly, full unit suite, lint with no new issues, and 16 focused tests pass; connected tests blocked because no device is attached | Finish unread state, folder drag persistence, member lists, and right/content panels |
| 2026-07-25 | OpenCode | Completed Phase 6 guild/channel navigation, folders, permissions, unread state, DMs, lazy members, panels, and production Gateway startup | Debug assembly and lint pass; focused unit/device checks passed earlier; user confirmed main screen boots and waived further tests after Android tooling repeatedly stalled | Begin Phase 7 messages and uploads |
| 2026-07-25 | OpenCode | Completed Phase 7 message history, optimistic per-channel queues, ordinary chat UI/input/actions, attachment uploads/progress, retry/CAPTCHA/AutoMod paths, pins/reactions/ACK, and background replay | Full unit suite, `assembleDebug`, and `lintDebug` pass; configuration cache enabled and reused | Begin Phase 8 users and social features |
| 2026-07-25 | OpenCode | Completed Phase 8 profiles/notes/mutual context, friends/blocked/pending relationships, suggestions/affinity, presence/typing/status, friends/settings/profile routes, and logout clearing | Full unit suite, `assembleDebug`, and `lintDebug` pass | Begin Phase 9 notifications and background work |
| 2026-07-25 | OpenCode | Corrected Phases 6-8 back to IN PROGRESS after runtime UI showed that compile/test coverage had been mistaken for visual and behavioral parity | Runtime evidence: missing/invisible UI, incomplete guild/DM/friends surfaces, and evolving READY parsing | Audit every Phase 6-8 screen and interaction against the reference before closing any phase |
| 2026-07-25 | OpenCode | Restored ExoPlayer resource enums to stop attachment-row inflation crashes, connected chat author profile clicks, and connected the sidebar notification action to channel mute state | Full unit suite and `assembleDebug` pass; `lintDebug` is blocked by 873 existing baseline/resource errors beginning with `manticord_app_name` missing translations; runtime retest pending | Retest attachment rows and profile/mute actions, then implement the reference channel search flow and permission-gated channel settings |
| 2026-07-25 | OpenCode | Fixed mutual-detail Back dispatch, keyed bottom navigation visibility to the left-panel state like the reference, and made guild icons/acronyms mutually exclusive | Full unit suite and `assembleDebug` pass; focused bottom-nav unit test passes; 12/14 connected tests pass, with existing auth-state and manifest-route tests failing independently | Runtime-check mutual-guild Back, guild icon fallback, and bottom navigation on the attached device |
| 2026-07-25 | OpenCode | Restored username click-to-mention, rebuilt pinned messages from standard chat rows, added live DM presence/activity and selected styling, and wired DM search/new-DM selectors | Full unit suite and `assembleDebug` pass; APK installs and launches on the attached device without a fatal log | Runtime-check the five corrected interactions; extend New DM to group-recipient multi-select after single-recipient behavior is verified |
| 2026-07-26 | Claude Code | Ran a 16-agent structural parity audit over every store, widget, view, model, and utility; committed 19 reports to `docs/parity-audit/`; reopened Phases 1/3/4/5 and replaced the parity ledger with measured coverage | Read-only audit; every finding cites `file:line` in the target and the reference class/method; JADX-broken regions are marked unverified rather than guessed | Execute the reconstruction campaign wave by wave, bottom-up |
| 2026-07-26 | Claude Code | Launched Waves 1, 2a, 2b, 2c (14 parallel implementation agents, disjoint file ownership): app framework base classes, store utilities, the 83-event gateway surface, textprocessing, icons/Fresco, permissions/entity utils, send pipeline, recycler/view utils, `attrs.xml` enums, 7 missing view classes, 13 view defects, REST endpoints, `api/**` and `models/**` subpackages | Each agent runs `compileDebugKotlin` + `testDebugUnitTest` and must leave the build green | Verify the wave boundary build, then start Wave 3 (store graph) |
| 2026-07-26 | Claude Code | Reconstructed `com.discord.stores.utilities` (`RestCallState` hierarchy, `BatchManager`/`Batched`, `LeadingEdgeThrottle`, `leadingEdgeThrottle`, `snapshotDirtyKeys`), throttled `StoreGuildsSorted` rebuilds to one per second on the leading edge, restored `StoreUser.snapshotData`'s per-source guard, made the `StoreV2` store-thread assertion log instead of throw, and restored `ObservationDeck` breadcrumbs plus `logNotifyError` | `compileDebugKotlin` in an isolated build directory reports zero errors in the owned files (the 20 remaining errors belong to other agents' in-flight files); `testDebugUnitTest` not run per the coordinator's serialized-build directive | Coordinator runs the wave-boundary build; then run the four new test classes |
| 2026-07-26 | Claude Code | Completed `Permission` (ELEVATED/MANAGEMENT/MODERATOR/stage masks, every bit decomposed against the reference), rewrote `PermissionUtils` into the reference three-way split (`computeNonThreadPermissions`/`computeThreadPermissions`/`computePermissions`) with MFA elevation, lurker and timeout allow-lists, and the `@everyone`-absent `DEFAULT` fallback; added `PermissionsContexts` (`ManageGuildContext`/`ManageMessageContext`/`ManageUserContext`), canonical `com.discord.api.channel.ChannelUtils`, `RoleUtils`, `UserUtils`, `PresenceUtils`/`ActivityUtils`, `GuildMemberUtils`, and `ChannelPermissionUtils` | `compileDebugKotlin` reports zero errors in the owned files (the 20 remaining errors belong to other agents' in-flight files); test sources unverified and `testDebugUnitTest` not run, per the coordinator's serialized-build directive | Store wave must converge `StoreChannels`/`StoreReadStates`/`StoreUserGuildSettings`/`WidgetChannelMembersList`/`CallModel` onto `ChannelUtils`, and `StorePermissions` onto `computePermissions` with stage instances and thread membership |
| 2026-07-26 | Claude Code | Reconstructed the send pipeline: `utilities/networking/Backoff` (jittered growth + process-wide pending gate), the reference-shaped `MessageQueue` (per-channel serialization, `DrainListener`, `InflightRequest`, timeout, rate-limit and network-backoff retry), `MessageRequest.Send/Edit` with `validateMessage()`, the full `MessageResult` outcome set with payloads restored (`Success(message)`, `CaptchaRequired(error, channelId, nonce)`, `AutoModBlock(error, message)`), `utilities/rest/SendUtils` (`SendPayload` preprocessing/ready stream), `utilities/rx/ObservableExtensions` with the `appSubscribe` error funnel, the `RestApiError` toast/log/handle surface plus `AppCancellationException`, and the hCaptcha round trip in `CaptchaHelper` | `MessageQueue.doSend` verified against DEX disassembly and the Vineflower Kotlin decompile (both agree); 4 new test classes added; Gradle not run per the coordinator's serialized-build directive | Coordinator runs the wave-boundary build; store wave moves the `StoreStream` upload path onto `MessageRequest.Send.attachments` |
| 2026-07-26 | Claude Code | Reconstructed the Gateway store layer: `IncomingParser` now types all 83 reference dispatch events (plus `USER_CONNECTIONS_INTEGRATION_JOINING`), `StoreGatewayConnection` owns the 79 subjects, `ClientState` socket lifetime, the store-owned send API and `BatchManager` VOICE_STATE_UPDATE coalescing; added `StoreConnectivity`, `StoreConnectionOpen`, `StoreClientDataState` (real guild-hash resume) and `utilities/networking/NetworkMonitor`; replaced `StoreStream`'s string `when` router with reference-shaped `initGatewaySocketListeners()` + `onBackpressureBuffer` `dispatchSubscribe`; added the missing outgoing opcodes (voice state, call connect, member request, stream create/watch/delete/ping, application commands, forum unreads) | Event table, subject types and Behavior/Publish choice verified against the Vineflower Kotlin decompile; removed the invented `CHANNEL_PINS_UPDATE` route (absent from the reference); Gradle not run per the coordinator's serialized-build directive | Next wave connects the 25 subjects whose destination store does not exist yet (listed as TODOs on `initGatewaySocketListeners`) |
| 2026-07-26 | Claude Code | Reconstructed the full REST contract: `RestAPIInterface` grew from 47 to 285 declarations covering all 264 unique reference verb+path pairs, and `RestAPIParams` from 27 to 108 request bodies (every reference body, including `Role`, `Nick`, `GuildMember`, `ChannelPermissionOverwrites`, `ChannelPosition` + its parent-id serializer, `UserSettings` and its 18 factories, `Invite`, `ThreadCreationSettings`, `ComponentInteraction`/`ModalInteraction`); registered `ChannelPosition.Serializer` in `RestAPIBuilder`; added `RestEndpointContractTest` (guild/channel CRUD, member/role, threads, invites, user settings, search) | Verb mapping confirmed from Retrofit's own `parseMethodAnnotation` in the dex2jar (`i0.f0.b`=DELETE, `f`=GET, `g`=HEAD, `n`=PATCH, `o`=POST, `p`=PUT, `m`=OPTIONS, `h`=HTTP, `l`=Multipart; `s`=Path, `t`=Query, `i`=Header, `q`=Part, `a`=Body, `y`=Url); every signature's names/nullability/defaults checked against the Vineflower Kotlin decompile; endpoint set diffed programmatically against the reference (0 missing); Gradle not run per the coordinator's serialized-build directive | Coordinator runs the wave-boundary build; `utilities/rest/RestAPI` should now be widened onto the new endpoints |
| 2026-07-26 | Claude Code | Fixed the user-reported "Back closes the app" bug. Root cause: `AppActivity` registered its `OnBackPressedCallback` with the activity as `LifecycleOwner`, and from API 29 the activity's `ON_START` is dispatched *after* its fragments', which put the activity on top of the dispatcher stack so it swallowed every press before any screen saw it. Registered it eagerly and owner-less so it is the true fallback; replaced its `moveTaskToBack` fallback with the reference's pop-or-finish plus the `IllegalArgument`/`IllegalStateException` swallow; wired `WidgetTabsHost` and `WidgetHome` into `BackPressHandlerRegistry` through `AppFragment.setOnBackPressed` and deleted the bespoke `BackHandler` interface; restored the reference's home-tab panel action on tab selection; restored the resting panel in `OverlappingPanelsLayout.onRestoreInstanceState` before layout | Reference dispatch order derived from `b.a.d.d.onBackPressed`, `AppFragment.setOnBackPressed` (key = `javaClass.name`, default priority `Integer.MAX_VALUE`), `WidgetHome.handleBackPressed`, and `TabsHostViewModel.handleBackPress`; tabs-host-before-home confirmed by computing the reference `HashMap` iteration order for the two class-name keys. 4 unit tests added to `BackPressHandlerRegistryTest` plus a new instrumented `HomeBackPressTest`; Gradle not run per the coordinator's serialized-build directive | Coordinator runs the wave-boundary build and `connectedDebugAndroidTest`; `widgets/chat` must move `WidgetChatInput` and `WidgetChannelPinnedMessages` off their bespoke `OnBackPressedCallback`s onto `setOnBackPressed` |
| 2026-07-26 | Claude Code | Rebuilt the guild sidebar as the reference's 7-class subsystem: `WidgetGuildsListViewModel` (all 23 `StoreState` fields, the 10-row assembly recovered from `handleStoreState` bytecode, drag preview/rollback, `RestAPI.updateUserSettings` persistence), `WidgetGuildListAdapter` (10 view types, `chooseDropTarget`, the TOP/CENTER/BOTTOM validity matrix, pending-guild guard), `FolderItemDecoration`, `GuildListItemBuilder`, and a fragment that is now only inflation + navigation; added `widgets/guilds/contextmenu` (guild + folder menus and their view models, circular-reveal anchoring); deleted the invented `GuildFolderReorder`/`GuildFolderDragCallback`/`GuildFolderPersistenceRequest`; completed `RoleIconView`'s public API | `handleStoreState` and `WidgetGuildListAdapter.onMove` are not decompiled by JADX and Vineflower fails on the view model, so both were reconstructed from `javap -c` of the dex2jar, instruction by instruction; 8 new unit tests in `GuildListItemBuilderTest`; Gradle not run per the no-Gradle directive | Wire the 12 sidebar inputs listed under "Guild sidebar — inputs with no source yet" in `docs/parity-audit/followups.md`; then rebuild the guild create/join/invite/profile clusters |
| 2026-07-26 | Claude Code | Made the chat-input autocomplete cluster reachable (`InputAutocomplete` wired to `chat_input_mentions_recycler` + the FlexInput `EditText`; mention-map tracking and `replaceAutocompleteDataWithServerValues` so typed `@name#0001`/`#channel`/`:emoji:` leave as `<@id>`/`<#id>`/`<:name:id>`); built `ChatInputViewModel` with the reference's 26-field `ViewState.Loaded` and the real `configureChatGuard` (lurker, follow, verification-level, system DM, timeout countdown, member-screening); added `GuildVerificationLevelUtils`, `MemberVerificationUtils.hasVerificationGate`, `DurationUtils.humanizeCountdownDuration`; moved `expressionBackCallback` onto `AppFragment.setOnBackPressed`; rebuilt the emoji picker on `EmojiPickerViewModel` + `WidgetEmojiAdapter` + `EmojiCategoryAdapter`/`EmojiCategoryItem` (flat headed grid, per-guild categories that finally instantiate `GuildIcon`, catalog parsed off the main thread with a logged failure path); reconstructed the bot UI-kit `SelectComponentBottomSheet` cluster (sheet, adapter, item, view holder, view model, `ComponentContext`) and replaced the chat row's `MaterialAlertDialogBuilder` stand-in with it | `replaceAutocompleteDataWithServerValues` ordering proved **descending** from `javap -c` (`$$inlined$sortedByDescending$1`) — ascending would corrupt any message with two mentions; `ViewState.Loaded` field order and every guard predicate taken from JADX `ChatInputViewModel.handleStoreState`; 3 new test classes (17 tests); Gradle not run per the no-Gradle directive | GIF picker (17 classes), expression tray (8), attachments UI, `WidgetChatInputEditText`, `AppFlexInputViewModel` remain; see the new followups entry for the store seams that gate them |
| 2026-07-27 | OpenCode | Completed the scheduled-event create/edit/start flow and role-subscription tier/setup/benefit slice, wired contact-sync and guild-profile stores, repaired five compile-time API drifts, isolated the duplicate read-state ack cache after a device `ClassCastException`, corrected the emoji category overline ID after a device inflation crash, and cleared the 16-error lint frontier | `assembleDebug`, all 1,166 `testDebugUnitTest` tests, and `lintDebug` pass; focused scheduled-event and ack-cache regressions pass | Runtime-retest read-state startup and both emoji category row types; reconstruct the shared channel/emoji pickers before enabling the deferred role-subscription benefit selectors |
| 2026-07-27 | OpenCode | Published `StoreLurking` and `StoreGuildWelcomeScreens` through `StoreStream`; restored lurk filtering in guild/channel/selection persistence and sorted guilds; routed READY, guild, selection, and voice events; connected stage, home, and chat-input consumers; restored reference `StoreNavigation.launchNotice` semantics | `assembleDebug`, full `testDebugUnitTest`, focused `StoreLurkingTest`/`StoreStreamWiringTest`, and `lintDebug` pass | Device-test previewing a guild, navigating away while connected/not connected to voice, converting the preview to membership, and welcome-screen presentation; then install `VoiceTransport` |
| 2026-07-27 | OpenCode | Installed `VoiceTransport` as the production voice composition root with shared connectivity and RTC-region probing; registered its stores; restored voice/stream gateway fan-out, resume/logout behavior, speaking and ping feeds, cross-channel stream targeting, public accessors, and idempotent transport teardown | `assembleDebug`, full `testDebugUnitTest`, `lintDebug`, five focused non-native voice-store test classes, and two Android 16 native lifecycle instrumentation tests pass | Exercise a real approved test-account voice join/leave/move and Go Live watch flow; then connect the notice queue to the activity host |
| 2026-07-27 | OpenCode | Connected `StoreNotices.getNotices()` to `AppActivity`; delivery now waits for a resumed valid activity/fragment screen, retries after fragment and activity resumes, preserves breadcrumb/auto-seen/show ordering, and releases lifecycle subscriptions and callbacks | `assembleDebug`, full `testDebugUnitTest`, `lintDebug`, and `NoticeHostTest` on Android 16 pass | Add `WidgetCallFullscreen` to `DEFAULT_VALID_SCREENS` when that screen exists; otherwise continue the Wave 3 store graph |
| 2026-07-27 | OpenCode | Installed the existing application-command/frecency/local-component/interaction stores in `StoreStream`; restored READY, connection, logout, channel, message, command-result, autocomplete, and interaction gateway fan-out; published their accessors and completed `ComponentChatListState.observeChatListComponentState()` | `assembleDebug`, full `testDebugUnitTest`, `lintDebug`, focused `StoreStreamWiringTest`, and `BotUiKitContractsTest` pass | Move slash-command sends onto `MessageQueue`, then complete `ComponentStateMapper` and the remaining bot UI-kit render pipeline |
| 2026-07-27 | OpenCode | Ran two exact-reference reconstruction waves: completed queued slash-command attachments, READY hydration error/scalar merge, RTC voice properties/AFK correction, embedded/subscription/payment gateway stores, directories, bot-component mapping, shared channel picker, account editing, group DMs, mobile reports, server roles, invite/sticker rows, user-sheet moderation, chat-list models, push payload parsing, security utility fixes, and several store slices; fixed chat usernames by restoring the author entry in `nickOrUsernames` | `compileDebugKotlin`, all 1,257 `testDebugUnitTest` tests, `assembleDebug`, `lintDebug`, Android-test compilation, startup, command attachment/presentation and chat-input device tests pass on Android 16; invalid attachment-sheet harness removed without changing production lifecycle | Pause requested. Next coordinator integrates completed graph-only stores (`MaskedLinks`, NSFW, stream previews, invite settings, media favorites, notification upsells, directories, analytics) and routes their consumers; `ModalComponent` agent was cancelled and made no changes |
| 2026-07-27 | OpenCode | Fixed the reported runtime/UI regression batch against reference evidence: friends toolbar streams now marshal to UI; FlexInput restoration adapts the public dependency to the patched APK lifecycle; chat send visibility follows text; header prefixes/actions/tints reset per channel; guild/DM switching restores each surface's last channel; DM start rows use recipient avatars; emoji search visibility, animated banners/stickers, voice-sheet routing and nested settings/pins Back behavior were restored | All 1,261 unit tests, `assembleDebug`, and `lintDebug` pass; Android tests compile, but runtime rerun was unavailable because the device disconnected | Device-check FlexInput process restoration, DM/guild toolbar transitions, animated assets, voice channel sheet, and nested Back; widget structural coverage is now 651/1,155 exact paths (56.4%), with behavioral parity still unmeasured pending a fresh audit |
| 2026-07-27 | OpenCode | Restored the recoverable 126.21 REST message identity contract offline: `Discord-Android/126021`, weighted device `Accept-Language`, `X-Super-Properties`, no invented `enforce_nonce`, and no interceptor/legacy hidden POST replay; fixed `InlineMediaView`'s ExoPlayer 2.16 listener ABI by implementing the complete runtime callback interface | Focused REST/transport/media tests, full unit suite, `assembleDebug`, and `lintDebug` pass; no live Discord request was made | A server-side spam classification cannot be diagnosed or reversed offline; keep live tests opt-in and route the remaining legacy attachment sender fully onto `MessageQueue` before live upload testing |
| 2026-07-27 | OpenCode | Restored the missing real `X-Super-Properties` producers: exact one-second `/proc` CPU/RSS/core monitor starts during app initialization, and `StoreAccessibility` publishes cumulative accessibility flags in reference snapshot order | Focused resource/accessibility tests, full unit suite, `assembleDebug`, and `lintDebug` pass; no network/device used | `device_advertiser_id` remains absent because 126.21 obtains GAID through Adjust and this project has no approved Adjust/AD_ID integration; do not fabricate or copy it |
| 2026-07-27 | OpenCode | Added legitimate GAID production support with recovered Play Services 17.0.0, `AD_ID`, asynchronous startup retrieval and limited-tracking/zero-ID rejection; fixed optimistic outgoing messages to carry `Loaded.me` so the sender name renders correctly before gateway reconciliation | Focused GAID/message-send tests, full unit suite, `assembleDebug`, and `lintDebug` pass; no live request made | Device-check GAID availability on Google Play and verify the optimistic username transitions cleanly to the gateway message |
| 2026-07-27 | OpenCode | Ran a 20-agent exact-reference widget wave: added command-composer widgets, full chat-list model/actions/reactions, server overview/channel sorting, MFA and billing screens, channel-list/settings gaps, guild create/context flows, hub event details, voice sheets, auth continuations, forums, NUX, user-profile helpers and `ModalComponent`; fullscreen call correctly stopped on missing service/activity dependencies | Integration compile, all 1,304 unit tests, `assembleDebug`, and `lintDebug` pass after four cross-slice API fixes | Exact-path widget coverage is 703/1,155 (60.9%), leaving 452; wire new entry points/stores centrally, then prioritize chat (142), servers (67), channels (55), settings (33), guilds (31), hubs (28), voice (24), guild events (17), and auth (16) |
| 2026-07-28 | OpenCode | Ran a second 20-agent exact-reference widget wave: completed autocomplete command wiring, chat entries/overlay/pins/mentions, server moderation/community/emoji, channel permissions/member lists/thread browser, authorized apps/settings, guild join/profile identity, hub onboarding, voice feedback/prerequisites, scheduled events, auth SMS, and user status/mentions; GIF/sticker/NUX audits avoided duplicate work | Production compile, all 1,355 unit tests, `assembleDebug`, and `lintDebug` pass after grouped integration repairs | Exact-path widget coverage is 778/1,155 (67.4%), leaving 377; largest deficits are chat 138, servers 59, channels 32, settings 29, hubs 28, guilds 18, auth 15, voice 12, guild events 10, and NUX 8 |
| 2026-07-29 | OpenCode | Ran a third 20-agent exact-reference widget wave: completed chat host/Flex/remote commands/media helpers, community wizard, server/channel/settings/premium/profile flows, hub concrete-store migration, guild clone/join, auth landing/age verify, scheduled-event wiring, exact NUX path split, bot-modal auto-open and small-package coverage; fullscreen voice stayed blocked on services/routes | Production compile, all 1,404 unit tests, `assembleDebug`, and `lintDebug` pass after integration and fixture corrections | Exact-path widget coverage is 819/1,155 (70.9%), leaving 336; NUX is structurally complete, while chat 132, servers 52, hubs 28, channels 24, settings 21, guilds 17, auth 13 and voice 11 remain largest |
| 2026-07-29 | OpenCode | Ran a fourth 20-agent mixed wave: wired ten completed stores into `StoreStream`, activated provider-neutral notifications, added overlay/voice-service prerequisites, and closed large exact-path gaps across chat, servers, hubs, channels, settings, guilds, scheduled events, bot UI, subscriptions, forums, user, stage and small packages | Production compile, all 1,440 unit tests, `assembleDebug`, and `lintDebug` pass after integration and asynchronous test corrections | Exact-path widget coverage is 929/1,155 (80.4%), leaving 226; hubs, guild events, forums and user are structurally complete; remaining deficit is concentrated in chat 111, servers 40, channels 23, settings 14, auth 13 and voice 9 |
| 2026-07-29 | OpenCode | Ran a fifth 20-agent closure wave: split consolidated chat entries/models/commands, completed member verification/server/settings/guild/channel/voice/small paths, structurally closed hubs/events/forums/user/stage/botuikit/subscriptions, and added exact notification/store graph integrations; overlay service correctly stopped on RTC state mismatch | Production compile, all 1,440 unit tests, `assembleDebug`, and `lintDebug` pass after integration, nullable token persistence, async test and API-21 fixes | Exact-path widget coverage is 1,071/1,155 (92.7%), leaving 84; 36/45 packages are structurally complete, with remaining gaps concentrated in chat 37 and servers 33 |
| 2026-07-30 | OpenCode | Ran a 21-agent widget completion wave across chat, server settings, channels, settings, auth, voice and residual packages; restored concrete message/action/invite/server flows, exact auth source paths, transient-only auth credentials, channel selection through `ChannelSelector`, first-start centering with saved-panel preservation, and corrected guild image update hashes | Production compile, all 1,443 unit tests, `assembleDebug`, and `lintDebug` pass; no live backend or device used | Exact-path widget coverage is 973/1,155 (84.2%), leaving 182; remaining deficits are chat 84, servers 39, channels 23, settings 11, voice 9, guilds 4, and 12 across smaller packages. Structural coverage remains an upper bound pending behavioral/device audit |
| 2026-07-30 | OpenCode | Fixed detached tabs-host navigation crashes by lifecycle-guarding and clearing all host-owned global callbacks; initialized a fallback FlexInput keyboard manager before pause can occur; added Android 13 notification permission requesting and stopped requesting capped legacy storage permissions on newer Android versions | All 1,443 unit tests, `assembleDebug`, `lintDebug`, and Android-test compilation pass; the focused detached-host test passes on Android 16; installed APK cold-start/background produces no `AndroidRuntime` fatal | The source and merged manifests already contain every permission required by implemented features. Full `MainHostNavigationTest` still has three unrelated existing failures in home visibility and app-action routing |
| 2026-07-30 | OpenCode | Fixed Back navigation while retaining one app dispatcher call: AndroidX fragment managers pop nested/server settings stacks, and pinned messages use a stable center overlay so their pop never reconstructs chat into a missing parent container | All 1,443 unit tests, `assembleDebug`, `lintDebug`, and Android-test compilation pass; all 3 physical-Back `NestedBackPressTest` cases pass on Android 16 | Continue converting process-global navigation seams to direct router calls; do not add another custom back-stack dispatcher |
| 2026-07-30 | OpenCode | Fixed the physical phone Back path on Android 13+: AppCompat 1.2 predates predictive Back integration, so Android 16 bypassed `OnBackPressedDispatcher` and finished the activity; the application now opts out through `enableOnBackInvokedCallback=false`, preserving the reconstructed reference-era dispatcher | All 1,443 unit tests, `assembleDebug`, `lintDebug`, and Android-test compilation pass; `NestedBackPressTest` now uses `Espresso.pressBack()` and all 3 physical-Back cases pass on Android 16 | Keep predictive Back disabled until the app framework is upgraded and its callback semantics are reconstructed end-to-end |
| 2026-07-30 | OpenCode | Corrected full-screen navigation to the reference activity stack: `SettingsNavigation` now launches non-exported `AppActivity` screen intents instead of replacing `Main.app_root`, direct-screen activities no longer subscribe to `MainRoute` or back-stack their root fragment, and Back reveals the unchanged Home view and panel state; also restored FlexInput's consumed XML initializer on view recreation and replaced phone input's incompatible default-method lifecycle observer with `LifecycleEventObserver` | Full unit suite and forced `lintDebug --rerun-tasks` pass; all 4 physical-Back/navigation cases and the focused direct-auth phone-input lifecycle test pass on Android 16 | Keep tab-local settings nested in `WidgetTabsHost`; use `AppScreenRouter` only for reference full-screen destinations. Do not restore the obsolete in-place `SettingsNavigation` transaction |
| 2026-07-30 | OpenCode | Integrated the sixth parallel widget closure wave across chat exact paths, server monetization/boost/member verification, channels, auth/captcha/contact sync, PlayStation, home, bot UI, stage and voice prerequisites; then closed six residual chat facades and seven unblocked server role-subscription/member-verification paths | Production Kotlin compiles; all 1,492 unit tests, `assembleDebug`, and `lintDebug` pass; no live backend or device used | Physical source-path widget coverage is 1,144/1,155 (99.0%), leaving 11 filenames; four already emit the exact reference JVM classes from consolidated Kotlin files. Seven real blockers remain: Samsung OAuth, two billing-dependent boost screens, and four fullscreen voice screens |
| 2026-07-30 | OpenCode | Completed the fullscreen voice vertical slice: wired application stores and live `CallModel` supplements, restored RTC stream quality and media-sink focus negotiation, implemented active/preview call state machines and screens, dedicated call activity/routing, overlay service, foreground voice service/controller, call sounds, screen-share previews, private notification actions and stage invite actions | Production Kotlin compiles; all 1,514 unit tests, `assembleDebug`, and `lintDebug` pass; no live backend or device used | Physical widget source coverage is 1,148/1,155 (99.4%); four unmatched filenames already emit exact JVM classes. Runtime-check call/video/screen-share/overlay/service teardown on ARM64 before claiming voice behavior parity; only Samsung OAuth and two billing-dependent boost screens remain structurally blocked |
| 2026-07-31 | OpenCode | Fixed audit-log REST decoding by installing the reference streaming `Model` adapter, pinned all 47 Discord 126.21 action/type/target/title mappings from bytecode evidence, and kept future codes visible as unknown rows | `javap -p -c` verified enum ordinals and action tables; the 48-row JSON fixture passes `jq`; `git diff --check` passes. Gradle and live backend intentionally not used | Run `AuditLogContractTest` with the next coordinator-owned unit-test batch |
| 2026-07-31 | OpenCode | Ran a multi-agent runtime-regression closure: fixed saved-state pins navigation and right-panel overlay ordering; server reorder/role/reaction nullable-`Void` success handling; guild-subscription dispatcher threading; overview include roots; audit-log loading/action decoding; settings hierarchy/Back; guild creation parcelables; guild-sheet geometry; friend/member live updates; search Back; false guild voice icons; privacy REST wiring; authorized-app headers; FlexInput camera resource collision; deterministic chat emoji/keyboard state; sidebar IME dismissal; semantic button tints; Billing 5.2.1 Android-14 compatibility; complete guild-boost checkout | Production Kotlin compiles; all 1,552 unit tests, `assembleDebug`, and `lintDebug` pass; no live backend used in automated checks | Physical widget source coverage is 1,150/1,155 (99.6%); four unmatched filenames already emit exact JVM classes, leaving only Samsung OAuth externally blocked. Device-retest the reported flows and fullscreen voice lifecycle |
| 2026-07-31 | OpenCode | Ran a missing-surface reconstruction wave: recovered first-party dialogs and urgent-message flow, seven search/GIF/expression stores, three background workers, gateway REST/payload/logging glue, bot component and domain model adapters, null serialization, screenshot/i18n/app infrastructure, high-value utilities, live privacy consent terminal states, exact channel ordering diagnostics/optimism, profile media actions, dedicated animated pins route, user-sheet DM actions, mutual full-screen routing, friends DM selection, and member-list request/render wiring | Production Kotlin compiles; all 1,613 unit tests, `assembleDebug`, `lintDebug`, and `compileDebugAndroidTestKotlin` pass; no device attached and no live backend used | Continue from the ranked remaining ledger in `docs/parity-audit/followups.md`; highest behavioral gaps are Samsung OAuth, stage moderation actions, group-invite flow, scheduled-event location selection, Spotify provider, push/dynamic-link provider integration, residual dialog entry-point wiring, and ARM64/device regression |
| 2026-07-31 | OpenCode | Closed the next runtime/missing-feature wave: marshalled dimmer mutations to main, removed FlexInput attachment-preview resource collision, restored API-aware media permissions, corrected channel drag deltas and production-client diagnostics, implemented stage user-sheet moderation, group-invite friends, scheduled-event location selection, residual dialog reachability, user-sheet/friends DM navigation and mutual routes | Production Kotlin compiles; all 1,635 unit tests, `assembleDebug`, `lintDebug`, and `compileDebugAndroidTestKotlin` pass; no live backend used | Samsung OAuth is explicitly out of scope per user. Remaining work is provider integration (Spotify, project FCM/dynamic links), a few store/utility seams, unreachable premium/gift dialog call sites, and device validation using `ChannelReorder` diagnostics |
| 2026-07-31 | OpenCode | Fixed device-reported infrastructure regressions and continued parity: `withDimmer` UI-thread marshalling, FlexInput preview layout collision, version-aware gallery/camera permissions, production-path channel reorder delta/diagnostics, stage moderation actions, group invite friends, event location selection, dialog entry-point reachability, profile media results, user-sheet/friends DM selection, mutual full-screen routes and current-user popout actions | Production Kotlin compiles; all 1,635 unit tests, `assembleDebug`, `lintDebug`, `compileDebugAndroidTestKotlin`, and `git diff --check` pass | Samsung OAuth remains excluded. If channel reorder still fails, capture the sanitized `ChannelReorder` request/error lines; remaining feature work is Spotify/provider integration, store/utility seams, unreachable premium/gift dialog owners, and ARM64 regression |
| 2026-07-31 | OpenCode | Corrected three runtime-disproved behaviors: channel reorder now retains generation-aware optimistic ordering across stale store snapshots, Privacy & Safety handles cold REST installation plus 200/204/error terminal states, and Pinned Messages uses horizontal activity transitions rather than vertical slide | Full unit suite, `assembleDebug`, `lintDebug`, and Android-test compilation pass; focused privacy instrumentation passed twice on the physical device | Continue device regression; overlapping reorder generations and failure rollback are covered deterministically |
| 2026-07-31 | OpenCode | Updated phase statuses 9-13 to `IN PROGRESS`; fixed FlexInput's theme-less Fresco file placeholder, routed image attachments through the canonical `MessageSendCoordinator`/`MessageQueue`, increased Kotlin daemon heap for clean builds, and stopped Privacy consent loading from blocking the entire local-settings screen | Recovered icon inflates cleanly on device; PNG multipart/queue device test and focused consent unit tests pass; rebuilt APK installed with `adb install -r` and cold-launched on device `188b1209` | Retest Privacy UI and image send on the installed APK; consent rows remain hidden only while their finite GET is pending. Preserve `ChannelReorder` logs for any remaining reorder failure |
| 2026-07-31 | OpenCode | Fixed the actual Privacy cold-start deadlock by seeding explicit-content, default-guild-restriction, and friend-source BehaviorSubjects with the same values used by reset, allowing the screen's `combineLatest` to emit before READY refreshes them | Rebuilt APK installed with `adb install -r`; user confirmed Privacy & Safety now opens correctly on device `188b1209` | Continue device regression for image sends and channel reorder |
| 2026-07-31 | OpenCode | Made client-data guild-hash handling null-safe for partial emoji/sticker gateway updates, preventing the Main-StoreThread crash after emoji changes | Focused emoji/sticker partial-payload and sendability tests pass; rebuilt APK installed with `adb install -r` on device `188b1209` | Continue emoji/sticker device regression; DEX confirms 126.21 has no sticker-unlock experiment and standard stickers remain Nitro-gated |
| 2026-07-31 | OpenCode | Natively ported Aliucord's requested `DefaultStickers` behavior and fixed attachment-only composer visibility: non-Nitro users see/send standard packs, sticker-only messages pass validation and use scoped RN request metadata, and Send follows text-or-attachment selection count | Focused picker/sendability/queue/coordinator/header tests and `assembleDebug` pass; APK installed with `adb install -r` on device `188b1209` | Device-check standard sticker send and attachment add/remove visibility; RN metadata is isolated to non-premium sticker-id sends |
| 2026-07-31 | OpenCode | Replaced token login's platform `AlertDialog` with the reconstructed Discord-styled `WidgetNoticeDialog` input modal, preserving inline blank validation and preventing token field state persistence | Production Kotlin compiles, `assembleDebug` passes, and APK installed with `adb install -r` on device `188b1209` | Device-check token dialog styling, validation, cancel and successful login |
| 2026-07-31 | OpenCode | Restored animated profile-banner autoplay with reduced-motion gating and completed DM/group-DM notification settings parity with mute durations, private-channel payloads, optimistic confirmation and rollback | Focused profile-banner and DM-notification tests plus `assembleDebug` pass; APK installed with `adb install -r` | Device-check animated user/guild-member banners and DM mute/unmute duration/Back/error behavior |
| 2026-07-31 | OpenCode | Fixed user-popout banner source to use the fetched profile payload's banner/hash and color rather than the cached basic user; retained guild-member override, autoplay and reduced-motion gating | Production Kotlin compiles, `assembleDebug` passes, and APK installed with `adb install -r` | Device-check animated banner specifically in user popout |
| 2026-07-31 | OpenCode | Replaced the remaining channel-header notification platform dialog with `WidgetMuteSettingsSheet`, restored the persistent pins toolbar action, and refreshed FlexInput media pages immediately after first permission grant | Focused notification/pins/permission tests and `assembleDebug` pass | Device install/retest blocked because ADB device disconnected |
| 2026-07-31 | OpenCode | Fixed the Friends Message action to reuse an existing 1:1 DM or wait for DM creation/ingestion, then select guild `0`, select the DM channel, and finally switch Home/close panels; added direct cached and asynchronous ordering tests | Reference Friends callback, `ChannelSelector`, and `RestAPI.createOrFetchDM` ordering verified against Vineflower/JADX; `git diff --check` passes. Gradle, live backend, and device intentionally not used | Run `ChannelSelectorDirectMessageTest` in the next coordinator-owned unit-test batch and device-check both existing and first-message DM rows |
| 2026-07-31 | OpenCode | Fixed FlexInput gallery/camera runtime permissions: API 33+ direct MediaStore gallery uses `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, API 23-32 uses capped `READ_EXTERNAL_STORAGE`, camera uses `CAMERA`, obsolete write storage was removed, and Discord FlexInput page subclasses now request through `AppFragment` before exposing content while the system chooser remains permission-free | Direct API/manifest contract tests added and `git diff --check` passes; focused Gradle run is blocked by the unrelated raw `Observable` at `WidgetGroupInviteFriends.kt:409` | Run `AppPermissionsTest` after the concurrent group-invite compile error is fixed, then device-check deny/grant/permanent-deny behavior on API 32 and 33+ |
| 2026-07-31 | OpenCode | Reconciled Phases 9-13 and the parity ledger against the latest progress/followups and current test surface; replaced the 2026-07-26 counts with measured REST, Gateway, widget-path, and test checkpoints and recorded Samsung OAuth as out of scope | Documentation-only change; `git diff --check -- PLAN.md` passes. No Gradle, device, or live-backend check run | Continue provider integration, residual store/utility seams, and ARM64/device regression without marking these phases done |
| 2026-07-31 | OpenCode | Restored private-channel notification writes and UI applicability: guild ID `0` now uses the dedicated `users/@me/guilds/@me/settings` endpoint with the exact channel-override body, private overrides update optimistically with server confirmation/current-write rollback, and DM/group-DM notification routes show only the reference mute-duration surface rather than guild frequency/forum/system controls | Focused notification/store/REST tests, `assembleDebug`, and `lintDebug` pass; no live backend or device used | Device-check DM and group-DM mute/unmute duration selection, Back, failure toast, and server confirmation; guild notification screens were intentionally unchanged |
| 2026-07-31 | OpenCode | Fixed first-grant FlexInput refresh: gallery/files now replace permission gates and reload in place, camera resumes its existing page, permission cancellation/partial grants remain denied, and all paths retain the shared attachment selection and composer view | Production and Android-test Kotlin compile; focused `AppPermissionsTest` passes. The focused three-case device test could not run because no device is connected; the full unit run has two unrelated concurrent failures in channel reorder and privacy settings | Run `FlexInputPermissionRefreshTest` on API 23-32 and API 33+ devices to validate the system permission UI around the covered denial/grant transitions |
| 2026-07-31 | OpenCode | Corrected the device-reported static profile banner and search icons: animated `a_` user and guild-member banners again request the reference GIF path now that Fresco's GIF decoder is installed; avatar drawables use background-colored overlay clipping; guild search rows use the existing `GuildView`, including short-name initials when no icon exists | Focused `IconUtilsTest` and `ViewGlobalSearchItemTest`, `assembleDebug`, and `lintDebug` pass; the final `GuildView` integration also passes `assembleDebug`; no device or live backend used | Install and device-check animated popout banners plus user, DM, group-DM, and iconless guild search results |
| 2026-08-01 | OpenCode | Restored the reference self-profile action surface: user popouts no longer expose header avatar/banner pen controls, current-user sheets show Edit Default Profile, and guild-scoped current-user sheets also show Edit Server Profile with the recovered experiment-gated full-editor/legacy-identity routing; removed the tabs-host member-list override that discarded channel/guild context before opening the sheet | Focused `WidgetUserSheetActionsTest` and `assembleDebug` pass; no live backend used | Device-check both self-profile entry contexts, especially member-list launch, and each edit destination |
| 2026-08-01 | OpenCode | Migrated `WidgetUserSheet` from repeated `findViewById` lookups to generated `WidgetUserSheetBinding` with view-lifecycle cleanup, and made generated bindings mandatory in `AGENTS.md` whenever a layout provides one | Focused `WidgetUserSheetActionsTest`, production Kotlin compilation, and `assembleDebug` pass | Device-check sheet recreation and all profile action sections; use direct lookup only for genuinely dynamic child views |
| 2026-08-01 | OpenCode | Added Phase 15 for repository-wide migration of eligible static `findViewById` calls to AGP-generated bindings, including lifecycle rules, dynamic-lookup exceptions, verification boundaries, and a documented final allowlist | Documentation-only change; `git diff --check -- PLAN.md` passes | Inventory first-party lookups before assigning exclusive per-file migration slices |
| 2026-08-01 | OpenCode | Fixed successful kick handling by normalizing empty HTTP completions and dismissing the parent user sheet through a fragment result; connected `StoreMessageAck`'s bottom-of-chat detector to authoritative `StoreReadStates` so local badges/new-message markers clear and REST acks post; stopped one-shot acks replacing the long-lived auto-ack subscription; made Developer Options/experiment overrides permanently visible and routable; migrated `WidgetSettings` and `WidgetKickUser` to generated bindings | Focused kick, read-state, and settings-route tests plus `compileDebugKotlin` and `assembleDebug` pass; one unrelated existing privacy assertion fails when the entire `WidgetSettingsTest` class is selected | Device-check kick success/dismissal, automatic read clearing at chat bottom, and Developer Options experiment overrides |
| 2026-08-01 | OpenCode | Fixed Developer Options navigation by restoring toolbar Up and making `WidgetTabsHost` pop nested child destinations before switching to Home; restored the exact 37-entry Discord 126.21 `ExperimentRegistry` so untouched experiments remain visible with readable bucket choices; migrated the developer screen to generated binding; replaced `SearchInputView`'s ABI-incompatible `DefaultLifecycleObserver` with `LifecycleEventObserver`; added the reference-navigation rule to `AGENTS.md` | Focused registry and tabs tests plus `assembleDebug` pass after stopping stale Gradle daemons | Device-check Developer Options Up/physical Back, experiment override persistence, and every `SearchInputView` host lifecycle |
| 2026-08-01 | OpenCode | Fixed the Developer Options experiment-list crash: `ExperimentOverridesAdapter` now gives RecyclerView the generated binding root instead of the already-attached nested `ExperimentOverrideView` | Focused registry test and `assembleDebug` pass | Device-check scrolling, selecting, and clearing experiment overrides |
| 2026-08-01 | OpenCode | Fixed live DM badges in the guild sidebar by moving `WidgetGuildsListViewModel` from lagging server read-state mention counts to the same live `StoreMentions` map used by bottom navigation | Focused guild-list builder tests, including an incoming-DM row assertion, and `assembleDebug` pass | Device-check incoming DM appearance/count and clearing after opening the conversation |
| 2026-08-01 | OpenCode | Fixed stale DM badge clearing by invalidating `StoreMentions` when the local ack cursor advances; fixed invite acceptance gray-screen navigation by waiting for the gateway-backed joined channel before changing guild/channel selection and never selecting a guild absent from the store | Focused guild-list and guild-join tests plus `assembleDebug` pass | Device-check an existing 19-count DM clears after reading and accept a guild invite without leaving the current screen gray during gateway hydration |
| 2026-08-01 | OpenCode | Removed selection-time acknowledgements and restored the reference's exact uniform auto-ack gate: resolved channel, settled initial load, attached message window, bottom scroll position, non-managed channel, and no mark-unread lock; restored the reference 50 ms detached-state debounce | Focused ack-gate, read-state, and guild-list tests plus `assembleDebug` pass | Device-check badges clear only after reaching the bottom in DM, guild, thread, and voice-text channels |
| 2026-08-01 | OpenCode | Fixed partial `PRESENCE_UPDATE` crashes by restoring the reference's nullable-discriminator completeness guard before updating `StoreUser` | Focused partial-presence fixture test and `assembleDebug` pass | Device-check partial presence updates no longer terminate `Main-StoreThread` |
| 2026-08-01 | OpenCode | Diagnosed the persistent DM count: the ack cursor already equaled the newest message, so no network write was correctly required, but the reconstructed duplicate mention store retained stale server counts; added no-op-ack reconciliation that clears dependent local mention state without sending a redundant ack and removed temporary diagnostics | Focused ack-reconciliation and guild-list tests plus `assembleDebug` pass | Device-check the red count clears at bottom even when `current == recent` |
| 2026-08-01 | OpenCode | Preserved the exact 126.21 `StoreMentions` algorithm and fixed its stale input instead: REST-loaded message chunks now monotonically advance `StoreMessagesMostRecent`, so bottom auto-ack compares against the actual newest loaded DM message rather than stale channel metadata | Focused newest-message and ack-gate tests plus `assembleDebug` pass | Device-check the remaining red 2 clears after the loaded DM reaches bottom |
| 2026-08-01 | OpenCode | Added a narrow recovery for persisted server mention counts whose ack cursor already equals the newest message: the unchanged reference ID comparator still governs normal acks, but an authoritative non-zero server mention count re-posts the same cursor with `mention_count=0` so the badge does not return after restart | Focused ack-repair tests plus `assembleDebug` pass | Device-check the red 2 clears at bottom and remains cleared after restart |
| 2026-08-01 | OpenCode | Found the actual persistent DM badge root with store diagnostics: `StoreMentions` was already empty while `WidgetChannelListModel` rendered red counts from the wrong `StoreReadStates` source. Switched both private and guild channel-list mention inputs to `StoreMentions` exactly as 126.21 does, and removed the temporary diagnostics plus target-only ack/mention repair patches | Focused channel-list, guild-list, and exact ack-gate tests plus `assembleDebug` pass | Device-check the red 2 disappears immediately and remains absent after restart |
| 2026-08-01 | OpenCode | Ran four parallel feature-gap slices: membership-screened invites now race channel hydration against the current user's `GUILD_JOIN_REQUEST_CREATE` and open the real verification form; existing linked Spotify accounts now drive local-playback rich presence through token/track metadata with central store wiring; account disable/delete was binding-migrated and endpoint/payload verified; premium/boost purchase completion now reaches the reference activation dialogs | Focused invite, Spotify, account, and premium activation tests, `assembleDebug`, Android-test Kotlin compilation, and `git diff --check` pass; no destructive account or purchase calls used | Device install/retest is pending because ADB disconnected; new Spotify links still require Manticord-owned OAuth credentials |
| 2026-08-01 | OpenCode | Started Phase 14 with a 12-agent hardening wave: audited manifest exports/permissions/queries/shortcuts; made notification actions process-safe and account-bound; added shared serialize-nulls REST; migrated 15 high-value channel/guild/chat/user/settings/server screens to bindings; fixed RLottie disposal, boost copy and READY unresolved member/presence retention; hardened route parsing/recreation; added release R8 rules; restored account validators; improved RTL/large-font/touch layouts; isolated every logout store reset so one failure cannot retain another account's state | All 1,678 unit tests, debug/release assembly, Android-test Kotlin compilation, `lintDebug`, release lint-vital, and `git diff --check` pass | Connected instrumentation, device/API/RTL/tablet regression, shrinking, signed release, and project-owned FCM/OAuth/App Links remain before Phase 14 can close |
| 2026-08-01 | OpenCode | Closed the connected-test hardening boundary: made FlexInput permission hosts recreation-safe and mount through production XML; corrected isolated navigation/profile fixtures; made crop results accept the standard intent-data fallback when UCrop's parcelable extra is absent; removed transient bottom-nav assertions and stabilized activity recreation checks | All 59 connected tests pass on physical Android 16 device A065; all 1,678 JVM tests and `git diff --check` pass. The phone had to remain awake; its original two-minute timeout was restored afterward | API 21-35, tablet/landscape/RTL/large-font, shrinking, signing, live backend, and project-owned provider credentials remain open |
| 2026-08-01 | OpenCode | Restored incoming-share UI/state behavior, exact no-send composer guard, authoritative search mention badges, mention-message navigation ordering, reference 40dp composer/reply geometry, reference-bound guild-search voice-marker visibility, guild-list DM routing through `ChannelSelector`, and READY-time mention filtering against the dispatcher-current channel map; replaced all first-party platform AlertDialog/Material/date/time/raw bottom-sheet construction with Discord-styled fragment dialogs and selectors | Production and Android-test Kotlin compile; all JVM tests, `assembleDebug`, `lintDebug`, `git diff --check`, and 60/60 connected Android 16 tests pass; production scan has zero forbidden platform-dialog constructions; final restart-unread fix assembles but install is pending because ADB disconnected | Reconnect A065, install the latest APK, then restart with an unread DM and visually verify the guild-list row persists |
| 2026-08-01 | OpenCode | Rebuilt read state and acknowledgements from the 126.21 JADX, Vineflower, `javap`, and DEX algorithms: removed the duplicate `StoreReadStates` ACK ledger; restored reactive unread IDs/marker/counts; made `StoreMessageAck` authoritative for READY full/partial hydration, lock/viewed transitions, thread synchronization, mark-unread, local-first REST ACKs and account isolation; restored reference StoreStream ordering; stopped restoring stale most-recent IDs; moved chat status to the authoritative ACK | All focused ACK/read/restart tests, the full JVM suite, `assembleDebug`, `compileDebugAndroidTestKotlin`, `lintDebug`, and `git diff --check` pass; APK installed and cold-launched on A065 without clearing app data | Device-check unread DM persistence across restart, read-message clearing across restart, bottom auto-ACK, mark-unread lock release, threads/forums, and voice-text unread gating |
| 2026-08-10 | OpenCode | Fixed intermittent group-DM member-list loss by restoring the reference `PrivateChannelMemberListService` production route: the current-user/recipient roster is fixed independently from presence emissions, so an offline update removes only presence state and cannot remove the member row | Focused `PrivateChannelMemberListServiceTest` passes with an online-to-offline recipient transition; `assembleDebug` passes. Initial cached test run was blocked by concurrent resource-output cache packing; rerun with `--no-build-cache` passed. No device or live backend used | Device-check a group DM while multiple recipients transition online/offline and across reconnect; broad member-list runtime parity remains open |
| 2026-08-10 | OpenCode | Restored the guild-boost system-message CTA: reference wire types 8-11 now record `premium_guild_promotion_opened` with guild and Channel Text Area/Boost Gem Icon location traits, then open `WidgetGuildBoost` for that guild. Vineflower and JADX both prove 8-11; type 25 is the separate role-subscription purchase message, so the audit/request's 25-28 wording was not applied | Focused `AnalyticsPipelineTest.guild boost promotion opened carries the system message location` and `assembleDebug` pass with `--no-build-cache`; no device or live backend used | Device-check each boost/tier system row opens the correct guild boost screen; broad chat runtime parity remains open |
| 2026-08-10 | OpenCode | Closed three production composition gaps: `StoreUsersTyping` now uses the graph's default REST API and forwards message/thread cooldowns to `StoreSlowMode`; `StoreUserGuildSettings` binds that same API during runtime initialization; `StoreChannelMembers` now receives the shared dispatcher plus channel, role, and member-count providers in reference declaration order | Focused `StoreStreamWiringTest` and `StoreUsersTypingTest` pass; `assembleDebug` passes. Existing default-transport tests already pin client metadata, so no duplicate transport fixture was added. No device or live backend used | Device-check outbound typing and notification writes; remaining adjacent audit gaps are typing timer/standalone divergence, guild-settings analytics/reset divergence, and rich member-row parity |
| 2026-08-11 | OpenCode | Fixed placeholder message rows after startup: upload optimistic messages now persist author/timestamp, `StoreLocalMessagesHolder` normalizes channel/nonce, removes and immediately rewrites malformed restored entries, and `Persister` fsyncs the temporary file before first publication. Message rows no longer skip binding while viewer identity is loading, and the adapter rebinds when that identity arrives | Focused `MessageHistoryStoreTest` and `PersisterTest` plus `assembleDebug` pass through `scripts/build.sh`; no device was connected | Device-check force-stop during the first queued upload write, restart, and confirm valid pending sends restore while malformed historical placeholders are removed |
| 2026-08-11 | OpenCode | Closed two reported crashes: embed markdown parsing now falls back to one plain-text node when SimpleAST throws on unmatched input such as `Reported Review`; malformed READY/GUILD_CREATE embedded-activity summaries with a missing activity are skipped. The current READY_SUPPLEMENTAL path already consumes typed `EmbeddedActivityInstance`s, so the obsolete nullable summary path was not restored | Focused embed fallback and embedded-activity store tests plus `assembleDebug` pass through `scripts/build.sh`; no device was connected | Device-check the original embed and reconnect payloads; capture sanitized payload shape if either fallback is exercised repeatedly |
| 2026-08-11 | OpenCode | Restored attachment/sticker notification bodies. Gateway notifications now fall back from blank message content to the first sticker name or attachment filename; FCM parsing retains the first attachment filename from its embedded message JSON and applies the same localized `sticker_notification_body` / `notification_body_attachment` templates. The reference notification renderer displays media as text, not notification bitmaps | Focused notification payload/client/store tests and `assembleDebug` pass through `scripts/build.sh` | Device-check background Gateway and cold-process FCM notifications for sticker-only, attachment-only, and content-plus-media messages |
| 2026-08-11 | OpenCode | Completed the approved migration to Discord's client-side Firebase project. The checked-in `google-services.json` matches the reference sender/project/app identifiers while retaining Manticord's package match for resource generation | Focused notification config/payload tests and `assembleDebug` pass; APK installed on device 188b1209 and acquired a stable sanitized token hash with no FIS/auth error | Disconnect Gateway/force-stop the process and verify Discord backend delivery for a DM, guild mention, sticker, and attachment; keep private Firebase Admin credentials out of the project |
| 2026-08-11 | OpenCode | Isolated the app FCM service, Firebase's merged `FirebaseInstanceIdReceiver`, and Firebase's base messaging service in the dedicated `:fcm` process. The receiver override is required because Android otherwise starts the default process for the C2DM broadcast before forwarding to the isolated service. The FCM process initializes Firebase and notification prerequisites, then exits `App.onCreate` before StoreStream, Fast Connect, native voice, billing, or Gateway. Main-process cold launches retain Fast Connect unchanged. FCM checks Android process importance and drops delivery while the main app is foreground, avoiding duplicate system notifications while Gateway owns the open-app path. The obsolete Disable Headless Gateway flag, setting row, and preference were removed | Focused process-policy, foreground-delivery, and Fast Connect socket tests pass; merged manifest pins all FCM ingress components to `:fcm`; `installDebug` succeeds on device 188b1209 | Remove the app task and send FCM to verify isolated system delivery with no Gateway; then foreground the app and confirm the same push creates no system notification |
| 2026-08-11 | OpenCode | Fixed cold-process FCM authentication. `NotificationClient.SettingsV2` previously persisted only while already backgrounded, so logging in while foregrounded left the disk snapshot tokenless and data-only FCM messages woke the process but returned `delivery=null`. Auth-token changes, including logout, now persist immediately; ordinary foreground settings churn remains deferred | Focused notification-client persistence-policy test and `assembleDebug` pass through `scripts/build.sh`; APK installed on device 188b1209 | Open once after install so the authenticated snapshot is written, kill without force-stop, and resend the FCM test message |
| 2026-08-11 | OpenCode | Reconciled MESSAGE_CREATE notification presentation with 126.21: DM titles use the sender, guild/thread titles include guild and channel, reply subtitles stay on the sender line, sticker content wins over text, snowflake timestamps and exact group-conversation types are used, and user/guild/group icons load asynchronously into large icons and MessagingStyle persons. Successful direct replies append the returned message to the existing conversation instead of replacing it with a success toast. Conversation shortcuts are long-lived and use the reference 72dp-in-108dp adaptive-icon safe zone so Android does not crop avatars | Focused notification parsing, ShareUtils, and notification-client tests plus `assembleDebug` pass through `scripts/build.sh`; APK installed on device 188b1209 | Send DM/guild/reply/sticker/attachment FCM fixtures with the process dead and visually compare title, avatar scale, grouping, and inline direct-reply updates against 126.21 |
| 2026-08-11 | OpenCode | Prevented missing guild/channel icons from turning into an oversized launcher icon. Notification conversation shortcuts and `shortcutId` are now added only after a real user/guild/channel bitmap loads; null icon payloads stay generic instead of taking `ShareUtils`' direct-share launcher fallback | `assembleDebug` passes through `scripts/build.sh`; APK installed on device 188b1209 | Re-test a guild notification whose FCM payload omits `guild_icon` and confirm no launcher icon appears |
| 2026-08-11 | OpenCode | Wired the existing `WidgetChatInputTruncatedHint` into the live FlexInput composer. Empty-state channel hints now clamp to one ellipsized line, preventing long channel names from increasing composer height; the clamp and hint are removed as soon as the user types so actual messages remain multiline | `assembleDebug` passes through `scripts/build.sh`; APK installed on device 188b1209 | Device-check a maximum-length channel name with empty, single-line, and multiline composer text |
| 2026-08-11 | OpenCode | Guild member-list rows now use `IconUtils.getForGuildMemberOrUser`, restoring per-server avatar precedence instead of always rendering the account avatar | Focused guild-avatar adapter-row regression passes; `assembleDebug` passes and APK is installed on device 188b1209 | Device-check members with global plus per-server avatars in online/offline/role/thread sections |
| 2026-08-11 | OpenCode | Profile image launch URLs now preserve unrelated raw query parameters/fragments while replacing only `quality` and `size` with `quality=lossless&size=3072`. Explicitly sized profile assets bypass WidgetMedia's screen-sized proxy rewrite, and launch metadata is 3072x3072 instead of unknown 0x0 | Focused media URL/launch tests and `assembleDebug` pass through `scripts/build.sh`; APK installed on device 188b1209 | Device-check static and animated account/guild avatars and banners, including URLs carrying additional hash/format query parameters |
| 2026-08-11 | OpenCode | Fixed lazy member-list presence hydration. `GUILD_MEMBER_LIST_UPDATE` already parsed each embedded presence, but `StoreStream` discarded it while forwarding only the row structure; member users, guild members, presences, and game-party state now receive those items. Member rows show indicators only for active statuses and keep offline/invisible users indicator-free | Focused Gateway member-list parser/traversal test passes and `installDebug` succeeds on device 188b1209 | Reopen a guild member list and compare known online plus offline users; online/idle/DND dots should appear and offline users should have none |
| 2026-08-11 | OpenCode | Fixed chat invite cards with real future `expires_at` values being rendered as invalid. The inherited 126.21 comparison was inverted and only appeared harmless while invite resolution omitted expiration; current responses include expiration, so cards now reject only expired/revoked invites. Guilds carrying `MEMBER_VERIFICATION_MANUAL_APPROVAL` show `Apply to Join` on chat and full-page invite actions and enter the existing join-request/member-verification flow even without `COMMUNITY`. `WidgetInviteInfo` was migrated to generated view binding while touched | Focused future/expired and manual-approval invite regressions pass; `installDebug` succeeds on device 188b1209 | Reopen the reported application-gated guild invite, confirm `Apply to Join`, and submit only if this test application is safe to create |
| 2026-08-11 | OpenCode | Fixed notification routing and conversation state. `AppActivity.consumeIntent` now handles channel deep links immediately on cold and warm starts, selecting Home plus the addressed guild/DM channel before the tab host lifecycle can lose the route. Gateway and FCM notifications share `NotificationClient`'s single renderer/cache, so channel selection and read acknowledgements prevent read messages returning in later conversation notifications. Gateway reply messages now format their sender line from `referencedMessage` as the localized `replied to` label, matching FCM reply subtitles | Focused deep-link and notification-cache tests pass through `scripts/build.sh` | With the app process dead, tap a DM and guild notification; then verify Gateway and FCM replies show `replied to`, and read history stays absent after a later message |

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

Execute the reconstruction campaign. Read `docs/parity-audit/README.md` and the
report for your area before touching code, and follow the concurrency protocol
above whenever more than one agent is active.

**Now — finish Waves 1, 2a, 2b, 2c** (in flight; see the wave table above).
Verify the wave boundary with `assembleDebug`, `testDebugUnitTest`, and
`lintDebug` before starting Wave 3.

**Next — Wave 3, the store graph.** This is the last layer beneath the widgets,
so nothing in Wave 4 should start before it. In dependency order:

1. `StoreStream`: implement `deferredInit(Application)`, the ~57 store `init`
   calls, `NetworkMonitor`, the initialization timer, and the backgrounded
   subscription. Grow the graph from 36 toward 145 stores.
2. Fix the reset/logout semantics: remove the invented `resetForLogout()` and
   the `reset()` methods on `StoreAccessibility`/`StoreUserSettingsSystem`, and
   stop `handlePreLogoutInternal` conflating pre-logout with a full cache reset.
3. `ReadyPayloadHydrator`: restore the `HydrateResult`/`HydrateGuildResult` error
   branch so `handleHydrateError` can fire, carry all 22+ cached guild scalars
   across re-READY, and add the per-channel merge that preserves `lastMessageId`.
4. Clear the cross-cutting store defects listed in the audit table above —
   `markUnread`, `StoreReadStates.Inputs`, the inverted guild-member preserve
   flag, `ACTIVE_STATES`, the MFA login short-circuit, and the logout wipe.
5. Build the missing stores that shipping surfaces already depend on, highest
   leverage first: `StoreUserRequiredActions` (unblocks the `WidgetTos` crash),
   `StoreExperiments` (nothing currently evaluates already-parsed experiment
   DTOs, so every experiment-gated reference behavior diverges silently),
   `StoreLurking`, `StoreGuildMemberRequester`, `StoreMentions`, `StoreChat`,
   `StoreMessageReactions`, `StoreMessageReplies`, `StoreSlowMode`, the
   `StoreThread*` family, and `StoreMFA`/`StorePhone`.
6. Move the attachment upload path out of `StoreStream.kt:1119-1215` onto the
   rebuilt `MessageQueue`, and implement the `StoreMessages` send/edit/resend/
   delete pipeline.

**Then — Wave 4, widgets.** Build the ViewModel for each screen before its view;
the audit found the slice has no ViewModels at all and routes store state through
process-global mutable callbacks that default to `null`. Order by user-visible
impact: chat rows and input, channel list, guild sidebar, user sheet, then
settings/servers.

**Then — Wave 5**, the 24 zero-coverage packages, manifest components and
permissions, notifications/FCM with project-owned configuration, and the
shortcuts/actions wiring.

**Standing constraints.** Do not mark any phase `DONE` while its audit report
lists open blockers. Do not resolve a JADX-broken region by guessing — the
reports name every such region; inspect DEX/smali. Do not copy decompiled Java or
carry obfuscated names into the target; reconstruct readable Kotlin.

---

# Session record — 2026-07-26/27 (overnight parallel wave)

## How to resume

**Do not read this conversation.** Everything durable is in files:

- `docs/parity-audit/followups.md` (~2400 lines) — per-cluster handoffs, each with an ordered
  per-file remaining-work list giving target path, Vineflower reference path, and what it needs.
  This is the work queue. Start here.
- `docs/parity-audit/security-review.md` — the security audit, with a resume list.
- `AGENTS.md` § *Interruption Discipline* — the working rules every agent must follow.

## First job tomorrow: get a compile

**The tree does not currently compile.** ~35 agents ran concurrently and several clusters were
left mid-reconstruction by design (finish-the-file-then-stop), so the breaks are known and
localised, not mysterious. Run `assembleDebug`, then work the list. Reported breaks, some of
which later agents may already have closed:

- `widgets/guild_role_subscriptions/**` — 25 of 42 classes exist. `GuildRoleSubscriptionTierAdapter`
  and the whole `tier/create/**` subtree are referenced but unwritten. The owning agent's report
  lists all 17 in dependency order.
- `widgets/guildscheduledevent/**` — three bottom sheets reference `WidgetPreviewGuildScheduledEvent`
  and `WidgetGuildScheduledEventLocationSelect`, neither written. Building the first unblocks the rest.
- `WidgetServerSettings.kt` — two agents edited it concurrently; verify both sets of additions
  survived, and that `WidgetServerSettingsMembers` (built late in the session) resolves.
- `CheckedSetting.isChecked` — property form vs `isChecked()` call form is genuinely unresolved and
  one Gradle run settles it. Both forms exist in committed code. Prefer the call form.

## Applied this session (coordinator)

- **SECURITY H-1, high severity.** `AppScreenRouter.createScreenIntent` targeted the *exported*
  `AppActivity$Main`, and `consumeIntent` honoured `EXTRA_SCREEN` unconditionally — so any installed
  app could display any fragment with attacker-chosen arguments, and via `WidgetIncomingShare`'s
  unvalidated `uris` argument, exfiltrate the auth token from shared_prefs. Router intents now target
  the non-exported base `AppActivity`; `consumeIntent` refuses the extra when running as `Main`.
  **Not fully closed** — see the four open items in `followups.md`; item 1 (the exported
  `ACTION_SEND` path into the same code) is the one that matters.
- **The "back button closes the app" bug — root cause found and fixed.** `OverlappingPanelsLayout`
  initialised its opened-position sentinels to `0f`; the reference `<init>` bytecode uses
  `Float.MIN_VALUE`/`Float.MAX_VALUE`. Since `centerPanel.x` is also `0f` on first layout, the
  re-open guard fired every time, the start panel auto-opened, `selectedPanel` became `START`, and
  `WidgetHome` answered the next back press with `moveTaskToBack(true)`.
  **Falsifiable in one step:** log `getSelectedPanel()` at the top of `WidgetHome.handleBackPressed`;
  on a fresh launch with a channel selected it must now report `CENTER`, not `START`.
  Two follow-ups remain, both in `WidgetHome` (not the panels file): the target checks the child
  back stack *before* the panel branch and the reference has no such check at all — that ordering is
  inverted; and panel position is not restored after rotation. Precise edits are in `followups.md`.
- `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS=40` in `.claude/settings.local.json`.

## Reference bugs found this session — decisions taken

Reproduced deliberately (do not "fix" without changing the ledger): `GuildChannelsInfo.unelevated`
is a dead conjunction always false; `getSortedVisibleChannels` drops any group of exactly one;
`hasUnreadPins` is a literal `false`; `getChannelType` calls `isGuildForumPost(parent = null)` which
can never be true; `StoreAuditLog.recordDeletedTarget` compares Long to boxed Integer so a deleted
text channel loses its `#`; `QueryNode.Preprocessor.preprocess` discards its mapped list;
`Benefit.equals` ignores most of the emoji.

Deliberately **not** reproduced, each documented in code: `StoreGifPicker`'s two loading sentinels are
both `emptyList()`, so the identity check also rejects a real zero-hit result and a failed search
spins forever — distinct instances used instead. `WidgetOauth2Authorize` logs the full redirect URL
including the OAuth `code` — only scheme+host is logged here.

## Real bugs found and fixed in our own code

- `GuildScheduledEventTypeAdapters` called `reader.nextInt()` *inside* a `firstOrNull` predicate,
  advancing the reader once per enum constant — every real `GuildScheduledEvent` failed to parse.
- `EmojiPickerViewModel` folded case with `Locale.getDefault()`. In Turkish `"GRIN"` → `"grın"`, so
  emoji search returned nothing. Reference uses `Locale.ROOT`.
- `AppLog` redacted log messages but printed throwables raw, so a token quoted in an exception
  message bypassed redaction entirely.
- `StageStoreAccess` gated on an `install()` nothing ever called, so every stage screen threw.
- `StageAudienceBlockedBottomSheetAdapter` never attached itself to its recycler.
- `VoiceUtils.handleCallChannel` sent stage channels to the plain voice sheet.
- `PhoneOrEmailInputView.bind` left `onCountryCodeClick` null, pinning auth screens to +1.

## Evidence-hierarchy notes worth keeping

- **JADX was actively wrong** about `SearchQuery.Builder.buildFrom`'s filter reset; Vineflower's
  awkward `continue@label53` was correct. Settled from bytecode.
- **Vineflower inverted a loop** in `calculatePermissionChange` (`while(true){if(i<=63)break;}`),
  which would have made permission rendering dead code. Distrust every
  `$VF: Unable to resugar Kotlin loop` block.
- Vineflower fails outright (KotlinWriter NPE) on `WidgetPruneUsersViewModel`,
  `WidgetAuthLandingViewModel`, `WidgetAgeVerifyViewModel`, `PriceTierPickerBottomSheetViewModel`,
  `CreateGuildRoleSubscriptionTierViewModel`, `GuildRoleSubscriptionReviewViewModel`,
  `WidgetChannelTopicViewModel`, `WidgetGuildScheduledEventLocationSelectViewModel`. Use `javap -p -c`.
- Enum ordinals recovered from `<clinit>` this session are recorded per-cluster in `followups.md`.
  Decompilers print enum constants alphabetically; that trap bit three separate agents.

## Still dead / still blocking

- `StoreAnalytics` — constructed nowhere. A 28-row member→backing-call table is in the StoreStream
  agent's handoff, ready to implement.
- `VoiceTransport` is installed and the `StoreRtcRegion` cycle is closed through deferred callbacks;
  live voice/Go Live behavior still needs an approved-account device exercise.
- `StoreLurking` is now exposed and its persistence/sorting/stage/home/chat-input cascades are live;
  the full lurk/join/welcome flow still needs device verification.
- `StoreNotices` now delivers through the resumed `AppActivity` and visible fragment tree; its
  remaining shell gap is adding the absent `WidgetCallFullscreen` to `DEFAULT_VALID_SCREENS`.
- All 7 gateway TODOs at `StoreStream.kt:1116-1123` still open.

## 2026-08-02 modern voice codec negotiation

- `NativeEngine.getCodecCapabilities()` is now consumed using the PR 752 contract
  (`codec`, `decode`, `encode`) rather than guessed wire fields. The direct native RTC path always
  advertises Opus at payload type 120, filters video codecs to decodable entries, preserves native
  rank order, and assigns payload/RTX pairs from 101/102 upward before `SELECT_PROTOCOL`.
- `RtcVoiceGatewayV8Test.nativeCodecCapabilitiesBecomeSelectProtocolCodecs` verifies parsing,
  decode filtering, encode/decode propagation, priorities, and payload assignment. The focused test
  and `:app:assembleDebug` passed on 2026-08-02.
- Debug and release builds send every DAVE websocket package; temporary protocol fault injection
  has been removed.

Resumable task: `app/src/main/java/com/discord/rtcconnection/RtcConnection.kt` | PR 752
`voice/src/main/java/com/hammerandchisel/libdiscord/Discord.kt` | install the successful debug APK,
join a video-capable voice channel, and confirm `DaveWebSocket -> OUT SELECT_PROTOCOL` carries the
device-reported ranked codec list.

## 2026-08-02 voice and screenshare runtime fixes

- Removed the second native engine previously installed by `VoiceEngineServiceController`; voice
  and Go Live now share `VoiceTransport`'s single engine. DAVE calls are forwarded through
  `MediaEngineConnectionLegacy -> co.discord.media_engine.Connection -> NativeConnection`, matching
  PR 752. Live verification confirmed incoming voice works after this consolidation.
- Restored outgoing speaking signaling by subscribing to the engine-wide local VAD callback and
  sending opcode 5 transitions with the READY audio SSRC. This matches the loaded modern Discord
  APK's `MediaEngine.setOnVoiceCallback` flow.
- Fixed `WidgetCallFullscreen` binding the nonexistent `private_call_container`; the include
  overrides that root ID with `private_call`. The fresh device run no longer crashed opening the
  fullscreen/screenshare flow.
- Media-projection permission is now retained by the stream controller and applied from its
  `RtcConnected` callback. The loaded modern Discord APK follows the same ordering: permission is
  stored by its activity-result listener, then `connectionInstanceStartBroadcast` is called after
  the stream connection exists.
- `RtcVoiceGatewayV8Test`, `ScreenShareManagerTest`, and `:app:assembleDebug` pass. Final live
  verification of outgoing voice and screenshare is pending because device `188b1209` disconnected
  before the new APK could be installed.

Resumable task: `app/src/main/java/com/discord/rtcconnection/VoiceTransport.kt` | loaded modern APK
`com.discord.media.engine.MediaEngineModule` and PR 752 `VoiceChatFix.kt` | reconnect device
`188b1209`, install `app/build/outputs/apk/debug/app-debug.apk`, verify `OUT SPEAKING` changes to 1
while talking and logs contain `Setting screenshare true` followed by nonzero outbound video SSRC.

## 2026-08-02 voice/media parity closure

- Root cause of the remaining outbound failures was native context aliasing: voice and the first
  stream both used context `stream`, so watching a stream replaced the voice/camera sender. Voice now
  uses `default`; stream connections retain `stream`/`stream-<ssrc>`. Device verification confirmed
  outgoing/incoming voice, camera, screenshare, and remote stream playback coexist.
- Completed the original app's video path: RTC callbacks update `StoreVideoStreams`, renderers attach
  through the live `StoreMediaEngine` sink on the main thread, and callback-owned frames are released.
- Active-path codec payloads now carry native encode/decode capabilities and all decodable video
  codecs are installed as native decoders. DAVE capability/failure/state callbacks run on the same
  shared media connection used by voice and streams.
- Rotation no longer exposes the boot diagnostics overlay: restored direct-screen fragments now hide
  `appLoading` before `showScreen` returns early. Device rotation over a remote screenshare passed.
- Removed the voice-channel onboarding sheet launch. Camera enumeration now preserves known devices
  across transient empty callbacks while switching/focusing stream transports, preventing the camera
  button from disappearing.
- Focused gateway/video/store tests and `:app:assembleDebug` pass; latest debug APK was installed on
  device `188b1209`.
- `ModelNotificationSettingsTypeAdapter` now treats explicit JSON nulls like absent fields across
  guild and channel-override settings. `ModelNotificationSettingsTest.explicitNullsUseWireDefaults`
  covers the READY payload regression; focused test and debug APK build pass.

## 2026-08-02 modern usernames and display names

- Added the live API's nullable `global_name` to the API, `CoreUser`, and `MeUser` models and the
  `StoreUser` update path. This is an intentional backend-compatibility extension beyond the 126.21
  reference model; the recovered reference predates migrated usernames.
- Central name rendering now resolves guild nickname, then global display name, then username.
  Legacy discriminators render only for bot accounts with a nonzero discriminator.
- Chat authors and replies, member lists, voice participants, profile/settings headers, and mention
  autocomplete now consume the shared precedence. The READY Gson fixture verifies the
  `global_name` wire mapping.
- Guild user popouts no longer pass the current guild member as a group-DM AKA. That false AKA state
  hid the secondary unique username whenever a nickname or global display name was primary.
- Direct-message names now prefer hydrated `StoreUser.globalName` and fall back to embedded recipient
  `global_name`, then username. This covers the DM list and shared unnamed-private-channel headers.
- Focused model, chat, member-list, voice-display, settings, and autocomplete tests pass;
  `:app:assembleDebug` passes.

Resumable task: `app/src/main/java/com/discord/widgets/**` | live Discord user payloads and
`UserUtils.getDisplayName` | replace remaining low-traffic direct username labels only when their
screen is reconstructed or device evidence shows they are display-name surfaces.

## 2026-08-02 modern profile cosmetics, forum loading, and diagnostics

- Backported current profile metadata using Discord Userdoccers and the connected desktop client's
  SecurityResearchCompanion webpack evidence: global/guild theme colors, avatar decorations,
  profile badges, profile effects, and collectible nameplates. Guild profile fields override global
  fields independently; guild banners retain their own CDN scope.
- Profile themes now derive separate header/body/card/note/button/text surfaces from the desktop
  formulas instead of stretching one gradient over the sheet. Rich-presence and Material note-input
  surfaces use the same palette and reset cleanly for unthemed profiles without changing geometry.
- Profile effects resolve `profile_effect.sku_id` through `GET /collectibles-products/{sku}` in a
  dispatcher-confined, deduplicated `StoreUserProfile` cache. Product/layer camelCase wire fields are
  explicitly mapped; timed APNG layers, loops, randomized sources, reduced-motion fallbacks, and
  idempotent rebinds are supported.
- Modern `badges`/`guild_badges` load from profile payloads with CDN icons and preserve legacy flag
  badges as fallback. Unknown badge IDs remain renderable and safe.
- Nameplates render only behind member-list and DM-list rows. READY users now hydrate collectibles;
  DM artwork uses the desktop static CDN path, right-centered focus crop, and a non-measuring overlay.
  The original private-row hierarchy, dimensions, padding, margins, and touch geometry are preserved.
- Follow-up runtime fixes preserve that geometry while allowing the overlay to draw through parent
  padding. Artwork now scales from row height with its full source aspect and physical-right alignment
  instead of `FOCUS_CROP`, which showed only the palette and cropped away the subject.
- The DM artwork already ended at the physical row right edge; channel masking still obscured part of
  the subject, so DM rows now draw the full unmasked artwork. Member-list masking is unchanged.
- Selected/hovered DM rows also requested the animated collectible variant while the working member
  path used static artwork. DM placement now always requests the verified static PNG; member-list
  animation behavior remains unchanged.
- The final DM-only artwork blocker was measurement: the 0dp overlay was expanded after parent layout,
  but its internal Drawee child remained 0x0. The parent palette therefore rendered without the PNG.
  `NameplateView.onLayout` now gives the image the overlay's final bounds without affecting row size.
- Selected DM rows now load the animated APNG through LINE's decoder while showing static artwork as
  the download/failure fallback. Playback loops only while selected and allowed by reduced-motion;
  deselection, rebind, or detach cancels and recycles it. Member-list behavior is unchanged.
- Forum parent channels no longer enter ordinary message-history loading; forum posts/threads remain
  valid message channels. This stops the live backend retry loop for forum parent IDs.
- Debug builds now log only HTTP method, Retrofit route template, status, and duration. Release builds
  install no logger; URLs, concrete path/query values, headers, cookies, bodies, and credentials are
  never read by the interceptor.
- Fixed an ARM64 startup crash in `libdiscord.so`: registering the VAD callback eagerly created the
  native engine and started OpenSL capture before an input device existed. Listener registration is
  now lazy and audio input remains disabled until real native initialization. A065 cold-start smoke
  remained alive after the fix.
- Focused store/profile/forum/logger/nameplate tests and earlier connected nameplate/native lifecycle
  checks passed. The final integrated `assembleDebug` passes with a temporary 4 GiB Kotlin daemon
  heap after Windows cache packing and default-heap failures. The newest effect-rebind and exact
  private-row geometry assertions still need one batch run; repeated per-edit test runs were stopped
  at user request.
- Live profile payloads were confirmed to carry `profile_effect.sku_id` as a string. Product layers
  may omit `randomizedSources`; Gson then supplies null rather than the Kotlin default, so effect
  binding previously threw inside `runCatching` and silently hid the view. Nullable parsing plus
  `orEmpty()` fixes that path, and identical sheet rebinds no longer cancel active APNG playback.
  The focused profile/store/transport tests and final `assembleDebug` pass after these fixes.
- Live product inspection confirmed extensionless APNG layers. Android's platform decoder emitted
  `Image decoder ... unimplemented`, so playback now uses the already-installed LINE APNG decoder
  on an IO dispatcher. Layers decode independently at their start times; one slow/failed layer no
  longer hides the whole effect, and identical sheet updates do not restart playback. The latest APK
  containing this and the full-aspect nameplate transform was installed on WSA Android 13.
- Effects now play once per popout: completed APNG drawables are removed/recycled, identical state
  emissions do not restart them, and reopening creates fresh drawables with preserved bounds instead
  of reusing a completed/cropped drawable. DM rows now fall back from a sparse cached user to the
  embedded recipient collectible and use the member-list mask/placement semantics. The combined APK
  builds and was installed on WSA Android 13; focused assertions remain queued per the user's request
  to avoid per-edit test runs.

Resumable task: `app/src/main/java/com/discord/widgets/user/profile/ProfileEffectView.kt` | desktop
collectibles modules `590180`, `203632`, `575593`, `758836` | reconnect A065 `188b1209`, install the
latest debug APK, open a profile with an effect, and verify timed APNG layers survive sheet updates.

Resumable task: `app/src/main/java/com/discord/views/NameplateView.kt` |
`widget_channels_list_item_channel_private.xml` and desktop modules `174755`, `715069`, `746002` |
run the queued connected geometry assertion and visually confirm the restored-height DM row artwork.

## 2026-08-02 emoji and GIF frecents

- Backported the maintained `zt64/aliucord-plugins` Frecents behavior into native stores and picker
  flows rather than retaining Aliucord hooks. Expression frecency uses the plugin's bounded recent-use
  history, age weights, and lifetime-use multiplier.
- Emoji favorites/frecents use numeric custom-emoji IDs or Unicode surrogates as canonical identity.
  GIF favorites use decoded Tenor URLs and newest-first ordering; successful GIF sends update a
  bounded local GIF frecency history.
- Emoji picker sections now expose Favorites and Frequently Used before guild/Unicode sections. GIF
  picker exposes Favorites and Frequently Used before Trending/categories. Every GIF tile has a
  non-measuring star button: white when inactive, yellow when favorited; tapping it toggles favorite
  state without sending the GIF or changing tile geometry.
- Long-pressing an emoji tile now opens the existing emoji detail sheet, whose Add Favorite/Remove
  Favorite action updates the same persisted favorite store.
- GIFV and animated-image embeds in chat now expose the same white/yellow favorite star as picker
  tiles. Chat-added favorites retain the embed's real preview URL; no extension rewriting is used
  because changing a Discord CDN MP4/WebM suffix produces a nonexistent path. The star is a
  non-measuring overlay and does not change embed geometry.
- The Favorites landing tile previews a random saved GIF, matching the plugin.
- Existing `STORE_FAVORITES` persistence remains compatible. New local keys are
  `EMOJI_FRECENTS_V1`, `GIF_FRECENTS_V1`, and `GIF_HISTORY_ITEMS_V1`. Cross-device
  `/settings-proto/2` synchronization is now owned by the RNAPI settings sync described below.
- Focused persistence, identity, ordering, ranking, integration tests and `assembleDebug` pass.

Resumable task: `app/src/main/java/com/discord/widgets/chat/input/gifpicker/**` |
`zt64/aliucord-plugins/plugin/Frecents` | device-check GIF star/toast rendering and emoji
category-scroll synchronization.

## 2026-08-03 RNAPI settings-proto v2 sync

- Added typed GET/PATCH `/users/@me/settings-proto/2` transport and
  `USER_SETTINGS_PROTO_UPDATE` gateway parsing for cross-device emoji favorites/frecency and GIF
  favorites. No protobuf dependency was added; the minimal codec preserves unowned top-level fields
  while encoding the documented type-2 schema.
- Sync fetches after gateway ready, merges partial gateway updates, applies optimistic writes with
  `required_data_version`, batches emoji usage for 30 seconds, respects 429 `Retry-After`, and
  refetches after invalid-proto error 50105. Proto bodies are never logged.
- Server fields always win when present, including intentionally empty lists. Existing local data is
  migrated only when the matching server top-level field is absent; out-of-date migration responses
  discard the local candidate. Cached server state remains available offline.
- GIF usage ranking stays local because type-2 settings has no GIF-frecency field. Focused codec,
  partial-update, migration, transport, offline, and store tests plus `assembleDebug` pass. No live
  PATCH was issued during verification.
- Live server payload validation found the schema was correct but unknown-field skipping was not:
  `position += varint().toInt()` restored the pre-varint cursor under JVM compound-assignment
  evaluation. Current unowned fields therefore shifted parsing by one byte and the decode exception
  was silently dropped before store publication. The reader now advances length-delimited fields in
  two explicit steps, validates wire types, and emits safe count/version diagnostics on failures.
  Live-shaped unknown-field and ObservationDeck publication tests pass; a live GET decodes fields for
  GIF favorites, emoji favorites, and emoji frecency successfully.

Resumable task: `app/src/main/java/com/discord/stores/StoreMediaFavorites.kt` | desktop modules
`594061`, `617617`, `416380` | validate PATCH echo, cross-device gateway update, 429 retry, and offline
conflict behavior with an approved test account.

## 2026-08-03 TapTap local experiment

- Backported Vendicated/AliucordPlugins TapTap 1.1.6 as local user experiment
  `manticord_local_taptap`, default bucket 0/off; bucket 1 enables it without spoofing a server
  assignment.
- Two message-row clicks within 200 ms reply to another user's message or open editing for the
  current user's message. Existing reply/edit coordinators and message unparser paths are reused.
- Ephemeral, local, failed, and loading messages are ignored. Child link/media/spoiler/reaction
  handlers, collapsed blocked rows, and long-press behavior remain independent; no geometry changed.
- Focused experiment/gesture tests and `assembleDebug` pass.

Resumable task: `app/src/main/java/com/discord/widgets/chat/list/adapter/WidgetChatListAdapterEventsHandler.kt` |
`Vendicated/AliucordPlugins/TapTap` | device-check the 200 ms threshold against long-press, links,
media, spoilers, and thread-starter rows.

## 2026-08-03 debug logs screen

- Reconstructed reference `WidgetDebugging` and its nested model/adapter using the recovered layout
  and settings/developer navigation. Developer entry and screen retain the reference staff gate.
- `AppLog` now exposes the reference-style bounded 5,000-entry replay stream. Rows render newest
  first as `tag -> message`; the checked menu filter hides verbose entries. Tapping expands/collapses
  a row and long-press copies it. The reference has no clear/share/export/search/refresh actions, so
  none were added.
- Required security divergence: display and clipboard text pass through final redaction for tokens,
  authorization/cookies, passwords, MFA/captcha values, private message content, and throwable text.
  HTTP diagnostics remain method/route-template/status/duration metadata only.
- Focused AppLog/adapter/navigation tests and `assembleDebug` pass. Toolbar overflow, clipboard, and
  auto-scroll remain to be checked on device.

Resumable task: `app/src/main/java/com/discord/widgets/settings/WidgetDebugging.kt` | reference
`WidgetDebugging.java` | device-check verbose filtering, expansion, long-press copy, and navigation.

## 2026-08-03 request-wide captcha handling

- Captcha moved off the individual screens and onto the OkHttp chain.
  `restapi/CaptchaInterceptor` detects a challenge (400 + `captcha_key`, by presence and never by
  the array's contents), solves it, and re-sends the identical request. Installed outermost on all
  three `RestAPI` client variants, so a retry re-runs the chain below it and picks up fresh auth and
  analytics headers. Multipart uploads replay too — `AttachmentRequestBody` reopens its stream per
  write.
- Solutions travel as `X-Captcha-Key` plus `X-Captcha-Rqtoken` / `X-Captcha-Session-Id` when the
  challenge carried them. The `captcha_key`/`captcha_rqtoken` body fields are removed from
  `AuthLogin`, `AuthRegister`, `AuthRegisterPhone`, `ForgotPassword`, `InviteCode`, `Message`,
  `Phone` and `UserRelationship(+Add)`; Userdoccers records that form as deprecated.
  `RestAPIParams.CaptchaCode` keeps its fields — `users/@me/captcha/verify` is not a retry.
- `CaptchaErrorBody` gained `captcha_session_id`, `should_serve_invisible` and `user_flow`;
  `CaptchaService` gained `recaptcha_enterprise` and `turnstile` with their published site keys.
  `should_serve_invisible` reaches the hCaptcha widget as its `size`.
- `CaptchaSolver`/`ActivityCaptchaSolver` bridge the OkHttp thread to the prompt: challenges are
  serialised so concurrent challenged requests produce one prompt, every wait is bounded by
  `TIMEOUT_MS`, and nothing thrown by the UI escapes onto the main thread.
- Deleted: `WidgetCaptcha`, `WidgetAuthCaptcha`, `WidgetGuildJoinCaptchaBottomSheet`,
  `WidgetFriendsFindNearbyViewModel` (existed only to survive the captcha activity),
  `CaptchaHelper.showCaptchaHelpDialog` and the process-wide token hand-off slots, the
  `WidgetCaptchaBottomSheet.onSolved`/`onAbandoned` statics, and the upload captcha retry path in
  `StoreStream`. Their layouts are kept as recovered assets.
- The message send queue keeps its own prompt through `CaptchaRequestPolicy(handleInline = false)`:
  a queued send can run while the app is backgrounded, so it must fail fast rather than park a
  queue worker on a modal nobody can see.
- `CaptchaInterceptorTest` (MockWebServer) covers detect/retry, header echo and omission, the
  `MAX_CHALLENGES` cap, decline, pass-through of ordinary 400s, both policy modes, and the
  no-solver case. Full suite: 1,753 tests, `assembleDebug` and `lintDebug` pass.
  `StoreStreamTest.voiceStateUpdateHydratesJoiningMemberBeforePublishingParticipant` fails here and
  on a clean `HEAD` worktree — pre-existing and unrelated.
## 2026-08-03 modern application commands

- Reconstructed current application-command indexes and interaction submission using Aliucord PR 431
  only as historical evidence, with Discord Userdoccers and desktop modules `168186`/`545152` as the
  current contract. Guild/channel and user indexes merge with stale-response rejection and gateway
  invalidation refetch.
- Typed command models now cover localized names/descriptions, subcommands/groups, option types 1-11,
  choices, min/max values and lengths, context/integration/permission gates, focused autocomplete,
  attachments, and numeric normalization (`INTEGER` to `Long`, `NUMBER` to `Double`).
- Interaction payloads include command type/version/root identity, full command data, application,
  session/guild/channel IDs, nonce, analytics location, and attachment indices. Loading/failure and
  submit-failure states remain observable.
- Focused REST/gateway/store/serialization/autocomplete tests and `assembleDebug` pass. Live command
  index authorization, invalidation, localized nested submission, and attachment selection remain for
  approved-account device testing.

Resumable task: `app/src/main/java/com/discord/stores/StoreApplicationCommands.kt` | Userdoccers
application-command index docs and desktop module `545152` | live-test index fetch/invalidation and
nested localized command submission with attachments.

## 2026-08-03 forwarded messages and sharing

- Added typed forward references (`type=1`), `HAS_SNAPSHOT`, immutable message snapshots, resolved
  users/members, flags, polls, and bounded nested snapshot parsing from the current Userdoccers and
  desktop modules `762230`, `885918`, `843626`, `513480`, `320095`, `655758`.
- Message actions and `WidgetIncomingShare` can select a permitted destination and send the exact
  create-message forward body, optionally followed by context text. Existing channel permissions and
  server validation fail closed; no message content is logged.
- A dedicated forwarded container renders text, attachments, embeds, stickers, components, polls,
  source privacy, loading/deleted/error/depth states, and accessibility. Partial snapshot users are
  normalized without Kotlin `copy`, because Gson may leave omitted discriminator fields JVM-null.
- Forwarded source navigation resolves the original message ID from either `message_reference` or
  the typed snapshot, so accessible source channels still jump when the reference omits `message_id`.
  Both the card and its source footer trigger the shared loader jump.
- Source-channel visibility now follows the user's actual channel access rather than requiring the
  forwarded message's destination to be in the same guild. Accessible cross-guild sources expose and
  navigate to their channel; inaccessible sources still fail closed to guild-only or hidden labels.
- Source guild, channel, and message IDs are normalized from the reference first and typed snapshot
  second, treating zero IDs as absent. This removes payload-dependent jumps when Discord omits one or
  more source IDs from `message_reference`; focused omitted/zero-ID regressions pass.
- Cross-channel jumps now remain pending until `StoreMessagesLoader.handleChannelSelected()` consumes
  them. This removes the observer-order race where ordinary initial loading randomly cancelled the
  around-target request; a focused regression proves the selected target starts only `AROUND`.
  Redacted loader diagnostics now identify jump request, channel selection, cached-target, fetch, and
  resolution-drop stages without logging message content.
- Known jump channels are now read synchronously from `StoreChannels` and selected immediately;
  gateway connection state is consulted only when the channel is genuinely absent locally. The
  regression keeps connection observation silent and still reaches the local channel target.
- Shared channel selection now publishes a changed guild in one dispatcher transaction before
  validating the requested channel in the next, matching ordinary `ChannelSelector` taps. This
  prevents cross-guild jumps from validating against the previous guild snapshot and stalling.
- Forwarded cards use an explicit leading forward icon. Guild forwards show a 14dp source-guild icon
  and guild name in the bottom attribution instead of the channel name; no guild identity is repeated
  at the top, and original author avatars/usernames are deliberately omitted.
  The focused unit suite, connected forwarded binding test, `assembleDebug`, and `installDebug` pass.
- Focused wire/policy/store/share/render/privacy/logout tests and `assembleDebug` pass. No live forward
  was sent; the navigation/layout regressions, `assembleDebug`, and `installDebug` pass. Guild-level
  forwarding-disable/gated-channel state remains server-enforced.

Resumable task: `app/src/main/java/com/discord/widgets/share/ForwardedMessage.kt` | Userdoccers message
forwarding and desktop module `885918` | live-test cross-guild privacy, permission rejection, optional
context message, and nested snapshot rendering.

## 2026-08-03 modern Discord Markdown

- Updated the native parser from current desktop webpack evidence (`46054`, `29814`, `223637`,
  `873879`, `683412`, `478676`) rather than replacing it with generic CommonMark.
- Added headings, subtext, ordered/nested lists, masked links, command mentions, mail/tel/steam
  autolinks, URL-scheme rejection, callbacks, and copy/unparse handling while preserving mentions,
  timestamps, emoji, spoilers, code, quotes, Discord delimiter precedence, punycode, and untrusted-link
  confirmation.
- Bare links trim only unmatched trailing parentheses; balanced parenthesized targets remain linked.
  Malformed/unclosed input, Unicode boundaries, nesting precedence, and bounded pathological-input
  behavior are fixture-tested.
- Focused Markdown tests and `assembleDebug` pass. Android emits nested lists as per-item nodes where
  desktop groups the list AST; game/soundboard/static-route mentions remain unsupported.

Resumable task: `app/src/main/java/com/discord/utilities/textprocessing/Rules.kt` | desktop Markdown
modules above | add game/soundboard/static-route mentions when their typed models and click routes exist.

## 2026-08-03 Application Directory and OAuth installation

- Added the desktop-style Application Directory at `/discovery/applications`, distinct from the
  application-command index. Typed search/browse supports categories, debounce, pagination, ranked
  page merge, stale-response rejection, and loading/empty/error states using current Userdoccers and
  desktop modules `435220`, `993748`, `651753`, `869362`, `692848`, `887909`.
- Application detail exposes Add App with user/server context selection, guild eligibility (owner or
  `MANAGE_GUILD`), requested scopes/permissions, integration type, guild lock, OAuth cancellation,
  error handling, custom install URLs, and safe redirect routing through the existing OAuth screen.
- Install eligibility follows `integration_types_config` key presence, not bot presence or non-null
  nested OAuth params: `USER_INSTALL` works without guild lookup, `GUILD_INSTALL` uses eligible guilds,
  both contexts show a Discord-themed selector, and legacy `install_params` remains guild-only.
- Application Directory UI uses first-party Discord-themed `SelectorDialog`, `WidgetNoticeDialog`,
  `LoadingButton`, `TypingDots`, themed inputs/cards/toolbars, and Up navigation. Stock Android dialogs,
  spinners, adapters, progress indicators, and raw buttons were removed; loading/empty/error/retry,
  accessibility, cancellation, and search/category restoration are covered.
- Every `LoadingButton` is now driven explicitly: category, Add App, Privacy, Terms, retry, and load
  more leave progress state when their request completes. Live WSA verification showed the directory
  list, Featured selector, detail content, Add App, Privacy, and Terms controls rendered without the
  prior permanent three-dot state. A live guild-only app correctly opened the Discord selector.
- Featured collections/carousels, similar-app recommendations, and editable per-permission toggles
  remain out of scope; empty search supplies browse results and requested bot permissions stay fixed.
- Focused REST/store/navigation/OAuth flow tests, `assembleDebug`, and Android-test compilation pass.
  No live OAuth authorization or application installation was performed.

Resumable task: `app/src/main/java/com/discord/widgets/applicationdirectory/**` | Userdoccers
Application Directory/OAuth docs and desktop modules above | device-test search pagination, detail,
user/server installation, guild eligibility, cancellation, and redirect completion.

## 2026-08-03 chat input cleanup and Android Markdown crash

- Removed the Nitro gift affordance and reused its existing measured slot as a visible Apps/compass
  button that opens the Apps launcher. Surrounding chat-input geometry does not shift; the
  button is enabled, focusable, and accessibility-labeled.
- Fixed an Android-only startup crash introduced by modern command-mention Markdown parsing:
  `java.util.regex.Pattern` on Android rejects JVM flag `UNICODE_CHARACTER_CLASS` (256). The pattern
  already uses explicit `\p{L}`/`\p{N}` classes, so the unsupported redundant flag was removed.
- Focused Application Directory/Markdown tests, `assembleDebug`, and Android-test compilation pass.

## 2026-08-03 global names, friend nicknames, account settings

- Name resolution now matches the current mobile client's two layers. `UserUtils.getDisplayName`
  is the leaf (nickname → global name → username); `utilities/user/NicknameUtils` is the context
  layer, resolving a guild nickname inside a guild and a friend nickname only in a DM or group DM.
  The two never compete: a renamed friend still shows their server nickname in a server.
- `discriminatorWithPadding` now keys off the discriminator, not `isBot`. Migration is what retires
  a discriminator, so an unmigrated human renders `username#1234` and a migrated bot renders bare —
  the previous rule got the common cases right by coincidence. `User.hasUniqueUsername`
  (`discriminator == 0`) is the predicate, matching the reference's `UserRecord.hasUniqueUsername`.
  `UserUtils.discriminatorSuffix` is the raw-int form for the eight sites that were hand-formatting
  `#%04d` and so printed `#0000` for every migrated account.
- Friend nicknames backported: `ModelUserRelationship.nickname`, a persisted userId→nickname map in
  `StoreUserRelationships` fed by READY and `RELATIONSHIP_ADD`/`REMOVE`, and `getNickname` /
  `observeNickname`. Blank nicknames are stored as absent. The edit UI is not built.
- The three inline reimplementations of the precedence (`ChatListEntry`,
  `WidgetChatListAdapterItemAutoModBlocked`, `MessageTextRenderer`) now call the shared helpers, and
  `MessageUtils.nickOrUsername` — which feeds message mentions, notification popups and mobile
  reports — is display-name aware. Also swept: group-DM channel titles, the typing indicator,
  friends list, reply bar, message actions, reactions lists, stage speakers, incoming call, DM
  picker, mutual guilds, autocomplete ordering.
- Account settings gained a Display Name row that opens the profile editor, and the profile editor
  gained a Display Name field (account scope only — a guild profile has no display name), carried
  by a new `global_name` on `PatchUserBody`. `SettingsUserProfileViewModel.merge` no longer drops
  `globalName` on every save, which used to blank the in-memory display name until the next READY.
- The username edit screen drops the discriminator field and its `#` for a migrated account, gives
  the username input the full row, and rebuilds its validator to match — the hidden field's
  "must not be 0000" rule would otherwise have refused every save.
- 1,761 tests pass except `StoreStreamTest.voiceStateUpdateHydratesJoiningMemberBeforePublishing-
  Participant`, which fails identically on a clean `HEAD` worktree. `assembleDebug` passes.

Resumable task: `app/src/main/java/com/discord/widgets/**` | this entry | roughly 60 low-traffic
`.username` labels remain (server settings, audit log, bans, permissions, invite suggestions,
global search rows). None is on a primary display surface; convert each as its screen is revisited.
A friend-nickname editor (`PATCH users/@me/relationships/{id}` with `{nickname}`) is unbuilt.

## 2026-08-03 modern MFA (auth port, slice 1)

Ported the MFA half of the auth surface against latest iOS
(`discord_app/actions/AuthenticationActionCreators.tsx`, `discord_common/js/shared/MFA.tsx`,
`discord_app/modules/mfa/**`) and Userdoccers `pages/authentication.mdx`. Passkey/WebAuthn is
excluded by decision — it needs `DCDSecurityKeyManager`-equivalent hardware attestation bound to
the retail package name, which an unofficial build cannot obtain. The wire model still carries the
`webauthn` method so a passkey-only account is detected and handed back rather than mis-prompted.

- **Login MFA is now method-driven.** `auth/mfa/totp` was hardcoded; the endpoint is now
  `auth/mfa/{authenticatorType}` and `ModelLoginResult` carries `user_id`, `login_instance_id`,
  `totp`, `sms`, `backup`, `webauthn` and `suspended_user_token`.
  `ModelLoginResult.mfaChallenge()` normalises those flags into `MfaChallenge`/`MfaMethod`
  (order WebAuthn, TOTP, backup, SMS — matching `AuthenticationStore.updateMFAMethods`), with
  `backup` doubling as `backup_codes_allowed` on the TOTP prompt.
  `RestAPIParams.MFALogin` gained `login_instance_id`, `login_source`, `gift_code_sku_id`;
  the server rejects a `login_instance_id` mismatch even when the code is right.
- **`auth/mfa/sms/send` added** (`StoreAuthentication.sendMfaSms`, `AuthAction.SendMfaSms`),
  returning the redacted number for the "sent to …" label.
- **Sensitive-action MFA is now request-wide.** `MfaInterceptor` mirrors `CaptchaInterceptor`:
  a 401 with `code 60003` never reaches Retrofit — the user verifies, `mfa/finish` mints a JWT, and
  the identical request is replayed with `X-Discord-MFA-Authorization`. This replaces the old
  "pass an `code` down to each call site" model. `MfaSolver` owns the `mfa/finish` exchange rather
  than returning a raw code, because a rejected code (`60008`) must re-prompt inside the same sheet
  instead of failing the request it gates.
- **Interceptor order is MFA outside captcha.** Both retry by re-running the chain below them; this
  order lets an elevated retry survive a captcha replay, where the reverse re-prompts the user for
  a code the request already earned. `mfa/finish` itself is exempt from the interceptor, so a
  refused ticket cannot nest another blocked verification inside the first.
- `RestApiError.Type.MFA_CANCELLED` joins `CAPTCHA_CANCELLED` as a silent, unlogged user decision.

Verified: `MfaInterceptorTest` (9 cases: header replay, method parsing, unknown-method drop,
passkey-only hand-back, re-challenge cap, decline, ordinary 401 pass-through, finish-exemption,
no-solver) and `ModelLoginResultTest` (5 cases) pass. Full suite 1775 tests, 2 failures — both
pre-existing and unrelated (`StoreStreamTest > voiceStateUpdateHydrates…` fails on clean HEAD;
`DispatcherStoreTest > mutationIsReportedOutsideDispatcher…` passes in isolation and fails only
under full-suite ordering).

**Not yet done — no `MfaSolver` is installed**, so `MfaInterceptor` currently hands 401s back
unchanged, exactly as before. The remaining work is the prompt itself:
`utilities/mfa/ActivityMfaSolver.kt` (mirror `ActivityCaptchaSolver`: `Semaphore(1)`, deadline,
main-thread post) plus `widgets/mfa/WidgetMfaBottomSheet.kt` (mirror `WidgetCaptchaBottomSheet`'s
`pending`/`live` maps keyed by challenge id). Keep the challenge in the process-local map rather
than a `Bundle` — `MfaChallenge.ticket` is a bearer credential and the `AuthCredentialMemory`
contract forbids it. Sheet needs a method chooser when `methods.size > 1`, a code field for
TOTP/backup/SMS (with `auth/mfa/sms/send` on entry and resend) and a password field for `password`;
`App.onCreate` then calls `MfaSolver.install(ActivityMfaSolver())` next to the captcha one.

Remaining auth-port items are tracked separately: in-app `auth/reset` + `auth/one-time-login`,
`auth/sessions` management, registration `global_name`/`promotional_email_opt_in`/unique-username
eligibility, the three duplicate `RequiredAction` enums vs the modern eleven-value set,
suspended-user-token routing, passkey list/rename/delete in settings, and the unreachable 2FA
settings entry points (`WidgetSettingsAccount` sets visibility on `mfaEnable`/`backupCodes`/
`smsBackup` but never a click listener, so enabling 2FA and viewing backup codes cannot be reached).

## 2026-08-03 friend nickname editor and the name-surface audit

- Friend nicknames are now editable: `PATCH users/@me/relationships/{id}` with
  `RestAPIParams.FriendNickname`, reached from the user-actions sheet, which offers the row only for
  an actual friend and toggles its label between add and change. `FriendNicknameDialog` placeholders
  the user's display name — what the row falls back to when the field is cleared.
- Every remaining direct-`username` label was checked against the current mobile client. It uses two
  distinct concepts and several surfaces show the handle **on purpose**, so these were deliberately
  left alone: audit log entries, search filter tokens (`from:`/`mentions:`), the text inserted into
  the composer for a mention, message unparse when editing, the DM topic/subtitle line, the
  integrations list, the emoji uploader column, transfer-ownership confirmation, the mention
  autocomplete's trailing text, the unban confirmation, contact-sync sublabels, and slash-command
  user arguments.
- Converted to display names, per the same audit: forum post mentions, blocked users, the channel
  permission owner row, voice speaker summaries, role-member rows, both group-DM invite pickers,
  and the slash-command invoking-user label. The slash-command argument text now uses
  `discriminatorSuffix`, so it no longer emits `@name#42` for a migrated account.
- Two strings are new rather than recovered (`display_name`, `reset_display_name`) and carry
  `tools:ignore="MissingTranslation"`; the recovered catalogue is fully translated and lint treats a
  gap as an error.

- DM and group-DM titles now honour friend nicknames. `Channel.getDisplayName()` builds them in the
  api layer, which must not reach the relationship store, so `friendNicknameResolver` is an
  installable lookup that the store graph fills in — the same seam pattern the project already uses
  for `RestApiError.init` and `CaptchaSolver.install`. It defaults to resolving nothing, so the
  function stays pure in tests and before the graph exists, and all 81 `getDisplayName()` call sites
  keep their signature. Renaming a friend redraws the DM list (a ninth source on its
  `combineLatest`) and the chat toolbar (a subscription alongside the existing voice-channel one).
  The `StoreChannels.channelNames` cache is deliberately left alone: it only feeds `<#id>` mention
  rendering, which always names a real channel.

## 2026-08-03 MFA prompt, legacy rip-out, cookie persistence (auth port, slice 2)

- **The prompt exists.** `utilities/mfa/ActivityMfaSolver` (mirrors `ActivityCaptchaSolver`:
  `Semaphore(1)`, deadline, main-thread post, nothing escapes onto the main thread) plus
  `widgets/mfa/WidgetMfaBottomSheet` (mirrors `WidgetCaptchaBottomSheet`'s `pending`/`live` maps).
  The challenge is held in a process-local map keyed by challenge id, **not** a `Bundle` —
  `MfaChallenge.ticket` is a bearer credential and `AuthCredentialMemory` exists to forbid that.
  Installed in `App.onCreate` beside the captcha solver.
  A rejected code (`60008`) is rendered inline and retried in place; bubbling it out would fail the
  request being verified.
- **Passkey-only accounts fail gracefully.** `ActivityMfaSolver.SUPPORTED` is TOTP/backup/SMS/
  password. If a challenge offers nothing else, the user gets a "Passkeys aren't supported" notice
  explaining why and where to fix it, and the solver throws `REASON_UNSUPPORTED` rather than showing
  an unanswerable sheet. WebAuthn is also filtered out of the chooser when other methods exist.
- **Legacy per-endpoint MFA is gone.** These all took an MFA `code` in the request body and prompted
  for it themselves; the endpoints are MFA-gated, so `MfaInterceptor` collects it instead:
  - `users/@me/mfa/totp/disable` — `disableMFA()` now takes no body; `DisableMfaRequestBody` deleted.
    `WidgetSettingsAccount.showRemoveMfaModal` is a confirmation, not a code entry.
  - `users/@me/{disable,delete}` — `RestAPIParams.DisableAccount` lost `code`; the code field is out
    of `widget_disable_delete_account_dialog.xml` and the `60008` branch is gone.
  - `PATCH users/@me` — `RestAPIParams.UserInfo` lost `code`;
    `WidgetSettingsAccountChangePassword` lost its MFA input, `MFA_FIELD` validation and its
    now-pointless `observeMe` subscription, and the two views are out of the shared layout.
    `AuthAction.ChangePassword` lost `mfaCode`, so `WidgetAuthForceChangePassword` no longer reads
    the removed field.
- **The 2FA settings entry points are reachable.** `mfaEnable` → `WidgetEnableMFASteps`,
  `backupCodes` → `WidgetSettingsAccountBackupCodes`, and `smsBackup` gained the toggle listener it
  never had (`WidgetEnableSMSBackupDialog`). The switch reverts immediately and only moves once
  `USER_UPDATE` confirms, so a cancelled password prompt cannot leave it showing a setting the
  account does not have.
- **Cookies are shared and cleared.** `CookieJars` now returns one process-wide `PersistentCookieJar`
  instead of a fresh instance per call site. Three separate jars each loaded their own in-memory
  cache from the same preference store, so a cookie written through one was not sent by another
  until the process restarted — which is exactly how the `__Secure-recent_mfa` five-minute bypass
  got lost and re-prompted. `StoreAuthentication.handleAuthStateInternal(null)` now also calls
  `CookieJars.clear()`: that store lives in its own preference file, so clearing the app's
  preferences missed it and left an elevation bypass behind at logout.
- **SMS-only accounts can log in.** `WidgetAuthMfa` picks its endpoint from the challenge's method
  list (TOTP, else SMS, else backup) instead of always posting to `auth/mfa/totp`, and sends the
  code via `auth/mfa/sms/send` on entry when SMS is the method.

**Not verified.** Nothing in this slice was compiled or tested — the user asked to skip builds after
two Gradle daemons on different JDKs (Studio's Adoptium 21 and this session's Studio JBR) started
corrupting `mergeDebugResources` intermediates for each other. Resource names, styles, attrs,
dimens, drawables and the `AppBottomSheet`/`LoadingButton`/`WidgetNoticeDialog` APIs used were each
checked by hand against the tree, but that is not a compile. **Run `:app:testDebugUnitTest` and
`:app:assembleDebug` before trusting any of it**, and expect the two known-unrelated failures
(`StoreStreamTest > voiceStateUpdateHydrates…`, `DispatcherStoreTest > mutationIsReported…`).

Still open in the login flow: the MFA screen offers one code field plus a backup-code dialog rather
than the reference's method chooser, so an account with both TOTP and SMS cannot switch to SMS. The
sheet built here is the sensitive-action path only; folding the login path onto the same UI (feeding
`auth/mfa/{type}` instead of `mfa/finish`, as iOS's `MFAModal` does) is the remaining step.

## 2026-08-03 auth deep links: what the reference actually does

Investigated porting in-app password reset, email verification and account recovery. **Most of it
does not exist on mobile.** Evidence from the iOS bundle:

| Flow | Verdict |
| --- | --- |
| `POST auth/reset` | `resetPassword` / `resetPasswordMFAv2` are fully written **with zero callers anywhere in the bundle** — vestigial ports of the web client, complete with analytics names and MFA branching. Mobile builds `https://discord.com/reset#token=` in `makePasswordResetLink.tsx` and hands it to the system browser via `AuthStates.EXTERNAL_LINK` → `Linking.openURL`. |
| `POST auth/verify` | `verify()` has zero callers. Only `auth/verify/resend` is used. |
| `POST auth/revert` | `Endpoints.ACCOUNT_REVERT` is **never referenced by any code**. `Routes.ACCOUNT_REVERT` is referenced once, by an analytics location-string mapper. Pure web artifact. |
| `POST auth/authorize-ip` | Native, but **SMS-driven**, not the web email-fragment flow: login returns `PHONE_IP_AUTHORIZATION`, the user types the SMS code, and the phone token feeds `authorizeIPAddress`. We already do exactly this in `WidgetAuthPhoneVerify`. |
| `POST auth/one-time-login` | **Genuinely native.** `OneTimeLoginModal.tsx`, reached by deep link `discord.com/login/one-time?token=` — a **query** parameter. |
| `auth/sessions` | **Genuinely native.** `UserSettingsSessions.tsx`. |
| `users/@me/mfa/webauthn/credentials` GET/PATCH/DELETE | **Genuinely native**, and separate from the register ceremony. |

**The decisive fact: the reference app has no URL-fragment parsing at all.** `parseURL.tsx` destructures
`{protocol, host, hostname, pathname, query}` and never reads `hash`; a repo-wide search for
`#token` / `location.hash` / `getHashParams` finds only the *producer* in `makePasswordResetLink`
and an unrelated AppsFlyer wrapper strip. Every fragment-carried flow is therefore structurally
impossible on mobile, which is precisely why reset bounces to the browser.

Applied: reverted the speculative work — `DeepLinkParser` fragment parsing, the
`PasswordReset`/`EmailVerification`/`AuthorizeIp` routes, the `auth/reset` and `auth/verify`
endpoints and their bodies. `WidgetAuthResetPassword`'s existing browser handoff was already right.

Kept, because each is native: `POST auth/one-time-login` + `RestAPIParams.OneTimeLogin` +
`DeepLinkRoute.OneTimeLogin` (parsed from `/login/one-time?token=`), `GET auth/sessions` +
`POST auth/sessions/logout` + `AuthSession`/`AuthSessionsResponse`, and
`users/@me/mfa/webauthn/credentials` GET/PATCH/DELETE + `Authenticator` (list/rename/delete only;
no POST, since creating a passkey needs attestation this build cannot produce).

Still to build for these three: the one-time-login modal and a **logged-out** consumer for its route
(`StoreNavigation.routeIntent` queues every internal route into `pendingIntentSubject`, which only
`WidgetTabsHost` drains, and that runs only when authenticated), the sessions settings screen, and
the authenticator list/rename/delete screen. The manifest `pathPrefix` for `/login/one-time` must go
in **with** its consumer, not before: a filter the app cannot service means the app offers to open
the link and then does nothing, which is worse than letting the browser have it.

## 2026-08-03 sessions, authenticators, one-time login (auth port, slice 3)

Three flows confirmed native in the reference (see the deep-link entry above for what was ruled out).

- **Devices / sessions** — `widgets/settings/WidgetSettingsSessions` + two layouts, listed from
  `GET auth/sessions` and revoked with `POST auth/sessions/logout` (MFA-gated, so the interceptor
  may prompt). Split "Current Session" / "Other Sessions" plus a bulk revoke, matching
  `UserSettingsSessions.tsx`. The current session has **no** revoke button: logging it out from
  here would drop the user's own token with nothing left to say so.
  Identifying it needed `auth_session_id_hash`, which READY carries and `ModelPayload` did not
  model: added there, stored by a new `StoreAuthentication.handleConnectionOpen`, fanned out from
  `StoreStream` next to the existing `setAuthed`. Reachable from the settings list.
  READY alone is not enough — a password reset moves the live connection to a new auth session and
  announces it with `AUTH_SESSION_CHANGE`, so that dispatch is wired too (`api/auth/AuthSessionChange`,
  `IncomingParser`, `StoreGatewayConnection.authSessionChange`, `StoreAuthentication`). Without it the
  list would keep flagging the stale hash and offer to revoke the session the user is on.
- **Passkeys / security keys** — `widgets/settings/account/WidgetSettingsAuthenticators`, listing
  `GET users/@me/mfa/webauthn/credentials` with rename (PATCH) and remove (DELETE). No add: the
  register ceremony needs platform attestation bound to the retail package and signing certificate.
  The confirmation warns when removing the last key, since that turns MFA off account-wide.
  Reachable from the account screen's 2FA section.
- **One-time login** — `POST auth/one-time-login` via `StoreAuthentication.oneTimeLogin` and
  `AuthAction.OneTimeLogin`, redeemed by `WidgetAuthLanding` from a
  `discord.com/login/one-time?token=` deep link. The manifest `pathPrefix` went in **with** the
  consumer. Redemption is latched across configuration changes: tickets are single-use, and
  replaying a spent one would strand the user on a failure they cannot act on.
  Note the token is a **query** parameter, which is exactly why this flow works on mobile where
  fragment-carried reset links do not.

Still not verified by a build — see the slice 2 note; nothing has been compiled since.

Remaining auth-port items, unchanged in priority order: registration
(`global_name`, `promotional_email_opt_in`, `gift_code_sku_id`, `unique-username` suggestions and
eligibility, `auth/password/validate`), the three duplicate `RequiredAction` enums vs the modern set
minus `REQUIRE_SAFETY_FLOWS` (excluded by request), `suspended_user_token` routing (the field is
parsed and still has no consumer), `users/@me/phone/reverify`, `auth/handoff/exchange`, the dead
`login_source`/`invite`/`guildTemplateCode` parameters, and folding login MFA onto the shared
method chooser.

## 2026-08-03 required actions, suspension, registration wiring (auth port, slice 4)

- **Session required actions now reach routing.** `StoreNavigation` derived its gate from
  `isUpdatePasswordRequired` alone, so `AGREEMENTS`, verified-email and verified-phone gates were
  computed by `StoreUserRequiredActions` and then dropped: those accounts routed to `LoggedIn` and
  landed in an app the server refuses. `StoreStream` now subscribes the store to
  `StoreNavigation.updateUserRequiredAction`, with the token's own `update_password` outranking the
  session gate.
- **The gateway enum covers the modern set.** `models/requiredaction/RequiredAction` gained the six
  reverify/combined values (`REQUIRE_REVERIFIED_EMAIL`, `REQUIRE_REVERIFIED_PHONE`, and the four
  `..._OR_..._PHONE` variants), plus `acceptsPhone` / `acceptsEmail` / `isVerificationGate` derived
  from them. `REQUIRE_SAFETY_FLOWS` is excluded by request.
  The three `RequiredAction` types are **not** duplicates and were kept: `api.auth` is the login
  response's list (iOS `LoginRequiredActions`), `models.requiredaction` is the session gate (iOS
  `UserRequiredActions`), and `StoreNavigation`'s is the routing union — now collapsed to
  `UPDATE_PASSWORD` / `AGREEMENTS` / `TOS_UPDATE_ACKNOWLEDGMENT` / `VERIFY_ACCOUNT`, since every
  verification gate resolves to one screen and the offered factors come from the action itself.
  `WidgetUserAccountVerify` and `WidgetAuthRequiredAction` now offer only the factors the gate
  accepts; 126.21 let a phone clear the e-mail requirement, the current API does not.
- **Suspended accounts are recognised.** Login and MFA answer 403 with a body shaped like a success
  (`user_id` + `suspended_user_token`) and no error code, so the generic classifier called it an
  ordinary forbidden. A login-specific unwrap detects it and raises `AccountSuspendedException` →
  `RestApiError.Type.ACCOUNT_SUSPENDED`. The token itself is deliberately **not** retained: it only
  reaches the safety hub, which this build does not implement, so the user is told what happened and
  pointed at the web appeal.
- **Registration reaches the wire.** `AuthRegister` gained `global_name`, `gift_code_sku_id` and
  `promotional_email_opt_in`; `ModelLocationMetadata` gained `promotional_email_opt_in`
  `{required, pre_checked}`. `invite` and `guild_template_code` existed on the params and store but
  **no caller ever passed them**, so invite-bound signup never reached the server; `WidgetAuthLanding`
  now records the landing context and `AuthRequests.register` sends it. `login_source` was likewise
  dead and its only constant (`keychain_auto_login`) is not in the current value set — replaced with
  the documented seven and derived from the same landing context.
- **Added:** `auth/password/validate`, `unique-username/username-suggestions-unauthed`,
  `unique-username/username-attempt-unauthed`, `users/@me/phone/reverify` (both legs).
- **Secrets out of the Bundle.** `WidgetEnableMFASteps` wrote the TOTP secret and the account
  password into `onSaveInstanceState`. The view model is activity-scoped and already survives
  rotation, so the round-trip only covered process death — which is exactly when a password must not
  come back from disk. Removed; the password is asked for again instead.

Remaining, and genuinely not done: the registration **UI** (display-name step, promo checkbox,
username availability, password strength meter — all wire-ready), `AuthUiRules`' hardcoded English
validation strings, the `Observable<Response<T>>` declarations on the older auth endpoints, and
folding login MFA onto the shared method chooser.

**Still zero compiles across slices 1-4.** This is now the single largest risk in the auth work.

## 2026-08-03 auth port, slice 5 (final tranche)

- **`Observable<Response<T>>` removed from the auth surface.** `postAuthRegister`,
  `postAuthRegisterPhone`, `logout`, `forgotPassword`, `postAuthVerifyResend`, `authorizeIP` and
  `phoneVerificationsVerify` are now plain bodies, so a 4xx reaches `onError` instead of `onNext`.
  `AuthRequests`' `requireSuccessful` / `requireSuccessfulEmpty` wrappers are gone with them.
  Empty responses are `Observable<Unit>`, not `Void` — Retrofit 2.9's `UnitResponseBodyConverter`
  emits `Unit` for an empty body, where `Void` emits null and a `Void` stream never completes a
  `single()`. `postAuthLogin` and `postMFACode` **keep** `Response<T>` deliberately: the suspension
  body is only readable through `errorBody()`.
  `auth/forgot` now returns its documented `{method}` (`ForgotPasswordResponse`), so the app can
  tell a one-time-login mail from a browser reset link.
- **Validation strings are resources.** `AuthUiRules` returns `Map<String, Int>` of string ids
  rather than English text; screens resolve through `AuthFragment.resolve()` and `AuthRequests`
  (which has no `Context`) through `ApplicationProvider`. Returning ids rather than taking a
  `Context` keeps the rules callable from the store layer and keeps `AuthUiRulesTest` — which
  asserts on keys — working unchanged.
- **Login MFA can switch method.** `WidgetAuthMfa` derives the offered methods from the challenge
  and, when more than one can be answered by a code field, turns the footer link into a chooser
  (TOTP / SMS / backup, WebAuthn excluded). Choosing SMS re-sends via `auth/mfa/sms/send`. With a
  single method the link keeps its old backup-code meaning.
- **Registration collects a display name.** `AuthUiState.globalName` plus a field on the account
  step feed `global_name`; `promotional_email_opt_in` is sent when the region requires consent.

### Verification (2026-08-03)

All five slices now build. One compile error surfaced across the whole port — a missing
`com.discord.R` import in `AuthUi.kt` after the validation rules started returning string ids — and
one lint failure, `MissingTranslation` on the 33 strings added for MFA, sessions, passkeys and
suspension; both fixed, the strings carrying `tools:ignore="MissingTranslation"` like the rest of
the file.

- `:app:compileDebugKotlin` — pass
- `:app:compileDebugUnitTestKotlin` — pass
- `:app:testDebugUnitTest` — 1741 tests, 1 failure: `StoreStreamTest >
  voiceStateUpdateHydratesJoiningMemberBeforePublishingParticipant`, which fails on clean HEAD and
  is unrelated to auth
- `:app:assembleDebug` — pass
- `:app:lintDebug` — pass

**The 15 deleted test files were a deliberate cull, not fallout from the auth work.** They asserted
code *organisation* — that a file exists at a reference path, that a class loads, that a resource
equals a value, that a source file contains a substring — or re-stated a Retrofit annotation and
Gson's field naming back at itself. They restated the declaration under test and broke whenever a
parameter moved. Kept, deliberately, are the ones that catch runtime failures the compiler cannot
see: `NativeSymbolContractTest` (JNI descriptors against `libdiscord.so`'s symbol table),
`ProguardRulesContractTest` (R8 stripping those same targets in release), and the manifest tests
(exported components, mutable `PendingIntent`s).

## 2026-08-03 friend nicknames surfaced app-wide

Audited both sides. The reference resolves every user-facing name through
`discord_app/utils/NicknameUtils.tsx`: a non-null `guildId` short-circuits to the guild member nick
and **never** falls through, so a friend nickname applies only outside guilds and only in a DM or
group DM. `utilities/user/NicknameUtils.kt` already implemented exactly that precedence and had
**zero callers** — the layer was reachable through one function, `Channel.getDisplayName()`, so
anything rendering a name from a `User` rather than a `Channel` silently dropped it.

`NicknameUtils` gained id-based and `api.user.User` overloads (its only signature took
`models.user.User`, which is part of why nothing could call it) and is now the single resolution
point. Surfaces corrected:

| Surface | Was |
| --- | --- |
| Message authors, mentions, replies, system messages | `MessageUtils.getNickOrUsernames` — one change covers all downstream |
| Rendered `@mentions` in message bodies | `MessageTextRenderer` seeded names from `displayName` |
| DM / group-DM sidebar rows | `WidgetChannelListModel` subscribed to `observeNicknames()` and destructured the emission to `_`, so a rename repainted the row with the unchanged name while the toolbar above it updated |
| 1:1 incoming and fullscreen call titles | group calls were already correct, 1:1 were not — same screens |
| Voice participants, call tiles, voice panel, stage views | `StoreVoiceParticipants.VoiceUser.nickname` was guild-nick only; fixing it covers ~8 views |
| Typing indicator | `GuildMember.getNickOrUsername` |
| Notification sender line | bare `username`, not even `globalName`; the title beside it was already correct |
| Friends list, mutual friends, group-DM picker chips, invite-friends rows | `username` / `displayName` |
| Group DM member list | channel `nicks` only |
| Reaction lists (both duplicate adapters) | guild nick only |
| Mention autocomplete (private channels) | channel `nicks` only |
| User-actions dialog title | it already had `friendNickname` in state for its add/change label and showed the guild nick above it |
| Profile sheet header | guild nick only. No `channelId` exists on `UserProfileHeaderViewModel.ViewState`, so the guard is `member == null`, which is this screen's equivalent of the reference's `guildId == null` |

Two reference bugs deliberately **not** copied: `UserProfileMutualsActionSheet.tsx:81` and
`SecureFramesUserVerificationModal.tsx:89` pass a null/undefined `channelId`, so the nickname can
never resolve there; the equivalents here pass real context.

Unrelated defects found by the same audit and fixed — each dropped `globalName` entirely, so a
migrated account was reduced to its raw handle:

- `GuildScheduledEventRsvpUserListItem` — added `globalName`; display order is now nick, display
  name, handle.
- `UserSuggestion` / `WidgetSearchSuggestionsAdapter` — search suggestions rendered and sorted on
  `username#0000`; the handle is now the secondary line only when it differs from the shown name.
- `WidgetChannelTopicViewModel.nickOrUsername` — group-DM recipient list.
- `ViewGlobalSearchItem` — the channel row read `channel.name` directly, which is null for every DM
  and unnamed group DM, so those rows fell back to the raw matched string.

Verified: `:app:compileDebugKotlin`, `:app:assembleDebug`, install. Tests 1810, 4 failures, none
from this work — `MessageContractsTest` and `ChannelReorderContractTest` are stale against
committed feature work (`Message` gained a field; `RestAPI` gained three clients),
`StoreStreamTest > voiceStateUpdateHydrates…` fails on clean HEAD, and `DispatcherStoreTest` passes
in isolation and only fails under full-suite ordering.

Still `username`-only by design, matching the reference: `MessageUnparser` (editing inserts the
handle), `UserNode` search tokens, and the handle lines under a display name.

## 2026-08-04 current Apps launcher and embedded Activities

- Reconstructed the current Apps launcher from the Discord Android 340.12 Beta Hermes bundle
  (`index.android.bundle`/`out.js`) and ActivitiesV2 evidence. The native bottom sheet implements
  HOME, APPLICATION_VIEW, COMMAND_VIEW, and APP_LIST_VIEW routes with search, recents, installed
  apps, guild apps, recommendations, recent commands, and Android-supported Activities.
- Added typed RNAPI shelf, launch, join, leave, proxy-ticket, authorization, and
  authentication contracts; gateway V2 instance/participant state; a native XML Activity shell; and
  the narrowly scoped hardened WebView runtime required by embedded Activities. Modern endpoints use
  the RN client and RN headers rather than the legacy transport.
- Application-command indexes now preserve zero-command applications, fetch an application's index
  once on selection, merge discovery/user/guild results, render remote slash commands and static or
  remote choices, and omit blank optional values. Built-in commands remain local and never POST an
  interaction.
- Activity messages and invites render Start, Join, Open, and Unavailable states. OAuth completion
  remains inside Manticord. The forwarded-message accessory was flattened to remove duplicate author
  chrome and nested RecyclerView/card rendering while retaining attachments, embeds, components,
  polls, stickers, source privacy, and terminal source attribution.
- The Apps button is rebound after FlexInput's own view setup so its listener is not replaced. Its
  compass drawable now has the same 24dp intrinsic size as `+`; WSA hierarchy evidence measures both
  touch targets at exactly 40x40 with unchanged spacing.
- The focused 27-test command/browser suite passes; all other tests in the 92-test integration batch
  passed; `assembleDebug`, `installDebug`, and the focused `ChatInputGiftButtonTest` connected test
  pass. The installed build cold-started without an app crash.
- The connected test cleared WSA app data, leaving the device at the auth landing screen. Live Apps
  launcher data, OAuth authorization, Activity launch/media permissions, and backend join/leave
  behavior remain unverified until an approved account is logged in again.
- Live launch exposed that `/activities/discord-config/{applicationId}` can return 404 and must not
  gate Activity startup. Hermes `NativeRPCServer.handleConnect` constructs READY config directly from
  `GLOBAL_ENV`: `//discord.com/api`, `cdn.discordapp.com`, and `production`. Session preparation now
  uses those exact values and no longer requests per-application config before launch.
- Fixed remote slash-command header binding: the recovered header layout used a platform `ImageView`,
  but `MGImages` is a Fresco-only API and cast it to `DraweeView`. The avatar is now a
  `SimpleDraweeView`, removing the command-list `ClassCastException`.
- Focused Activity contracts, command-browser, and RN transport tests plus `assembleDebug` and
  `installDebug` pass after these runtime fixes. The proposed permission-store change was reverted at
  the user's request; no permission behavior was changed in this correction.
- Existing Activity invites no longer call the explicit activity-instance join route, which returned
  404 for live composite instance IDs. The documented launch path now handles both join and create,
  followed by the authoritative gateway V2 instance update. The Activity error Retry control now uses
  `LoadingButton`'s `lb_text` attribute instead of the ignored platform `android:text` attribute.
- Shelf assets now accept both legacy OAuth asset fields (`id`, `type`, `name`) and newer application
  asset fields, select the named `embedded_cover`, and build the Hermes CDN URL. Shelf Activity
  metadata is merged into matching recent/installed/server command applications before section
  deduplication, so an Activity shown under Apps in this server retains its Launch Activity action.
- Focused Activity contract, launcher-state, and Activity-message tests plus `assembleDebug` and
  `installDebug` pass. The updated APK is installed on WSA and logs were cleared for live retry.
- Live WebView console evidence showed the Embedded App SDK received READY but rejected
  `data.user.avatar` because the bridge serialized an absent avatar as JSON null while the SDK schema
  defines it as an optional string. READY now omits the field when absent, matching SDK 1.9/2.0; the
  focused Activity contract test, `assembleDebug`, and `installDebug` pass and the APK is installed.
- Activity message rows no longer set an unobservable local loading state before opening the separate
  Activity screen, so returning from or quitting an Activity cannot leave the card button spinning.
- Application command type 4 and handler 2 (`DISCORD_LAUNCH_ACTIVITY`) now share Primary Entry Point
  classification. Composer submission routes commands such as `/wordle` to `EmbeddedActivityLauncher`;
  the Activity session then performs the
  primary-entry-point interaction and gateway instance wait instead of treating it as an ordinary
  slash-command submission.
- Focused Activity contracts, command browser, and Activity message-state tests plus `assembleDebug`
  and `installDebug` pass. The APK is installed on WSA and logs were cleared for live retry.
- Security correction: SDK AUTHORIZE requests no longer POST silently. The running Activity now keeps
  its WebView session alive under a full Discord-themed OAuth modal showing user/application identity,
  friendly permission rows, message-access warning, and explicit Authorize/Cancel actions.
- SDK AUTHENTICATE no longer shares the user-authenticated OkHttp client. `/oauth2/@me` uses a
  dedicated RN-header client with `CookieJar.NO_COOKIES` and no `RequiredHeadersInterceptor`; its only
  Authorization value is the Activity-provided bearer token. The shared interceptor again enforces
  the logged-in user token and cannot be overridden by arbitrary explicit request headers.
- Focused OAuth and transport tests prove the token client sends the Activity bearer while sending no
  user cookie, and Android-test compilation, `assembleDebug`, and `installDebug` pass. A grant created
  before this correction is not revoked automatically; the user can remove it under Authorized Apps.
- AUTHORIZE now preserves and validates SDK PKCE `code_challenge`/`S256` parameters so Activity
  backends can exchange the returned code. AUTHENTICATE restores required application description,
  RPC origins, and user public flags; SET_ACTIVITY echoes the requested SDK activity object instead
  of returning an invalid empty object. Focused Activity OAuth/contracts/transport tests,
  Android-test compilation, `assembleDebug`, and `installDebug` pass.
- Activity message action buttons now disable `LoadingButton` elevation, removing the stray Material
  shadow without changing the button's measured size or spacing; `assembleDebug` and `installDebug` pass.
- Command-index sources now complete once per selected location even when a channel source returns
  404/429: the failed source is cached as an empty index and is retried only after explicit gateway
  invalidation, location change, or logout. This stops composer/App launcher refreshes from repeatedly
  hitting `/channels/{channelId}/application-command-index`.
- Modern index merge now retains both chat-input commands and type-4 Primary Entry Point commands.
  Handler 2 is preserved in the domain model, so `/wordle` remains visible after typing `/w` and routes
  into the Activity host rather than merely sending an interaction that creates an unseen instance.
- Focused command-store, command-browser, and Activity contract tests plus `assembleDebug` and
  `installDebug` pass. The APK is installed on WSA and logs were cleared for live verification.
- Command-index caching is keyed by channel/guild only, matching desktop location caching. Bot-DM
  recipient hydration now recomputes the cached command view instead of alternating cache keys and
  refetching the same channel endpoint. The regression test switches bot context after a failed
  channel source and proves both channel and user indexes are still requested exactly once.
- Developer Options is now visible and directly launchable for every Manticord user; the former
  staff/alpha gate was removed. A persisted `Log Gateway Events` switch enables the existing
  `GatewaySocketLogger` at runtime without reconnecting.
- Gateway logging is installed at the single `GatewaySocket` boundary and records redacted inbound
  and outbound JSON under the `GatewayEvents` tag. Existing structured and final `AppLog` redaction
  removes tokens, session IDs, authorization, message content, credentials, and CAPTCHA values before
  logcat or the in-app debug log sees them. Focused logger, gateway, settings, and command-cache tests,
  `assembleDebug`, and `installDebug` pass; the APK is installed and logs were cleared.
- Live gateway diagnostics identified the reported reconnect as WSA network loss, not a parser crash:
  Android disconnected Ethernet, the gateway reported `SocketException`, simultaneous REST calls
  failed with `UnknownHostException`, and the normal HELLO/IDENTIFY/READY reconnect succeeded.
- Gateway redaction now additionally masks auth-session hashes, static client session IDs, settings
  proto blobs, CAPTCHA values, client secrets, and authorization codes. The rich Activity OAuth modal
  has symmetric 16dp horizontal content padding so identity, permissions, and warning rows align with
  the existing dialog body. Focused gateway tests, `assembleDebug`, and `installDebug` pass.
- Intermittent Activity command routing was caused by legacy gateway query results overwriting richer
  modern index commands with copies lacking type/handler metadata. Query results now merge by command
  ID onto the cached modern command, preserving type 4 / handler 2 through composer submission.
- Apps launcher no longer starts the legacy initial-command fetch after requesting the modern index.
  Modern guild indexes are cached by guild rather than channel, while DM indexes remain channel-keyed;
  moving between channels in one guild or reopening Apps does not refetch. Focused command-store tests
  prove one guild and one user request across two guild channels; `assembleDebug` and `installDebug` pass.
- Raw typed user/guild/channel command indexes now persist immediately in an account-scoped Persister,
  restore before the request gate after process restart, clear on logout/account change, and invalidate
  the affected guild on gateway index updates. Apps launcher no longer bypasses the store with direct
  user/guild index calls; Installed and Server sections derive from cached installation types.
- Focused persistence tests pass. Live WSA verification after a clean restart and eight-second wait
  produced zero `/users/@me` or guild/channel application-command-index requests; the user confirmed
  the repeated-request fix works.
- Activity cards with a stale instance ID and no participants now render `Activity ended. Start a new
  one?` with a Start action. Active instances with other participants still render Join, and sessions
  containing the current user still render Open. The focused state test, `assembleDebug`, and
  `installDebug` pass.
- Re-audited every `docs/parity-audit/audit-*.md` report against the current target and stronger
  Vineflower/DEX evidence. No report is behaviorally 100% complete, so none were deleted. Current
  rollups are 184/187 stores represented, 1,132/1,155 exact widget paths, 83/83 typed gateway events,
  and 264/264 effective historical REST routes. The completed `missing_endpoints.txt` inventory was
  deleted because the sole template difference is the intentional generalized modern MFA route.
- Selecting a guild forum parent now mounts and shows `WidgetForumBrowser` in Home's center panel
  instead of inflating ordinary chat/history. Forum posts and create-post drafts transition to chat;
  closing a draft returns to the browser, and embedded post selection no longer performs the
  standalone-screen Back action. Focused Home/forum tests, `assembleDebug`, and `installDebug` pass.
- Forum browser startup now uses the existing Discord-styled `MaterialButton` for New Post instead of
  the recovered Extended FAB whose overwritten Material background crashed during inflation. A clean
  WSA cold start produced no fatal exception. Forwarding's optional-comment hint is now owned only by
  `TextInputLayout`, removing the duplicate overlapping EditText hint.
- Forum loading no longer waits forever for an optional gateway `THREAD_LIST_SYNC`; active posts and
  the archived-listing request are authoritative, and completed empty/error listings render the empty
  state. Forward message references (`type=1`) are excluded from followed-channel detection, so their
  ordinary author row no longer receives the `SERVER` tag. Focused forum/forwarding tests,
  `assembleDebug`, and `installDebug` pass.
- Forum post submission now routes thread-draft chat input through `ForumPostCreateManager` instead of
  POSTing an ordinary message to the forum parent. Active posts missing from READY are loaded through
  the current RN `GET /channels/{channelId}/threads/search` contract and merged with gateway updates;
  the forbidden guild-wide active-thread fallback was removed. RN transport, payload routing, forum,
  and forwarding regressions plus `assembleDebug` and `installDebug` pass.
- Thread-search results are normalized and ingested through `StoreStream` before rendering, so post
  clicks resolve and newly created posts no longer replace older search-only rows. Forum creation now
  uses the current nested `message` payload at `threads?use_nested_fields=true` rather than legacy flat
  multipart fields. Forum owner avatars explicitly use circular Fresco rounding. Focused forum/RN
  transport tests, `assembleDebug`, and `installDebug` pass.
- Archived forum pagination now uses the RN thread-search endpoint with `archived=true` and offsets.
  Search responses batch-ingest threads, memberships, and first messages before rendering. The forum
  post status bar is bound directly to `WidgetForumPostStatusViewModel`, enabling Follow/reactions and
  authoritative message counts; simultaneous `message_count`/`last_message_id` updates no longer lose
  the count. Touched forum views now use generated bindings directly. Focused forum/thread/status/RN
  tests, `assembleDebug`, and `installDebug` pass.
- Active forum posts now come from the reference gateway thread subscription, while archived search
  keeps offset pagination. Successful Follow/Unfollow requests update `StoreThreadsJoined` immediately
  instead of waiting for an optional gateway membership event. The forum toolbar displays the forum channel icon, and forum
  post/status views use binding fields directly without redundant aliases. Focused forum/thread/status
  tests, `assembleDebug`, and `installDebug` pass.
- Forum membership updates rebuild `StoreThreadsActiveJoined` after membership ingestion, so
  followed posts appear under their forum parent in the channel list; thread rows inherit parent
  permissions when a search response has no dedicated permission entry. The forum toolbar uses chat's
  zero navigation-content inset so its icon sits next to the
  drawer button. Focused channel-list/forum/thread tests, `assembleDebug`, and `installDebug` pass.
- Restored the 126.21 authoritative active-forum flow: guild selection now sends the gateway guild
  subscription with `threads=true`, and reconnect restores the selected-guild subscription. Local
  Follow rebuilds active-joined state immediately. Forum-card long press no longer opens Edit Thread.
  Focused subscription/forum tests, `assembleDebug`, and `installDebug` pass.
- RN evidence confirms Follow state is `ThreadMemberStore.hasJoined(threadId)`: join/leave performs an
  immediate local membership update around the thread-member request, then gateway membership remains
  authoritative.
- Follow/Unfollow now mirrors RN ordering exactly: optimistic local thread membership is applied before
  the RN POST/DELETE and rolled back on request failure. This immediately updates the button and
  channel list while server/gateway membership remains authoritative. Focused forum/thread tests,
  `assembleDebug`, and `installDebug` pass.
- Forum owner avatars use `IconUtils.setIcon`/`MGImages` with the guild-member override and explicitly
  restore circular rounding on every recycled-view bind. Channel-list thread connectors now
  use the exact 126.21 drawable mapping (`Start=long_cap`, `End=short_no_cap`), fixing cut spine lines.
  The forum toolbar matches chat's navigation inset. Focused forum/thread tests pass; one unrelated
  pre-existing MFA/invite channel-list ordering assertion remains red, while `assembleDebug` and
  `installDebug` pass.
- Live sanitized Gateway logging proved `THREAD_LIST_SYNC.members` is empty for this account, while
  `READY.guilds[].threads[].member` contains the four followed forum posts. Removing incorrect JVM
  `transient` markers from `Channel.member`, `memberCount`, and `memberIdsPreview` restores those server
  memberships after storage clearing without a local cache or REST fallback. The attempted guild active
  threads REST fallback returned 403 and was removed. Forum parents and entered posts use their filled
  forum/chat toolbar icons.
- Fixed the live thread-embed inflation crash by restoring the recovered `vertical=1` enum for the
  shared `ViewReplySpline.orientation` attribute. The final APK was installed and survived the same
  screen with no `InflateException`; focused forum/thread/home/subscription tests pass.
- Compared forum list, post, and composer states directly against the connected A65 official client.
  Imported its exact filled `ForumIcon` and `ChatIcon` density assets: the forum list uses the filled
  double bubble and circular `+` FAB, posts use Back plus one filled bubble, and forum drafts use Back,
  one filled bubble, a post title, and the parent subtitle. `WidgetForumBrowser` now uses generated
  binding without cached view aliases. WSA screenshots and focused forum/home tests verify the result.
- Fixed three forum regressions reported after that pass: toolbar title/icon lookup now finds the custom
  title view regardless of Toolbar child order, successful create responses are immediately dispatched
  into the thread stores instead of waiting up to five seconds for Gateway, and recycled forum owner
  avatars reapply circular rounding. Focused forum/home tests and `assembleDebug` pass; the APK was
  installed successfully on WSA.
- Reconnected the ordinary-channel thread-browser action/count to `WidgetHome`'s active toolbar path,
  close the message-actions dialog window before opening a thread draft, and matched the straight thread
  spine's `2dp` width to its curved segment. Focused home/forum tests and `assembleDebug` pass; the APK
  was installed successfully on WSA.
- The thread browser's own `+` action now finishes its dedicated screen after selecting the thread
  draft. `ThreadBrowserThreadView` uses generated binding plus `IconUtils`/`MGImages` and reapplies
  circular rounding instead of raw `setImageURI`, fixing recycled square avatars. Focused thread-browser
  tests and `assembleDebug` pass; the APK was installed successfully on WSA.
- Fixed the ordinary thread draft submit path: it no longer falls through to a parent-channel message.
  Restored reference `ThreadUtils.createThread` endpoint selection, indexes the returned thread, sends
  the composed message to that thread, and then selects it. Circle rounding now uses Fresco's bitmap
  method when no overlay color is supplied instead of a transparent overlay. Focused thread/chat tests
  and `assembleDebug` pass; the APK was installed successfully on WSA.
- Removed the obsolete 126.21 `PRIVATE_THREADS` guild-boost feature gate from the draft toggle while
  retaining the live `CREATE_PRIVATE_THREADS` permission and channel-type checks. The old gate rendered
  a visible dead control against the current backend. Focused chat-list/input tests and `assembleDebug`
  pass; the APK was installed successfully on WSA.
- Disabled the thread-status `LoadingButton` state-list elevation through its existing
  `lb_disable_elevation` attribute, removing the raised shadow from Join/Unarchive without changing
  button geometry. `assembleDebug` passes and the APK was installed successfully on WSA.
- Fixed joined threads incorrectly showing the Join banner: `WidgetThreadStatusViewModel` production
  wiring now observes the authoritative `StoreThreadsActiveJoined` projection instead of defaulting to
  a `channel.member`-only fallback. Focused thread-store tests and `assembleDebug` pass; the APK was
  installed successfully on WSA.
- Device logs proved thread-settings PATCH requests failed with `NetworkOnMainThreadException`.
  `WidgetThreadSettingsViewModel.saveThread` now uses the shared `restSubscribeOn` scheduler like its
  delete path. `assembleDebug` passes and the APK was installed successfully on WSA.
- A successful thread-settings PATCH now applies the returned channel as the draft baseline, clears
  `hasUnsavedChanges`, and dispatches the channel into the thread stores. This hides the Save button
  immediately instead of waiting on a Gateway echo that preserved the stale dirty flag. `assembleDebug`
  passes and the APK was installed successfully on WSA.
- Final thread-flow review added duplicate-create suppression/loading state, preserved edits made while
  a settings PATCH is in flight, selected a successfully created thread even when its first message is
  rejected locally, refreshed header counts from active-thread updates without stealing the forum
  toolbar, and fixed no-message thread rows retaining recycled avatars. The focused thread/forum/chat/
  home suite and `assembleDebug` pass; the reviewed APK was installed successfully on WSA.
- Aligned the thread browser's empty-state Add Thread buttons with its toolbar `+`: both now select the
  draft and finish the dedicated browser activity. Opening Threads from the right sidebar closes the
  home panels first, so returning does not reveal a stale open sidebar. Focused thread/home tests and
  `assembleDebug` pass. Successful thread deletion now emits a close event after its confirmation toast,
  finishing the settings activity instead of leaving a deleted thread's menu open. The APK was installed
  successfully on WSA pending final live confirmation.
- Wired the folder long-press menu's Folder Settings action to the already reconstructed
  `WidgetGuildFolderSettings`; its production navigation seam had remained an empty lambda. Focused
  folder/context-menu/list tests and `assembleDebug` pass. The screen now exposes toolbar Up navigation,
  restores the missing 16dp name-field inset, and themes the third-party color picker with Discord
  background and title/input colors across Presets/Custom layouts. Its stock AlertDialog actions are
  replaced by the reference-shaped three-action row: `colorBackgroundAccent` filled Custom/Presets,
  text-only Reset (which restores the brand default), and primary filled Select, all with mixed-case Discord styling and
  no platform elevation. Custom uses the same material insets and visible size as Select, and the folder
  name's text/hint baseline aligns with the 16dp section content line. The APK was installed successfully
  on WSA.
- Pending reply state now participates in the live chat-list model. Every diffable row owned by the
  replied-to message (body, attachments, embeds, reactions, bot components, thread preview, Activity
  cards, and forwarded-message cards) receives
  the existing persistent neutral wash and brand gutter while the reply composer is active, then reverts
  automatically when sent or cancelled. Activity/forwarded cards keep their inset geometry inside
  full-width non-measuring row overlays, so the wash covers the entire message block rather than only the
  inner card. Focused model coverage and `assembleDebug` pass; the APK was installed successfully on WSA.
- Replaced the stale inert `PrivateCallLauncher` implementation with the existing reference-shaped
  `widgets.voice.call.callAndLaunch` pipeline. DM header, friend/sidebar, and profile voice/video actions
  now share permission handling, privacy/ring decisions, RTC/selected-channel observation, and
  `WidgetCallFullscreen` launch. `WidgetHome` installs the User Sheet call destination for its lifetime.
  Text-in-Voice itself remains available, but its fullscreen onboarding/upsell is removed. Device logs
  proved the RTP send warnings occur only during UDP setup/teardown around a successful RTC `CONNECTED`
  transition. The reported disconnect coincided with WSA killing all background processes by signal 9
  and cycling its Ethernet network, not an app-issued voice disconnect. The actionable adjacent fault
  was Fresco invoking `VoiceUserView` bitmap listeners off the
  main thread, now fixed centrally with URL-stale guarding and generated binding. Focused fullscreen/
  profile/home/voice tests and `assembleDebug` pass; the APK was installed on WSA.
- Fixed FlexInput attachment navigation by replacing the library's keyboard-offset listeners with
  direct page/tab synchronization so the
  keyboard icon remains absent while Photos/Files/Camera map correctly. Public CameraKit capture was
  unusable because its required `com.jpegkit.Jpeg` artifact is unavailable from approved repositories
  and its old JitPack build produces no artifact. The camera page now uses the already-installed
  `cameraview` dependency from the original app: full-height black preview, white controls, reference
  shutter asset with outer ring, lifecycle-bound Camera2, direct `PictureResult.toFile`, permission UI,
  flash/facing controls, and system-camera fallback. `assembleDebug` passes; the APK was installed on
  WSA and A65.
- Marking a selected attachment as spoiler now survives conversion into `UploadAttachment`. Current
  Discord sends `attachments[].is_spoiler` and receives attachment flag `IS_SPOILER = 1 << 3`; new
  uploads therefore keep their normal filename while multipart payload metadata carries the boolean.
  Incoming rendering reads the flag with `SPOILER_` filename support retained for legacy messages. Because
  public FlexInput selections are plain `Attachment` instances, the action-sheet callback now replaces
  the selected aggregator item with a `SourcedAttachment` carrying the mutated flag, matching the original
  fork's attachment-owned state transfer. Its selection RecyclerView draws the missing dark cover and
  SPOILER label from that transferred state as soon as the sheet closes. Activity
  cards no longer repeat the application name already shown by the bot/message header: their primary
  header now carries the current status (`Activity ended`, participant state, or launch failure), the
  redundant subtext is hidden, and the action button uses a balanced 96x44dp size with medium text at the
  row end instead of an oversized or clipped scaled drawing. Focused activity/attachment tests and `assembleDebug` pass; installed
  on WSA and A65.
- Backported current Discord polls from Aliucord's core plugin as a native Manticord slice. The
  attachment tray exposes Poll only in DMs or with `SEND_POLLS`; XML-backed creation supports 2-10
  answers, optional emoji, seven durations, and multiselect. Chat renders XML poll cards with
  selection, live percentages, winner/self markers, result peeking, vote removal, paged voter details,
  creator-only early closure, reply previews, and type-46 closure notices that jump to the source poll.
  Current RN create/vote/voter/expire routes receive React Native headers, and typed
  `MESSAGE_POLL_VOTE_ADD/REMOVE` events reconcile counts and `me_voted` through the store dispatcher.
  The complete Aliucord `Message.patch` extension field set is typed on API/domain messages and retained
  through conversion/merge/copy without shifting existing component order. Poll and message contract
  tests plus `assembleDebug` pass; APK installation succeeds on WSA and A65. The full 1,867-test run
  reached four unrelated existing failures (instant invites, voice member hydration, MFA/invite ordering,
  forwarded-layout snapshot). `lintDebug` remains blocked by 7,035 stale-baseline errors outside this
  slice; the refreshed report contains no poll errors. Live create/vote/expire behavior remains untested.
- Poll emoji binding now reuses the existing Fresco/Drawee reaction renderer for Unicode and custom
  emoji in creation rows, chat answers, and voter-detail tabs instead of printing names such as
  `:emoji:`. Creation keeps two initial rows but permits deleting down to the Aliucord minimum of one.
  Answer counters no longer enlarge and vertically misalign the emoji/delete controls; the duration and
  multiselect rows now share the form content line and explicit 56dp geometry. Focused poll tests and
  `assembleDebug` pass. Device logs then proved the create POST was still subscribed on main and failed
  with `NetworkOnMainThreadException`; it now uses the shared REST scheduler. Drawee text spans still
  clipped despite a larger content box, so poll creation, chat answers, and detail tabs now center fixed
  24dp Fresco `SimpleDraweeView`s inside their XML rows; font metrics can no longer crop emoji artwork.
  A post-install WSA screenshot confirms the existing poll-card emoji render fully. The corrected APK is
  installed on WSA and A65. Duration selection now dismisses the reused radio-selector sheet after
  applying the chosen value; previously the unchanged open sheet obscured the update and made each row
  appear inert. Focused poll tests and `assembleDebug` pass after the fix.
- The creation row's no-emoji state now uses a normal tinted XML `ImageView`; attempting to load the
  vector picker glyph through Fresco left the 48dp action visibly empty. Fresco remains isolated to
  selected Unicode/custom emoji. Focused poll tests and `assembleDebug` pass; installed on WSA/A65.
- Poll closure type 46 embeds are metadata (`poll_question_text`, winner votes, total votes) consumed by
  the system-message renderer, not user-visible cards. `ChatListEntry.createEmbedEntries` now suppresses
  that one metadata embed type, removing the duplicate raw field card shown below the closure notice.
  A focused row-builder regression test and `assembleDebug` pass; installed on WSA/A65.
- Poll result bars now animate from zero to their percentage only when the card transitions from voting
  to results (peek or submitted vote). Initial already-voted cards do not replay animation on scroll,
  and the existing reduced-motion setting disables it. Focused poll tests and `assembleDebug` pass;
  installed on WSA/A65.
- Pending-reply propagation now includes `PollEntry`, so the poll row receives the same full-width wash
  and brand gutter as the message body, attachments, embeds, reactions, Activity, and forwarded rows.
  `WidgetChatListModelTest` covers the poll branch; focused chat-model/poll tests and `assembleDebug` pass.
- Poll review follow-up closes all four findings: domain message merges retain prior results when closure
  updates omit them and carry forward `me_voted` as Aliucord does; vote add/remove events now update live,
  pinned, and forum-first-message caches; voter details render explicit empty/error states with retry; and
  attached poll rows refresh their relative expiry text every minute while cancelling callbacks offscreen.
  The review also corrected density-aware emoji CDN sizing, rounded closure percentages like Aliucord,
  and dismisses the launcher page if channel selection disappears. Poll, pending-reply, pinned-message,
  and forum-store tests plus `assembleDebug` pass.
- Poll percentage animation now treats a newly created/recycled holder (`previous == null`) like a real
  voting-to-results transition. The old strict `previous == false` condition made identical cards animate
  or jump depending on RecyclerView reuse. Same-holder result rebinds still do not replay, and reduced
  motion still disables animation. Focused transition tests and `assembleDebug` pass; installed on WSA/A65.
- Final pre-commit review removed the one-case creation event hierarchy and made successful submission
  part of replayed view state, so rotation/backgrounding cannot lose completion and restore a permanently
  disabled form. No remaining correctness or unnecessary-complexity findings were found. Focused poll,
  message-contract, pending-reply, pinned, and forum tests plus `assembleDebug` pass.
- Backported current modal and Components V2 contracts using Aliucord PR 681, Discord Userdoccers, and
  Awoocord's ComponentsV2 renderer as references. The existing polymorphic component adapter now covers
  types 9-14, 17-19, and 21-23 with IDs and current optional fields. Modals render legacy rows plus Label,
  Text Display, string/entity selects, radio groups, checkbox groups, checkboxes, and filtered multi-file
  uploads; restore entered text, selections, checked state, selected URIs, and an in-flight picker across
  recreation; and submit the documented typed nested tree, switching to multipart only for file fields.
  Chat maps V2 components in preorder (including Section accessories) and renders XML-backed markdown,
  sections, entity selects, thumbnails, galleries, files, separators, and spoilerable accented containers
  through the existing recycler/provider pipeline. The unified metadata-client migration keeps the old
  RN accessor/metadata names as aliases rather than restoring a second client. Focused component adapter,
  mapper, and modal tests plus `assembleDebug` pass. Live modal submission and device rendering/recycling
  remain unverified.
- Installed the Components V2 build on WSA (`localhost:58526`) and launched the authenticated app through
  Gateway READY, channel navigation, and message loading with no fatal, inflation, modal, or component
  errors. Added `ComponentsV2ViewSmokeTest`; its focused connected run on Android 13 inflates all four
  current modal layouts, resolves generated bindings, and creates/measures every V2 message view through
  `ComponentInflater`. The Android-test compile also exposed two stale tests from the preceding UI refactor:
  the nameplate test now calls the current three-argument `bind`, and the camera permission test exercises
  the real final camera fragment instead of removed subclass hooks. `connectedDebugAndroidTest` passes for
  the focused V2 test. Live bot-driven modal submission remains the only unverified part of this slice.
- Fixed forwarded snapshots with Components V2 rendering as empty cards. `forwardedAccessoryRows` had
  carried only attachments, embeds, and stickers, while the forwarded adapter reduced legacy components
  to disabled text summaries; every V2 type therefore vanished. Forwarded snapshots now map components
  through the shared preorder mapper, retain the forwarded message/channel/member context required by
  markdown and media, disable all nested actions, and configure the same XML component views through
  `ComponentProvider`. The sanitized live-shaped forwarding fixture asserts that a disabled bot-component
  row is produced. Focused forwarded/component tests and `assembleDebug` pass; the corrected APK is
  installed and running on WSA with no forwarded/component inflation or fatal errors in logcat.
- WSA exposed a follow-up that fixture construction had hidden: Gson allocates component DTOs without
  running Kotlin body initializers, so deserialized `ContentComponent.components` was null and the shared
  preorder mapper threw before publishing any chat rows. The empty child list is now a computed getter,
  with a deserialization regression assertion. After reinstall, the live forwarded card renders gallery
  media, separators, Section text/thumbnail, disabled action rows/selects, and top-level text; UIAutomator
  confirms populated component bounds and logcat has no mapper, fatal, or inflation failure. Focused
  forwarded/component tests and `assembleDebug` pass.
- Replaced the initial functional Components V2 styling with current RN/Awoocord geometry. Hermes module
  7069 (`transformMessageComponents.tsx`) confirms full markdown parsing, Mosaic media, themed rounded
  containers, disabled forwarded interactions, and component-specific spoiler/media models; module 11019
  confirms Text Display is full-width measured markdown. XML views now use 8dp rounded containers with
  8dp content padding, responsive 1/2/3/4+ mosaics, large-left/stacked-right three-item layout, 16sp body
  text and 20sp H2, 4px bullet radii, button accessories wrapped below Section text, thumbnail accessories
  retained beside text, 44dp controls, one-line spoiler and ALT badges, separators, and bordered file rows
  with link-colored names/download affordances. Forwarded chrome places its label above the card like RN.
  WSA screenshots were inspected after each geometry pass; replacing `GridLayout` with exact rectangle
  placement removed all gallery constraint warnings. Focused markdown/forwarded/component tests and
  `assembleDebug` pass; the corrected APK is installed and running on WSA without component failures.
- Device follow-up corrected the remaining component interaction/geometry mismatches. Disabled selects no
  longer fade their background and muted placeholder together; only foreground content is dimmed, retaining
  readable contrast. Buttons and selects measure exactly 44dp, link-button Section accessories retain a
  160dp minimum, and forwarded top-level components receive 12dp horizontal insets. The Forwarded label is
  back inside its card. Unfurled images/videos now create typed `MessageAttachment` launch data, video proxy
  previews request WebP like Hermes module 7069, and media taps route to `WidgetMedia` rather than Chrome.
  WSA verification revealed the spoiler, then opened `WidgetMedia`; navigation breadcrumbs named
  `WidgetMedia` and ExoPlayer initialized for the video. Focused forwarded tests and `assembleDebug` pass;
  the updated APK is installed on WSA.
- Final WSA component pass keeps disabled select surfaces opaque and dims only foreground content, fixes
  buttons/selects to explicit 44dp height with 160dp link-button minima, adds 12dp forwarded top-level
  insets, and keeps the Forwarded marker inside its card. Unfurled video previews use the RN WebP proxy
  transform and all image/video taps build typed attachments for `WidgetMedia`; WSA navigation reached
  `WidgetMedia` and ExoPlayer initialized, with no Chrome route. A nested view-binding crash found during
  cold-start verification was fixed by assigning unique `media_spoiler` and `container_spoiler` IDs instead
  of sharing `spoiler` across nested layouts. Focused tests and `assembleDebug` pass; a force-stop/cold launch
  on WSA has no app fatal, inflation, mapper, cast, or null failure. Final visual tuning increases component
  buttons from 44dp to 48dp while retaining the verified select and link-button widths; the APK was rebuilt
  and installed on WSA.
- Replaced the legacy `WidgetMedia` video presentation with the current RN single-media design: black
  edge-to-edge surface, X/overflow top chrome, overlay mute and custom ExoPlayer play/time/seek/duration
  controls, and optional author avatar/name/timestamp context supplied by Components V2 messages. Media
  remains FIT_CENTER and preserves the existing ExoPlayer lifecycle, cover frame, menu actions, and chrome
  timeout. The muted and unmuted icons now share an explicit white tint. The select component's near-black
  tertiary outline was also replaced with the current modifier-accent stroke. Focused media/forwarded tests
  and `assembleDebug` pass; the APK is installed and cold-launches on WSA without media/player failures.
- Post-review simplification removed the obsolete RN REST/metadata aliases, delegated duplicate bot-entry
  construction, deleted the unused spoilable marker/current-entry alias, and reused modal field flattening
  for file collection. Focused component/modal/poll/media tests and `assembleDebug` pass; APK reinstalled.
- Began the media-channel backport from current Discord RN/Hermes and Userdoccers rather than the 126.21
  reference, which predates channel type 16. `GUILD_MEDIA` now participates in channel-list, selection,
  thread-only browser, active/archived post, first-message, unread/ack, notification, draft-composer, search,
  and icon routing. Media post cards use the RN `ForumPostGridBody` geometry: a 192dp, screen-minus-48dp
  mosaic with up to four previews, large-left three-item layout, portrait containment over a dimmed cover,
  video indicators, spoiler concealment, and overflow count. Type-15 forum cards are unchanged. Focused
  channel/forum/home tests and `assembleDebug` pass; the APK is installed and cold-launches on WSA without
  a fatal. Live type-16 visual/create-post verification remains open until the test account has an accessible
  media channel.
- Added the RN media-channel display header and sort sheet: the 32dp sort pill opens persisted Recent
  Activity/Creation Time radio options and reorders active and archived sections while retaining pinned posts.
  Its sort and chevron assets use the RN `colorInteractiveNormal` themed tint rather than fixed gray resources.
  Replaced the forum adapter's unconditional `notifyDataSetChanged()` with key/content `DiffUtil` updates, so
  first-message, typing, read-state, and timestamp emissions no longer rebuild the entire list. Focused forum
  ordering tests and `assembleDebug` pass; APK installed and cold-launched on WSA.
- Media-channel device follow-up gives the sort sheet title a standalone 16dp inset, runtime-tints the media
  toolbar icon with `colorInteractiveNormal`, and mounts the shared mention badge over the media/forum
  hamburger toolbar. Draft typing no longer POSTs to thread-only parent channels. Post creation now requires
  both `SEND_MESSAGES` and `CREATE_PUBLIC_THREADS` in the browser and again at the request boundary, preventing
  the observed parent-typing and unauthorized thread-create 403s. Focused home/forum/create tests and
  `assembleDebug` pass; APK reinstalled on WSA.
- Developer Options now exposes opt-in sanitized REST request/response logging. Default debug logs remain
  metadata-only; the toggle adds safe headers, redacted JSON or multipart structure, and response API
  `code`/`message`/validation errors without consuming the body. Authorization, cookies, super-properties,
  message content, names, nonces, filenames, file bytes, tokens, email, phone, and credentials remain
  redacted. MockWebServer redaction/403-body tests and `assembleDebug` pass; APK reinstalled on WSA.
- Fixed reconnect selection fallback: when a selected forum/media channel is temporarily absent during guild
  rehydration, `StoreChannelsSelected` may display the first available channel but no longer overwrites the
  user's requested channel intent. The existing reactive resolver returns to that channel when it reappears;
  an explicit user selection still replaces it. Focused store tests and `assembleDebug` pass; APK reinstalled.
- Backported current RN message requests, a feature absent from 126.21. DM channels now retain the modern
  `is_message_request`, request timestamp, and spam fields; pending/spam requests are removed from ordinary
  DMs and surfaced through a counted Message Requests row. The modal uses RN-style centered/X chrome,
  Requests/Spam segmented tabs, count headings, pending illustration empty states, 40dp sender rows,
  relative timestamps, three-line previews, and 32dp accept/ignore actions. Preview opens the existing chat
  surface with request/spam-specific guards instead of the normal composer. Accept/not-spam sends consent
  status `2`; ignore/report-spam deletes `/channels/{id}/recipients/@me`. Focused DTO, route, channel-list,
  predicate, and action tests plus `assembleDebug` pass; APK installed and cold-launches on WSA. Live action
  verification remains open until the test account receives a request/spam DM.
- Device follow-up replaced `MaterialButtonToggleGroup`, which crashes against the recovered overridden
  MaterialButton background, with the existing first-party `SegmentedControlContainer`/`CardSegment` control.
  The request pages now use RN's primary content surface beneath the secondary tab strip. Row actions use
  separated 32dp circular surfaces, and sticker-only/attachment/embed requests render semantic preview labels
  instead of the unloaded fallback. Focused request tests and `assembleDebug` pass; corrected APK reinstalled.
- Message-request preview navigation now replaces the normal DM hamburger with Back; toolbar or hardware Back
  clears the preview selection and reopens Message Requests. Row timestamps reserve 12dp before the fixed
  action column, preventing collisions with accept/ignore. Focused request/home tests and `assembleDebug`
  pass; APK reinstalled on WSA.
- Moved the Message Requests entry from a full DM recycler row into a 40dp private-header action immediately
  before Create Group DM, with a capped red count badge. The normal DM list remains request-free and the
  header stays visible when requests are the only private-channel content. Focused channel-list tests and
  `assembleDebug` pass; APK reinstalled on WSA.
- Replaced the header's generic pending-state icon with the mail/conversation-request glyph. Pending/spam count
  headings are now RecyclerView header rows via `ConcatAdapter`, so they scroll away with request content
  instead of remaining pinned beneath the segmented tabs. Focused request tests and `assembleDebug` pass;
  APK reinstalled on WSA.
- Message-request actions now settle on `Observable<Void>.onCompleted`, matching the endpoint's 204 response,
  so accept/ignore cannot remain loading forever. Preview waits for channel selection, then closes the home
  side panel before dismissing. The first-party segmented control is 40dp tall to prevent Requests/Spam text
  clipping. Focused request tests and `assembleDebug` pass; APK reinstalled on WSA.
- Fixed animated avatar decorations at the shared `AvatarDecorationView`: the static fallback remains visible
  while APNG loading or on failure, is hidden once animation starts, and is restored when animation clears.
  This removes the doubled static/animated frame on profiles without profile-only visibility workarounds.
  `assembleDebug` passes; live profile playback remains the device verification boundary.
- Began the current RN guild-onboarding backport after confirming the existing `WidgetChannelOnboarding` is
  only the unrelated 126-era channel-list tooltip. Added exact prompt/option/connection/response DTOs, modern
  guild features and member onboarding flags, a dispatcher-owned `StoreGuildOnboarding`, dedicated RN REST
  transport headers, GET onboarding plus POST completion and PUT response routes, required and single-select
  behavior, seen timestamps, provider/application connection cards, automatic STARTED-to-COMPLETED launch,
  default-channel handoff, and a Channels & Roles re-entry row. Focused store, transport, channel-list, NUX,
  and persistence tests plus `assembleDebug` pass; APK installed on WSA. Remaining parity work is deliberately
  open: integrate membership-screening rules inside the modal, add the Channels & Roles Browse channel-opt-in
  tab and Server Guide/home actions, verify application account-linking completion, and run a live onboarding
  guild through role/channel grants and the Gateway COMPLETED_ONBOARDING update.
- Also added the current mobile post-registration NUF requested during this slice: persisted new-user type and
  resumable steps, avatar upload/default selection and skip confirmation, notification pre-prompt/system grant,
  the existing complete contact-sync funnel, discoverability and its phone/name/background-sync branch, deferred
  invite handoff, and `nuo_transition` analytics. WSA verified notification -> contact sync -> discoverability
  routing without a fatal; focused persistence/order tests and `assembleDebug` pass.
- Onboarding device-log follow-up found and fixed three runtime defects: store-thread emissions touched views,
  authorized-app lookup performed network I/O on main, and a valid 204 response from PUT onboarding-responses
  entered a non-null DTO callback and crashed. All UI observations now hop to main, OAuth token fetch uses the REST
  scheduler, and completion/update endpoints return Retrofit `Response` so 204 is handled without a fake body.
  An independent restored emoji-picker context now falls back to Global instead of throwing an exhaustive-when
  crash. Material button elevation and pressed elevation animators are disabled globally at the shared Material
  base style. Focused onboarding/transport/emoji/channel-list tests and `assembleDebug` pass; APK reinstalled and
  a cleared-log cold launch has no app fatal, wrong-thread, network-on-main, or onboarding exception.
- Guild-onboarding visual/device follow-up added the missing options-list layout manager and replaced option emoji
  name text with the shared Fresco pipeline: Unicode stays text, custom emoji load from Discord's emoji CDN, and
  animated emoji use GIF/autoplay only when the user's animated-emoji plus reduced-motion setting allows it.
  The globally flattened Material button base now fixes both resting and pressed elevation. WSA exercised live
  onboarding GET and PUT responses including a 204; cleared logs contain no fatal, wrong-thread, main-network,
  exhaustive-when, or onboarding exception. Focused tests and `assembleDebug` pass; APK reinstalled.
- Current-RN onboarding follow-up extracted the exact embedded 60fps 512px guild-onboarding Lottie from Hermes,
  added its two-second landing sequence, reduced-motion freeze and prompt transition, and restored the explicit X
  exit that returns to the previously selected guild. Prompt options now fetch role-member counts and show the
  `+N people in common` badge only while selected; the footer resolves selected role/channel names into the
  `You will receive...` summary. WSA verified option emoji images, selected-only count, role summary, X exit, and
  a fatal-free flow. `Channels & Roles` intentionally continues to open onboarding for now; its later purpose is
  the current app's channel-visibility selector, and that Browse/opt-in behavior remains an explicit open task.
- Landing-flow parity follow-up adds the guild splash plus fade gradient behind the exact Lottie, moves/fades the
  whole landing composition upward before the delayed prompt entrance, and prevents question 1 or the connections
  page from navigating backward into the landing animation. Guild selection itself now observes incomplete
  onboarding directly: entering any `GUILD_ONBOARDING` guild without `COMPLETED_ONBOARDING` launches the modal,
  and leaving/re-entering re-arms it. WSA verified question 1 exposes only X/Next, with no Back path to the cover;
  focused home/onboarding/transport tests and `assembleDebug` pass, and the APK is installed.
- Auto-open and navigation-animation follow-up retries the incomplete-onboarding decision when guild or self-member
  hydration changes, not only when the selected guild ID changes; the authoritative gate is now simply
  `GUILD_ONBOARDING && !COMPLETED_ONBOARDING`, matching current RN. The onboarding activity opens/closes vertically,
  prompt pages slide horizontally forward/back, and question 1 remains X-only. `Channels & Roles` no longer enters
  edit mode: until Browse/visibility selection is built, it intentionally serves the same four enabled
  `in_onboarding` questions rather than exposing all admin-only prompts. Focused home/channel-list/store tests and
  `assembleDebug` pass; WSA shows a four-question flow and the updated APK is installed.
- Hydration/animation follow-up separates the current guild visit from the launched-modal guard: guild/member updates
  can now retry an early incomplete check, X suppresses immediate reopening, and leaving/re-entering re-arms the
  launch. Prompt transitions keep the outgoing page at 72% opacity and move only 28% width before the incoming page
  overlaps it, avoiding the prior blank visual gap. Focused home/onboarding/channel-list tests and `assembleDebug`
  pass; APK reinstalled.
- Transition/render follow-up replaces the visible 72%-opacity page swap with a real snapshot overlap: the old page
  and synchronously rendered new page now move concurrently, so there is no blank frame or midpoint jump. Option
  rows now match the RN card structure more closely with rounded outlined/selected surfaces, circular checks,
  left-aligned large headings, selected-only member-count pills and normalized role summaries. The guild-entry
  observer combines selected guild, guild hydration and computed self-member hydration, so an early null/stale check
  cannot suppress incomplete onboarding for the visit. Focused tests and `assembleDebug` pass; WSA confirms automatic
  onboarding activity launch and the updated four-question card UI, with no app fatal in logs.
- Final visual pass replaces the snapshot cross-fade with simultaneous fully opaque full-width page slides and lays
  out the old-page snapshot explicitly, removing the blank/jump glitch. Selected options use the lighter modifier
  surface, brand outline, circular animated check and selected-only count pill. The landing screen now uses a full
  guild-splash background/gradient, 420dp exact Lottie art, separate `Welcome to {guild}!` and
  `Let's customize your experience` typography, and linked Privacy Policy footer without reserving recycler space.
  Its dwell is 2 seconds while the shortened exit/entry keeps total handoff responsive. Focused tests and
  `assembleDebug` pass; WSA confirms automatic launch and the four-question card screen, and APK is installed.
- Post-onboarding completion pass locks page transitions against repeated taps, renders outgoing/incoming pages from
  a laid-out snapshot with synchronous option rows, and adds immediate press-scale plus selected check animations.
  Membership screening is now part of the flow: onboarding fetches the guild form, launches the real Rules/
  verification screen before submitting responses, waits for a successful activity result, then shows the explicit
  completion page and Finish CTA. The chat-input blocker and channel-list screening notice now open that same real
  screen instead of nullable/no-op hooks. Focused onboarding, member-verification and chat-input tests plus
  `assembleDebug` pass; APK reinstalled and cleared device logs show no app fatal.
- Rules/error-state follow-up makes membership screening X-only, consumes hardware Back as close, and computes the
  initial Complete CTA from actual form validity plus terms approval; the ViewModel also refuses disabled/double
  submissions. Onboarding no longer auto-retries a failed final request or renders completion early: it exposes an
  explicit error/Retry state, chains successful Rules back into response submission, then shows one completion page
  with Finish. Successful 204/200 onboarding responses suppress immediate auto-relaunch while member flags catch up.
  Both chat and channel-list “complete a few more steps” entry points now launch the real verification screen.
  Focused onboarding/member-verification/chat tests and clean `assembleDebug` pass; APK installed.
- Live Rules acceptance returned HTTP 410 only after a Gateway `GUILD_MEMBER_UPDATE`; this is the existing-member
  idempotency path, not a malformed payload. The verification ViewModel now treats 410 as success only when the live
  self-member is already non-pending, suppressing the false “failed to update settings” toast while preserving real
  410 failures. Rules remains X-only and Complete remains guarded in both UI state and the submit method. Focused
  member-verification/onboarding tests and `assembleDebug` pass; APK reinstalled.
- Join-flow follow-up routes a successful invite for a `GUILD_ONBOARDING` guild directly into onboarding, including
  the membership-screening race branch, instead of waiting for Home hydration or opening Rules first. Rules keeps
  the post-onboarding position in the chain. Its toolbar now uses a themed X rather than the black recovered clear
  vector. Focused invite/member-verification/onboarding tests and `assembleDebug` pass; APK installed.
- Simplification pass removes the unused future edit mode and PUT response route, unused row IDs/member flags,
  duplicate Home trigger, dedicated Handler and duplicate themed X vector. Guild onboarding, option rows, new-user
  onboarding, membership verification and its avatar view now use generated bindings. Page changes use a native
  `ViewAnimator` with the existing slide resources instead of manually driving overlay view animations. Rules
  success hides onboarding behind a neutral loading state, submits once, and closes directly to the channel; the
  intermediate “You’re all set” page and old-question flash are removed. Failed submits restore an explicit Retry
  state. Focused onboarding/rules/invite tests and `assembleDebug` pass; APK reinstalled.
- A65 follow-up found membership acceptance returning 403 (versus WSA's 410) while the device remained behind the
  screening gate. The Rules toolbar now installs an explicit themed-X click listener, so it always closes on Samsung/
  Nothing Android behavior instead of relying on host toolbar wiring. Idempotent 403/410 responses are accepted only
  when Gateway has already made the live self-member non-pending; genuine pending-member failures remain visible.
  Focused member-verification tests and `assembleDebug` pass; APK installed on A65.
- A65 403 follow-up corrected the verification-level gate: LOW now requires verified e-mail even when a phone is
  present, HIGHEST requires phone, and NONE/MEDIUM/HIGH do not invent an e-mail step. This prevents Complete from
  sending a form Discord must reject when the account still needs e-mail verification. The latest focused rules
  tests and `assembleDebug` pass; APK reinstalled on A65.
- Lifecycle follow-up prevents the two-second landing runnable and page-cleanup callback from touching a detached
  `WidgetGuildOnboarding`; both now require a live binding/attached fragment and become no-ops after close. Prompt
  content/actions are hidden in XML until first state render, removing the brief next-page flash. Rules reapplies X
  and hardware-back close handling on every resume. `assembleDebug` passes; APK installed on A65 and WSA.
- Rules close follow-up restores the required `action_bar_toolbar` ID. The host now adopts the Rules toolbar before
  binding the screen, so its X and back dispatcher belong to `WidgetMemberVerification` rather than the underlying
  onboarding activity. The latest `assembleDebug` passes and APK is installed on A65.
- Reopen/false-launch follow-up treats Rules cancellation as cancellation of the whole onboarding flow, so returning
  from X cannot immediately relaunch Rules from the final prompt. Home auto-launch now requires authoritative
  `STARTED_ONBOARDING` and absence of `COMPLETED_ONBOARDING`; partial members with default flags no longer flash
  onboarding for already-completed servers. Direct invite joins still launch immediately. Focused onboarding/invite
  tests and `assembleDebug` pass; APK installed on A65 and WSA.
- Re-entry follow-up resets the onboarding visit guard before filtering DM/unselected guild ID `0`; closing to DMs
  and re-entering the same incomplete guild now counts as a new visit and relaunches onboarding. `assembleDebug`
  passes; APK installed on A65 and WSA.
- Rules handoff follow-up submits onboarding responses before opening membership screening. On successful onboarding
  response, Rules launches as a separate activity and the onboarding activity finishes immediately; accepting or
  closing Rules can no longer reveal the previous question underneath. Completion handling is one-shot to prevent
  duplicate launches from concurrent store emissions. Focused onboarding/rules tests and `assembleDebug` pass; APK
  installed on A65 and WSA.
- Completion navigation now keeps onboarding visible until `StoreChannelsSelected` confirms the target guild/channel;
  only then does it launch Rules or finish. Main can no longer expose the DM list while the dispatcher resolves the
  post-onboarding channel. Focused onboarding tests and `assembleDebug` pass; APK installed on A65 (WSA offline).
- Final-prompt handoff no longer enters the page-transition host: Finish submits directly into the neutral loading
  state, so the last question cannot appear twice before Rules. Focused onboarding tests and `assembleDebug` pass;
  APK installed on A65.
- Second simplification pass removes the unused edit/PUT path, mutable one-implementation verification hooks, API
  provider injection, binding aliases, duplicate metadata implementation, unused row IDs/flags and one-use render
  wrappers. Guild/new-user onboarding, option rows, Rules and verification avatar views use generated bindings.
  Privacy uses `I18nHooks`; page changes use a native `ViewAnimator` plus existing slide resources. Focused
  onboarding/rules/invite tests and `assembleDebug` pass; APK installed on A65.
- Added a `master` push workflow that builds the debug APK with Java 17 and creates a numbered GitHub release using
  the standard release action. The first server-side run remains verification.
- Backported guild-tag display from Aliucord PR #594: the exact four-field `primary_guild` payload now survives API
  parsing and user-store merges, and one reusable `GuildTagView` renders its CDN badge/tag beside message and reply
  authors, member-list names, profile names and one-to-one DMs. Tags open the existing guild-profile sheet instead of
  duplicating Aliucord's 448-line sheet. The sheet now fetches the typed current `/guilds/{id}/profile` contract through
  the single default client instead of dismissing after a failed legacy preview. Focused model/transport tests and
  `assembleDebug` pass; three existing member-list tests still fail because they access `StoreStream` without
  initialization. Tag adoption remains outside this display slice and requires `/users/@me/clan` before enabling UI.
- Guild-tag profile HTTP 403 is now an explicit successful private state: the sheet stays open, shows "Private Server"
  and the visibility explanation, and exposes no join/view actions. Focused store/profile/transport tests and
  `assembleDebug` pass; APK installed on WSA.
- Guild-profile Join now waits for success, selects the joined guild, and restores the button with an error toast on
  failure. View Server routes known guilds directly and unknown profiles through lurking. `WidgetGuildProfileSheet`
  now uses generated binding for its static layout, with
  lookup retained only inside dynamically inflated action layouts. Focused profile tests and `assembleDebug` pass;
  APK installed on WSA.
- Hermes module 5942 and a captured desktop request confirm guild-tag profiles enter through View Server using
  `PUT /guilds/{id}/members/@me?lurker=true&session_id=...`. The sheet now exposes only that preview action; it no
  longer attempts `lurker=false` membership conversion. Focused profile/lurking/transport tests and `assembleDebug`
  pass; APK installed on WSA.
- Lurk hydration now matches RN `LurkingStore`: READY guilds with no `joined_at` repopulate the lurked-ID set, reopening
  an existing preview navigates without another PUT, and a successful lurk response immediately hydrates guild/channel
  stores instead of waiting for Gateway ordering. Empty welcome screens are suppressed and a per-ViewModel gate stops
  duplicate welcome sheets before the store's seen update propagates. Focused lurking/profile tests and `assembleDebug`
  pass; APK installed on WSA.
- Guild-profile CTA gating now follows Hermes `getGuildProfileCTAType`: View Server is exposed only for
  `DISCOVERABLE` profiles, and current-user `GUILD_MEMBER_ADD` clears lurk state so joined guilds lose the Join prompt.
  Lurk requests send RN's Base64 `{}` `X-Context-Properties` header through the shared context helper. Focused
  lurking/profile/transport tests and `assembleDebug` pass; APK installed on A65 (`188b1209`).
- Guild profile sheets no longer render or observe the emoji grid/upsell, and the obsolete Join Server button is
  removed from the action layout. The remaining upload/view controls bind through generated
  `WidgetGuildProfileActionsBinding`; View Server drops its 8dp leading gap when it is the sole action. Focused profile
  tests and `assembleDebug` pass; APK installed on A65.
- Backported RN profile Boards from Hermes modules 4559-4573 and current `UserProfileContent`/`UserProfileWidgetsBoard`:
  profile responses now retain typed widget payloads, widget application records are fetched through
  `StoreApplication`, and user sheets expose conditional Main/Board tabs. Board cards preserve server order and render
  RN's detailed favorite-game, compact rotation, and three-column played/want-to-play layouts with comments and tags;
  application widgets fetch typed user application identities and render usernames, metadata, hero media, primary stats,
  and discriminated dynamic text/number/image fields. Raw identity metadata JSON is not displayed. Tabs reset scroll to
  their anchor without an intermediate smooth-scroll frame. Main/Board are overlaid pages that track horizontal drag
  live and settle over 180ms without consuming vertical scroll; the tab indicator tracks the same fractional offset.
  Drag frames move only the indicator; selected-tab text changes once after settlement to avoid midpoint flicker.
  Indicator tracking starts at normal touch-slop and represents the Board edge as tab 1/offset 0, avoiding start/end jumps.
  Swipe-driven tab selection preserves vertical scroll position; only direct tab taps anchor to the tab strip.
  Rotation covers use fit-center and explicit row spacing;
  game-data override cover/icon hashes and legacy `cover_image` use Discord's game `app-icons` CDN contract; splash hashes
  retain app-assets. Rounded placeholder backgrounds replace square fills, and icon-only fallbacks remain inset and
  uncropped. Tags use subtle translucent outlined chips and collapse to three plus an expandable `+N`, and
  tapping a game reports its resolved name. Missing wire collections are nullable
  and normalized because Gson bypasses Kotlin defaults. Focused profile/user-sheet/transport tests pass; `installDebug`
  installed on WSA and A65. Clean-log public-profile launches returned profile HTTP 200 on both devices with no Discord or
  AndroidRuntime error. Exact app-config-driven hero/contained/stats/progress/collection layouts and Wishlist remain open.
- Simplification removed obsolete public-application game fallbacks/override DTOs, deferred Wishlist DTOs, a redundant
  recolor pass, and write-side serialization for the read-only application-identity response. `ViewPager2` was not used:
  bridging the existing bound views and dynamic page height inside `NestedScrollView` would add more code than the pager.
- Emoji autocomplete now inserts the readable `:name:` composer form, immediately rebuilds the mention map after the
  programmatic edit, and converts custom emoji to `<:name:id>` only at send time. The emoji search sheet creates its picker
  in `BOTTOM_SHEET` mode, restores the real search input/app bar, and focuses it with the keyboard after child-fragment
  commit. Known source guilds now remain visible in emoji details even when they are the current guild. Both touched sheets
  use generated bindings, with lookup retained only for the dynamically mounted child search input. Focused autocomplete/
  model/picker/guild-info tests pass; `installDebug` installed on WSA and A65.
- Chat message and reply render contexts now route emoji spans to `WidgetEmojiSheet`. Emoji pickers request unusable custom
  emoji so joined-guild/Nitro-locked sections remain visible and dim those rows while disabling selection; autocomplete
  independently filters unavailable or unusable emoji through the live `StoreEmoji` source, so non-Nitro users are not
  offered emoji they cannot send. Locked picker rows open a themed Nitro notice instead of inserting, and the Nitro/favorite
  action gap is reduced. `WidgetEmojiPicker` and both emoji sheets use generated bindings. Focused picker/store/autocomplete/
  detail tests pass; `installDebug` installed on WSA and A65.
- Emoji-picker backspace now edits the composer selection directly, deleting one Unicode code point when there is no
  selection instead of dispatching ignored key events to an unfocused input. The sole attachment caller supplies that
  callback directly; the unused fallback and bottom-sheet callback plumbing were removed. The backspace control remains
  available only in the inline composer tray; holding it repeats after Android's long-press timeout until release. Favorite
  emoji are excluded from Recent and their ordinary guild/
  Unicode sections so each favorite appears once. Focused surrogate-deletion and favorite-projection tests pass;
  the obsolete pre-`StoreEmoji` local set and source-installation seam were deleted; `installDebug` installed on WSA and A65.
- Soundboard sending is implemented as a modern extension beyond the 126.21 reference. An equal-weight in-call control beside
  camera/mic opens a rounded three-column sound tile grid. Default sounds load over REST; custom sounds from every joined
  server are requested in one Gateway opcode 31 payload and merged from `SOUNDBOARD_SOUNDS` responses before selection posts to
  `POST /channels/{channelId}/send-soundboard-sound` with `sound_id` and optional `source_guild_id`. Unavailable sounds and
  duplicate IDs are excluded. The endpoint uses the default metadata-bearing REST transport; its focused path/body/header
  contract test passes. The launcher is present in bottom-sheet and fullscreen voice controls. Gateway response bursts are
  buffered before the main-thread boundary, fixing the A65 `MissingBackpressureException`; restored emoji singleton contexts
  are matched by type, fixing the follow-on process-restart crash. Control icons use explicit themed/white tint. The picker
  groups default and custom sounds under full-width server headers and filters names locally through its search field.
  Both voice surfaces now use Discord RN's exact `SoundboardIcon` megaphone path resolved to the same strong white as neighboring controls; fullscreen uses the camera's
  runtime `buttonBackground(false)` (`white_alpha_24`) rather than the inherited black circle. Sending keeps the sheet open. Since the Android native
  bridge lacks RN/desktop's sample-playback completion callback, the Android UI mirrors Discord's web fallback with the
  existing asynchronous ExoPlayer stack: playing state begins on actual playback and clears on ended/error without blocking UI.
  ExoPlayer does not request audio focus because the voice session already owns it; this fixes the A65 click crash. Unicode sound emoji render from bundled
  Twemoji resources and custom emoji from Discord's CDN. The sheet uses the RN primary surface with secondary tiles; its RecyclerView owns the
  scrolling viewport, reuses one adapter without animations, and batches bursty guild responses to avoid remeasure stutter.
  Sections order current guild first, remaining guilds by `StoreGuildsSorted`, then defaults. Developer settings expose an
  Actual/None/Guild-only/Basic/Classic/Nitro tier selector that overrides shared client UI snapshots only and resnapshots immediately.
  The compact-sheet launcher alone uses themed interactive tint; fullscreen remains strong white and applies the exact
  inactive mic/camera background at runtime. Fullscreen primary controls horizontally scroll instead of compressing.
  Focused tests pass and `installDebug` installed on
  WSA/A65.
- Appearance now offers a local-only Material You theme. Android 12+ resolves the system accent and neutral palettes;
  older versions use the matching Discord dark palette fallback. The selection persists locally, survives incoming account
  theme snapshots, and never calls Discord's user-settings endpoint. The Appearance screen was migrated to generated binding.
  Its focused persistence/API-isolation test and `assembleDebug` pass; device visual verification remains open.
- Removed the unused Android Studio LeakCanary auto-installer after it crashed non-debuggable benchmark APKs before
  `Application.onCreate`. Memory and Gateway performance measurements use Perfetto without an auto-start provider.
- Gateway transport now uses `zstd-stream` through zstd-jni `1.5.7-12`, with reusable direct buffers, a persistent
  per-connection context, 16 MiB compressed/64 MiB decompressed guards, and trace slices. A65
  loaded the ARM64 library and completed HELLO/IDENTIFY/READY plus live dispatches without fallback. Matching 35-second cold
  traces measured Gateway CPU at 2.668 s/7.62% for zlib and 2.519 s/7.20% for zstd (5.6% lower in this single run). Zstd
  decoding itself took 87.7 ms across 124 payloads (0.707 ms average; 50.98 ms READY), while unchanged JSON parsing took
  2.329 s total and 2.034 s for READY. Peak ART heap was 284,222 KiB versus 282,309 KiB for zlib; GC count was 9 versus 10.
  Heapprofd attributed 26.1 MiB of sampled startup Java allocation to persisted Kryo reads and 3.4 MiB to Unicode emoji
  loading; the observed Gson allocation belonged primarily to that emoji load, not Gateway JSON. Focused Gateway JVM tests,
  debug assembly/install, and live ARM64 transport pass. The zstd device test is added but the whole Android-test source set
  remains blocked by unrelated stale native-engine, SendUtils, and FlexInput test APIs.
- Gateway envelopes and every typed dispatch body now stream through `JsonReader`: normal `op`/`t`-before-`d` envelopes parse
  in one pass, while unusual `d`-first envelopes retain the order-independent two-pass fallback. Unicode emoji assets use the same direct
  recursive streaming approach. Persister eager reads run on one named preload lane instead of unbounded Rx IO workers, and
  `ObservationDeck` snapshots matching observers under its monitor but invokes callbacks after releasing it. Gateway fixtures,
  reordered-envelope coverage, emoji/persistence/observer tests, debug assembly/install, and live A65 READY traffic pass.
  Against the pre-stream zstd trace, READY parsing fell from 2.034 s to 1.787 s, Gateway CPU from 2.519 s to 2.071 s, peak ART
  heap from 284,222 KiB to about 200,046 KiB, and cold activity start from 1.519 s to 0.926 s. GC count varies by run (9 before,
  6 after streaming alone, 7 after all startup changes), so only repeated medians should be treated as release evidence.
- The decoder/parser boundary now stays byte-native: zstd and zlib return UTF-8 `ByteArray` frames and `IncomingParser`
  streams them through Gson without constructing a full UTF-16 frame string. A Fastjson2 metadata-only candidate was built,
  installed, and traced on A65, then removed. The comparable 15-second cold traces were:

  | Parser / input | Events | READY parse | Ordinary parse/event | Ordinary decode/event | Gateway CPU | Peak ART heap | GCs | Cold start |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
  | Gson / string | 89 | 1,787 ms | 1.491 ms | 0.218 ms | 2.071 s | 200,046 KiB | 7 | 0.926 s |
  | Fastjson2 metadata + Gson body / bytes | 154 | 1,738 ms | 1.704 ms | 0.153 ms | 2.147 s | 207,582 KiB | 10 | 0.923 s |
  | Gson / bytes, READY tree | 30 | 1,842 ms | 1.532 ms | 0.079 ms | 1.944 s | 199,240 KiB | 6 | 0.919 s |
  | Gson / bytes, streaming READY (retained) | 27 | 1,348 ms | 1.519 ms | 0.064 ms | 1.441 s | 105,446 KiB | 5 | 1.104 s |

  Event counts varied with live traffic, so aggregate CPU and READY are directional only; ordinary per-event parsing shows no
  Fastjson2 benefit. Streaming READY reduced its parse slice by 26.8%, Gateway CPU by 25.9%, and peak ART heap by 47.1% in
  the comparable run; cold start regressed 20.1% and is treated as unrelated run variance rather than a parser win.
  `GatewayContractTest`, `ReadyPayloadHydratorTest`, `assembleDebug`, A65 install, and live READY/dispatch traffic pass.
- A fresh 20-second A65 cold-connect Perfetto capture on 2026-08-09 measured process start to HELLO at 1,428.4 ms,
  HELLO-to-READY arrival at 1,560.2 ms, and READY decode/parse at 44.6/1,139.9 ms. READY parsing was 99.79% running CPU,
  not scheduler or network wait. The initial queued dispatch burst drained 16.7 ms later, 4,189.9 ms after process start.
  Excluding READY, 24 Gateway frames averaged 0.060 ms decode, 1.080 ms parse, and 1.159 ms total client pipeline time.
- Inspection of the modern Android APK's RN bundle and `com.discord.fast_connect.FastConnectModule` DEX shows Fast Connect is
  an early native WebSocket handoff, not the prioritized-READY capability: it caches token/user/client state, opens and identifies
  before RN initialization finishes, injects native database guild versions, buffers messages, then lets the normal Gateway adopt
  the socket without re-identifying. Moving Manticord's measured +891.9 ms Gateway start to process start has a theoretical
  ceiling of about 892 ms off connection readiness; it does not reduce the 1,139.9 ms READY parse itself.
- Manticord now starts one zstd Gateway socket from persisted auth/client state before store-graph construction, sends IDENTIFY
  on WebSocket open with `is_fast_connect=true`, buffers typed events, then adopts the same socket after listener setup and cache
  restore. Focused Gateway tests and debug assembly pass; a clean A65 launch shows one physical connect/IDENTIFY, HELLO, READY,
  and continued live dispatches with no reconnect. The first A/B trace moved Gateway thread start from +891.9 to +446.2 ms,
  HELLO from +1,428.4 to +1,045.0 ms, READY arrival from +2,988.6 to +2,346.9 ms, and parsed READY completion from +4,173.2
  to +3,929.5 ms; READY parse variance rose from 1,139.9 to 1,547.0 ms in that single concurrent-startup run.
- `StartupMetrics` now records monotonic cold-boot milestones plus every Gateway frame's inter-arrival wait, zstd decode, and JSON
  parse durations to sanitized `StartupMetrics` debug logs. Developer Options exposes a dedicated Startup Metrics screen with
  live Summary and Timeline tabs and a copy-report action for repeatable on-device benchmarking.
- Startup Metrics tabs explicitly consume Discord selected/muted text and indicator colors instead of Material defaults.
- Gateway envelope parsing no longer tokenizes every normal payload twice. Focused Gateway parser/socket tests pass, and an A65
  cold run reduced READY parsing from 1,293 ms to 1,222 ms; ordinary live events remain mostly below 3 ms. Per-field timing found
  `guilds` dominates READY (177 guilds, 863-945 ms), followed by `merged_members` (50-155 ms). The persisted v1 client state restores
  message/read/settings versions but always has zero guild hashes because the current backend's READY no longer supplies the old
  hash objects. Modern Gateway caching uses `CLIENT_STATE_V2` plus `guild_versions`. The full payload contains 177 guilds,
  9,938 channels, 11,098 emojis, and 6,814 roles. Bounded parallel guild trees regressed READY parse to 1,592 ms, a handwritten
  streaming Channel adapter regressed it to 1,492 ms, and Gson 2.13.2's three-run median was 1,220 ms (effectively unchanged);
  all three candidates were removed.
- CLIENT_STATE_V2 is now reconstructed from the modern Hermes `GatewaySocket`, `GuildVersions`, `NonGuildVersions`, and READY
  hydration modules. Cache-only entity versions are unsigned `Long`s; the store persists the maximum guild metadata/role/emoji/
  sticker/channel version plus message/read/settings/API versions and sends capability 1024 with `guild_versions`. Typed partial
  READY guild envelopes merge writes/deletes into restored guild, channel, role, emoji, and sticker state. On A65, the first full
  sync populated 177 guild versions; the next cold READY reduced JSON parse from 1,712 ms to 445 ms and completed account-cache
  hydration at +2.87 s, while a following reconnect parsed READY in 180 ms. Gson is updated from 2.8.8 to 2.13.2; its standalone
  parser median was neutral, but the maintained release is retained as requested.
- V2 cache versions are unsigned and therefore stored as `Long`; the initial `Int` implementation overflowed and discarded every
  version. Connection-open now persists the rebuilt state in the same operation instead of leaving the just-cleared empty file for
  the deferred flush. Repeated process starts load 177 versions and receive `{partial=177}` READY payloads (263 ms measured parse).
  Observed 25-second connection stalls were explicit `SocketTimeoutException` retries; they did not clear or bypass the cache.
- Verbose Gateway logging no longer reparses/redacts frames above 256 KiB. It logs only a sanitized byte count for those frames;
  this removed an unmeasured 10-12 second READY queue stall while preserving full redacted logs for ordinary payloads.
- WebSocket `onOpen` now runs through the same generation guard as message/close/failure callbacks. A connection that finishes
  opening after its retry replacement is ignored instead of issuing a second IDENTIFY. The regression test simulates stale and
  replacement opens; an A65 cold launch showed one discovery, one socket open, one cached IDENTIFY, and `{partial=177}` READY
  parsing in 491 ms.
- Fast Connect v2 state now has a synchronous ~3 KiB SharedPreferences JSON mirror for the process-start read, with Kryo retained
  as the normal store cache/fallback. This avoids intermittent Kryo default-state reads without adding network or database startup
  work. Connection refresh no longer persists an empty intermediate state. The adopted Fast Connect socket also ignores the auth
  store's initial null while restoration is pending, preventing it from being closed and replaced by a zero-state second socket.
  Device verification shows one discovery/open/IDENTIFY per process; full READY streams in 1,405 ms and the following cached
  `{partial=177}` READY in 276 ms.
- The auth store's initial null emission no longer closes the adopted Fast Connect socket while token restoration is pending; real
  later nulls still close on logout. V2 guild-array detection now buffers only its first item and streams the remainder, reducing a
  full READY parse to 1,159-1,405 ms and a normal `{partial=177}` READY to 164 ms. Partial counters correctly show only transmitted
  deltas (for example roles=0, emojis=0, channels=3), while a full sync reports all 6,814 roles, 11,098 emojis, and 9,942 channels.
- V2 publication now follows the RN database invariant instead of merely persisting versions early. After StoreV2 snapshots, a
  second dispatch handler flushes the required guild metadata/emoji/sticker, guild-role, channel, read-ack, mention-count,
  notification-settings, and client-state Persisters before publishing the ~3 KiB Fast Connect mirror. The `TX2` mirror forces one
  safe full sync from earlier transaction sets. Device proof for the original transaction: full sync streamed in 1,153 ms; the next
  process used 177 committed versions, one socket, `{partial=177}`, and parsed READY in 496 ms with hydration complete at +2.88 s.
- Review follow-up separated inbound and outbound Gson again: Gateway dispatches use `InboundGatewayGsonParser`, whose modifier
  policy retains transient cache-only entity versions, while outbound JSON continues omitting them. Partial hydration now preserves
  every cached guild metadata field, including safety-alert channel, premium progress, NSFW/content levels, incidents, and official
  message color. Focused tests are added but currently cannot compile because unrelated `ActivityMessageEntryTest.kt:113` omits its
  new required `message` argument; `assembleRelease` succeeds and the updated release APK is installed.
- Library refresh compatibility: `androidx.core` remains at 1.18.0 and `zstd-jni` at 1.5.7-12 because their next releases require
  compile SDK 37, which is not installed. Upgraded dependencies now own `buttonSize`, `colorScheme`, and
  `imageAspectRatioAdjust`; duplicate recovered declarations were removed for modern AAPT compatibility.
- Fresco 3.7 is retained rather than downgraded. Inline avatars/status/custom emoji now use Vito `ImageSource`/`ImageOptions`
  spans with the default Vito provider initialized from the configured Fresco pipeline, image-pipeline experiments are configured through the 3.7 builder, circle processing wraps the now-final Fresco
  postprocessor, and custom scale types provide the required description. Release compilation, lint-vital, packaging, and device
  installation pass with the refreshed dependency set.
- CLIENT_STATE_V2 private-channel partialness is keyed to the `private_channels_version` actually sent, not to whether any guild in
  READY happens to use `data_mode=partial`. DM deltas therefore merge even for an empty/all-unavailable guild list. A versioned DM
  response with no cached private channels is rejected and retried as a full sync instead of persisting an empty sidebar. Full READY
  still replaces the list. Focused tests cover delta-only and missing-cache cases; the test task remains blocked by unrelated
  `ActivityMessageEntryTest.kt:113`. Debug assembly and A65 installation pass.
- The private-channel sidebar now re-anchors to the selected DM only when its channel ID or adapter index changes. Cached-to-READY
  reordering can no longer leave RecyclerView at an unrelated preserved position, while ordinary presence/unread rebinds do not
  trigger repeated scrolling. The selected row uses an explicit zero top offset rather than RecyclerView's ambiguous
  `scrollToPosition` placement. Updated release assembly and installation pass.
- DM cached-to-READY reordering now captures the selected row's pre-update pixel offset and restores that same offset at its new
  adapter index. Ordering changes therefore no longer teleport the sidebar, while the open channel stays anchored. Updated release
  assembly and installation pass.
- If READY arrives while the start panel is closed, the selected-DM anchor now remains pending until `PanelState.Open`; it is no
  longer consumed against an off-screen RecyclerView layout. Opening the sidebar applies the latest selected channel/index once.
  Updated release assembly and installation pass.
- `WidgetChatInput` now consumes the already-wired `StoreChat` one-shot event stream. Username clicks append the reference
  `@username#discriminator ` text at the cursor, replace-text events update the whole composer, and both focus/show the keyboard.
  Updated release assembly and installation pass.
- Autocomplete/user-ID tokens remain `<@id>` in the editable for drafts and outbound wire correctness, but the composer overlays
  them with a rounded Discord-themed `@username` replacement span. Text changes rebuild spans from the user store without altering
  the underlying token. Updated release assembly and installation pass.
- `StoreChat.appendMention` is now the canonical mention insertion path for username clicks and other callers, emitting `<@id> `
  while warming guild members. Composer mention spans are atomic during deletion: removing any part deletes the entire underlying
  token. Updated release assembly and installation pass.
- Mention pill rendering/deletion now belongs to `InputAutocomplete`, matching the reference ownership. Text changes emit/apply
  `ReplacePillSpans`; a deletion intersecting a pill emits an explicit whole-token `RemoveText` action. `WidgetChatInput` no longer
  contains deletion-range state. Updated release assembly and installation pass.
- Voice participant display now lets connected mute/self-mute/self-deaf state override a stale speaking flag, so muted users cannot
  retain the green speaking treatment. Existing speaking-over-ringing and disconnected behavior remains intact. Updated release
  assembly and installation pass.
- The global call banner restores the reference 4dp top/8dp bottom padding. Banner taps now route directly: guild voice opens
  `WidgetVoiceBottomSheet`, private calls and stages open fullscreen. The obsolete optional callback seam was removed. Updated
  release assembly and installation pass.
- The global status indicator now applies the system status-bar inset as its own top padding, matching the reference; call/stage
  colours therefore paint continuously to the physical top edge instead of starting below a separate status-bar strip. Compact
  connection banner height includes the inset. Updated release assembly and installation pass.
- Voice participant mute/deafen badges now use `VoiceUser`'s local-aware derived state, and the video-grid indicator container checks
  child visibility rather than `isShown` while its parent is hidden. Private-call `CALL_CREATE/UPDATE.voice_states` are routed into
  `StoreVoiceStates`, so users joining after the call opens become connected immediately. Speaking is suppressed by all mute/deafen
  sources. Updated release assembly and installation pass.
- Fullscreen private-call avatar tiles now opt into dedicated mute/deafen overlays; other `VoiceUserView` consumers remain unchanged
  to avoid duplicate badges. `CallModel.isMeMutedByAnySource` now uses the participant's local-aware derived mute/deafen state, so
  the bottom-sheet mute selector updates immediately. Updated release assembly and installation pass.
- Detailed boot progress is controlled by a persisted `Verbose Boot` developer setting. It defaults off with the current
  recovered white Clyde mark while caches restore; enabled mode shows every safe boot event. Authenticated navigation no longer waits for Gateway
  READY, so cached UI opens while the existing global connectivity banner reports background connection state. Focused boot
  and route-matrix tests, debug assembly, A65 install, and live HELLO/IDENTIFY/READY pass.
- Cold startup now resolves `CACHE_KEY_SELECTED_CHANNEL_IDS` immediately after the existing `STORE_CHANNELS_V26`, guild/role,
  permission, and `STORE_MESSAGES_CACHE_V38` snapshots are restored; `StoreChannelsSelected.restore()` no longer waits for READY
  to start validation. The first restored channel closes the initially open left panel, matching an explicit channel open; later
  reconnects do not repeat that action, including after saved-state restoration. `WidgetTabsHost` now mounts the previously
  missing global-status fragment, and its reference `Connecting…` text plus animated three-bar network vector shows immediately
  whenever Gateway is connecting. Its parent container is 36dp only for connectivity states, wraps content for call/stage states,
  becomes `GONE` when inactive, and overlaps the status inset by 4dp to reduce the top gap. While visible, tab content no longer
  applies a duplicate status-bar top inset. An empty relationship cache stays `Unloaded` until READY instead of flashing the
  Wumpus empty state. The authenticated shell is published only after cached users, relationships, guilds, channels,
  permissions, selection, and messages finish restoring, so its first frame is the resolved channel with panels already centered.
  Focused stream/cache/connectivity/friends tests, debug assembly, and A65 install pass.
- Added the AndroidX `:baselineprofile` generator module and app integration. The latest non-destructive A65 startup run generated
  `app/src/release/generated/baselineProfiles/{baseline-prof,startup-prof}.txt` (35,174 rules each): 6,998 rules added, 4,397
  removed, and 28,176 unchanged from the prior profile. The generator and debug assembly pass; the debug APK was restored on A65.
  D8 drops stale synthetic entries whose generated names differ between the non-minified collection build and release, while
  retaining and packaging valid rules.
- Local and GitHub Actions debug/release APKs now share the repository-owned Manticord development certificate, avoiding
  update failures caused by Android Studio and CI generating different debug keys. Continuous release builds the signed
  release APK, which packages the committed generated Baseline Profile, instead of publishing a debug APK. Local debug and
  release assembly pass; `apksigner` verifies both with SHA-256 certificate digest
  `f3dc2959fdb2686777fa0f193836700e4ae296cb1ab3089da50d001cd8a9c269`, and the release APK contains
  `assets/dexopt/baseline.prof` plus `baseline.profm`.
- Build provenance is compiled into `BuildConfig` and shown by the existing app-information settings header as
  `Dev <build id> <commit hash>`. Master pushes build and upload only a signed debug Actions artifact; manual workflow runs
  build the signed release APK with its Baseline Profile and create the numbered GitHub tag/release. Local debug/release
  assembly passes; local metadata resolves to build `local` plus the current short Git hash, and the release APK still contains
  both dexopt Baseline Profile assets.
- Screenshot detection no longer initializes or subscribes from the activity, and its developer-options toggle is removed, so
  taking a screenshot cannot open the internal bug-report form. The form implementation remains available for an explicit
  future route; debug assembly passes.
- Primary-guild tags in profile headers now consume the resolved profile pill surface and text color instead of the global app
  theme, and restore their defaults when no custom profile theme is active. This prevents the dark tag card on white profiles.
- Selecting Main or Board on a user profile no longer calls `scrollTo` on `ProfilePagerScrollView`; switching tabs now preserves
  the user's current vertical position instead of immediately jumping down to the tab strip.
- Continuous master builds use `actions/upload-artifact@v3` because this repository runs on GHES, which does not support the
  artifact service v2 required by `upload-artifact@v4`.
- CI build provenance is generated as an Android string resource rather than changing `BuildConfig` every run, so build IDs no
  longer invalidate the app's Kotlin compilation cache. The GHES workflow also caches its API 36.1 platform/build-tools download,
  and Gradle chooses a runner-appropriate worker count instead of always creating up to 12 workers. Because GHES runners are
  ephemeral and this is one large app module, the workflow persists only Gradle execution history and Kotlin/Java incremental
  compiler state between commits; it deliberately does not cache the 3.6 GB build tree or 9 GB global local-build cache.
- Wrapped profile badges now use one rounded background on their shared container rather than separate row backgrounds, keeping
  the same outer geometry while filling the space between the first and second rows.
- Profile-header usernames are constrained to one line with native end ellipsis, preventing long names from wrapping the header.
- Profile-header display names receive the full content width, with the username/pronouns immediately beneath and badges on
  their own following row instead of a right-side column that cramped long names. Badge wrapping uses the full available width,
  while the shared background remains content-sized rather than painting empty row space.
- Add-friend and pending-request profile actions now keep the resolved profile text/icon palette on custom themes; unthemed
  profiles restore their original semantic green/yellow text from the captured default state.
- The profile badge surface is translated left by its unchanged 7dp internal padding so badge icons align with the username;
  measurement, wrapping, padding, and touch-target size remain unchanged.
- User sheets resolve server bios from the profile response's `guildMemberProfile` before cached member and account fallbacks,
  and mark the About Me guild chip from that effective source rather than only checking the cached member.
- User-sheet DM navigation now selects known/created DM channels directly without capturing `Fragment.requireContext()` in the
  asynchronous creation callback, preventing detached-fragment crashes on `Main-StoreThread` after the sheet closes.
- User profiles are cached separately by `(user, guild?)` while preserving the latest-profile API for unscoped consumers. User
  sheets switch instantly to a cached matching scope, or an empty loading profile, without flashing another scope's data.
- Fenced `diff` code blocks now provide SimpleAST's addition/deletion style slots with Discord green/red text appearances;
  previously those two optional providers defaulted to empty spans, leaving diff lines monochrome.
- Common text/code attachments render a binary-safe 300-byte preview with SimpleAST highlighting and bounded 128 KiB expansion.
  The reference-shaped footer contains filename, expand, wrap, copy, and download controls at 8dp spacing. No-wrap scrolls up to
  16 viewports without opening side panels; wrap pins text to the viewport. Spoilers and recycled rows cancel or defer loading,
  and failures restore the ordinary file card.
- The chat attachment gallery now queries both image and video rows from `MediaStore.Files`; the public FlexInput
  `PhotoCursorAdapter` was hardcoded to `MediaStore.Images` despite video permission being requested. The combined adapter is
  installed both after granting permission and when permission already exists. Existing selection behavior is retained and video
  rows use native thumbnails with a persistent 28dp play overlay; the full-cell play placeholder was removed to prevent selection
  flashes while thumbnails reload. Selection payloads now update only the check indicator instead of reloading the thumbnail, and
  animate the thumbnail to the reference 0.85 selected scale. The composer preview strip draws a non-measuring video overlay, and
  the attachment action sheet reserves a square video preview with its own persistent icon. The poll page uses a dedicated poll
  vector whose fill resolves `flexInputIconColor`, matching the native attachment-tab tint. Debug assembly and A65 installation pass.
- A device ANR trace proved an `ObservationDeck` lock inversion while opening Android's DocumentsUI: the UI thread held the deck
  monitor while waiting to stale an observer, and the Store thread held that observer while connecting another stream. `disconnect`
  no longer takes the deck monitor because it only mutates the observer. A deterministic concurrency regression test was added; its
  task remains blocked by unrelated `ActivityMessageEntryTest.kt:113`. Debug assembly and A65 installation pass.
- The message context menu's Forward action now uses React Native's current 24dp `ForwardingIcon` path
  (`ArrowAngleRightUpIcon`) instead of the unrelated share glyph. The exact module and asset were recovered from the local Hermes
  bundle. Debug assembly and A65 installation pass.
- Profile-popout action labels now resolve the platform Whitney medium family explicitly while retaining the support-family fallback.
  Devices/session rows and notices use `UiKit.TextAppearance` styles rather than incorrectly passing `UiKit.TextView` widget styles
  to `android:textAppearance`, restoring Whitney medium/semibold consistently without geometry changes. Debug assembly and A65
  installation pass.
- Foreground incoming private calls now consume `StoreCallsIncoming.observeHasIncoming()` from the activity lifecycle and open the
  existing single-task, lock-screen-capable incoming-call activity. Its screen plays the looped communication-request ringtone and
  stops/releases it in `onStop`, matching the recovered `StoreNavigation` and `WidgetVoiceCallIncoming` flow. Outgoing rings now
  ignore `StoreCalls`' meaningful null placeholder while resolving a call, so they wait for the real `CALL_CREATE` and its message
  ID instead of either disappearing or sending an ineffective early request with `message_id=0`. Debug REST logs now show concrete
  encoded paths, including channel/user IDs, while still omitting query strings and redacting credentials and bodies. A focused
  null-placeholder regression test was added. Debug assembly, A65 installation, and
  `git diff --check` pass; a second approved account is still required for live ring/answer/decline verification. The focused logger
  test is blocked by unrelated `ActivityMessageEntryTest.kt:113`, which omits its required `message` argument.
- Voice messages now use the current React Native contract recovered from Hermes modules 11361/11479/11480: message flag 8192,
  `voice_message` send location, `duration_secs`, a base64 waveform capped at 256 samples, 500 ms minimum, and 1,199,500 ms
  maximum. The composer records Ogg/Opus on API 29+ (AAC/M4A fallback below API 29), sends through the existing cloud-upload and
  per-channel message queue, preserves metadata for retries, and renders ordinary/forwarded voice attachments with waveform seek,
  play/pause, duration, and the RN playback-rate cycle. User Settings now exposes a Feature Flags page whose persisted Voice
  Messages switch removes the microphone button entirely. A focused payload/waveform contract test was added but remains blocked
  before execution by unrelated `ActivityMessageEntryTest.kt:113`; debug assembly and A65 installation pass. Live backend send,
  receive, and playback remain unverified.
- The voice-message control now follows RN's gesture state machine instead of the temporary tap-only interaction: touch starts
  recording, horizontal movement past half the composer enters cancel, upward movement past 40dp enters lock, release sends,
  cancels, or locks according to that state, and locked recording exposes explicit trash/send controls. The mic and Send now swap
  inside FlexInput's existing external `send_btn_container`; the rounded `main_input_container` remains its sibling and still owns
  the emoji. The installed A65 hierarchy confirms non-overlapping bounds: input `[281,1296][901,1401]`, emoji
  `[785,1296][890,1401]`, mic `[943,1296][1048,1401]`. The mic remains visible but disabled when channel permissions deny voice
  messages, while the user Feature Flag removes it entirely.
  Debug assembly, installation, and `git diff --check` pass; gesture/live-send device verification requires a channel granting
  `SEND_VOICE_MESSAGES`.
- Voice-message availability now matches RN's explicit `channel.isPrivate()` branch: DMs and group DMs do not consult guild
  permission maps, while guild channels still require `ATTACH_FILES` and `SEND_VOICE_MESSAGES`. The recording overlay is attached
  to a non-measuring `FrameLayout` around the FlexInput `FragmentContainerView`; adding it directly to
  `FragmentContainerView` crashed after microphone permission because that class rejects non-fragment children. Debug assembly,
  A65 installation, and `git diff --check` pass after both fixes.
- The recording overlay now renders the recorder's live 100ms amplitude samples instead of a placeholder-only center. The mistaken
  `ic_trash_outline` selector (an oval target background, not a trash glyph) was removed from the hold state; real delete/send
  controls appear only after locking. The mic's touch stream disallows ancestor interception until release/cancel, preventing the
  channel drawer from opening during RN's slide-left-to-cancel gesture. Debug assembly, A65 installation, and `git diff --check`
  pass.
- MediaRecorder waveform conversion now uses a calibrated linear 0.6 gain instead of mapping ordinary near-full-scale microphone
  peaks through a -100..0dB curve that made nearly every bar tall. Playback downsamples the wire's 256 samples to RN's visible 6dp
  bar spacing, caches active/inactive theme colors instead of resolving an attribute per bar per frame, and avoids duplicate
  duration text layouts. Voice rows now use a tertiary surface with a 1dp interactive-muted outline so they remain distinct from
  the message background. Debug assembly, A65 installation, and `git diff --check` pass; the focused contract tests remain blocked
  by unrelated `ActivityMessageEntryTest.kt:113`.
- Device feedback raised the MediaRecorder waveform calibration from 0.6 to 0.85 gain after the linear correction proved too
  subdued. Voice-message cards now use a 16dp corner radius while retaining their tertiary fill and 1dp outline, matching the
  rounder RN visual treatment. Debug assembly and A65 installation pass.
- Voice-message playback now uses `AppMediaPlayer.playAudio`, which prepares ExoPlayer without constructing or attaching a hidden
  `PlayerView`/video surface for every audio row. Waveform seeking claims parent touch interception on ACTION_DOWN and seeks on
  ordinary move events, so dragging forward works immediately without a long press or opening the channel drawer. Debug assembly,
  A65 installation, and `git diff --check` pass.
- Voice playback position polling is configurable per player: video/GIF remains at 500ms, while voice rows update at 50ms so the
  played waveform advances continuously instead of jumping between half-second chunks. Debug assembly and A65 installation pass.
- The voice playback-rate label now uses a 56dp centered box with font padding disabled, keeping `0.75x` visually centered.
  Non-voice audio attachments are detected from `content_type` or common music extensions and render a compact play/pause,
  seek, and elapsed/total-time player beneath the normal filename/size/download row; they deliberately do not invent waveforms.
  The same player is available in forwarded attachment rows, uses surface-free audio playback, releases with row lifecycle, and
  claims seek-bar drags from parent panels. Debug assembly, A65 installation, and `git diff --check` pass.
- Ponytail simplification consolidated voice/music ExoPlayer ownership into one `AudioPlaybackController` and recording/playback
  bars into one `VoiceWaveformView`. Audio detection now uses `URLConnection` MIME inference, elapsed labels use Android
  `DateUtils`, and music seeks use duration milliseconds directly as the native `SeekBar` range. `WidgetChatInput` now uses
  generated root/FlexInput bindings throughout, with its five recording-overlay references collapsed into one lifecycle holder.
  Debug assembly, A65 installation, and `git diff --check` pass.
- `StoreGuilds.merge` no longer dereferences constructor-bypassed JVM nulls from partial guild payloads. Gson can allocate the
  nominally non-null API model without its Kotlin constructor; omitted `name`, `features`, and `nsfw_level` now preserve the cached
  guild values. This fixes the observed `api.name.ifEmpty` Main-StoreThread crash and prevents the next two equivalent null crashes.
  A focused partial-update regression test was added; debug assembly and A65 installation pass, while unit-test compilation remains
  blocked by unrelated `ActivityMessageEntryTest.kt:113`.
- Post-READY `GUILD_CREATE` and `BACKGROUND_SYNC` now use the CLIENT_STATE_V2 envelope hydrator; `DELETED_ENTITY_IDS` opcode 30
  reconciles channels, roles, emojis, and stickers when the gateway reports `unable_to_sync_deletes`. Channel timestamp deltas retain
  pin timestamps, guild messages alone advance `highest_last_message_id`, and DM messages advance `private_channels_version`. Full
  private-channel syncs replace and prune the cached list, while versioned deltas merge and reject a missing cache. The hydrator
  normalizes constructor-bypassed null `name`, `features`, and `nsfw_level` values before data-class copies. All 25 focused V2/store
  tests pass; debug assembly, A65 installation, and `git diff --check` pass. The full 1,952-test run has seven unrelated failures in
  frecency, lurking, voice, premium, community, and settings tests.
- AutoMod message cards now consume the current RN field contract: all 18 message keys and 9 notification keys, including keyword
  match, flagged message, timeout/quarantine, decision reason/outcome, app interaction, alert execution, raid/mention-raid, activity
  alert, and suspicious-mention context. The card adds dynamic headers/badge, highlighted matched text, role dot/icon, timestamp,
  reason/notification sections, permission-gated rounded channel chip, rule label, flagged-message jump, member validation, current-
  user quarantine profile repair, and notification-specific incident actions. Raid actions use the existing incident endpoint for
  24-hour invite/DM pauses and resolution. AutoMod feedback now defaults to RN's BUG option and emits live
  `guild_automod_feedback` analytics with embed content/decision id. A full field-extraction test was added; debug assembly and A65
  installation pass, while unit-test compilation remains blocked by unrelated `ActivityMessageEntryTest.kt:113`. Live incident
  mutation and notification-card variants remain unverified against an approved guild.
- AutoMod card author/timestamp rows now stay single-line: long usernames ellipsize while the timestamp retains a fixed
  wrap-content slot, preventing localized dates from collapsing into multiple narrow lines. The `#channel` link remains clickable
  but no longer has a persistent chip fill. Debug assembly and A65 installation pass.
- AutoMod author metadata now stays grouped on the left with a bounded username; the timestamp consumes only the remaining row
  width. The role dot is hidden unless the member actually has a colored role, eliminating the fallback gray dot. Debug assembly
  and A65 installation pass.
- The `AutoMod` badge now labels the alert header rather than the offending user. Rows explicitly show “No longer in server” when
  the author is absent from current guild membership, and stale role dots/icons are hidden for those users. Debug assembly and A65
  installation pass.
- AutoMod's Actions link now opens a dedicated permission-aware moderator sheet instead of the user profile directly. It reuses
  existing View Profile, Timeout, Kick, and Ban flows (including their confirmation dialogs) and adds Copy ID; unavailable
  moderation operations stay hidden for insufficient permissions or self-targeting. Debug assembly and A65 installation pass.
- Former-member AutoMod rows still expose View Profile, Copy ID, and Ban-by-ID when the moderator has `BAN_MEMBERS`; only Timeout
  and Kick require current guild membership. Debug assembly and A65 installation pass.
- The guild-sidebar Channels & Roles row now opens the current RN-style two-tab page instead of replaying initial onboarding.
  Customize includes every enabled prompt (including prompts excluded from first-run onboarding), preserves single-select behavior,
  shows granted role/channel summaries, and synchronizes edits through `PUT guilds/{guildId}/onboarding-responses`; queued rapid
  edits retain the newest local selection while an earlier request completes. Browse supports search, category select-all, and
  individual channel opt-in. Until channel visibility is reconstructed it deliberately lists every non-thread guild channel, as
  requested, and first use atomically enables guild opt-in while preserving all other channels as selected. Category/bulk changes
  use one guild-settings request. The new PUT transport is covered by metadata-header/path/body assertions, and customization prompt
  coverage has a store test. Debug assembly and A65 installation pass; unit-test compilation remains blocked by unrelated
  `ActivityMessageEntryTest.kt:113`, and live channel/role synchronization remains unverified.
- Channels & Roles visual structure now follows the RN reference: the sidebar icon consumes the same state tint as its label;
  Browse uses an outlined dark search field, natural-case category headings with Follow Category controls, channel-type icons,
  grouped rounded channel cards, and inset separators. Customize renders onboarding prompts as grouped rounded option cards and
  keeps each option's role/channel gain summary visible. Browse updates the toolbar title independently. Debug assembly and A65
  installation pass.
- Channels & Roles now uses the muted divider token for the search outline and the exact generated onboarding option view for
  Customize, including emoji, selected border/check treatment, and visible role/channel gain summaries. Browse channel rows share
  one secondary-surface card treatment per category. Partial response bodies from `PUT onboarding-responses` no longer replace the
  loaded prompt list, so selecting an option cannot make the Customize UI disappear. Debug assembly and A65 installation pass.
- The Browse search field no longer inherits the child style's hardcoded “Search” hint. “Search channels” remains as the floating
  label, with explicit muted hint colors and a stateful outline (divider when idle, interactive when focused). Debug assembly and
  A65 installation pass.
- Browse category channel groups now use the visibly distinct tertiary surface instead of a secondary color that blended into the
  page background on the active theme. Rounded group edges and inset separators are retained. Debug assembly and A65 installation
  pass.
- Ponytail cleanup removed unused Channels & Roles row keys/option edge state, consolidated the shared onboarding option renderer,
  and centralized onboarding response-body construction. Customize changes are now marked dirty and synchronized once when the
  screen stops, matching RN lifecycle behavior and removing the overlapping-request resync state machine. Debug assembly and A65
  installation pass.
- Customize prompt questions now wrap without ellipsis, and the Customize/Browse controls use 48dp heights instead of the cramped
  40dp variant. Debug assembly and A65 installation pass.
- Feature Flags now owns TapTap instead of the experiment override registry and adds an opt-in Fetch Uncached Users switch. Tapping
  an unknown user mention enqueues the existing `users/{id}` transport only when enabled; `StoreUser` deduplicates IDs, permits one request in
  flight, caches successful users, suppresses repeated failures, advances after errors, and ignores callbacks from an old READY/logout
  generation. Active IDs publish separately from failed IDs, so message and embed mentions immediately change from `@invalid-user`
  to `@fetching` after a tap and then re-render with the resolved name. Message rows carry those values through DiffUtil, while embed
  holders keep their resolved-name subscription for the holder lifecycle instead of discarding updates after `.take(1)`. Resolved
  embed mentions route through the same user-profile click action as message-body mentions. Focused user-store and row-map tests,
  debug assembly, A65 installation, and `git diff --check` pass.
- Ponytail cleanup removed the unused fetched-user callback, changed the serial transport from singleton sets to one user ID, folded
  repeated feature-flag preference access into two helpers, passed one `UserUpdates` value through chat row construction, and removed
  the embed-only pending-ID change token. Focused mention/store tests and debug assembly pass.

Resumable task: `app/src/main/java/com/discord/gateway/io/IncomingParser.kt` and
`app/src/main/java/com/discord/models/domain/ModelPayload.kt` | add separate Perfetto slices for envelope metadata and each
large READY field; if the metadata pass is material, replace the second full-frame scan with a tested byte-level top-level
envelope indexer, then add a custom streaming adapter only for the measured dominant guild/member/presence field. Do not
parallelize READY or replace Gson without trace evidence.

Resumable task: `app/src/main/java/com/discord/widgets/user/profile/UserProfileWidgetsView.kt` | RN
`modules/user_profile/native/UserProfileApplicationWidgetCard.tsx` and `modules/application_widget/**` | add typed
application config surfaces for exact hero/contained plus stats/progress/collection ordering, then add Wishlist.

Resumable task: `app/src/main/java/com/discord/widgets/chat/input/applauncher/**` | Android 340.12
Hermes `out.js` Apps launcher modules | log in on WSA and verify HOME/search/application/command/list
routes against live command, directory, frecency, installed-app, and shelf data.

Resumable task: `app/src/main/java/com/discord/activities/**` | Android 340.12 Hermes Activity modules
and ActivitiesV2 | use an approved account to verify authorize/authenticate, launch, join, leave,
proxy-ticket, media permissions, lifecycle cleanup, and voice-channel Activity cards.

Resumable task: `app/src/main/java/com/discord/app/AppActivity.kt` and
`app/src/main/java/com/discord/widgets/voice/call/WidgetVoiceCallIncoming.kt` | reference `StoreNavigation.java` and
`WidgetVoiceCallIncoming.java` | use two approved accounts to verify foreground incoming activity launch, ringtone stop on
answer/decline/cancel, video answer, multiple incoming calls, and background FCM behavior.

Resumable task: `app/src/main/java/com/discord/widgets/chat/input/VoiceMessageRecorder.kt` and
`app/src/main/java/com/discord/widgets/chat/list/VoiceMessageView.kt` | RN Hermes modules 11361/11479/11480/11581 | use an
approved account to verify Ogg upload acceptance, incoming waveform metadata, reply sends, retry after restart, playback/audio
focus, background teardown, API 21-28 M4A fallback acceptance, and the Feature Flags visibility toggle on device.

Resumable task: `app/src/main/java/com/discord/stores/StoreStream.kt` and
`app/src/main/java/com/discord/stores/ReadyPayloadHydrator.kt` | current RN Hermes `GatewaySocket`, `GuildVersions`, and
`NonGuildVersions` modules | with an approved account, join a guild and verify post-READY hydration, background sync, deleted-entity
reconciliation, and private-channel deletion across a reconnect.

Resumable task: `app/src/main/java/com/discord/stores/StoreUser.kt` and
`app/src/main/java/com/discord/utilities/textprocessing/node/UserMentionNode.kt` | user-provided ValidUser behavior and live
`GET users/{id}` transport | with an approved account, enable Fetch Uncached Users and verify multiple unknown mentions resolve in
serial without repeat requests for invalid IDs or cross-account callback leakage.

- Production callback/provider wiring now matches recovered 126.21 behavior for guild timeout permission recomputation, member-
  verification approval success enqueue, connected-voice guild subscription retention, channel-open/text-in-voice/guild-viewed
  analytics, and review-prompt analytics. The existing guild-subscription `events` subject remains the sole Gateway update path;
  `onSubscription` was intentionally not wired because that side effect is already compensated in `StoreGatewayConnection`.
  `assembleDebug` and 63 focused tests across `StoreStreamWiringTest`, `GuildStoreGraphTest`, `NoticeStoresTest`,
  `StoreAnalyticsTest`, and `StoreChannelsSelectedTest` pass. A wider attempted batch found 15 unrelated existing
  `GuildAdminStoresTest` fixture failures because its fixture creates `StoreUser` before `Persister.init()`.

Resumable task: `app/src/main/java/com/discord/stores/StoreStream.kt` | reference Vineflower `StoreGuildSubscriptions.kt` and
`StoreRtcConnection.kt` | on an approved live voice session, disconnect/reconnect Gateway and verify the active voice guild is
retained and replayed; unit coverage proves provider installation and retention semantics separately but cannot create native RTC.

