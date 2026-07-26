# Parity audit — authentication / navigation / notifications stores

TARGET: `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (JADX, Discord Android 126.21)

Read-only audit. Line cites are `file:line`.

---

## 1. `stores/StoreAuthentication.kt` (268 L) vs `stores/StoreAuthentication.java` (623 L)

### MISSING FIELDS / STRUCTURE

| Reference | Target | Severity |
|---|---|---|
| `extends Store` (base class supplying `getPrefs()` / `getPrefsSessionDurable()` / `init(Context)` contract) — `StoreAuthentication.java:65` | Target is a free-standing class taking `preferences` + `sessionDurablePreferences` ctor params (`StoreAuthentication.kt:30-40`). No `Store` base. | MINOR (deliberate DI inversion, but breaks the `Store.init` lifecycle contract) |
| `private final StoreStream storeStream` — `.java:82` | ABSENT. Replaced by 5 injected lambdas (`onAuthStateChanged`, `onLoginResult`, `onPreLogout`, `pushTokenProvider`, plus `timeoutScheduler`/`logoutTimeoutMillis`) `.kt:34-39` | MINOR (divergent but equivalent for the wired subset) |
| `private RestAPI` accessed via `RestAPI.Companion.getApi()` statically everywhere | Target uses a nullable late-bound `private var api: RestAPIInterface?` + `requireApi()` `.kt:52,247`. Throws `IllegalStateException` if any auth call happens before `bindApi`. | MINOR |
| `authStateSubject: SerializedSubject<AuthState, AuthState>` seeded with `BehaviorSubject.l0(null)` — `.java:211` | `.kt:45` same, seeded via `BehaviorSubject.create()` **without** the `null` seed. Reference emits an initial `null` to every subscriber; target emits nothing until `init()` runs. | **MAJOR** — `observeAuthState()`/`observeIsAuthed()`/`getAuthedToken()` never fire before init in the target, whereas the reference immediately publishes "logged out". |
| `ageGateError: BehaviorSubject<String>` — `.java:72` (non-final, reassigned) | `.kt:42` `BehaviorSubject.create<String?>()`, `val`. Equivalent. | OK |
| `_isConsentRequired: Boolean?`, `isConsentRequiredSubscription: Subscription?`, `fingerprint: String?`, `fingerprintSubject`, `oauthUriSubject` (seeded `Uri.EMPTY`), `savedLogin`, `userInitiatedAuthEventSubject`, `authStateCache` | ALL PRESENT `.kt:41-51`. | OK |
| Companion constants `CACHE_KEY_FINGERPRINT`, `CACHE_KEY_LOGIN`, `CACHE_KEY_TOKEN_RNA="token"`, `SHARED_PREFS_NAME_RNA="CacheStore"` — `.java:66-70` | PRESENT `.kt:262-267` (renamed `RNA_CACHE_NAME`/`RNA_TOKEN_KEY`, values identical). | MINOR (name drift only) |
| **Nested class `AuthRequestParams(inviteCode: StoreInviteSettings.InviteCode, guildTemplateCode: String?)`** — `.java:88-151` | **ABSENT**. No equivalent anywhere in target (`StoreInviteSettings` itself is MISSING from the target tree). | MAJOR |

### MISSING METHODS / BEHAVIOR

1. **`getShouldShowAgeGate(): Observable<StoreNavigation.AgeGate>`** — `.java:367-383`.
   Combines `users.observeMe(true)` + `getAgeGateError()` + `channelsSelected.observeSelectedChannel()`; returns `REGISTER_AGE_GATE` when the user has no birthday and the account snowflake postdates `2021-02-05T12:00:00+0000`, else `NSFW_CHANNEL_AGE_GATE` when no age-gate error, no birthday and the selected channel is NSFW.
   **Target: completely ABSENT.** `StoreNavigation.AgeGate` exists as an enum (`StoreNavigation.kt:50`) but nothing ever computes it; `AuthNavigationState.ageGate` (`StoreNavigation.kt:17`) is always `null` at every call site (`StoreStream.kt:1373,1381,1514` never pass it). → **BLOCKER**: the NSFW/register age gate can never fire.

2. **`handleAuthState$app_productionGoogleRelease(AuthState)` logout side-effects** — `.java:391-401`:
   ```java
   this.authState = authState;
   this.authStateCache.cacheAuthState(authState);
   if (authState == null) { Persister.Companion.reset(); getPrefs().edit().clear().apply(); }
   ```
   Target `handleAuthStateInternal` (`.kt:171-174`) does the first two lines only — **it never resets `Persister` nor clears the prefs store on logout**. Target's `Persister.reset()` exists (`utilities/persister/Persister.kt:204`) but is unreachable from here. → **BLOCKER**: persisted state of the previous account survives logout.

3. **`handleLoginResult` analytics + NUX** — `.java:404-415`: calls `AnalyticsTracker.INSTANCE.appFirstLogin()` and `StoreStream.Companion.getNux().setFirstOpen(true)`.
   Target `handleLoginResultInternal` (`.kt:140-145`) omits both (no `StoreNux`/`AnalyticsTracker` in target tree). → MAJOR.

4. **`login(...)` MFA short-circuit** — `.java:493-503`: `doOnNext { if (loginResult.getMfa()) return; dispatchLogin(...) }`.
   Target `.kt:83` unconditionally `.doOnNext(::handleLoginResult)`. → **BLOCKER**: an MFA-challenge login response is treated as a successful login and publishes a null-token auth state / fires the auth event.

5. **`login`/`authMFA`/`authorizeIP`/`forgotPassword`/`logout` network-action analytics** — reference wraps every call in `RestCallStateKt.logNetworkAction(..., StoreAuthentication$xxx$1.INSTANCE)` and `login` additionally builds a `TrackNetworkActionUserLogin` carrying the pending invite code (`.java:462-491`). Target has none. → MAJOR.

6. **`register(...)` signature drift** — reference `.java:538`: `(username, email, phoneToken, password, captchaPayload, consent, dateOfBirth)` — 7 args, captcha as a single `CaptchaHelper.CaptchaPayload`, and **no** `invite`/`guildTemplateCode`. Target `.kt:86-97` has 10 args including `invite` and `guildTemplateCode` and flattens captcha into two strings. Also the reference `register` does **not** dispatch a login result at all (the `$register$1` lambda body is a separate synthetic); target synthesises `ModelLoginResult(token, null, null, null)` and calls `handleLoginResult` (`.kt:115-117`). → MAJOR (behaviour not verifiable from the reference `register$1` lambda — see "JADX gaps").

7. **`login`/`forgotPassword` captcha parameter type** — reference takes `CaptchaHelper.CaptchaPayload`; target takes two loose `String?`s. `CaptchaHelper` is absent from target. → MINOR/MAJOR (API-shape divergence, callers must reimplement payload extraction).

8. **`setFingerprint(String, boolean)`** — `.java:570-610` (JADX emits raw smali but the control flow is legible):
   - persists + assigns + `fingerprintSubject.onNext(value)` **even when `value == null`**;
   - when `value != null` also `FirebaseCrashlytics.getInstance().setCustomKey("fingerprint", value)`;
   - **after** the assignment, if the *old* fingerprint was non-null and differs from the new one and the new one is non-null → `AnalyticsTracker.externalFingerprintDropped(old, new)`.
   Target `.kt:196-202` only emits when `value != null`, has no Crashlytics key, and no `externalFingerprintDropped` telemetry. Note also target reads `fingerprint` *before* the write so the "dropped" comparison is structurally impossible. → MAJOR.

9. **`init(Context)` ordering + user-initiated-auth subscription** — `.java:424-446`:
   - order is: fingerprint → cached auth state → `logout()` **or** (`this.authState = cached` **and** `publishAuthState(cached)`) → `handleAgeGateError(null)` → `setSavedLogin(prefs ?: deviceEmail)` → subscribes `userInitiatedAuthEventSubject.distinctUntilChanged().computationLatest().ui()` to `StoreAuthentication$init$1(context)` (the login/logout UI transition).
   - Target `.kt:58-68` never assigns `authState` synchronously on the restore path (it only dispatches `publishAuthState`, and `handleAuthStateInternal` runs later on the dispatcher) → `isAuthed()`/`getAuthState()` return `false`/`null` for a window after init. **MAJOR**.
   - Target has **no** `userInitiatedAuthEventSubject` subscription in `init` at all — the auth-transition UI hook is gone. **MAJOR**.
   - Target's `savedLogin` assignment `.kt:65` uses the raw field, bypassing `setSavedLogin`, so it is not written back to prefs (reference `setSavedLogin` at `.java:281-285` always persists + `AppLog.g(null, str, null)`). MINOR.
   - Reference `init` is `synchronized` **and** `@StoreThread`; target `init` is neither. MINOR.

10. **`logout()`** — `.java:508-521`: uses `StoreStream.Companion.getNotifications().getPushToken()`, `takeSingleUntilTimeout(default 10 s)`, then `appSubscribe(onNext = $logout$3, onError = $logout$4)`.
    Target `.kt:154-162` is structurally equivalent (10 s default) **but additionally calls `onPreLogout()` synchronously before the REST call**, which the reference does not do here (reference pre-logout is driven from `StoreStream` off `getPreLogoutSignal`). Also `pushTokenProvider` defaults to `{ null }` and is **never supplied** by `StoreStream.kt:126-133` → the logout call always sends a null device token, so the push registration is never revoked server-side. → MAJOR.

11. **`invalidate()`** (`.kt:164-168`) and **`loginWithToken(String)`** (`.kt:134-137`) and **`bindApi`** (`.kt:54-56`) and **`resendVerificationEmail()`** (`.kt:129-130`) are TARGET-ONLY additions. `resendVerificationEmail` has no counterpart in reference `StoreAuthentication` (it lives elsewhere in 126.21). → EXTRA / MINOR.

12. **`getDeviceEmail`** — reference `.java:179` matches `Patterns.EMAIL_ADDRESS.matcher(account.**type**)` (an upstream Discord bug); target `.kt:258` matches `account.name`. Behaviourally *better* but divergent. → MINOR (note as intentional?).

13. Naming: reference exposes `getAuthState$…()` (field) **and** `m8getAuthState$…()` (Observable) plus `getFingerPrint$…()` vs `getFingerprint$…()`. Target renames to `getAuthState()` / `observeAuthState()` / `getFingerprint()` / `observeFingerprint()`. → MINOR.

**SEVERITY (pair 1): 3 BLOCKER, 9 MAJOR, 8 MINOR.**

---

## 2. `stores/authentication/AuthStateCache.kt` (34 L) vs `stores/authentication/AuthStateCache.java` (67 L)

Only one file exists under `stores/authentication/` on both sides.

- Fields `gson`, `prefs`: PRESENT (`.kt:8-9`). Target makes `gson` an injectable ctor param with a default — EXTRA, harmless.
- `Keys.AUTH_STATE = "STORE_AUTH_STATE"`, `Keys.TOKEN = "STORE_AUTHED_TOKEN"`: PRESENT and byte-identical (`.kt:31-32` vs `.java:22,24`).
- `getCachedAuthState()`: PRESENT (`.kt:11-16`). Reference falls back to `AuthState.Companion.from(token, emptySet())` (`.java:63`); target constructs `AuthState(it, emptySet())` directly (`.kt:15`) — same result since the token is already non-null. Exception swallow parity OK (`runCatching` vs `try/catch (Exception)`).
- `cacheAuthState(AuthState?)`: PRESENT (`.kt:18-28`), identical put/remove semantics.

**No gaps. SEVERITY: none. (Target is a faithful reconstruction; the injectable `gson` is a benign EXTRA.)**

---

## 3. `stores/StoreNavigation.kt` (141 L) vs `stores/StoreNavigation.java` (585 L)

The two files are architecturally different: the reference is an **activity-lifecycle-driven imperative navigator**; the target is a **declarative route-state store**. Almost none of the reference surface is reproduced.

### PRESENT in target
- `PanelAction {NOOP, OPEN, CLOSE, UNLOCK_LEFT}` — `.kt:49` == `.java:481-486`.
- `AgeGate {REGISTER_AGE_GATE, NSFW_CHANNEL_AGE_GATE}` — `.kt:50` == `.java:473-476`.
- `navigationPanelActionSubject` / `leftPanelStateSubject` / `rightPanelStateSubject` + `getNavigationPanelAction()` / `observeLeftPanelState()` / `observeRightPanelState()` / `setLeftPanelState` / `setRightPanelState` / `setNavigationPanelAction(action, panelLayout?)` / `handleHomeTabSelected` — `.kt:52-54,58-60,102-121`. Semantics match `.java:40-42,505-508,518-528,544-584`.
  - DIVERGENCE: reference `getNavigationPanelAction()` wraps in `ObservableExtensionsKt.computationLatest(...)` (`.java:519`); target only `distinctUntilChanged()` (`.kt:58`). Panel actions are therefore delivered on the caller thread. MINOR.
  - DIVERGENCE: reference `setNavigationPanelAction` `when` only handles OPEN/CLOSE (`WhenMappings` `.java:497-498`); target adds explicit no-op branches — equivalent.

### MISSING — BLOCKER-class
1. **`init(Application)`** — `.java:530-533`: `application.registerActivityLifecycleCallbacks(new ActivityNavigationLifecycleCallbacks(stream))`. **ABSENT** from target. The whole global-navigation pipeline never starts.
2. **`ActivityNavigationLifecycleCallbacks`** (`.java:48-468`) — **ENTIRELY ABSENT**, including:
   - `getGlobalNavigationData(): Observable<ModelGlobalNavigation>` (`.java:331-357`) — a 14-way `combineLatest` over
     `nux.getNuxState()`, `StoreStream.isInitializedObservable()`, `authentication.observeAuthState()`, `channelsSelected.observeInitializedForAuthedUser()`, `callsIncoming.observeHasIncoming()`, `userRequiredAction.observeUserRequiredAction()`, `clientVersion.getClientOutdated()`, `guildInvite.getInviteCode()`, `guildTemplates.observeDynamicLinkGuildTemplateCode()`, `notices.getNotices()`, `authentication.getShouldShowAgeGate()`, `authentication.getOAuthUriObservable()`, `tabsNavigation.observeSelectedTab()`, `users.observeMe(true).map { UserUtils.getHasPhone(it) }`, then `leadingEdgeThrottle(500 ms)` + `computationLatest()` + `distinctUntilChanged()`.
   - `ModelGlobalNavigation` data class with **14 fields**: `nuxState, initialized, authState, isInitializedForAuthedUser, incomingCall, userRequiredAction, clientOutdated, inviteCode, guildTemplateCode, notice, shouldShowAgeGate, oAuthUri, navigationTab, userHasPhone` (`.java:66-312`).
   - `handleGlobalNavigationData` chain-of-responsibility over 12 handlers, first `true` wins (`.java:360-364`).
   - The 12 handlers themselves (`.java:49-61`, bodies in `$…$1.java`):
     `clientInitializedNavHandler` (swallow until initialized), `clientOutdatedNavHandler` (launch/dismiss `WidgetClientOutdated`), `authNavHandler` (auth screens ↔ home, `WidgetAuthForceChangePassword` on UPDATE_PASSWORD), `tosNavHandler` (`WidgetTos` for `TOS_UPDATE_ACKNOWLEDGMENT`/`AGREEMENTS`), `ageGateNavHandler` (`WidgetAgeVerify`, NSFW variant flag), `verificationNavHandler` (`WidgetUserAccountVerify` for `REQUIRE_VERIFIED_EMAIL`/`REQUIRE_VERIFIED_PHONE`), `callNavHandler` (`WidgetVoiceCallIncoming`), `inviteCodeNavHandler` (`WidgetGuildInvite` / contact-sync onboarding experiment `2021-04_contact_sync_android_main`), `guildTemplateCodeNavHandler` (`WidgetGuildClone`, "Dynamic Link"), `nuxStateNavHandler` (`WidgetHubEmailFlow` / `WidgetGuildTemplates` post-register funnel), `oAuthStateHandler` (`WidgetOauth2Authorize`, then resets `setOAuthUriSubject(Uri.EMPTY)`), `noticeHandler` (valid-screen matching, `markSeen`, breadcrumb, `notice.show(activity)`).
   - `onActivityCreatedOrResumed` including the **task-root/LAUNCHER/MAIN dedupe `finish()`** guard (`.java:421-466`).
3. **`launchNotice(noticeName, showAction)`** — `.java:535-542`: builds a `StoreNotices.Notice`, `markInAppSeen()`, `requestToShow(notice)`. **ABSENT** in target (no `StoreNotices` in the target tree at all).

### EXTRA / DIVERGENT (target-only inventions, no reference counterpart)
- `AuthNavigationState` data class (`.kt:12-18`), local `RequiredAction` enum (`.kt:20-27` — **shadows** and is not the reference `com.discord.models.requiredaction.RequiredAction`, which has a `NONE` member and a `fromApiString` factory), `MainRoute` sealed class (`.kt:29-35`), `IntentRouteResult` (`.kt:37-41`), `PendingIntentState` (`.kt:43-46`), `mainRouteSubject`/`pendingIntentSubject` (`.kt:55-56`), `updateAuthState` ×2 (`.kt:68-81`), `routeIntent` (`.kt:84-95`), `consumePendingIntent` (`.kt:98-100`), `reset()` (`.kt:123-129`), `Companion.resolveMainRoute` (`.kt:132-139`).
  These replace the handler chain with a 5-state router consumed by `app/AppActivity.kt:102,114,132,144`. Functionally covers only handlers #3 (`auth`), #5 (`ageGate` — but the input is never populated) and partially #4/#6 (`Required`). Handlers #1,#2,#7,#8,#9,#10,#11,#12 have **no coverage at all**.
- `RequiredAction` local enum omits `NONE` and the `REQUIRE_CAPTCHA→REQUIRE_VERIFIED_PHONE` coercion that `StoreUserRequiredActions.updateUserRequiredAction` performs (`StoreUserRequiredActions.java:25-30`). It *lists* `REQUIRE_CAPTCHA` (`.kt:26`) but nothing maps it. MAJOR.

**SEVERITY (pair 3): 3 BLOCKER (init, ActivityNavigationLifecycleCallbacks + 12 handlers + ModelGlobalNavigation, launchNotice), 2 MAJOR, 2 MINOR.**

---

## 4. `stores/StoreTabsNavigation.kt` (55 L) vs `stores/StoreTabsNavigation.java` (118 L)

### FIELDS
| Reference `.java` | Target `.kt` | Verdict |
|---|---|---|
| `dispatcher`, `storeStream`, `observationDeck` (`:18-22`) | `:9-11` | PRESENT |
| `selectedTab: NavigationTab = HOME` (`:21,115`) | `:13` | PRESENT |
| `dismissTabsDialogEventSubject: PublishSubject<Unit>` (`:18,116`) | `:15` | PRESENT |
| — | `selectedTabSnapshot` (`:14`) | EXTRA (target implements `snapshotData()`; the reference `StoreTabsNavigation` **does not override `snapshotData`** and reads `selectedTab` live inside `$observeSelectedTab$1`) |
| `ObservationDeckProvider.get()` default for `observationDeck` (`:24-26`) | ABSENT — deck is mandatory | MINOR |

### METHODS
- `getSelectedTab()` — `.java:68` returns the **live** `selectedTab`; target `.kt:17` returns `selectedTabSnapshot`, i.e. the value from the *last completed dispatch*. Callers reading immediately after `selectTab` on the store thread get a stale tab. → **MAJOR** (semantic drift).
- `observeSelectedTab()` `.java:83-87` / `.kt:19-20`: PRESENT, both `distinctUntilChanged`.
- `observeDismissTabsDialogEvent()` `.java:77-81` / `.kt:22`: PRESENT.
- `selectHomeTab(panelAction, dismissTabsDialogs=false)` `.java:89-92` / `.kt:25-30`: PRESENT. Reference `$selectHomeTab$1` calls `handleTabSelection(HOME)` → `notifyHomeTabSelected(panelAction)` → optional `dismissTabsDialogs()`; target inlines exactly that (`.kt:27-29`). OK.
- `selectTab(navigationTab, dismissTabsDialogs=false)` `.java:94-97` / `.kt:33-34`: PRESENT.
- `selectTabInternal$app_productionGoogleRelease` `.java:100-106` / `.kt:37-40`: PRESENT.
- `handlePreLogout()` `.java:73-75` / `.kt:43`: PRESENT.
- `handleTabSelection` `.java:36-39` — unconditionally assigns + `markChanged()`. Target `.kt:46-50` **early-returns when the tab is unchanged**, so `markChanged()` is skipped. Divergent (arguably a fix) but it means a repeat `selectTab(HOME, dismissTabsDialogs=true)` still dismisses dialogs — matches. → MINOR.
- `notifyHomeTabSelected(PanelAction)` `.java:43-45` is a named `@StoreThread` private; target inlines `storeStream.handleHomeTabSelected(...)` at `.kt:28`. → MINOR (no separate `handleHomeTabSelected` reachable from `selectTabInternal`).
- `dismissTabsDialogs()` `.java:29-32` private; target inlines `.onNext(Unit)` twice (`.kt:29,39`). → MINOR.

**SEVERITY (pair 4): 0 BLOCKER, 1 MAJOR (`getSelectedTab` snapshot vs live), 4 MINOR.**

---

## 5. `stores/StoreNotifications.kt` (115 L) vs `stores/StoreNotifications.java` (247 L)

> **CONTEXT — BLOCKER above everything else:** `StoreNotifications` is **never instantiated in the target**. `grep -rn "StoreNotifications" --include=*.kt` over the whole target tree returns **zero** hits outside `stores/StoreNotifications.kt` itself. It is not in `StoreStream`'s ctor block (`StoreStream.kt:126-281`), not in the `storesV2` list (`StoreStream.kt:282-312`), not in `handlePreLogoutInternal` (`StoreStream.kt:1395-1425`), and `StoreAuthentication`'s `pushTokenProvider` (`.kt:37`) is left at its `{ null }` default (`StoreStream.kt:126-133`). The store is dead code. **BLOCKER.**

### FIELDS
| Reference `.java` | Target `.kt` | Verdict |
|---|---|---|
| `clock` (`:52`) | `:12` | PRESENT |
| `stream: StoreStream` (`:57`) | replaced by lambdas `clearChannelNotifications`, `onMessageCandidate`, `registerPushToken` (`:14-16`) | DIVERGENT (MINOR) |
| `context: Context?` (`:53`) set/cleared by `configureContextSetter` | **ABSENT** | MAJOR (see #4) |
| `authToken: String?` (`:51`) | `:22` | PRESENT |
| `pushToken: String?` (`:55`) | `:23` | PRESENT |
| `pushTokenPersisted: String?` (`:56`) | `:24` | PRESENT |
| `notificationSettings: Persister<NotificationClient.SettingsV2>` key `"STORE_NOTIFICATIONS_SETTINGS_V2"` (`:54,76`) | `settingsCache: Persister<NotificationSettingsV2>` same key (`:17`, `SETTINGS_CACHE_KEY` `:111`) | PRESENT, key matches |
| `INAPP_MESSAGE_WINDOW_MS = 10000` (`:50`) | `MESSAGE_WINDOW_MS = 10_000L` (`:112`) | PRESENT |
| `extends Store` (`:48`) | `extends StoreV2` + `settings`/`settingsSnapshot` + `snapshotData()` + `ObservationDeck` (`:18-20,26-28,79-81`) | DIVERGENT (MAJOR — see #1) |

**Model field gap — `NotificationClient.SettingsV2` has 9 fields** (`utilities/fcm/NotificationClient.java:235-244`): `isAuthed, isEnabled, isEnabledInApp, isWake, isDisableBlink, isDisableSound, isDisableVibrate, token, locale, sendBlockedChannels`. Target's `models/notification/NotificationSettingsV2` has **6** — missing `token`, `locale`, `sendBlockedChannels` (and reference's `isAuthed`). Defaults match for the 6 shared (`isEnabled=true, isEnabledInApp=true`, rest `false`; ref default ctor `NotificationClient.java:428`). → **MAJOR** — the settings object the reference feeds to `NotificationClient` is not reconstructible.

### MISSING METHODS / BEHAVIOR

1. **`getSettings(): Observable<SettingsV2>`** — `.java:183-185` is `computationBuffered(notificationSettings.getObservable())`, i.e. sourced from the **Persister's own observable**. Target `observeSettings()` (`.kt:27-28`) is sourced from the ObservationDeck snapshot. Also target adds a non-reactive `getSettings(): NotificationSettingsV2` (`.kt:25`) which shadows the reference name with a different return type. → MAJOR.

2. **`configureNotificationClient()`** — `.java:97-137`. **ABSENT in target.** This is the whole point of the store:
   - `NotificationClient.INSTANCE.setRegistrationIdReceived($configureNotificationClient$1)` — the FCM token callback wiring. Without it `handleRegistrationToken` (`.kt:50`) is never called by anything.
   - `combineLatest(getSettings(), authentication.getAuthedToken(), userSettingsSystem.observeSettings(false), permissions.observePermissionsForAllChannels().leadingEdgeThrottle(1 s).map { channels where !PermissionUtils.can(SEND_MESSAGES) → HashSet<channelId> })` → `settings.copy(token=authToken, locale=systemLocale, sendBlockedChannels=nonSendable)`.
   - `combineLatest(..., d.d.a() /* isBackgrounded */)` → `computationBuffered().distinctUntilChanged()` → `appSubscribe("nsClient", $configureNotificationClient$3)`.
   → **BLOCKER**: no push token registration callback, no locale, no send-blocked-channel suppression, no backgrounded gating.

3. **`configureContextSetter(Application)` + `init(Application)`** — `.java:79-95, 216-221`. **ABSENT.** Target has no `init` at all; it has a target-only `restore()` (`.kt:31-35`) that reads the persister. → **BLOCKER** (store cannot be started).

4. **`displayPopup(Message, Channel)`** — `.java:139-148`: freshness check (`snowflake + 10 s > now`), then `NoticePopupChannel.enqueue(context, "{InAppNotif}#<channelId>", message, $displayPopup$1)`. **ABSENT.** Target delegates via an injected `onMessageCandidate(Message, Delivery)` lambda (`.kt:15,63`) that nothing supplies. → MAJOR.

5. **`handleMessageCreate(com.discord.api.message.Message)`** — `.java:204-210`. **JADX FAILED TO DECOMPILE** ("Method dump skipped, instructions count: 332"). I cannot enumerate its exact predicate set. What is recoverable: it is `@StoreThread`, takes a raw API `Message` (not a policy input), and from the imports (`Permission`, `PermissionUtils`, `ModelNotificationSettings`, `NoticePopupChannel`, `SnowflakeUtils`, `Func4`) it clearly performs the mute/permission/frequency/mention evaluation inline and calls `displayPopup`.
   Target signature is `handleMessageCreate(NotificationPolicy.Input, isForeground: Boolean)` (`.kt:59`) with the logic extracted into `stores/NotificationPolicy.kt`. **Cannot certify parity — the reference body is unavailable.** Flag as UNVERIFIABLE / MAJOR: the target requires the caller to pre-assemble a 12-field `NotificationPolicy.Input` (channel, parentChannel, guild, relationshipType, effectivePermissions, roleIds, guildSettings, selectedChannelId, isDnd), which no caller in the target does.

6. **`handleChannelSelected(long)`** — `.java:193-195` launches a coroutine on `Dispatchers.Default` (`f.H0(f.c(k0.a), …, $handleChannelSelected$1)`) which clears the notification tray for that channel. Target `.kt:56` calls an injected `clearChannelNotifications` lambda that defaults to a no-op and is never supplied. → MAJOR.

7. **`setEnabled(boolean)`** — `.java:223-226`: `notificationSettings.getAndSet(true, …)` (**synchronous**, persists immediately) + `AnalyticsTracker.INSTANCE.updateNotifications(z2)`. Target `.kt:37` routes through `dispatcher.schedule` and has no analytics. → MAJOR (analytics missing) + MINOR (async drift).

8. **`setEnabledInApp(boolean isEnabledInApp, boolean logToggle = true)`** — `.java:228-234`, **2 parameters**; only fires `AnalyticsTracker.updateNotificationsInApp` when `logToggle && oldValue != new`. Target `.kt:38` takes **1 parameter** and has no analytics. → MAJOR (signature + behavior).

9. **`setWake`** — target `.kt:39` exists; reference has **no** `setWake` (the field exists but no setter). → EXTRA / MINOR.

10. **`setNotificationLightDisabled` / `setNotificationSoundDisabled` / `setNotificationsVibrateDisabled`** — `.java:236-246` vs `.kt:40-42`. PRESENT (async vs sync drift as in #7). MINOR.

11. **`handlePreLogout()`** — `.java:212-214` resets the persister to a **default `SettingsV2`** and is **NOT** `@StoreThread`. Target `.kt:68-77` additionally nulls `authToken`/`pushTokenPersisted` and asserts the store thread. Nulling the auth token is correct-looking but divergent; reference relies on `handleAuthToken(null)` from `StoreStream`. → MINOR.

12. **`tryTokenPersist()`** — `.java:168-177`: if `authToken == null` clear `pushTokenPersisted`; else if `pushToken != pushTokenPersisted` → `RestAPI.userCreateDevice(UserDevices(pushToken))` then `$tryTokenPersist$1` sets `pushTokenPersisted`. Note the reference fires **even when `pushToken` is null**. Target `.kt:93-102` requires *both* non-null (`?: return` on `pushToken`) and delegates to the `registerPushToken` lambda (default: instantly succeed). Behaviourally close; the REST call is not wired. → MAJOR (unwired).

13. **`getPushToken()`** — `.java:179-181` `synchronized`; `.kt:26` `synchronized(this)`. PRESENT.

14. **`handleAuthToken`/`handleRegistrationToken`** — `.java:187-190, 151-154` (`handleRegistrationToken` is **private** in the reference, driven by the NotificationClient callback). Target makes it public (`.kt:50`). MINOR.

15. **`isFresh(Message)`** (`.kt:104-108`) is a target-only extraction of the reference's inline `(id >>> 22) + DISCORD_EPOCH + 10000 <= clock.currentTimeMillis()` guard (`.java:141`). Target additionally prefers `message.timestamp.dateTimeMillis` when present — divergent input. MINOR.

**SEVERITY (pair 5): 3 BLOCKER (store unwired; `configureNotificationClient`; `init`/`configureContextSetter`), 9 MAJOR, 6 MINOR.**

---

## 6. `stores/StoreThreadScheduler.kt` (30 L) vs reference

**Verdict: TARGET-ONLY INVENTION — but it is a faithful *extraction*, not a fabrication.**

There is no `stores/StoreThreadScheduler.java` in the reference (`ls stores/ | grep -i "thread\|scheduler"` returns only `StoreThread*` domain stores). The behaviour lives inline in `StoreStream`'s constructor:

```java
// StoreStream.java:868-882
Executors.newSingleThreadExecutor(new ThreadFactory() {
    public final Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, Companion.getSTORE_THREAD_NAME()); // "Main-StoreThread"
        thread.setPriority((int) 8.0d);
        Companion.setSTORE_THREAD_ID(thread.getId());
        return thread;
    }
});
… Schedulers.from(executor) … new Dispatcher(scheduler, false)
```
with `private static long STORE_THREAD_ID` (`StoreStream.java:130`) and `private static final String STORE_THREAD_NAME = "Main-StoreThread"` (`StoreStream.java:279`).

Parity check against the target object:
- `STORE_THREAD_NAME = "Main-StoreThread"` (`.kt:8`) — **matches** `StoreStream.java:279`.
- priority `8` (`.kt:19`) — **matches** `StoreStream.java:873`.
- `storeThreadId` set in the thread factory (`.kt:20`) — **matches** `StoreStream.java:874`.
- `Schedulers.from(newSingleThreadExecutor(::newThread))` (`.kt:14-16`) — **matches**.

### GAPS
- `bindCurrentThread()` (`.kt:23-25`) and `assertCurrentThread()` (`.kt:27-29`) are **target-only**. The reference's equivalent confinement check is `Dispatcher.assertCleanDispatch()` (`Dispatcher.java:19-23`), which compares against `dispatcherThread` and only **warns** (`AppLog.g.w("dirty dispatch", …)`) — and only when `assertCleanDispatches == true`, which `StoreStream.java:882` passes as **`false`**. The target's `check(...)` **throws**. → MAJOR: a confinement violation that the reference tolerates silently will crash the target. Also target `Dispatcher.kt:22` calls `bindCurrentThread()`, which is not a reference concept.
- Reference `STORE_THREAD_ID` is exposed via `StoreStream.Companion` and consumed by `@StoreThread` tooling; target hides it behind `StoreThreadScheduler.storeThreadId` with a private setter. → MINOR (relocation only).
- Reference `Dispatcher` also owns `dispatchHandlers`, `registerDispatchHandlers`/`unregisterDispatchHandlers`, `onDispatchEnded()` (`Dispatcher.java:29-45,68-75`) and logs an error when `schedule` runs with no handlers registered (`Dispatcher.java:47-52`) — verify separately against `stores/Dispatcher.kt` (out of this slice).

**SEVERITY (pair 6): 0 BLOCKER, 1 MAJOR, 2 MINOR. Not an invention — a legitimate extraction of `StoreStream$storeThreadScheduler$1`.**

---

## 7. Reference stores with no target owner

Grep basis (target tree, `--include=*.kt`): `RequiredAction`, `mfa`, `countrycode|PhoneCountryCode`, `isConnectionOpen|observeConnectionOpen`, `connectivity|NetworkMonitor`, `clientdatastate|guildHashes|IdentifyClientState`.

### 7.1 `StoreUserRequiredActions.java` (48 L) — **PARTIAL / effectively MISSING**
- Reference: `extends StoreV2`; field `userRequiredAction: RequiredAction = NONE` (`:16,21`); `updateUserRequiredAction` with the `REQUIRE_CAPTCHA → REQUIRE_VERIFIED_PHONE` coercion (`:25-30`); `handleConnectionOpen(ModelPayload)` → `RequiredAction.Companion.fromApiString(payload?.requiredAction)` + `markChanged()` (`:33-36`); `handleUserRequiredActionUpdate(UserRequiredActionUpdate)` (`:39-43`); `observeUserRequiredAction()` via ObservationDeck (`:45-47`).
- Target: **no store exists.** The only trace is the local `enum class RequiredAction` in `stores/StoreNavigation.kt:20-27` (a different, `NONE`-less enum) and `com.discord.api.auth.RequiredAction` used by `AuthState`. There is **no `handleConnectionOpen` consumer of `payload.requiredAction`**, **no `USER_REQUIRED_ACTION_UPDATE` gateway handler**, and **no `observeUserRequiredAction()`**. The `AuthNavigationState.requiredAction` field (`StoreNavigation.kt:16`) is populated only from `AuthState.isUpdatePasswordRequired` (`StoreNavigation.kt:78`) — i.e. only 1 of 6 required-action types is reachable.
- **SEVERITY: BLOCKER.** TOS acknowledgement, agreements, verified-email, verified-phone and captcha gates are all unreachable.

### 7.2 `StoreMFA.java` (136 L) — **MISSING**
- Reference: `extends StoreV2`; deps `stream`, `dispatcher`, `observationDeck`; field `state: State(MFAActivationState.NONE, isTogglingSMSBackup=false)` (`:16,110`); nested `enum MFAActivationState {PENDING_ENABLED, PENDING_DISABLED, NONE}` (`:22-26`); nested `data class State(activationState, isTogglingSMSBackup)` (`:31-101`); `handleUserUpdated(User)` resetting state when `user.id == me.id` (`:114-120`); `observeState()` (`:122-126`); `togglingSMSBackup()` (`:128-130`); `updatePendingMFAState(MFAActivationState)` (`:132-135`).
- Target: **no store, no `MFAActivationState`, no `State`.** MFA appears only as (a) `StoreAuthentication.authMFA(code, ticket)` (`StoreAuthentication.kt:120-121`), (b) `RestAPIParams.MFALogin`, (c) `MeUser.mfaEnabled`, (d) `widgets/auth/AuthFragments.kt` / `AuthUi.kt` login-MFA screens. The *settings-side* MFA enable/disable pending state machine and SMS-backup toggle are **absent**.
- **SEVERITY: MAJOR** (login-MFA works; account-settings MFA flow does not).

### 7.3 `StorePhone.java` (101 L) — **MISSING**
- Reference: `extends StoreV2`; fields `countryCodes: List<PhoneCountryCode>` (lateinit, from `assets/data/countries.json`), `defaultCountryCode = PhoneCountryCode.DEFAULT_COUNTRY_CODE`, `selectedCountryCode = MISSING_COUNTRY_CODE`, `selectedCountryCodeCache: Persister("CACHE_KEY_PHONE_COUNTRY_CODE_V2")` (`:20-32`); `loadCountryCodesFromDisk` (`:43-53`); `getCountryCode()` fallback (`:55-57`); `getCountryCodes()` (`:59-65`); **`handleConnectionOpen(ModelPayload)` → `updateDefaultCountryCode(payload.countryCode)`** (`:68-74`); `init(Context)` (`:77-83`); `snapshotData()` persisting the selection (`:87-90`); `updateDefaultCountryCode(alpha2)` / `updateSelectedCountryCode(PhoneCountryCode)` (`:92-100`).
- Target: model `models/phone/PhoneCountryCode.kt` and view `views/phone/PhoneOrEmailInputView.kt` exist; `models/domain/ModelPayload.kt` mentions a country code. **No store.** No `countries.json` loader, no persister `CACHE_KEY_PHONE_COUNTRY_CODE_V2`, no `handleConnectionOpen` consumer of `payload.countryCode`.
- **SEVERITY: MAJOR.**

### 7.4 `StoreConnectionOpen.java` (59 L) — **MISSING (partially duplicated in the wrong place)**
- Reference: `extends StoreV2`; `isConnectionOpen` via `StoreV2.MarkChangedDelegate(false)` (`:19`); `handleConnectionOpen()` → `true` (`:47-49`); `handleConnected(connected)` → sets `false` when disconnected (`:39-44`); `observeConnectionOpen(delayForUI = true)` — ObservationDeck + `.delay(100 ms)` when `delayForUI` (`:51-58`).
- Target: **no store.** A private `isConnectionOpen: Boolean` with `handleConnected`/`handleConnectionOpen` mutators lives inside `stores/StoreChannels.kt:26,62,68,71,255`, used only to gate `preloadedThreads`. There is **no `observeConnectionOpen(...)` API** and no `MarkChangedDelegate` / 100 ms UI delay.
- **SEVERITY: MAJOR.** Nothing outside `StoreChannels` can observe gateway-open state.

### 7.5 `StoreConnectivity.java` (295 L) — **MISSING ENTIRELY**
- Reference: `extends StoreV2`; deps `stream`, `dispatcher`, `clock`; constants `DELAY_NOMINAL=1000`, `DELAY_EXTENDED=10000`, `DELAY_INTERVAL=100` (`:30-34`); fields `channelMessagesLoading`, `deviceNetworkState = NetworkMonitor.State.OFFLINE`, `isReadyPayloadReceived`, `stateTriggeredDelay = 10000`, `stateTriggeredTimeMillis: Long?`, `state$delegate = MarkChangedDelegate(DelayedState(CONNECTING, 10000))` (`:35-43,177-179`); nested `enum State {ONLINE, OFFLINE, OFFLINE_AIRPLANE_MODE, CONNECTING}` (`:147-152`) and `data class DelayedState(state, delay = 1000)` (`:60-142`); `getNextStateDelayInitial` (`:182-199`), `getStateActiveMillis` (`:206-212`), `getStateDelay` (100 ms quantised, clamped ≥0) (`:214-216`), `updateConnectivityState` (`:234-251`); handlers `handleBackgrounded(boolean)` (`:254-262`), `handleConnected(boolean)` (`:265-271`), `handleConnectionReady(boolean)` (`:274-277`), private `handleDeviceNetworkStateUpdated`/`handleChannelMessagesLoading` dispatcher hops (`:219-226`); `init(NetworkMonitor)` subscribing `networkMonitor.observeState()` and `messagesLoader.observeChannelMessagesLoading()` (`:280-284`); `isConnected()` (`:286-288`); `observeState()` (`:290-294`).
- Target: grep for `connectivity|NetworkMonitor` over the whole target tree returns **zero hits**. Nothing. There is no `NetworkMonitor` utility either.
- **SEVERITY: BLOCKER.** No connecting/offline/airplane-mode banner state at all.

### 7.6 `StoreClientDataState.java` (350 L) — **MISSING / STUBBED**
- Reference: `extends StoreV2`; fields `guildHashes: Map<Long, GuildHashes> = HashMap()`, `highestLastMessageId = 0`, `readStateVersion = -1`, `userGuildSettingsVersion = -1`, `clientDataStateSnapshot` (`:34-39,345-348`); nested `data class ClientDataState(guildHashes, highestLastMessageId, readStateVersion = -1, userGuildSettingsVersion = -1)` with `toIdentifyData(): OutgoingPayload.IdentifyClientState` (`:44-185`); private `updateGuildHash(Guild)` (`:196-207`), `updateHighestLastMessageId(long)` (`:210-214`); handlers `clear()` (`:217-223`), `handleConnectionOpen(ModelPayload)` (`:241-264`), `handleGuildAdd` (`:276-280`), `handleGuildRemove` (`:283-287`), `handleChannelCreateOrUpdateOrDelete` (`:226-238`), `handleRoleAddOrRemove(guildId, GuildHashes?)` (`:315-321`), `handleEmojiUpdate(GuildEmojisUpdate)` (`:267-273`), `handleStickersUpdate(GuildStickersUpdate)` (`:324-330`), `handleMessageCreate(Message)` (`:308-312`), `handleMessageAck(ModelReadState)` (`:299-305`), `handleGuildSettingUpdated(ModelNotificationSettings)` (`:290-296`); `observeClientState()` (`:332-334`); `snapshotData()` (`:337-340`).
- Target: the DTO `gateway/io/GatewayPayloads.kt:22-24` (`IdentifyClientState(guildHashes, highestLastMessageId, readStateVersion, userGuildSettingsVersion)`) and `api/guildhash/GuildHashes.kt` exist, and `api/channel/Channel.kt` / `api/guild/Guild.kt` / `api/role/GuildRoleEvents.kt` carry guild hashes — **but there is no store**. `StoreStream.kt:442` hardcodes:
  ```kotlin
  IdentifyData(token, IdentifyClientState(emptyMap(), 0, -1, -1))
  ```
  i.e. every IDENTIFY claims a cold cache; all 11 handlers are absent.
- **SEVERITY: BLOCKER.** Guild-hash / lazy-ready resume is permanently disabled and no `observeClientState()` exists.

### 7.7 Collateral (blocks pair 3 reconstruction)
These reference stores are also **absent from the target tree** and are hard dependencies of `ModelGlobalNavigation`:
`StoreNotices`, `StoreNux`, `StoreClientVersion`, `StoreInviteSettings`, `StoreGuildTemplates`, `StoreCallsIncoming`, `StoreExperiments`, `StoreAnalytics`, `StoreGatewayConnection`. (Out of slice; recorded so pair 3 is not re-triaged in isolation.)

---

## JADX gaps — do not guess

1. `StoreNotifications.handleMessageCreate(com.discord.api.message.Message)` — `.java:204-210` — **not decompiled** ("Method dump skipped, instructions count: 332"). The exact notification-eligibility predicate chain is unrecoverable from this dump. Target parity for `NotificationPolicy.evaluate` is therefore **unverified**, not "verified good".
2. `StoreAuthentication.setFingerprint(String, boolean)` — `.java:570-610` — emitted as raw smali. The control flow above is read directly from the bytecode listing (labels `L7`/`L2c`/`L42`) and is reliable, but the exact ordering of `fingerprintSubject.onNext` vs the Crashlytics key vs the `externalFingerprintDropped` comparison is inferred from label order, not from Java source.
3. `StoreNavigation.ActivityNavigationLifecycleCallbacks.onActivityCreatedOrResumed` — `.java:421-466` — raw smali; the `isTaskRoot && hasCategory(LAUNCHER) && action==MAIN → finish()` guard is legible, the rest is a plain `appSubscribe`.
4. `StoreAuthentication.register` delegates to `StoreAuthentication$register$1` (a synthetic lambda file). I did not open it; the register request body / post-register dispatch behaviour is **unconfirmed**.
5. `StoreConnectivity.DelayedState.<init>` synthetic (`.java:129-141`) — raw smali, but the default (`1000L`) is legible.

---

## Roll-up

| Pair | BLOCKER | MAJOR | MINOR |
|---|---:|---:|---:|
| 1. StoreAuthentication | 3 | 9 | 8 |
| 2. AuthStateCache | 0 | 0 | 0 |
| 3. StoreNavigation | 3 | 2 | 2 |
| 4. StoreTabsNavigation | 0 | 1 | 4 |
| 5. StoreNotifications | 3 | 9 | 6 |
| 6. StoreThreadScheduler | 0 | 1 | 2 |
| 7. Unowned reference stores | 3 | 3 | 0 |
| **Total** | **12** | **25** | **22** |
