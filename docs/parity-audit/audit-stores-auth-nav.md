# Authentication, navigation, and notification stores re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References: the read-only JADX tree and Vineflower tree for Discord Android
126.21. Vineflower was used for the formerly broken notification predicate.

This replaces the 2026-07-26 report. No production code was modified.

## Current result

| Surface | Classification | Open findings |
| --- | --- | ---: |
| `StoreAuthentication` | PARTIAL | 2 BLOCKER, 4 MAJOR, 5 MINOR |
| `AuthStateCache` | IMPLEMENTED | 0 |
| `StoreNavigation` | PARTIAL | 1 BLOCKER, 4 MAJOR, 2 MINOR |
| `StoreTabsNavigation` | PARTIAL | 1 MAJOR, 3 MINOR |
| `StoreNotifications` / `NotificationPolicy` | PARTIAL | 3 MAJOR, 5 MINOR |
| `StoreThreadScheduler` | PARTIAL | 2 MINOR |
| formerly unowned stores | PARTIAL | 1 BLOCKER, 1 MAJOR |
| **Total** | **PARTIAL** | **4 BLOCKER, 13 MAJOR, 17 MINOR** |

## 1. StoreAuthentication

### Resolved since 2026-07-26

- `authStateSubject` is seeded with logged-out state
  (`StoreAuthentication.kt:64-72`).
- Login now short-circuits MFA challenge responses
  (`StoreAuthentication.kt:97-113`).
- Logout auth-state handling resets `Persister`, preferences, and cookies
  (`StoreAuthentication.kt:232-245`).
- `getShouldShowAgeGate` now contains the reference register/NSFW predicates
  (`StoreAuthentication.kt:262-287`).
- MFA methods, one-time login, auth-session updates, suspension classification,
  and required-action token state are present (`StoreAuthentication.kt:152-214,
  352-373`).
- Registration uses the current backend's invite/template/optional-field contract
  rather than the retired seven-field captcha payload. This is an approved modern
  replacement and is excluded from open legacy-parity findings.

### Open blockers

- **BLOCKER:** production construction supplies neither `observeMe` nor
  `observeSelectedChannel` (`StoreStream.kt:144-151`). Both retain the
  `Observable.never()` defaults (`StoreAuthentication.kt:54-57`), so
  `getShouldShowAgeGate()` can never emit in production.
- **BLOCKER:** nothing subscribes `getShouldShowAgeGate()` into navigation. The
  only production reference is its declaration; `StoreNavigation.updateAuthState`
  never supplies `AuthNavigationState.ageGate` (`StoreNavigation.kt:81-95`). Both
  register and NSFW age-gate routes remain unreachable despite implemented UI.

### Other open findings

- **MAJOR:** `pushTokenProvider` remains its `{ null }` default in production
  (`StoreAuthentication.kt:51`; `StoreStream.kt:144-151`). Logout therefore posts
  `UserDevices(null)` (`StoreAuthentication.kt:216-223`) and cannot revoke the
  registered device token.
- **MAJOR:** `handleLoginResultInternal` does not call reference
  `AnalyticsTracker.appFirstLogin()` or `StoreNux.setFirstOpen(true)`
  (`StoreAuthentication.kt:201-207`), although both target APIs now exist.
- **MAJOR:** user-initiated auth events are published and exposed
  (`StoreAuthentication.kt:78,206,217,227,257`) but the reference init-time UI
  transition subscription is absent. Routing currently depends on the separate
  StoreStream/AppActivity state path.
- **MAJOR:** `setFingerprint` still does not emit null, set the reference
  diagnostic key, or report old-to-new fingerprint replacement
  (`StoreAuthentication.kt:294-300`).
- **MINOR:** target uses injected preferences/API/callbacks and does not extend
  `Store`; the reference owns `StoreStream` and resolves static REST.
- **MINOR:** cached auth state is published asynchronously and `authState` is not
  synchronously assigned in `init` (`StoreAuthentication.kt:85-95,325-331`). A
  direct `isAuthed()` read can briefly lag initialization.
- **MINOR:** restored `savedLogin` bypasses `setSavedLogin`, so device-derived
  email is not persisted (`StoreAuthentication.kt:92,320-323`).
- **MINOR:** target correctly matches `Account.name` for device email
  (`StoreAuthentication.kt:375-380`) rather than preserving the reference bug
  that tests `Account.type`.
- **MINOR:** logout invokes `onPreLogout` directly before REST
  (`StoreAuthentication.kt:216-223`), while the reference StoreStream subscribes
  to the pre-logout signal. Current StoreStream also performs a full reset; see
  `audit-stores-core.md`.

