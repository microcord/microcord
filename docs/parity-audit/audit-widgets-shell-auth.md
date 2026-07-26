# Parity Audit — App Shell, Auth, Home, Tabs, Share, Captcha, BotUIKit

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`
Reference: `C:\Users\manti\StudioProjects\discord-jadx` (Discord Android 126.21, JADX)
Date: 2026-07-26. Read-only audit. Contract: PLAN.md — every first-party custom View,
Fragment, dialog, bottom sheet, adapter row, and interaction must be recreated with
complete behavior. PLAN Phase 5 (Authentication and Navigation) is marked **DONE**
(PLAN.md:466-479), so any missing auth/nav behavior is a **regression against a closed phase**.

---

## 0. Headline

**Phase 5 is not done.** Three findings, each independently sufficient to reopen it:

1. **All password/email/phone/MFA authentication is dead code in production.**
   `AppActivity.submit()` (`app/src/main/java/com/discord/app/AppActivity.kt:188-204`)
   handles exactly one action — `AuthAction.TokenLogin`. Every other action falls to
   `AppActivity.kt:202` and returns `AuthResult.Failure(message = "Authentication is not initialized.")`.
   The escape hatch `AppActivity.authSubmitter` (`AppActivity.kt:215`) is assigned **only from
   instrumentation tests** (`app/src/androidTest/java/com/discord/app/MainHostNavigationTest.kt:40`).
   `StoreAuthentication.login/register/authMFA/forgotPassword/resendVerificationEmail`
   (`app/src/main/java/com/discord/stores/StoreAuthentication.kt:70,86,120,126,129`) have **zero
   production callers** — only `loginWithToken` is called (`AppActivity.kt:196`).
   Net effect: login, register, MFA, phone verify, email verify, age verify, forgot password,
   reset password, undelete, force-change-password all render but cannot succeed.

2. **The four base classes every reference screen depends on do not exist.**
   `AppFragment`, `AppBottomSheet`, `AppDialog`, `AppLog`/`AppLogger`/`LoggingConfig`,
   `AppPermissionsRequests`, `AppTransitionActivity` — none present anywhere in the target
   (verified: `find … -name "AppFragment*" -o -name "AppBottomSheet*" -o -name "AppDialog*" -o
   -name "AppLog*"` returns nothing). Consequences enumerated in §3.

3. **The whole `widgets/botuikit` package is unreachable dead code.** No class in the target
   implements `ComponentActionListener` and nothing constructs `ComponentProvider`
   (verified by repo-wide grep). Layouts `widget_select_component_bottom_sheet.xml`,
   `view_interaction_modal.xml`, `bot_uikit_text_input.xml` ship with no Kotlin owner.

---

## 1. Reference enumeration (non-synthetic)

| Package | Reference count | Target count |
|---|---|---|
| `widgets/auth` | 21 | 3 files / 17 declarations |
| `widgets/home` | 11 | 2 files / 3 declarations |
| `widgets/tabs` | 6 | 2 files / 2 declarations + 2 helpers |
| `widgets/share` | 1 | 1 |
| `widgets/captcha` | 4 | 1 |
| `widgets/botuikit` (incl. `views`, `views/select`) | 21 | 5 |
| `com/discord/app` | 15 | 7 |
| `com/discord/panels` | 2 | 1 file / 2 declarations |
| **Total** | **81** | **22 files** |

Reference file list (from `ls | grep -v '\$'`):

- **auth (21)**: AuthInviteInfoView, SmsAuthCodeBroadcastReceiver, WidgetAgeVerify,
  WidgetAgeVerifyViewModel, WidgetAuthAgeGated, WidgetAuthBirthday, WidgetAuthCaptcha,
  WidgetAuthForceChangePassword, WidgetAuthLanding, WidgetAuthLandingViewModel, WidgetAuthLogin,
  WidgetAuthMfa, WidgetAuthPhoneVerify, WidgetAuthRegisterAccountInformation,
  WidgetAuthRegisterIdentity, WidgetAuthResetPassword, WidgetAuthUndeleteAccount,
  WidgetOauth2Authorize, WidgetOauth2AuthorizeSamsung, WidgetRemoteAuth, WidgetRemoteAuthViewModel
- **home (11)**: HomeConfig, HomePanelsLayout, LeftPanelManager, PanelLayout, WidgetHome,
  WidgetHomeHeaderManager, WidgetHomeModel, WidgetHomePanelLoading, WidgetHomePanelNsfw,
  WidgetHomeViewModel, WidgetMainSurveyDialog
- **tabs (6)**: BottomNavViewObserver, NavigationTab, OnTabSelectedListener,
  TabsHostBottomNavigationView, TabsHostViewModel, WidgetTabsHost
- **share (1)**: WidgetIncomingShare
- **captcha (4)**: WidgetCaptcha, WidgetCaptchaBottomSheet, WidgetCaptchaBottomSheetKt, WidgetCaptchaKt
- **botuikit (21)**: BotComponentExperiments, ComponentChatListState, ComponentChatListStateKt,
  ComponentExperiments, ComponentInflater, ComponentProvider, ComponentStateMapper, ModalComponent;
  views/{ActionRowComponentView, ButtonComponentView, ButtonComponentViewKt, ComponentActionListener,
  ComponentView, ComponentViewUtils}; views/select/{ComponentContext, SelectComponentBottomSheet,
  SelectComponentBottomSheetAdapter, SelectComponentBottomSheetItem,
  SelectComponentBottomSheetItemViewHolder, SelectComponentBottomSheetViewModel, SelectComponentView}
- **app (15)**: App, AppActivity, AppBottomSheet, AppComponent, AppDialog, AppFragment, AppLog,
  AppLogger, AppPermissionsRequests, AppScrollingNestedViewBehavior, AppScrollingViewBehavior,
  AppTransitionActivity, AppViewFlipper, DiscordConnectService, LoggingConfig
- **panels (2)**: OverlappingPanelsLayout, PanelState

---

## 2. `App` and `AppActivity` — target vs reference

### 2.1 `App`

Reference `com/discord/app/App.java:196-268` `onCreate` order:
SharedPreferencesProvider → ApplicationProvider → ActivityProvider → ClockFactory →
AppLog init (+ default SharedPreferences) → `LoggingProvider.init(AppLog)` → Firebase init →
`BuildUtils.isValidBuildVersionName` gate on `FirebaseCrashlytics.setCrashlyticsCollectionEnabled`
(App.java:211-216, disables + warns on modified clients) → `SystemLogUtils.initSystemLogCapture`
(217) → `libdiscord_version` breadcrumb + `DebugPrintableCollection.initialize` (219-225) →
`AdjustConfig.init` (226) → backgrounded activity-lifecycle callbacks with error handler (227-231) →
`PersisterConfig.init` (232) → simpleast/markdown parser + link colour + URL click handler (233-246) →
`RestAPI.Companion.init` (247) → `NotificationClient.init` (248) → `MGImagesConfig.init` (249) →
`Error.init` (250) → `LinkifiedTextView.Companion.init` (251) → `ModelEmojiCustom.setCdnUri` (252) →
`SurveyUtils.init` (253) → `AppCompatDelegate.setDefaultNightMode(1 /* NO */)` (254) →
`initializeFlipper()` (255) → `initializeRLottie()` = `System.loadLibrary("dsti")` (256, 187-189) →
crash-on-previous-execution → `AnalyticsTracker.appCrashed()` (258-260) →
`AnalyticsDeviceResourceUsageMonitor.start()` (261) → RNA rollout experiment subscribe (262-266) →
`AppLog.i("Application initialized.")` (267).
`onTrimMemory` → `MGImagesConfig.onTrimMemory(level)` (App.java:270-274).

Target `app/App.kt:17-47`: BootLog → SharedPreferencesProvider → ApplicationProvider →
ActivityProvider → ClockFactory → Backgrounded → PersisterConfig → Fresco →
`setDefaultNightMode(MODE_NIGHT_YES)` → `System.loadLibrary("dsti")` → `StoreStream.initialize`.

Gaps (MAJOR unless noted):
- **`AppLog`/`LoggingProvider` never initialized** — no app logger exists at all. BLOCKER-adjacent
  because every reference screen's `getAppLogger().b()` impression tracking depends on it (§3).
- No Crashlytics init, no modified-client detection gate (`App.java:211-216`).
- No `SystemLogUtils.initSystemLogCapture` (`App.java:217`), no `libdiscord_version` breadcrumb
  (219-224), no `DebugPrintableCollection` (225).
- No `AdjustConfig` (226), no `AnalyticsTracker.appCrashed` (259),
  no `AnalyticsDeviceResourceUsageMonitor` (261) — target has **zero** `AnalyticsTracker` references
  repo-wide.
- No `NotificationClient.init` (248), no `SurveyUtils.init` (253), no `Error.init` (250),
  no `LinkifiedTextView` URL handler (251), no markdown/`UriHandler` link wiring (233-246).
- `setDefaultNightMode(MODE_NIGHT_YES)` (`App.kt:30`) vs reference `MODE_NIGHT_NO` (`App.java:254`) —
  MINOR but a behavioural difference; the reference relies on explicit themes, not night mode.
- `onTrimMemory` uses Fresco directly (`App.kt:37-42`) rather than `MGImagesConfig` — MINOR,
  functionally equivalent.
- Target does not model `isUnderTest` / `initializeFlipper` overrides (`App.java:183-193`) — MINOR.

### 2.2 `AppActivity`

The two are **architecturally different**, not merely incomplete.

Reference (`com/discord/app/AppActivity.java:79-783`) is a *one-activity-per-screen* host:
- `extends b.a.d.d` (`b/a/d/d.java:12`) which extends `AppTransitionActivity`; implements `AppComponent`.
- `d()` resolves the screen `Class<? extends AppComponent>` from
  `intent.getSerializableExtra("com.discord.intent.extra.EXTRA_SCREEN")`, defaulting to
  `WidgetTabsHost` (`AppActivity.java:314-325`).
- Subclasses `Main`, `AppAction` (share/SDK → `WidgetIncomingShare`, else `WidgetTabsHost`,
  `AppActivity.java:100-135`), `Call` (`WidgetCallFullscreen`, 140-149),
  `IncomingCall` (`WidgetVoiceCallIncoming.SystemCallIncoming`, 154-163).
- `attachBaseContext` (409-425): calls the **one-time app-activity bootstrap** `b.j.invoke2()`
  (173-207 — `StoreStream.initialize`, `AnalyticsUtils.initAppOpen`, `AccessibilityMonitor.initialize`,
  `ShareUtils.updateDirectShareTargets`, `GooglePlayBillingManager.init`, `ScreenshotDetector`,
  `BugReportManager.init`), then applies **font scale** via `FontUtils.getTargetFontScaleFloat`
  and `createConfigurationContext` (418-422), recording `originalFontScale`.
- `applyOverrideConfiguration` (395-406) preserves `uiMode` while resetting to base config.
- `onCreate` (624-655): theme select from `StoreStream.getUserSettingsSystem().getTheme()`
  (Light/Dark/Dark_Evil, 630-638) → `b(locale, false)` locale apply (639) →
  `a(0)` = consume external routing intent + resolve `AppTransitionActivity.Transition`
  (reduced-motion → `TYPE_FADE_FAST`, settings screens → `TYPE_SLIDE_HORIZONTAL`) + install screen
  fragment via `j.g(...)` (743-781) → `a(1)` = bind `R.id.action_bar_toolbar` toolbar (747-753) →
  **screen analytics** `StoreStream.getAnalytics().appUiViewed(d())` unless on the Home tab (642-645) →
  on any exception, launch `WidgetFatalCrash` and finish (646-654).
- `onResume` (684-708): `INTENT_RECREATE` handling; **locale-change recreate**; subscribe to
  `observeSettings` and recreate on theme/locale/font-scale change (`c.invoke`, 222-293 — partially
  undecompilable, see §7); subscribe to `ScreenshotDetector` → `BugReportManager.onScreenshot`.
- `onPause` (678-681): `unsubscribeSignal.onNext(null)`.
- `onNewIntent` (664-675): stores `mostRecentIntent`, runs
  `IntentUtils.consumeExternalRoutingIntent`, then fans out to per-fragment `newIntentListeners`.
- Keyboard: `showKeyboard`/`hideKeyboard` → `m(boolean, View)` (581-603) with 250 ms post-delay,
  `TextInputLayout`→`editText` unwrap, window-token fallback to `android.R.id.content` child,
  exceptions logged to `AppLog`.
- Action bar: `n(Toolbar)` (605-621) injects a `ToolbarTitleLayout` at index 0, themes it with
  `selectableItemBackground`, sets a navigation click listener that hides the keyboard then calls
  `onBackPressed`; `k(showHomeAsUp, iconRes, a11yLabel)` (545-569) tints the back icon with
  `colorInteractiveActive` and sets content description; `l(title, leftDrawable)` (571-579).
- Back: handled by `b.a.d.d.onBackPressed` (`b/a/d/d.java:16-44`) — a `TreeMap<Integer,
  HashMap<String, Function0<Boolean>>>` of **priority-ordered back handlers**, first `true` wins,
  otherwise `super.onBackPressed()` with `IllegalArgumentException`/`IllegalStateException` swallowed.
- `g(List<KClass>)` / `h(KClass)` screen predicates (505-527), `j(Context)` "go home" (533-543).

Target (`app/AppActivity.kt:48-220`) is a *single-activity, in-place fragment router*:
- `AppCompatActivity`, implements `AuthUiActions`; nested `class Main : AppActivity()` (210).
- `attachBaseContext` (54-59): locale only — **no font scale**, no one-time bootstrap.
- `onCreate` (61-80): theme from user settings (62-68) — matches reference; inflates
  `ActivityAppBinding`; installs a single `OnBackPressedCallback` that pops the back stack or
  `moveTaskToBack` (72-77); consumes the launch intent.
- `onStart`/`onStop` (94-112): BootLog tail into `binding.appBootLog`, and
  `StoreStream.getNavigation().observeMainRoute()` → `showRoute`.
- `showRoute` (114-129): swaps a single fragment into `R.id.app_root` keyed by
  `"main-route:$route"`.
- `consumeIntent` (131-142): `AppIntentParser.parse` → `StoreNavigation.routeIntent`;
  external → `ACTION_VIEW`; queued → stash; then resets `intent` to `ACTION_MAIN`.
- `navigate`/`submit`/`close` (165-208): the `AuthUiActions` implementation.

Gaps:

| Reference behavior | Target | Severity |
|---|---|---|
| Per-screen activity + `EXTRA_SCREEN` routing + `Call`/`IncomingCall` subclasses (`AppActivity.java:100-163,314-325`) | absent; single host + `MainRoute` | MAJOR (architectural; blocks call/incoming-call screens) |
| One-time activity bootstrap: `AnalyticsUtils.initAppOpen`, `AccessibilityMonitor`, `ShareUtils.updateDirectShareTargets`, `GooglePlayBillingManager`, `ScreenshotDetector`, `BugReportManager` (`AppActivity.java:187-206`) | none | MAJOR |
| Font-scale config override + `originalFontScale` tracking (`AppActivity.java:414-423`) | none (`AppActivity.kt:54-59`) | **BLOCKER** (Phase 5 "main fragment host") |
| Recreate-on-settings-change (theme / locale / font scale) via `observeSettings` (`AppActivity.java:697-702` + `c.invoke` 222-293) | none | **BLOCKER** |
| `applyOverrideConfiguration` uiMode preservation (`AppActivity.java:395-406`) | none | MINOR |
| `INTENT_RECREATE` handling (`AppActivity.java:688-695`) | none | MINOR |
| `AppTransitionActivity.Transition` selection incl. reduced-motion (`AppActivity.java:762-773`) | none; no `AppTransitionActivity` in target | MAJOR |
| Screen analytics `getAnalytics().appUiViewed(screen)` (`AppActivity.java:645`) | none | MAJOR |
| `WidgetFatalCrash` fallback on onCreate exception (`AppActivity.java:646-654`) | none | MAJOR |
| `showKeyboard`/`hideKeyboard` (`AppActivity.java:529-531,581-603,710-713`) | none | **BLOCKER** (every auth form relies on it) |
| Toolbar/`ToolbarTitleLayout` binding, `setActionBarDisplayHomeAsUpEnabled`, title/subtitle (`AppActivity.java:545-621`) | none; **zero `ToolbarTitleLayout` references repo-wide** | **BLOCKER** |
| Priority back-handler map (`b/a/d/d.java:13-44`) | single flat `OnBackPressedCallback` (`AppActivity.kt:72-77`) | MAJOR |
| `newIntentListeners` fan-out + `setOnNewIntentListener` (`AppActivity.java:83,664-675`) | none | MAJOR |
| `IntentUtils.consumeExternalRoutingIntent` + `AppActivity.m` "already routed" flag (`AppActivity.java:670,131-133`) | partially covered by `StoreNavigation.routeIntent` (`AppActivity.kt:132`) | MINOR |
| `unsubscribeSignal` on pause + `AppComponent` impl (`AppActivity.java:519-522,678-681`) | `AppComponent` interface exists (`app/AppComponent.kt:5-7`) but **AppActivity does not implement it** and nothing else does | MAJOR |
| `mostRecentIntent` accessor (`AppActivity.java:454-462`) | absent | MINOR |

`AppActionActivity.kt:12-29` is a reasonable stand-in for reference `AppActivity.AppAction`
(`AppActivity.java:100-135`), but it forwards `SdkAction` to `Main` and finishes rather than
hosting `WidgetTabsHost`, and it does not implement the `AppActivity.m` finish-if-already-routed
guard (`AppActivity.java:131-133`). MINOR.

`AppViewFlipper.kt`, `AppScrollingViewBehavior.kt`, `AppScrollingNestedViewBehavior.kt` are faithful
ports of `AppViewFlipper.java`, `AppScrollingViewBehavior.java`, `AppScrollingNestedViewBehavior.java`
— **REAL**.

`DiscordConnectService.java` (251 lines, Wear/companion connect service) → **MISSING**. MINOR.

---

## 3. Base classes — `AppFragment` / `AppBottomSheet` / `AppDialog` / `AppScreen`

**None of these exist in the target.** There is no `AppFragment.kt`, `AppBottomSheet.kt`,
`AppDialog.kt`, `AppLog.kt`, `AppLogger.kt`, `LoggingConfig.kt`, `AppPermissionsRequests.kt`,
`AppTransitionActivity.kt` anywhere under `manticordrebuild`. Target screens extend
`androidx.fragment.app.Fragment` / `DialogFragment` directly (e.g. `AuthFragments.kt:34`,
`WidgetHome.kt:30`, `WidgetTabsHost.kt:45`, `WidgetIncomingShare.kt:29`,
`WidgetCaptchaBottomSheet.kt:11`).

### 3.1 `AppFragment` (reference `com/discord/app/AppFragment.java:55-719`)

Missing base behaviors, each of which every screen in this slice silently loses:

| Behavior | Reference | Severity |
|---|---|---|
| **`onViewBound(view)` contract** — called from `onViewCreated` after toolbar binding + `drawUnderSystemBars` | `AppFragment.java:414-436` | **BLOCKER** |
| **`onViewBoundOrOnResume()` contract** with `onViewBoundOrOnResumeInvoked` de-dup on first resume | `AppFragment.java:404-411,419-421,435` | **BLOCKER** |
| **`isRecreated`** flag from `savedInstanceState != null` | `AppFragment.java:309-311,431` | MAJOR |
| **Unsubscribe-on-pause** — `unsubscribeSignal.onNext(null)` in `onPause`; `AppComponent` impl | `AppFragment.java:288-291,397-401` | **BLOCKER** (target screens hand-roll `onStart`/`onStop` subscription juggling per file) |
| **Impression/screen analytics** — `getAppLogger().b()` in `onViewBoundOrOnResume`, driven by `LoggingConfig` (`trackImpression`, dedup properties, `AnalyticsSchema` supplier) | `AppFragment.java:419-421`; `AppLogger.java:59-...`; `LoggingConfig.java:16-20` | MAJOR |
| **Keyboard** — `showKeyboard(view)` / `hideKeyboard([view])` delegating to `AppActivity` | `AppFragment.java:298-307,630-636` | **BLOCKER** |
| **Action bar API** — `setActionBarTitle` (×4 overloads), `setActionBarSubtitle` (×2), `setActionBarTitleColor`, `setActionBarTitleClick`, `setActionBarTitleAccessibilityViewFocused`, `setActionBarTitleLayoutExpandedTappableArea`/`MinimumTappableArea`, `setActionBarDisplayHomeAsUpEnabled` (×2), `setActionBarOptionsMenu` (×2), `bindToolbar` | `AppFragment.java:241-250,485-596,638-718` | **BLOCKER** |
| **Priority back handling** — `setOnBackPressed(Func0<Boolean>, priority)` registering into `AppActivity.l`, cleaned up in `onDestroyView` | `AppFragment.java:598-618,360-375` | **BLOCKER** |
| **`setOnNewIntentListener`** + `onDetach` cleanup | `AppFragment.java:620-628,377-385` | MAJOR |
| **Runtime permissions** — `requestMedia`, `requestMediaDownload`, `requestMicrophone`, `requestVideoCallPermissions`, `requestCameraQRScanner`, `requestContacts`, `hasMedia` | `AppFragment.java:293-296,442-477,689-693` | MAJOR |
| **Media picker** — `MediaPicker.Provider`, `getImageFile`, `openMediaChooser`, `onActivityResult` request-code demux (CAMERA/GALLERY/DOCUMENTS/CHOOSER/CROP), `onImageChosen`/`onImageCropped` | `AppFragment.java:272-275,313-357,387-395,438-440` | MAJOR |
| **`TextWatcher.Companion.reset(this)`** on `onDestroyView` (leak guard) | `AppFragment.java:373` | MINOR |
| **`DisplayUtils.drawUnderSystemBars(view)`** on every screen | `AppFragment.java:432` | MAJOR (edge-to-edge insets) |
| **`getMostRecentIntent()`** | `AppFragment.java:282-286` | MINOR |
| **`requireAppActivity()` / `getAppActivity()`** | `AppFragment.java:260-262,479-483` | MINOR |

### 3.2 `AppBottomSheet` (reference `com/discord/app/AppBottomSheet.java`)

`extends BottomSheetDialogFragment implements AppPermissionsRequests, AppComponent, AppLogger.a`
(metadata at `AppBottomSheet.java:47`). Missing contract:

- `getContentViewResId()` abstract + `onCreateView` inflating it (`AppBottomSheet.java:302,353-357`).
- `shouldAvoidKeyboard` → `UiKit_Sheet_KeyboardAvoiding` style in `onCreate` (327-332), plus
  the `OnShowListener` keyboard-avoid wiring (`c`, 104-145, 345-347).
- `onCreateDialog`: `setCanceledOnTouchOutside(true)`; reduced-motion → `R.style.FadeInOut`
  window animations (335-351).
- `BottomSheetBehavior.BottomSheetCallback` → `onStateChanged(int)` hook (147-166, 383-386, 393-396).
- `bindSubscriptions(CompositeSubscription)` created on resume, unsubscribed on pause (276-279,
  365-381).
- `unsubscribeSignal.onNext(null)` on pause (373).
- `getAppLogger().b()` impression tracking in `onViewCreated` (397).
- `DisplayUtils.drawUnderSystemBars` (392) and `fixWindowInsetHandling()` on resume (377).
- `TextWatcher.Companion.reset(this)` on `onDestroyView` (359-363).
- `hideKeyboard`, `getArgumentsOrDefault`, `getAppActivity`/`requireAppActivity`, the six
  permission-request delegates (288-325, 400-437).

**Severity: BLOCKER.** Every bottom sheet in the codebase (`WidgetCaptchaBottomSheet`,
`SelectComponentBottomSheet`, and every sheet in the wider app) is built on this.

### 3.3 `AppDialog` (reference `com/discord/app/AppDialog.java`)

`extends DialogFragment implements AppComponent` (`AppDialog.java` metadata line 22-ish; methods
listed at 51-227). Missing: `onViewBound(view)` (166-169), `onViewBoundOrOnResume()` (171-173),
`onViewCreated` wiring (175-183), `isRecreated` (105-108), `unsubscribeSignal` +
`onPause` unsubscribe (90-92, 128-132), `onStart` window sizing (144-164), `onCreateDialog`
(110-120), `setOnClickAndDismissListener` (185-190), guarded `show(FragmentManager, String)` /
`show(FragmentTransaction, String)` (192-206, 224-227), `hideKeyboard`/`showKeyboard`
(94-103, 208-214), `getArgumentsOrDefault` (80-88), `@LayoutRes` constructor (216-222).
**Severity: BLOCKER** — `WidgetMainSurveyDialog` and all confirmation dialogs depend on it.

### 3.4 `AppScreen` router — `b/a/d/j.java`

Reference `b/a/d/j.java:85-222` is the screen registry + launcher:
- Screen groups: auth screens (`j.a`, line 87), age-verify (`j.f58b`, 90), OAuth
  (`j.c`, 91), settings-like slide-transition screens (`j.d`, 92), verification screens
  (`j.e`, 93), tabs host (`j.f`, 94).
- `b(context, isAuthed, intent)` (96-112): authed → `selectHomeTab(OPEN|CLOSE)` +
  `WidgetTabsHost`; unauthed → `WidgetAuthLanding`; then `d(context, cls, intent)`.
- `d(context, screenClass, parcelable)` (124-…) builds the `EXTRA_SCREEN` intent.
- `g(fragmentManager, activity, screenClass, …)` performs the fragment transaction into the host.

**No target equivalent.** `AppActivity.createRouteFragment` (`AppActivity.kt:144-158`) covers only
`Loading / LoggedIn / LoggedOut / AgeGated / Required`, and `AppActivity.navigate`
(`AppActivity.kt:165-186`) hard-codes a 14-way `when` over `AuthRoute`. The reference's
"launch any screen from anywhere with a transition and back-stack" contract is absent.
**Severity: BLOCKER** (Phase 5 "main fragment host").

---

## 4. Coverage table

Legend: **R** = REAL (behaviorally comparable), **P** = PARTIAL, **I** = INERT-PLACEHOLDER
(present but does nothing meaningful / unreachable), **—** = MISSING.

### 4.1 `com/discord/app` + `com/discord/panels`

| Reference | Target | Class | Notes |
|---|---|---|---|
| App.java | app/App.kt:17 | **P** | see §2.1 |
| AppActivity.java | app/AppActivity.kt:48 | **P** | see §2.2 |
| AppActivity.AppAction | app/AppActionActivity.kt:12 | **P** | no `AppActivity.m` guard |
| AppActivity.Call / IncomingCall | — | — | call screens unhosted |
| AppComponent.java | app/AppComponent.kt:5 | **I** | interface declared; **no implementors** |
| AppFragment.java | — | — | BLOCKER, §3.1 |
| AppBottomSheet.java | — | — | BLOCKER, §3.2 |
| AppDialog.java | — | — | BLOCKER, §3.3 |
| AppLog.java | — | — | no logging subsystem |
| AppLogger.java | — | — | no impression/screen analytics |
| LoggingConfig.java | — | — | |
| AppPermissionsRequests.java | — | — | no runtime-permission plumbing |
| AppTransitionActivity.java | — | — | no screen transitions |
| AppScrollingViewBehavior.java | app/AppScrollingViewBehavior.kt:13 | **R** | |
| AppScrollingNestedViewBehavior.java | app/AppScrollingNestedViewBehavior.kt:12 | **R** | |
| AppViewFlipper.java | app/AppViewFlipper.kt:11 | **R** | |
| DiscordConnectService.java | — | — | |
| b/a/d/j.java (AppScreen) | — | — | BLOCKER, §3.4 |
| b/a/d/d.java (back-handler map) | — | — | MAJOR |
| panels/OverlappingPanelsLayout.java | panels/OverlappingPanelsLayout.kt:29 | **R** | faithful; target adds `SavedState` (`OverlappingPanelsLayout.kt:372-427`) the reference lacks — harmless extra |
| panels/PanelState.java | panels/OverlappingPanelsLayout.kt:22-27 | **R** | `Closed/Opening/Open/Closing` as data objects |

### 4.2 `widgets/auth` (21 reference)

| Reference | Target | Class | Notes |
|---|---|---|---|
| AuthInviteInfoView | widgets/auth/AuthInviteInfoView.kt:21 | **R** | guild/group/direct/template variants present (`:39-118`); adds an `AuthLandingContent` overload (`:57-89`) |
| WidgetAuthLanding | AuthFragments.kt:125 | **P** | 3 clicks only; no SmartLock (`WidgetAuthLanding.java` d2 `loginWithSmartLock`), no invite/template resolution, no analytics |
| WidgetAuthLandingViewModel (630 ln) | — | — | invite/template/`StoreGuildTemplates` state, events |
| WidgetAuthLogin | AuthFragments.kt:141 | **P** | no captcha (`WidgetAuthLogin.java` `WidgetCaptcha.Companion.processErrorsForCaptcha`/`registerForResult`), no SmartLock, no analytics; **submit is dead** (§0.1); adds a non-reference token-login dialog (`:164-193`) |
| WidgetAuthRegisterIdentity | AuthFragments.kt:203 | **P** | no captcha `registerForResult`; reference calls `RestAPI.postAuthRegisterPhone` directly |
| WidgetAuthRegisterAccountInformation | AuthFragments.kt:239 | **P** | no captcha, no `AnalyticsTracker.openModal` |
| WidgetAuthBirthday | AuthFragments.kt:268 | **P** | no store-backed consent, no `j.launch` navigation |
| WidgetAuthMfa | AuthFragments.kt:295 | **P** | missing action-bar options menu (`R.id.menu_auth_mfa_backup_codes`, `menu_auth_mfa_info`, `menu_code_verification_paste`) and the info dialog (`R.layout.widget_auth_mfa_info`); backup-code dialog present (`:307-317`) |
| WidgetAuthPhoneVerify | AuthFragments.kt:325 | **P** | no SMS auto-fill (`startSmsListener`/`stopSmsListener`), no paste menu, no `RestAPI` resend |
| WidgetAgeVerify | AuthFragments.kt:376 | **P** | no ViewModel, no `AnalyticsTracker.openModal`, no `setOnBackPressed`, no `hideKeyboard` |
| WidgetAgeVerifyViewModel (538 ln) | — | — | age-gate store/REST logic |
| WidgetAuthAgeGated | AuthFragments.kt:414 | **P** | one button + one text |
| WidgetAuthForceChangePassword | AuthFragments.kt:424 | **P** | reference *extends* `WidgetSettingsAccountChangePassword` and posts analytics; target duplicates a thin form and its submit is dead |
| WidgetAuthResetPassword | AuthFragments.kt:443 | **I** | two click listeners, **no `render`** — submit path dead, nothing displays the token/email |
| WidgetAuthUndeleteAccount | AuthFragments.kt:450 | **P** | submit path dead |
| WidgetAuthCaptcha | — | — | cluster: captcha |
| SmsAuthCodeBroadcastReceiver | — | — | cluster: phone verification |
| WidgetOauth2Authorize (793 ln) | — | — | cluster: **OAuth/authorize** |
| WidgetOauth2AuthorizeSamsung (241 ln) | — | — | cluster: OAuth/authorize |
| WidgetRemoteAuth (164 ln) | — | — | cluster: **remote auth (QR login)** — target parses the `/ra/<fingerprint>` deep link (`utilities/intent/DeepLinkParser.kt:63`) but has no screen to handle it |
| WidgetRemoteAuthViewModel (326 ln) | — | — | cluster: remote auth |

Target-only additions (not in the reference `widgets/auth`): `WidgetAuthEmailVerify`
(`AuthFragments.kt:348`), `WidgetAuthRequiredAction` (`AuthFragments.kt:361`) — these stand in for
reference `widgets/user/email/WidgetUserEmailVerify` and `widgets/user/account/WidgetUserAccountVerify`
(both in `j.e`, `b/a/d/j.java:93`); and the whole `AuthUi.kt` abstraction
(`AuthUiState`/`AuthAction`/`AuthResult`/`AuthUiActions`/`AuthUiRules`/`AuthUiViewModel`,
`AuthUi.kt:7-168`), which has no reference analogue — the reference uses per-screen ViewModels
(`b.a.d.d0`) and direct `RestAPI`/`StoreStream` calls.

### 4.3 `widgets/home` (11 reference)

| Reference | Target | Class | Notes |
|---|---|---|---|
| PanelLayout | home/HomePanelsLayout.kt:7 | **R** | `openStartPanel/openEndPanel/closePanels` |
| HomePanelsLayout | home/HomePanelsLayout.kt:13 | **R** | |
| WidgetHome (844 ln) | home/WidgetHome.kt:30 | **P** | see below |
| HomeConfig | — | — | cluster: home nav/config |
| LeftPanelManager | — | — | cluster: home nav drawer — reference drives left-panel lock state via `observeLockState` (`WidgetHome.java:258`) |
| WidgetHomeHeaderManager (421 ln) | — | — | cluster: home header/toolbar — target's `configureToolbar` (`WidgetHome.kt:128-148`) is a hand-rolled ~20-line substitute that searches the view tree for a `Toolbar` |
| WidgetHomeModel (512 ln) | — | — | |
| WidgetHomeViewModel (1171 ln) | — | — | **BLOCKER** — all home state |
| WidgetHomePanelLoading | — | — | cluster: home panels |
| WidgetHomePanelNsfw | — | — | cluster: home panels — NSFW gate never shown |
| WidgetMainSurveyDialog | — | — | cluster: surveys (no `SurveyUtils` in target at all) |

`WidgetHome.kt` missing vs `WidgetHome.java` (all MAJOR unless noted):
`onTabSelected` (`OnTabSelectedListener`, `WidgetHome.java:704`); gesture regions
`onGestureRegionsUpdate` → `setChildGestureRegions` (metadata, `b.a.o.b$a`); panel corner rounding
`roundPanelCorners`/`unroundPanelCorners`/`setPanelCorners`; `setPanelWindowInsetsListeners`;
`animatePeek`; `setupSmoothKeyboardReaction`; `configureFirstOpen` (`:254`); NSFW panel
(`:376,499,744`); loading panel (`:747,787`); unread count view (`getUnreadCountView`, `:622`);
`handleGlobalStatusIndicatorState` (`:837`); survey display (`:807-816`);
`showUrgentMessageDialog`; `maybeShowHubEmailUpsell` (`:391`); guild-event upsell + welcome sheet +
channel-onboarding sheet; PlayStation / guild-role-subscription upsells;
`showInteractionModal(InteractionModalCreate)` (`:835`); `setOnGuildListAddHintCreate` (`:750`);
`handleCenterPanelBack`; forum-channel-list and directory child fragments (`:405-410`).
`setOnBackPressed` priority registration (`:752`) is replaced by a bespoke `BackHandler`
interface (`WidgetHome.kt:26-28`).

### 4.4 `widgets/tabs` (6 reference)

| Reference | Target | Class | Notes |
|---|---|---|---|
| NavigationTab | tabs/TabsHostBottomNavigationView.kt:20 | **R** | all 6 values match `NavigationTab.java:9-16` |
| TabsHostBottomNavigationView (309 ln) | tabs/TabsHostBottomNavigationView.kt:22 | **P** | see below |
| WidgetTabsHost (374 ln) | tabs/WidgetTabsHost.kt:45 | **P** | see below |
| TabsHostViewModel (643 ln) | — | — | **BLOCKER** — tab visibility sets, badge counts, unread logic |
| OnTabSelectedListener | — | — | tab re-selection callback into child fragments |
| BottomNavViewObserver | — | — | height propagation to content margins |

`TabsHostBottomNavigationView` gaps:
- Signature drift: reference `updateView(NavigationTab, Function1, boolean, long myUserId, Set,
  int, int, Function0, Function0, Function0)` (`TabsHostBottomNavigationView.java:143`) uses a
  **user id** + `UserAvatarPresenceViewController`; target takes `User` + `Presence`
  (`TabsHostBottomNavigationView.kt:83-95`) and configures the avatar synchronously
  (`:156-165`), so presence never live-updates. MAJOR.
- No `ViewExtensions.setOnLongClickListenerConsumeClick` semantics
  (`TabsHostBottomNavigationView.java:238`) — target uses a plain `OnLongClickListener`
  (`:122-127`). MINOR.
- Target adds `SavedState` (`:181-229`) not in the reference — harmless.
- Height listener (`addHeightChangedListener`, `onSizeChanged`) is present (`:167-174`) but has
  **no consumer** (no `BottomNavViewObserver`), so bottom-nav height never adjusts content
  margins the way `WidgetTabsHost.java:245` does. MAJOR.

`WidgetTabsHost` gaps:
- **Badge counts hard-coded to `0`** (`WidgetTabsHost.kt:234-235`) — home mention badge and
  friends-request badge never appear. BLOCKER (visible regression).
- **Visible tab set hard-coded** to 5 tabs (`WidgetTabsHost.kt:37-43`); reference switches between
  `NO_GUILD_TABS` and `AT_LEAST_ONE_GUILD_TABS` (`TabsHostViewModel.java:500-505`). MAJOR.
- `buttonsEnabled` always `true` (`WidgetTabsHost.kt:231`); reference derives it from view state. MAJOR.
- `onMentionsLongPress` is `{}` (`WidgetTabsHost.kt:238`). MAJOR.
- Search tab: target hosts a `WidgetGlobalSearch` fragment in `R.id.widget_tabs_host_search`
  (`:55,210`); reference has **no search container** (`WidgetTabsHost.java:124-133` shows only
  home/friends/mentions/settings) and instead opens a search dialog, with
  `Event.DismissSearchDialog` (`TabsHostViewModel.java:175`). MAJOR (behavioural divergence).
- Missing: `configureSystemStatusBar` + `GlobalStatusIndicatorState` (`WidgetTabsHost.java:92`);
  bottom-nav height → content margin (`:245`); `Event.TrackFriendsListShown`
  (`TabsHostViewModel.java:186`); `AnalyticsTracker` usage. MAJOR.
- Target uses `WidgetXxx.onYyy` **static mutable callback fields** for cross-screen navigation
  (`WidgetTabsHost.kt:71-116` sets 12 of them) — a pattern with no reference analogue; these leak
  across fragment recreation and are cleared nowhere except `WidgetHome.onDestroyView`
  (`WidgetHome.kt:95-99`). MAJOR (design/leak risk).

### 4.5 `widgets/share` (1 reference)

| Reference | Target | Class |
|---|---|---|
| WidgetIncomingShare (1000 ln) | share/WidgetIncomingShare.kt:29 | **P** |

Target reimplements the screen with two hand-rolled `TextView`-only adapters
(`CandidateAdapter` `:111-120`, `AttachmentAdapter` `:122-130`, `TextHolder` `:132-138`,
`textView()` `:231-237`).

Missing vs `WidgetIncomingShare.java`:
- **`WidgetGlobalSearchAdapter`** for recipient search (`WidgetIncomingShare.java:71,576-591,816`) —
  target substitutes plain text rows with no avatars, no user/channel typing, no result grouping.
  MAJOR.
- **Image preview adapter** `Adapter`/`ViewHolder` over `R.layout.view_image` with click-to-remove
  (`WidgetIncomingShare.java:130-232`) — target shows the URI's last path segment as text
  (`:127-129`). MAJOR.
- **Send path**: reference `StoreMessages.sendMessage(...)` with application/`MessageActivity`/
  `captchaPayload` and `onSendCompleted` (`:766`); plus `isOnCooldown`, `maxFileSizeMB`,
  `isUserPremium` gates in `onSendClicked` (`:535,766`). Target's `defaultCallbacks.send`
  (`WidgetIncomingShare.kt:189-212`) has no cooldown, no size limit, no premium check, no captcha. MAJOR.
- **`AnalyticsTracker.addAttachment(ATTACHMENT_SOURCE_SHARE, mimeType, …)`**
  (`WidgetIncomingShare.java:642`) — absent. MAJOR.
- **Dynamic menu** — reference swaps `menu_external_share` ↔ `menu_empty` based on whether there
  is anything to send (`:535,571`); target always inflates `menu_external_share`
  (`WidgetIncomingShare.kt:58`). MINOR.
- **`setOnNewIntentListener`** (`:908`) and `ViewEmbedGameInvite` game-invite sharing
  (`:766`, `Model`) — absent. MAJOR.
- Toolbar is located by `((root as ViewGroup).getChildAt(0) as AppBarLayout).getChildAt(0) as Toolbar`
  (`WidgetIncomingShare.kt:109`) — an unchecked structural cast that will `ClassCastException`
  if the layout changes; the reference uses the `AppFragment` action-bar API. MINOR (fragility).
- Unused imports indicate abandoned work: `Permission` is used only in `defaultCandidates`
  (`:177`), `Toast` shows a bare `R.string.error` on failure (`:92`).

### 4.6 `widgets/captcha` (4 reference)

| Reference | Target | Class | Notes |
|---|---|---|---|
| WidgetCaptchaBottomSheet (213 ln, `extends AppBottomSheet`) | captcha/WidgetCaptchaBottomSheet.kt:11 | **P** | see below |
| WidgetCaptchaBottomSheetKt | folded in | **R** | trivial |
| WidgetCaptcha (274 ln, `extends AppFragment`, full-screen captcha) | — | — | cluster: captcha |
| WidgetCaptchaKt | — | — | |

Target `WidgetCaptchaBottomSheet.kt` is a `DialogFragment` hosting a **hand-written hCaptcha
WebView** (`:14-33`, HTML template at `:86-94`) with a `@JavascriptInterface` bridge (`:45-56`).
The reference uses `CaptchaHelper.CaptchaRequest.HCaptcha` / `ReCaptcha`
(`WidgetCaptchaBottomSheet.java:182`, `WidgetCaptcha.java:165`) — i.e. the real hCaptcha/reCAPTCHA
SDKs, with an activity result and a `captchaPassed` flag.

Missing (all MAJOR):
- reCAPTCHA support (`WidgetCaptcha.java:171` picks `verify_by_recaptcha` vs `verify_by_hcaptcha`).
- `CaptchaErrorBody` / `createFromError` plumbing (`WidgetCaptcha.java:72`).
- `Companion.enqueue(...)` via `StoreNotices.Notice` so a captcha can be queued from any screen
  (`WidgetCaptchaBottomSheet.java:89`) and `resolvableFragments` gating.
- `Companion.registerForResult` using `FragmentKt.setFragmentResultListener`
  (`WidgetCaptchaBottomSheet.java:96`) — target instead uses a `ConcurrentHashMap` of callbacks in a
  companion object (`WidgetCaptchaBottomSheet.kt:60`) plus a global `onSolved` var (`:59`), which
  does not survive process death.
- `onCaptchaAttemptFailed` callback and `onDismiss` failure signalling
  (`WidgetCaptchaBottomSheet.java` metadata / `onDismiss`).
- `getContentViewResId()`/`R.layout.widget_captcha_bottom_sheet` (`:192`) — target builds the
  `Dialog` in code (`WidgetCaptchaBottomSheet.kt:29-32`), so the sheet has no Discord chrome
  (no title bar, no cancel button, no theming).

### 4.7 `widgets/botuikit` (21 reference)

| Reference | Target | Class | Notes |
|---|---|---|---|
| views/ComponentView | botuikit/views/ComponentView.kt:13 | **R** | |
| views/ComponentActionListener | botuikit/views/ComponentView.kt:18 | **R** | merged |
| views/ComponentViewUtils | botuikit/views/ComponentView.kt:33-60 | **R** | `setEnabledAlpha`, `setEmojiOrHide`, `emojiUri` |
| views/ActionRowComponentView | views/ActionRowComponentView.kt:18 | **R** | |
| views/ButtonComponentView (+Kt) | views/ButtonComponentView.kt:27 (+`:97`) | **R** | |
| views/select/SelectComponentView | views/select/SelectComponentView.kt:25 | **R** | |
| ComponentProvider | botuikit/ComponentProvider.kt:17 | **R** | |
| ComponentInflater | folded into `ComponentProvider.inflate` (`:30-35`) | **P** | |
| views/select/SelectComponentBottomSheet | — | — | cluster: **select dropdown** |
| views/select/SelectComponentBottomSheetAdapter | — | — | cluster: select dropdown |
| views/select/SelectComponentBottomSheetItem | — | — | cluster: select dropdown |
| views/select/SelectComponentBottomSheetItemViewHolder | — | — | cluster: select dropdown |
| views/select/SelectComponentBottomSheetViewModel | — | — | cluster: select dropdown |
| views/select/ComponentContext | — | — | cluster: select dropdown |
| ComponentStateMapper (277 ln) | — | — | cluster: interaction state |
| ComponentChatListState (+Kt) | — | — | cluster: interaction state |
| ModalComponent (296 ln, `extends AppFragment`) | — | — | cluster: **interaction modals** |
| BotComponentExperiments | — | — | cluster: experiments |
| ComponentExperiments | — | — | cluster: experiments |

**All five target files are unreachable.** Repo-wide grep finds no implementation of
`ComponentActionListener` and no construction of `ComponentProvider` outside the package itself.
Layouts `widget_select_component_bottom_sheet.xml`, `widget_select_component_bottom_sheet_item.xml`,
`view_interaction_modal.xml`, `bot_uikit_text_input.xml` exist with no Kotlin owner. Classify the
package **INERT-PLACEHOLDER at the package level** even though the individual view classes are
internally REAL. Severity MAJOR (Phase 7 scope, not Phase 5).

---

## 5. MISSING grouped by cluster

**OAuth / authorize** (BLOCKER — Phase 5)
`WidgetOauth2Authorize` (793 ln), `WidgetOauth2AuthorizeSamsung` (241 ln).
No app-authorization flow at all; `RestAPI` `createOauthAuthorize` / OAuth scope views
(`OAuthPermissionViews.InvalidScopeException`) unused. `StoreAuthentication.getOAuthUriObservable`
(`StoreAuthentication.kt:188`) has no consumer.

**Remote auth / QR login** (BLOCKER — Phase 5)
`WidgetRemoteAuth`, `WidgetRemoteAuthViewModel`. `DeepLinkRoute.RemoteAuth`
(`utilities/intent/DeepLinkRoute.kt:17`) is parsed (`DeepLinkParser.kt:63`) and then dropped.

**Captcha** (BLOCKER — Phase 5)
`WidgetCaptcha`, `WidgetAuthCaptcha`, `WidgetCaptchaKt`; plus the enqueue/notice, registerForResult,
reCAPTCHA, and `CaptchaErrorBody` machinery of `WidgetCaptchaBottomSheet`.
No auth screen can recover from a captcha challenge.

**Password reset / account recovery** (BLOCKER — Phase 5)
`WidgetAuthResetPassword` present but inert (`AuthFragments.kt:443-448`, no `render`);
forgot-password submit dead; `WidgetAuthUndeleteAccount` submit dead; force-change-password submit dead.

**Account verification** (BLOCKER — Phase 5)
`SmsAuthCodeBroadcastReceiver` (SMS auto-fill) missing; `startSmsListener`/`stopSmsListener`
(`WidgetAuthPhoneVerify.java`) missing; MFA info dialog + backup-codes/paste action-bar menu missing;
email/phone verify submit dead.

**Auth view models** (BLOCKER — Phase 5)
`WidgetAuthLandingViewModel` (630 ln), `WidgetAgeVerifyViewModel` (538 ln),
`WidgetRemoteAuthViewModel` (326 ln). Replaced by a single generic `AuthUiViewModel`
(`AuthUi.kt:122-133`) with a `BehaviorSubject` and no store or REST access.

**Home nav drawer / panels** (BLOCKER — Phase 5 "tabs/home shell")
`LeftPanelManager`, `HomeConfig`, `WidgetHomeHeaderManager`, `WidgetHomeModel`,
`WidgetHomeViewModel`, `WidgetHomePanelLoading`, `WidgetHomePanelNsfw`.

**Surveys / notices** (MAJOR)
`WidgetMainSurveyDialog`; no `SurveyUtils` anywhere in the target; no `StoreNotices`-driven
notice display.

**Tabs badges / tab-set logic** (BLOCKER — Phase 5)
`TabsHostViewModel`, `BottomNavViewObserver`, `OnTabSelectedListener`.

**BotUIKit select dropdown** (MAJOR)
`SelectComponentBottomSheet` + Adapter + Item + ItemViewHolder + ViewModel + `ComponentContext`.

**BotUIKit interaction state / modals / experiments** (MAJOR)
`ComponentStateMapper`, `ComponentChatListState(+Kt)`, `ModalComponent`,
`BotComponentExperiments`, `ComponentExperiments`.

**App shell base classes** (BLOCKER)
`AppFragment`, `AppBottomSheet`, `AppDialog`, `AppLog`, `AppLogger`, `LoggingConfig`,
`AppPermissionsRequests`, `AppTransitionActivity`, `b.a.d.j` (AppScreen), `b.a.d.d`
(priority back handler), `DiscordConnectService`.

---

## 6. REAL / PARTIAL / INERT classification of every target file

| Target file | Class | Evidence |
|---|---|---|
| `app/App.kt` | **PARTIAL** | 12 of ~28 reference init steps (`App.kt:22-34` vs `App.java:198-267`) |
| `app/AppActivity.kt` | **PARTIAL** | no keyboard/toolbar/back-priority/font-scale/analytics; `submit` dead for all but token (`AppActivity.kt:188-204`) |
| `app/AppActionActivity.kt` | **PARTIAL** | `AppActionActivity.kt:12-29`; no already-routed guard |
| `app/AppComponent.kt` | **INERT-PLACEHOLDER** | `AppComponent.kt:5-7`; zero implementors repo-wide |
| `app/AppViewFlipper.kt` | **REAL** | `AppViewFlipper.kt:11-64` ≡ `AppViewFlipper.java` |
| `app/AppScrollingViewBehavior.kt` | **REAL** | `AppScrollingViewBehavior.kt:13-51` |
| `app/AppScrollingNestedViewBehavior.kt` | **REAL** | `AppScrollingNestedViewBehavior.kt:12-28` |
| `panels/OverlappingPanelsLayout.kt` | **REAL** | `:29-428`; gestures, fling, lock states, LTR/RTL, gesture regions, system-gesture guard all present |
| `widgets/auth/AuthInviteInfoView.kt` | **REAL** | `:39-141` |
| `widgets/auth/AuthUi.kt` | **PARTIAL** (no reference analogue) | `:7-168`; `AuthUiActions.openPasswordManager`/`openSupport` default to `Unit` (`:118-119`) and `AppActivity` does not override → the login "password manager" link (`AuthFragments.kt:153`) and the required-action "support" link (`AuthFragments.kt:365`) are **no-ops** |
| `widgets/auth/AuthFragments.kt` — `AuthFragment` base | **PARTIAL** | `:31-123`; ad-hoc replacement for `AppFragment` |
| — `WidgetAuthLanding` | **PARTIAL** | `:125-139` |
| — `WidgetAuthLogin` | **PARTIAL** | `:141-201`; submit dead |
| — `WidgetAuthRegisterIdentity` | **PARTIAL** | `:203-237`; submit dead |
| — `WidgetAuthRegisterAccountInformation` | **PARTIAL** | `:239-266`; submit dead |
| — `WidgetAuthBirthday` | **PARTIAL** | `:268-293` |
| — `WidgetAuthMfa` | **PARTIAL** | `:295-323`; submit dead |
| — `WidgetAuthPhoneVerify` | **PARTIAL** | `:325-346`; submit dead |
| — `WidgetAuthEmailVerify` | **PARTIAL** | `:348-359`; submit dead |
| — `WidgetAuthRequiredAction` | **PARTIAL** | `:361-374`; `openSupport` no-op |
| — `WidgetAgeVerify` | **PARTIAL** | `:376-412`; submit dead |
| — `WidgetAuthAgeGated` | **PARTIAL** | `:414-422` |
| — `WidgetAuthForceChangePassword` | **PARTIAL** | `:424-441`; submit dead |
| — `WidgetAuthResetPassword` | **INERT-PLACEHOLDER** | `:443-448`; two listeners, no `render`, submit dead |
| — `WidgetAuthUndeleteAccount` | **PARTIAL** | `:450-461`; submit dead |
| `widgets/home/HomePanelsLayout.kt` | **REAL** | `:7-17` |
| `widgets/home/WidgetHome.kt` | **PARTIAL** | `:30-192`; ~15 reference behaviors absent (§4.3) |
| `widgets/tabs/TabsHostBottomNavigationView.kt` | **PARTIAL** | `:22-230`; presence not live, no observer consumer |
| `widgets/tabs/WidgetTabsHost.kt` | **PARTIAL** | `:45-285`; badges `0` (`:234-235`), tab set fixed (`:37`), `onMentionsLongPress` empty (`:238`) |
| `widgets/share/WidgetIncomingShare.kt` | **PARTIAL** | `:29-248`; text-only adapters, no analytics, no premium/cooldown/size gates |
| `widgets/captcha/WidgetCaptchaBottomSheet.kt` | **PARTIAL** | `:11-96`; custom WebView instead of the SDK, no reCAPTCHA, no notice queue |
| `widgets/botuikit/ComponentProvider.kt` | **REAL** but **unreachable** | `:17-57`; no callers |
| `widgets/botuikit/views/ComponentView.kt` | **REAL** but **unreachable** | `:13-60` |
| `widgets/botuikit/views/ActionRowComponentView.kt` | **REAL** but **unreachable** | `:18-51` |
| `widgets/botuikit/views/ButtonComponentView.kt` | **REAL** but **unreachable** | `:27-99` |
| `widgets/botuikit/views/select/SelectComponentView.kt` | **REAL** but **unreachable** | `:25-113`; `onSelectComponentClick` (`:83`) has no receiver |

Totals: **REAL 10**, **PARTIAL 24**, **INERT-PLACEHOLDER 2** (+5 REAL-but-unreachable in botuikit).

---

## 7. Where JADX output is broken (not guessed)

- `AppActivity.java:222-293` — `AppActivity.c.invoke(StoreUserSettingsSystem.Settings)`:
  *"Method not decompiled"* / raw smali. It is the recreate-on-settings-change handler
  (locale → `b(locale, true)`; theme via attr `0x7f0406c4`; font scale vs `originalFontScale`;
  then `i(this, false)` recreate). I described its shape from the raw bytecode, not from
  reconstructed Java; treat the exact branch ordering as unverified.
- `AppLogger.java:52-...` — `AppLogger.b()` (the impression-tracking method) is emitted as raw
  smali with *"Code decompiled incorrectly"*. I state only what is unambiguous from the register
  trace: it is a no-op when impression logging is disabled, reads `LoggingConfig.c`
  (`Function0<AnalyticsSchema>`), and dedups against a stored previous schema using
  `LoggingConfig.a` (a boolean) and `LoggingConfig.f2082b` (a property list). Exact dedup semantics
  are unverified.
- Kotlin `@Metadata d2` name tables in the `widgets/auth` files are stripped/obfuscated
  (only class and superclass FQNs survive), so per-method names for those classes were derived from
  bytecode-level grep (`RestAPI.`/`StoreStream.`/`R.id.`/`R.layout.`/`extends`), not from readable
  signatures. Behaviour descriptions for auth screens are therefore at the "what it touches" level,
  not line-by-line.
- `AppFragment.java:66-70,82-86,96-98,126-130` — JADX emitted `AppFragment.this = r1;` inside
  static-nested lambda constructors (invalid Java). Harmless artefact; semantics are clear.

---

## 8. Severity roll-up

**BLOCKER (Phase 5 regressions — must reopen Phase 5)**
1. All non-token auth submits are dead (`AppActivity.kt:188-204`; `authSubmitter` test-only,
   `MainHostNavigationTest.kt:40`); `StoreAuthentication.login/register/authMFA/forgotPassword/
   resendVerificationEmail` have no production callers.
2. `AppFragment` base class missing → no `onViewBound`/`onViewBoundOrOnResume`, no
   unsubscribe-on-pause, no keyboard API, no action-bar API, no priority back handling,
   no permission plumbing, no `drawUnderSystemBars`.
3. `AppBottomSheet` base class missing → no sheet contract.
4. `AppDialog` base class missing → no dialog contract.
5. `b.a.d.j` (AppScreen router) + `b.a.d.d` (priority back dispatch) missing.
6. `AppActivity`: no keyboard, no `ToolbarTitleLayout`/action bar, no font-scale, no
   recreate-on-settings-change, no `appUiViewed` analytics, no `WidgetFatalCrash` fallback.
7. OAuth/authorize cluster entirely absent (2 classes, 1034 ln).
8. Remote-auth/QR-login cluster entirely absent (2 classes, 490 ln) despite the deep link being parsed.
9. Captcha cluster: `WidgetCaptcha`/`WidgetAuthCaptcha` absent; sheet lacks reCAPTCHA, notice
   queueing, and `registerForResult`.
10. Auth view models absent (`WidgetAuthLandingViewModel` 630 ln, `WidgetAgeVerifyViewModel` 538 ln).
11. `WidgetAuthResetPassword` is inert (`AuthFragments.kt:443-448`).
12. `WidgetHomeViewModel` (1171 ln) and `TabsHostViewModel` (643 ln) absent.
13. Tabs badge counts hard-coded to `0` (`WidgetTabsHost.kt:234-235`).
14. `openPasswordManager()` / `openSupport()` are no-ops (`AuthUi.kt:118-119`).

**MAJOR**
`App` init gaps (Crashlytics/Adjust/analytics/notifications/surveys/logging);
`AppComponent` with no implementors; per-screen activity architecture (`Call`, `IncomingCall`);
`AppTransitionActivity`; `SmsAuthCodeBroadcastReceiver`; MFA info dialog + action-bar menu;
`LeftPanelManager` / `HomeConfig` / `WidgetHomeHeaderManager` / `WidgetHomeModel` /
`WidgetHomePanelLoading` / `WidgetHomePanelNsfw` / `WidgetMainSurveyDialog`;
`WidgetHome` panel corners / insets / gesture regions / first-open / unread / global status /
upsells / interaction modals; `BottomNavViewObserver` + `OnTabSelectedListener`;
tab-set-by-guild-count; `WidgetTabsHost` static-callback wiring pattern;
`WidgetIncomingShare` global-search adapter, image previews, analytics, send gates, new-intent;
whole `botuikit` package unreachable + select bottom sheet + `ModalComponent` +
`ComponentStateMapper` + `ComponentChatListState` missing.

**MINOR**
`MODE_NIGHT_YES` vs `MODE_NIGHT_NO`; `onTrimMemory` via Fresco not `MGImagesConfig`;
`applyOverrideConfiguration`; `INTENT_RECREATE`; `mostRecentIntent`;
`AppActionActivity` already-routed guard; `WidgetIncomingShare` toolbar structural cast +
always-on menu; long-click consume-click semantics; `DiscordConnectService`;
extra `SavedState` implementations in `OverlappingPanelsLayout`/`TabsHostBottomNavigationView`
(harmless additions).
