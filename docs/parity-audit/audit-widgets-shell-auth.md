# Parity Re-audit - App Shell, Auth, Home, Tabs, Share, Captcha and Bot UI Kit

Date: **2026-08-04**

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

Reference: `C:\Users\manti\StudioProjects\discord-jadx` (Discord Android 126.21 Stable)

Supplemental reference: `C:\Users\manti\Desktop\projeler\discorddecompile\out`

This report supersedes its 2026-07-26 snapshot. The earlier report described a pre-wave shell in
which most authentication screens and all shared app bases were absent. Those claims are no longer
current.

## Current counts

| Scope | Reference non-synthetic classes | Target Kotlin files on 2026-08-04 |
| --- | ---: | ---: |
| `com.discord.app` | 15 | **24** |
| `widgets/auth` | 21 | **22** |
| `widgets/home` | 11 | **10** (`PanelLayout` is declared in `HomePanelsLayout.kt`) |
| `widgets/tabs` | 6 | **6** |
| `widgets/share` | 1 | **2** |
| `widgets/captcha` | 4 | **2** (intentional transport redesign; see below) |
| `widgets/botuikit/**` | 21 | **21** |
| `com.discord.panels` | 2 | 1 file / both declarations |
| **Total** | **81** | **88 Kotlin files** |

Raw target file count exceeds the reference count because the target has support classes such as
`AuthRequests`, `AuthUi`, `AppScreenRouter`, `BackPressHandlerRegistry`, media-picker helpers and a
forwarded-message model. It does not imply behavioral completion.

## Resolved headline findings

### Authentication is live

The old token-only branch is gone. `AppActivity.submit()` delegates every `AuthAction` to
`AuthRequests.submit()` (`AppActivity.kt:583-589`). Credential login, registration, MFA, phone/email
verification, reset/undelete/forced-password, age gate and required-action flows have concrete
requests and state transitions. `WidgetAuthLandingViewModel`, `WidgetAgeVerifyViewModel`,
`WidgetRemoteAuth`, `WidgetRemoteAuthViewModel` and `WidgetOauth2Authorize` now exist.

Focused source-level tests cover auth rules/request conversion, remote-auth finish, OAuth parsing,
safe redirects and completion routing. `AuthUiRoutesTest` inflates every reconstructed auth route.

### Shared app framework exists and is used

All historically missing bases are present:

- `AppFragment`, `AppBottomSheet`, `AppDialog`
- `AppLog`, `AppLogger`, `LoggingConfig`
- `AppPermissionsRequests`, `AppTransitionActivity`
- `AppScreenRouter`, `BackPressHandlerRegistry`
- `DiscordConnectService`

`AppFragment.kt:76-99` restores `drawUnderSystemBars`, `onViewBound`,
`onViewBoundOrOnResume`, impression logging and pause unsubscription. Its action-bar, new-intent,
back-handler, permission and media-result APIs occupy `:134-352`. Codegraph reports 86 current
`AppFragment` subclasses, 31 `AppDialog` callers and live `AppBottomSheet` consumers.

### Activity shell behavior exists

`AppActivity` now provides:

- direct-screen and main-route hosting through `AppScreenRouter`
- dedicated `Call` and `IncomingCall` activities (`AppActivity.kt:630-653`)
- priority back dispatch and new-intent listeners (`:317-350`)
- shared toolbar/title/up behavior (`:355-392`)
- keyboard show/hide with the reference delay and fallback token (`:396-423`)
- locale/theme/font-scale recreation (`:227-274,428-449`)
- notice hosting, fatal-crash fallback and screenshot/bug-report integration

The manifest registers call/incoming-call activities, Direct Share shortcuts, Google App Actions and
`DiscordConnectService` (`AndroidManifest.xml:240-353`). The old manifest/deep-link dead-end claims
are resolved.

### Home, tabs and Bot UI kit are connected

- `WidgetHomeViewModel`, `WidgetHomeModel`, `WidgetHomeHeaderManager`, loading/NSFW panels,
  `LeftPanelManager`, survey dialog and panel configuration now exist.
- `TabsHostViewModel`, `BottomNavViewObserver` and `OnTabSelectedListener` exist. `WidgetTabsHost`
  consumes dynamic visible tabs/badges and bottom-nav height (`WidgetTabsHost.kt:65,93,146-177`).
- All 21 Bot UI kit reference owners are represented. `WidgetChatListAdapter.kt:62` creates
  `ComponentProvider`; bot component rows implement `ComponentActionListener`, launch the select
  sheet and submit interactions. `ModalComponent` is a real routed fragment.
- `WidgetIncomingShare` now owns recipient/media adapters and the message-send path; it is also used
  by modern message forwarding.