`stores/authentication/AuthStateCache.kt` still matches key names, fallback,
serialization, and null-removal behavior. It remains IMPLEMENTED.

## 2. StoreNavigation

### Resolved or replaced since 2026-07-26

- Required actions now come from `StoreUserRequiredActions` and are mapped into
  routes (`StoreNavigation.kt:22-37,98-113`; `StoreStream.kt:1806-1815`).
- `launchNotice` now builds and queues a real `StoreNotices.Notice`
  (`StoreNavigation.kt:140-154`).
- `AppActivity` observes `MainRoute` and hosts auth, TOS/agreement,
  verification, age-gate, and logged-in screens (`app/AppActivity.kt:216,
  517-550`).
- Deep-link/share/action state is represented by `AppIntentContract` and the
  pending-intent subject (`StoreNavigation.kt:47-56,115-132`).
- Incoming call, OAuth, invite, template, NUX, notices, and other routes have
  dedicated current target flows outside this store. The absence of the original
  monolithic lifecycle callback is therefore not itself proof those flows are
  absent.

### Open

- **BLOCKER:** age-gate state is never fed into `AuthNavigationState`; see the two
  authentication blockers above (`StoreNavigation.kt:14-20,81-95`).
- **MAJOR:** reference `ModelGlobalNavigation` and its throttled 14-source
  priority chain remain absent. Routing is distributed between StoreNavigation,
  AppActivity, AppScreenRouter and feature callbacks; no current audit proves the
  same priority when two conditions become true together.
- **MAJOR:** `StoreClientVersion` remains absent, so the client-outdated branch of
  global navigation has no owner.
- **MAJOR:** the reference activity task-root/launcher duplicate guard lives in
  `ActivityNavigationLifecycleCallbacks`; there is no equivalent in
  `StoreNavigation`. Any replacement must be verified in AppActivity before this
  can close.
- **MAJOR:** target `reset()` is a target-only imperative route reset
  (`StoreNavigation.kt:171-177`) and is called by the target-only logout reset
  paths. Observer ordering differs from the reference handler chain.
- **MINOR:** panel-action observation lacks reference `computationLatest`
  (`StoreNavigation.kt:62-70`).
- **MINOR:** target route-state classes are architectural replacements, not the
  reference `ModelGlobalNavigation` data-class contract.

## 3. StoreTabsNavigation

Fields and public operations remain present (`StoreTabsNavigation.kt:8-55`).

- **MAJOR:** `getSelectedTab()` returns `selectedTabSnapshot`, not the live field
  (`StoreTabsNavigation.kt:13-19`). A same-dispatch caller can see the previous
  tab; the reference getter returns live state.
- **MINOR:** unchanged selection returns before `markChanged`
  (`StoreTabsNavigation.kt:45-50`); the reference marks every selection.
- **MINOR:** target snapshots this store although the reference reads its live
  field from post-dispatch callbacks (`StoreTabsNavigation.kt:52-54`).
- **MINOR:** the observation deck is mandatory rather than defaulting to the
  provider. Production behavior is otherwise equivalent.

## 4. StoreNotifications and NotificationPolicy

### Resolved since 2026-07-26

- The store is constructed, included in `storesV2`, restored, fed auth/channel/
  message state, reset on logout, and exposed (`StoreStream.kt:545-555,
  847-849,1107,1387-1411,2442,2488,2547,2679,3104`).
- Context lifecycle, NotificationClient initialization, registration callback,
  four-way completed-settings fold, blocked-channel permissions, locale, auth,
  and background state now exist (`StoreNotifications.kt:65-187`).
- Push registration now uses the device endpoint and allows null registration
  after a registration callback (`StoreNotifications.kt:198-209,253-286`).
- NotificationClient's completed nine-field settings are constructed at
  `StoreNotifications.kt:164-184`; the six-field persisted toggle model is no
  longer incorrectly expected to carry token/locale/blocked channels.
- Vineflower recovers the formerly JADX-broken reference
  `handleMessageCreate` at `StoreNotifications.kt:176-244`. It checks in-app
  enablement, DND, blocked users, selected channel, channel/thread/parent/guild,
  computed members/settings, joined threads, selected voice channel and
  effective permissions before displaying a fresh popup.

### Open

- **MAJOR:** target `NotificationPolicy.Input` has no joined-thread map or
  selected voice-channel input (`NotificationPolicy.kt:14-27`), although both are
  passed to reference `NotificationTextUtils.shouldNotifyInAppPopup`
  (Vineflower reference `StoreNotifications.kt:224-237`). Thread/voice-text popup
  suppression is therefore not proven equivalent.
