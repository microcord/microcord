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
- [x] Implement zlib-stream decoding.
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
- [ ] OAuth authorize, remote-auth/QR login, and the real captcha widgets are
      missing. `/ra/<fingerprint>` is parsed by `DeepLinkParser.kt:63` and dropped.
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
- [ ] `MessageResult.Success` drops the created message; `CaptchaRequired`'s
      payload shape is incompatible with the target's own `resendMessageWithCaptcha`.
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

Coverage figures are measured, from the 2026-07-26 audit in
`docs/parity-audit/`. A row is not `DONE` until its coverage is complete and its
listed defects are cleared — compiling and inflating is not parity.

| Area | Status | Measured coverage | Notes |
| --- | --- | --- | --- |
| Build/XML shell | DONE | — | Phase 0 baseline holds |
| Native media load | DONE | — | ARM64 runtime waived by user decision |
| Resources/themes | IN PROGRESS | 134/134 layout classes resolve; **34 of 518 `<enum>` and 0 of 130 `<flag>` attr children** | `cs_view_type` dead across 39 layouts |
| Custom views | IN PROGRESS | 69/76 classes present; **32 inert**, 13 behavior defects | No inflation crash |
| Dispatcher/stores | IN PROGRESS | **51 of 187** stores; `StoreStream` wires **36 of 145**; `stores/utilities` **0 of 9** | 99 missing, 37 partial |
| REST | IN PROGRESS | **42 of 264** endpoints; **17 of 107** request bodies | `missing_endpoints.txt` |
| Gateway | IN PROGRESS | **44 of 83** events; opcodes 21/21; `OutgoingPayload` 0 of 16 | 39 events discarded as raw JSON |
| Models/DTOs | IN PROGRESS | `api/**` **158 of 286**; `models/**` **59 of 149**; adapters **20 of 39** | Core DTOs verified field-exact |
| Utilities | IN PROGRESS | **57 files vs 416** classes; ~62 of 108 subpackages at 0% | `IconUtils`, textprocessing, `mg_recycler` absent |
| App framework | IN PROGRESS | `AppFragment`/`AppBottomSheet`/`AppDialog`/router **absent** | Every screen hand-rolls the base contract |
| Authentication | IN PROGRESS | `widgets/auth` **3 of 21** files | Only token login reaches the store |
| Navigation/deep links | IN PROGRESS | 9 of 12 nav handlers absent | `WidgetTos` route crashes; 4 live dead-ends |
| Guilds/channels | IN PROGRESS | channels **31 of 114**; guilds **7 of 75** | 13 of 20 row types never produced |
| Messages/uploads | IN PROGRESS | chat **40 of 277** | 33 of 46 row types unimplemented; no send pipeline in the store |
| Users/relationships | IN PROGRESS | user+friends **28 of 68** | Sheet inert; typing never sent; settings never persisted |
| Settings/servers | NOT STARTED | settings **10 of 69**; servers **9 of 108** | 4 target files are inert placeholders |
| Voice/calls/video | NOT STARTED | voice **13 of 66**; stage **2 of 31** | 8 voice stores absent; existing views are shells |
| Notifications | NOT STARTED | `StoreNotifications` exists but is **dead code** | Never constructed, never reset on logout |
| Extended guild features | NOT STARTED | scheduled events **9 of 47**; role subs **4 of 42**; hubs **0 of 54**; directories **0 of 16** | 24 widget packages at 0% |
| Billing/integrations | NOT STARTED | 0% | Cannot reuse official products |

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

Headline result: the target is roughly **11% of the reference widget surface**,
**27% of stores**, **16% of REST**, and **53% of gateway events**. Several phases
previously marked `DONE` were closed on compile/inflate evidence rather than
behavior, and have been reopened above with their specific defects.

### Cross-cutting defects

These are correctness bugs live in shipping surfaces, not missing features. The
full list with locations is in `docs/parity-audit/README.md`. The most damaging:

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

### Live dead-ends