## Intentional captcha divergence

The exact 126.21 full-screen classes `WidgetCaptcha`, `WidgetAuthCaptcha` and
`WidgetGuildJoinCaptchaBottomSheet` were deliberately removed on 2026-08-03. `PLAN.md:1198`
records the approved replacement: `CaptchaInterceptor` handles challenges globally, sends
`X-Captcha-*` headers and invokes `WidgetCaptchaBottomSheet` as the prompt. The current sheet is a
layout-backed `AppBottomSheet`, supports hCaptcha/reCAPTCHA selection and reports one outcome to the
interceptor (`WidgetCaptchaBottomSheet.kt:24-141`). `CaptchaInterceptorTest` covers challenge
retries and the two-challenge bound.

This closes the old "auth screens cannot recover from captcha" defect, but it is a deliberate
structural/flow departure from 126.21. The deleted reference classes must not be counted as exact
source-path parity.

## Open findings and caveats

### 1. SMS verification auto-fill is absent - MAJOR

The reference `SmsAuthCodeBroadcastReceiver` and `WidgetAuthPhoneVerify.startSmsListener` /
`stopSmsListener` automatically capture verification codes. There is no target receiver or SMS
listener in `widgets/auth`; current auth sources only describe and submit manually entered SMS
codes. Manual phone/MFA verification works, but auto-fill does not.

### 2. Samsung OAuth is intentionally excluded - external caveat, not a blocker

`WidgetOauth2AuthorizeSamsung` has no target counterpart. Vineflower confirms it depends on
`SamsungConnectActivity`, Samsung authorization callbacks and Samsung-specific client state
(`WidgetOauth2AuthorizeSamsung.kt:17-19,37-206`). `PLAN.md:893,962` explicitly excludes Samsung
OAuth and forbids treating it as a release blocker. It remains a reference-parity exception and
cannot be called verified complete.

### 3. Provider and end-to-end auth validation is incomplete - BLOCKER to declaring parity

OAuth and remote-auth logic have unit coverage, but this re-audit found no connected/device test for
an actual OAuth authorization, remote QR handshake, captcha challenge, SMS verification or
credential-manager fallback. No live backend was used. Project-owned provider configuration and the
full cold-start/deep-link/device matrix remain prerequisites for a parity claim
(`PLAN.md:962-963`).

### 4. Shell/device regression matrix remains incomplete - BLOCKER to declaring parity

The shared bases have broad call-site adoption and focused tests, but there is no complete API
21-current, rotation, process-death, tablet, RTL, large-font, keyboard, permission and nested-screen
matrix. `PLAN.md:961` keeps App framework `IN PROGRESS` for that reason.

## JADX/Vineflower/DEX integrity

- **Resolved:** JADX marked `AppLogger.b()` as incorrectly decompiled. Vineflower fully recovers the
  synchronized impression dedup algorithm (`AppLogger.kt:48-72`): logging is gated, the current
  schema is compared either wholly or through configured properties, and state is updated before
  tracking. `AppLoggerTest` exercises enabled, disabled and dedup behavior.
- **Still decompiler-broken, bytecode checked:** Vineflower cannot decompile `AppActivity` at all
  because an anonymous class lacks Kotlin metadata (`AppActivity.kt:1-11`). The settings-recreation
  behavior previously marked unverified was inspected from bytecode: locale, theme and font-scale
  changes recreate the host. The target implements that contract at `AppActivity.kt:227-243,
  :430-449`. Exact anonymous-class source shape is not claimed.
- **Partially broken but sufficient:** Vineflower recovers most of `AppFragment`; `openMediaChooser`
  still fails to resugar, but its bytecode shows the `requestMedia` delegate and the activity-result
  request-code demultiplexing is recovered at `AppFragment.kt:134-199,286-305`. The target's media
  picker is therefore compared to bytecode/call-site evidence, not guessed Java.
- Auth Kotlin metadata remains obfuscated in JADX; current findings use Vineflower signatures,
  concrete target requests and tests rather than JADX method-name guesses.

## Verification boundary

Latest project-wide evidence in `PLAN.md:942-948` is 1,678 unit tests, debug/release assembly,
debug lint, release lint-vital, Android-test compilation and 59/59 connected tests on Android 16.
This documentation re-audit ran no Gradle task, device test or live-backend action.

## Remaining blockers

1. Restore SMS verification auto-fill if exact 126.21 phone-auth behavior is required.
2. Keep Samsung OAuth recorded as an approved external exception unless project-owned integration is supplied.
3. Exercise credential, MFA, captcha, OAuth and remote-auth flows end to end on device/live backend.
4. Complete the shell lifecycle/configuration/accessibility/device regression matrix.