- **MAJOR:** target policy reimplements the reference utility predicate
  (`NotificationPolicy.kt:29-74`) rather than calling an equivalent
  `NotificationTextUtils`; no exhaustive fixture matrix compares mute,
  frequency, role/everyone suppression, parent overrides, thread membership and
  voice selection.
- **MAJOR:** toggle analytics are absent. `setEnabled` and
  `setEnabledInApp(logToggle)` accept the reference shape but ignore logging
  (`StoreNotifications.kt:189-196`).
- **MINOR:** target persists toggle changes on the dispatcher/ObservationDeck;
  reference `Persister.getAndSet` is synchronous and exposes the Persister
  observable.
- **MINOR:** `setWake` is target-only; reference has the field but no setter.
- **MINOR:** target context tracks every `Activity`, while reference tracks
  `AppActivity` created/resumed. A non-AppActivity could become popup context.
- **MINOR:** freshness prefers explicit timestamp before snowflake id
  (`StoreNotifications.kt:266-270`); reference uses only the snowflake.
- **MINOR:** pre-logout clears local token fields in addition to resetting
  settings (`StoreNotifications.kt:228-238`); reference token clearing arrives
  through auth-state fan-out.

## 5. StoreThreadScheduler

The dedicated thread name, priority, id capture and scheduler match the inline
reference thread factory (`StoreThreadScheduler.kt:8-22`). The historical
throwing assertion is resolved: `assertCurrentThread` now logs and continues
(`:28-42`).

- **MINOR:** `bindCurrentThread()` is target-only and called per dispatch, which
  lets alternate schedulers redefine store-thread identity (`:24-26`;
  `Dispatcher.kt:20-24`).
- **MINOR:** thread identity is relocated from StoreStream companion state.

## 6. Formerly unowned reference stores

| Store | Current status | Evidence / remaining gap |
| --- | --- | --- |
| `StoreUserRequiredActions` | IMPLEMENTED | Complete state, captcha coercion, READY/update handlers and observation at `StoreUserRequiredActions.kt:21-53`; wired at `StoreStream.kt:557,1806-1815,2738`. |
| `StoreMFA` | IMPLEMENTED | Reference state/enum/handlers at `StoreMFA.kt:18-50`; wired at `StoreStream.kt:558,1671-1675`. |
| `StorePhone` | PARTIAL | Reference state and methods exist (`StorePhone.kt:22-84`) and READY is routed (`StoreStream.kt:2741`), but production never calls `phone.init(context)`. **BLOCKER:** `countryCodes` stays empty, so READY/default and user selection cannot resolve. |
| `StoreConnectionOpen` | IMPLEMENTED | Constructed, in `storesV2`, connected/READY routed and exposed (`StoreStream.kt:473,800-802,1858,2579,2774,3047`). |
| `StoreConnectivity` | IMPLEMENTED | Constructed, initialized from `NetworkMonitor`, background/connected/ready routed and exposed (`StoreStream.kt:474,939,1254-1258,1857,1866,3048`). |
| `StoreClientDataState` | IMPLEMENTED | Constructed, gateway mutations/READY/error/reset and identify-state provider are wired (`StoreStream.kt:472,1524-1531,1544-1563,1706-1733,2696-2700,2735`). |
| `StoreClientVersion` | MISSING | **MAJOR:** no current target class; client-outdated navigation remains unavailable. |

`widgets/phone/PhoneCountryCodes.kt:10-27` still documents StorePhone as unwired,
which is stale in one direction: the store is now constructed/published, but the
missing production `init` means its fallback remains operationally relevant.

## Reference caveats

- JADX could not decompile `StoreNotifications.handleMessageCreate`; Vineflower
  recovers the body at reference `StoreNotifications.kt:176-244`, so the old
  "unverifiable" classification is retired.
- `StoreAuthentication.setFingerprint` is readable in Vineflower/bytecode; this
  report does not infer Crashlytics/provider behavior beyond the recovered calls.
- The exact `StoreNavigation` priority behavior is recoverable from its ordered
  handler chain, but target equivalence requires a cross-file AppActivity/router
  audit and is not claimed here.

## Verification gaps

- Documentation-only re-audit; no Gradle, device, or live-backend check ran.
- No focused production-wiring test proves age-gate emission.
- No test proves `StorePhone.countryCodes` is populated by application startup.
- Notification policy lacks a reference-derived joined-thread/voice-channel
  fixture matrix.