User-reachable paths that lead nowhere: the channel sidebar **Search** button
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
| Attachment sends still bypass `MessageQueue` | Uploads get no per-channel ordering, timeout, backoff or captcha handling | `StoreStream.startUpload` must move onto `MessageRequest.Send(attachments = ...)`; the multipart-capable surface is in place |
| `CaptchaRequest.ReCaptcha` cannot run | SafetyNet fallback is unavailable | No Play Services dependency is installed; the request resolves to the reference's `captcha_failed_play_services` failure until one is added |
| `WidgetCaptchaBottomSheet` has no dismissal callback | A closed captcha challenge never completes its Observable | Add a dismissal hook on that sheet so `CaptchaHelper.tryShowCaptcha` can reproduce the reference `CHALLENGE_CLOSED` completion |
| `com.discord.restapi.utils.RetryWithDelay` is not reconstructed | The REST retry policy lives inline in `restSubscribeOn` | Policy is reproduced faithfully (IOException-only, non-GET and 401/403/404/429/503 excluded, 1s/2s/4s, three retries); move it into `RetryWithDelay` when that package is rebuilt |

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
| 2026-07-26 | Name the reconstructed obfuscated classes `AppScreenRouter` (`b.a.d.j`) and `BackPressHandlerRegistry` (`b.a.d.d`) | The project forbids carrying obfuscated names into the target; responsibilities were recoverable from call sites | Consumers reference the descriptive names; the mapping is recorded here so reference lookups stay possible |
| 2026-07-26 | `AppLog` redacts every message through a `redactSecrets` pass | The security contract forbids logging tokens, cookies, MFA codes, passwords, and message content; the reference fan-out to Crashlytics/AnalyticsTracker does not exist in the target | Masks `token`/`access_token`/`refresh_token`/`session_id`/`authorization`/`cookie`/`password`/`mfa_code`/`backup_code`/`ticket`/`secret`/`api_key`/`captcha_key`/`fingerprint`/`content` in JSON, `k=v`, `k: v`, and `Bearer\|Bot\|Basic <value>` forms |
| 2026-07-26 | Host `leadingEdgeThrottle`/`LeadingEdgeThrottle` in `com.discord.stores.utilities` instead of the reference's `com.discord.utilities.rx` | `com.discord.utilities.rx` does not exist in the target yet and is outside the owning agent's file scope | The operator must move to `com.discord.utilities.rx` (with `ObservableExtensionsKt`, `computationLatest`, `appSubscribe`) when that package is reconstructed; `com.discord.stores.utilities.StoreUtilities.kt` also carries the reference's real `snapshotDirtyKeys` |
| 2026-07-26 | Route `Logger`/`AppLog` calls in `StoreV2` and `ObservationDeck` through `android.util.Log` | `com.discord.utilities.logging.Logger` and `com.discord.app.AppLog` are not reconstructed yet | Reference log levels and gating are preserved (`LogLevel.VERBOSE` breadcrumbs, `>= ERROR` notify errors); the calls should be redirected to `Logger` once it exists. `android.util.Log` is unmocked in JVM unit tests, so the off-store-thread report path cannot be asserted directly |
| 2026-07-26 | `AppActivity` registers its `OnBackPressedCallback` eagerly in `onCreate` with no `LifecycleOwner` | A lifecycle-bound callback only enters the dispatcher stack on its owner's `ON_START`, and from API 29 the activity's `ON_START` is delivered after its fragments'. That ordering put the activity *above* every screen and is what made Back leave the app | The activity is pinned to the bottom of the stack, so screen-owned callbacks and the `BackPressHandlerRegistry` always get the first look. It is removed with the activity, so no explicit teardown is needed |
| 2026-07-26 | `WidgetTabsHost` and `WidgetHome` both register at `BackPressHandlerRegistry.DEFAULT_PRIORITY`, relying on registration order for their relative precedence | This is what the reference does: every reference `setOnBackPressed` call site uses the default priority, and the tabs-host-before-home order falls out of `HashMap` bucket order for the two `javaClass.name` keys (computed and confirmed). Inventing distinct priority constants would diverge from the reference for no behavioral gain | A parent fragment's view is bound before its child's, so registration order reproduces the reference order deterministically. Pinned by `BackPressHandlerRegistryTest` |
| 2026-07-26 | Keep `ObservationDeck.notify`'s try/catch, contrary to the decompiled Java | `dexdump classes2.dex` shows `notify` has a 7-entry catch table: the loop is guarded, the handler compares `logLevel.compareTo(ERROR)`, calls `logNotifyError` when `>= 0` and rethrows otherwise, with `logBreadcrumb("notify END")` duplicated on all three exits. JADX dropped the whole region | Observer failures still propagate at the default `LogLevel.NONE`; only a deck configured to `ERROR`/`VERBOSE` absorbs and reports them, matching the reference |

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
