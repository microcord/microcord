# Security review — 2026-07-27

Defensive review of `app/src/main/` in this project (Manticord, `dev.mantikafasi.manticord`).
Scope as briefed: exported manifest components, every `PendingIntent`, deep-link/URI handling,
secret handling, `FileProvider` paths, network/TLS construction, `WebView` usage, and hardcoded
credentials recovered from the reference APK.

**Every finding below was reproduced by opening the cited file.** Findings I could not fully
reproduce from the code in front of me are in the UNVERIFIED section and are labelled as such.
`docs/parity-audit/README.md` records that three earlier agent reports in this project were
confidently wrong; this one quotes the line it is claiming about.

Gradle was not run and no git command was issued (coordinator-only constraint).

## Counts

| Severity | CONFIRMED | UNVERIFIED |
| --- | --- | --- |
| High | 1 | 0 |
| Medium | 3 | 1 |
| Low | 7 | 1 |

One fix applied (listed at the end). Everything else is report-only.

---

# CONFIRMED

## H-1 (High) — Fragment injection via the exported `AppActivity$Main`, exploitable into private-file exfiltration

**Files**
- `app/src/main/AndroidManifest.xml:156-163` — `com.discord.app.AppActivity$Main`, `android:exported="true"`, `android:launchMode="singleTask"`
- `app/src/main/java/com/discord/app/AppActivity.kt:404-411` — `consumeIntent`
- `app/src/main/java/com/discord/app/AppActivity.kt:429-441` — `showScreen`
- `app/src/main/java/com/discord/app/AppScreenRouter.kt:55-56` — `screenOf`
- `app/src/main/java/com/discord/widgets/share/WidgetIncomingShare.kt:46-53, 84-95, 190-201`

**The code**

`AppScreenRouter.screenOf` takes the fragment class straight off the intent:

```kotlin
fun screenOf(intent: Intent?): Class<out Fragment>? =
    intent?.getSerializableExtra(EXTRA_SCREEN) as? Class<out Fragment>
```

`AppActivity.consumeIntent` honours it unconditionally, on every intent, from any source:

```kotlin
AppScreenRouter.screenOf(intent)?.let { screen ->
    showScreen(screen, intent)
    return
}
```

and `showScreen` instantiates it with the intent's own extras as fragment arguments:

```kotlin
val fragment = supportFragmentManager.fragmentFactory
    .instantiate(classLoader, screen.name)
    .apply { arguments = intent.extras }
```

`AppActivity$Main` is exported, so any installed app can start it explicitly with
`setClassName("dev.mantikafasi.manticord", "com.discord.app.AppActivity\$Main")` — no permission,
no user-granted capability. `EXTRA_SCREEN` is a `Serializable` `Class`; Java serialization records
only the class *name*, so an attacker declaring a same-FQN stub in their own APK and passing
`WidgetIncomingShare::class.java` deserializes in our process to *our* class.

**The attack**

`WidgetIncomingShare` reads its attachment list straight out of `arguments` with no validation of
scheme or origin:

```kotlin
content = ShareUtils.SharedContent(
    arguments?.getString(ARG_TEXT),
    arguments?.getString(ARG_SUBJECT),
    arguments?.getStringArrayList(ARG_URIS).orEmpty().map(Uri::parse),
    ...
)
```

and `send()` hands those URIs to the upload handler, which ends at
`AttachmentRequestBody.kt:29` → `contentResolver.openInputStream(uri)` — running as this app's UID.

So a third-party app with no permissions can post:

```
component  = dev.mantikafasi.manticord/com.discord.app.AppActivity$Main
extra      = com.discord.intent.extra.EXTRA_SCREEN → com.discord.widgets.share.WidgetIncomingShare
extra      = "uris" → ["file:///data/data/dev.mantikafasi.manticord/shared_prefs/<prefs>.xml"]
extra      = "text" → "hi"
```

The victim sees what looks like the ordinary share sheet, picks a channel, taps send — and the
app's own private preferences file, which holds `STORE_AUTHED_TOKEN` (see
`stores/authentication/AuthStateCache.kt:25`), is uploaded to a Discord channel the attacker can
read. One user tap; no permission; no visible anomaly.

Reachability is reliable on a **warm** process: `onNewIntent` → `consumeIntent` → `showScreen`
commits with `addToBackStack`, and `showRoute` does not re-run because `observeMainRoute()` is a
`BehaviorSubject` behind `distinctUntilChanged` (`StoreNavigation.kt:55, 61`) — no route change,
no pop. On a cold start `onStart`'s `showRoute` may pop the injected transaction first; the attack
does not need the cold path.

Note that this is the *general* primitive: any fragment in the app can be shown with attacker-chosen
arguments (`WidgetAuthLogin` for credential phishing on top of the real app, settings screens,
`WidgetTosReportViolation`, the media viewer). `WidgetIncomingShare` is simply the most damaging
instance found.

Secondary issue in the same line: `getSerializableExtra` on a cross-UID `Bundle` deserializes
attacker-chosen `Serializable` graphs inside our process, which is its own gadget surface.

**Fix.** The manifest already declares the non-exported base activity
(`AndroidManifest.xml:150-155`, `com.discord.app.AppActivity`, `exported="false"`). Route screen
intents there instead: change `AppScreenRouter.createScreenIntent` to
`.setClass(context, AppActivity::class.java)` and make the exported `Main` subclass ignore
`EXTRA_SCREEN` entirely (e.g. `consumeIntent` calls `screenOf` only when `this !is Main`). Belt and
braces: replace the `Class`-valued extra with a `String` key checked against a hardcoded allowlist
of routable screens, so a non-routable fragment can never be named. Separately,
`WidgetIncomingShare` should reject any argument URI whose scheme is not `content`, reusing
`ShareContractParser.isSafeAttachment`.

Out of scope for me to apply (`widgets/**` is contended, and this is a structural change, not one
of the three sanctioned fix classes).

---

## M-1 (Medium) — Unsanitised `fileName` in `DownloadUtils.downloadFile` is a path-traversal primitive

**File** `app/src/main/java/com/discord/utilities/file/DownloadUtils.kt:28-29`

```kotlin
val destination = File(downloadDirectory, fileName)
val partial = File(downloadDirectory, "$fileName.part")
```

`fileName` is a plain `String` joined into a directory with no sanitisation. A value of
`../shared_prefs/dev.mantikafasi.manticord_preferences.xml` (or `../databases/...`) escapes
`cacheDir` and lets a remote response body overwrite arbitrary app-private files — a
remote-content-controlled write into the app's own data dir.

**Not currently exploitable.** The only caller is `utilities/stickers/StickerUtils.kt:59, 64`,
which builds `"${sticker.getStickerId()}${sticker.getFileExtension()}"` — a `Long` plus an
enum-derived extension. Reported as Medium rather than Low because this is a `@JvmStatic`
general-purpose utility whose reference counterpart is used for *attachment* downloads, where the
filename is server-supplied; the next caller added is very likely to be the unsafe one.

**Fix.** Route `fileName` through the sanitiser this project already has —
`com.discord.utilities.io.sanitizeDownloadFileName` (`utilities/io/UserDownloadManager.kt:110-118`),
which strips path separators, control characters and leading dots — and assert
`destination.canonicalPath.startsWith(downloadDirectory.canonicalPath)` before writing.

---

## M-2 (Medium) — `AppLog` printed exception stack traces without redaction  — **FIXED**

**File** `app/src/main/java/com/discord/app/AppLog.kt:111` (before the fix)

`AppLog.log` redacts the message (`val safeMessage = redactSecrets(message)`), then printed the
throwable raw:

```kotlin
throwable?.let { println(priority, Log.getStackTraceString(it)) }
```

`println` does not redact. An exception message routinely quotes what failed — a malformed auth
response body, a request URL carrying a token query parameter, a Gson `JsonSyntaxException` echoing
the payload — so any secret arriving via the throwable path bypassed the entire `redactSecrets`
design. `WidgetDebugging.kt:204-205` had already noticed and fixed this for the in-app log screen;
logcat had not been covered.

Severity Medium not High because logcat is not readable cross-app from API 16+; the exposure is to
`adb logcat`, bug-report captures and any diagnostic upload.

**Applied fix** (in my file-ownership per the brief's "a secret reaching a log call — redact it"):

```kotlin
throwable?.let { println(priority, redactSecrets(Log.getStackTraceString(it))) }
```

`AppLog.LoggedItem` still carries the unredacted `Throwable` object, which is correct — its only
renderer (`logRowText`) already redacts.

---

## M-3 (Medium) — `mutablePendingIntentFlag` throws on API 31+; its documented rationale is wrong

**File** `app/src/main/java/com/discord/utilities/extensions/PendingIntentExtensions.kt:29-37`

```kotlin
/**
 * ... Deliberately does *not* set `FLAG_MUTABLE`, matching the reference: only a receiver that fills
 * in the intent ... needs mutability, and on those platforms the absence of `FLAG_IMMUTABLE`
 * already means mutable. ...
 */
fun mutablePendingIntentFlag(flags: Int = 0): Int = flags
```

From API 31 a `PendingIntent` must state its mutability explicitly or `PendingIntent.getX` throws
`IllegalArgumentException`; "absence of `FLAG_IMMUTABLE` already means mutable" stopped being true
at exactly the version this helper exists for. `app/build.gradle.kts` targets well past 31, so any
future call site crashes.

**It has zero call sites today** — verified by grepping `PendingIntent.get`,
`mutablePendingIntentFlag` and `immutablePendingIntentFlag` across `app/src`: the only
`PendingIntent` constructions in the whole tree are the three in `NotificationRenderer.kt:153, 162,
177`, which use that class's own correct local helpers. So this is a latent trap, not a live defect.

**Fix.** Either delete the file (dead code — `NotificationRenderer` does not use it) or make it
`if (SDK_INT >= 31) flags or PendingIntent.FLAG_MUTABLE else flags`. Not applied: the sanctioned fix
class is "a *missing* `FLAG_IMMUTABLE`", and no live `PendingIntent` is missing one.

---

## L-1 (Low) — Exported `AppActivity$Main` will open any attacker-supplied `https` URL

**Files** `app/src/main/java/com/discord/app/AppActivity.kt:412-413`,
`stores/StoreNavigation.kt:84-95`, `utilities/intent/DeepLinkParser.kt:25-28`

`consumeIntent` routes `IntentRouteResult.External` to `UriHandler.handle(this, result.uri)`. The
manifest's `VIEW` filters only advertise Discord hosts, but an *explicit* intent to the exported
`Main` bypasses filters entirely, so a third-party app can hand the activity any `https://` URL that
passes `ExternalUriPolicy.isAllowed` and have it opened in a Custom Tab under Discord's chrome.

Low because a hostile app can open a browser itself; the only gain is the appearance that Discord
navigated there. Worth noting alongside H-1 because both stem from `Main` consuming intent payloads
it was never meant to receive from outside the process.

## L-2 (Low) — `AppActionActivity` forwards a foreign intent verbatim into `AppActivity$Main`

**File** `app/src/main/java/com/discord/app/AppActionActivity.kt:21-25`

```kotlin
is AppIntentContract.SdkAction -> startActivity(
    Intent(intent)
        .setClass(this, AppActivity.Main::class.java)
        ...
)
```

`Intent(intent)` copies every extra — including an attacker's `EXTRA_SCREEN` — and re-delivers it
to `Main`. This is a second route into H-1 rather than an escalation on its own (the attacker can
target `Main` directly), but it means "fix `Main`" is the only complete fix; hardening
`AppActionActivity`'s filters would not close it.

The `SEND`/`SEND_MULTIPLE` half of this activity is **clean**: `ShareUtils.getSharedContent` →
`ShareContractParser.parse` → `isSafeAttachment` (`ShareContractParser.kt:32-35`) requires
`scheme == "content"` with a non-blank host and no userinfo, so `file://` exfiltration through the
legitimate share entry point is blocked. That validation is exactly what H-1 skips.

## L-3 (Low) — `Retrofit @Url` interfaces would attach the auth token to an arbitrary host

**Files** `app/src/main/java/com/discord/restapi/RestAPIInterface.kt:1716-1732`,
`restapi/RequiredHeadersInterceptor.kt:16-26`, `stores/StoreStream.kt:2488-2490`

`RestAPIInterface.Dynamic`, `.Files` and `.RtcLatency` all declare `@GET fun get(@Url url: String)`.
`RequiredHeadersInterceptor` unconditionally sets `header("Authorization", token)` on every request
of whatever client it is installed on. If any of these three is ever built through
`RestAPIBuilder.build(..., interceptors = listOf(..., RequiredHeadersInterceptor(headers)))` — which
is how the one live client is built at `StoreStream.kt:2488` — then fetching a CDN/attachment URL
that came out of a chat message sends the user's Discord token to an attacker-chosen host.

**Latent only:** grepping `RestAPIInterface.Dynamic|Files|RtcLatency` across `app/src` returns no
matches — none of the three is constructed anywhere yet.

**Fix.** Build the `@Url` clients from a `RestAPIBuilder` with `interceptors = emptyList()`, or gate
`RequiredHeadersInterceptor` on `chain.request().url.host` being a Discord host.

Redirect leakage is *not* a concern here: `gradle/libs.versions.toml:36` pins OkHttp 4.8.0, which
strips `Authorization` on a cross-host redirect.

## L-4 (Low) — Raw `ACTION_VIEW` in the OAuth flow bypasses `UriHandler`'s scheme filter

**File** `app/src/main/java/com/discord/widgets/auth/WidgetOauth2Authorize.kt:196-202`

```kotlin
val redirect = Uri.parse(response.location)
AppLog.d("Redirect OAuth flow to: ${redirect.scheme}://${redirect.host}")
startActivity(Intent(Intent.ACTION_VIEW, redirect).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
```

This is the last raw `ACTION_VIEW` in the tree (the other four were already routed through
`UriHandler`, per `followups.md` → "CLOSED"). `response.location` is server-supplied and carries a
freshly minted authorization `code`, so a redirect with a scheme `UriHandler.UNSAFE_SCHEMES` would
reject (`intent:`, `android-app:`, `file:`) is launched without that check. The value comes from
Discord's own server, which validates `redirect_uri` against the registered application, so this is
Low.

Positive note: the log line here is a deliberate improvement over the reference, which logs the
whole redirect including the `code`. Keep it.

## L-5 (Low) — `GatewaySocket` logs through `android.util.Log`, bypassing `AppLog.redactSecrets`

**File** `app/src/main/java/com/discord/gateway/GatewaySocket.kt:471-474`

```kotlin
private fun logInfo(message: String) { runCatching { Log.i(TAG, message) } }
```

None of the current call sites (`:117-422`) logs a payload body — `IDENTIFY`/`RESUME` are logged as
bare event names and `IdentifyData.toString()` is redacted (`:54`). But this is the transport that
carries the auth token, and it is the one place in the app that logs outside the central redaction
pass. **Fix:** route these four through `com.discord.app.AppLog`.

## L-6 (Low) — MFA ticket and phone token live in fragment arguments, which the system persists

**Files** `app/src/main/java/com/discord/widgets/auth/AuthUi.kt:45-47`,
`app/src/main/java/com/discord/app/AppActivity.kt:480`

`AuthUiState` carries `phone`, `phoneToken` and `ticket`, and `AppActivity.navigate` sets it as
fragment arguments (`arguments = AuthFragment.arguments(state)`). Fragment arguments are written
into the saved instance state the system persists to disk for process death, so a short-lived MFA
ticket ends up on disk in the activity record.

`AuthUiState.toString()` is correctly redacted (`AuthUi.kt:66-72`), and `allowBackup="false"` plus
the exclude-everything `backup_rules.xml` / `data_extraction_rules.xml` keep it off cloud backup.
Low, and it matches the reference; noted because it is where a credential lands unencrypted.

## L-7 (Low) — `res/xml/file_paths.xml` exposes all of external storage and the whole `files/` dir

**File** `app/src/main/res/xml/file_paths.xml:3-6`

```xml
<external-path name="Discord" path="."/>
<external-files-path name="Discord-Files" path="."/>
<files-path name="files" path="."/>
```

`files-path path="."` is the app's entire private `files/` directory.

**Currently harmless — verified:** the only `<provider>` in the manifest
(`AndroidManifest.xml:136-144`) points at `@xml/image_share_filepaths`, and grepping `file_paths`
across `app/src` finds no other reference. It is dead recovered configuration. It is reported
because it is a loaded gun sitting next to the safe file: any future provider that points at it
inherits full private-dir exposure.

`res/xml/image_share_filepaths.xml` itself is **clean** — `files-path path="image_provider/"` and
`external-files-path path="Pictures/"`, both properly scoped, with an in-file comment explaining
why `file_paths.xml` was not reused.

---

# UNVERIFIED

## U-1 (Medium?) — `DISCORD_DOMAINS_REGEX` may be bypassable via Java's end-of-input `$`

**File** `app/src/main/java/com/discord/utilities/uri/UriHandler.kt:73, 156`

```kotlin
private val DISCORD_DOMAINS_REGEX = Regex("(?:^|\\.)(?:discordapp|discord)\\.com$")
...
IMPLICITLY_TRUSTED_HOSTS.contains(host) || DISCORD_DOMAINS_REGEX.containsMatchIn(host)
```

The `(?:^|\.)` prefix correctly defeats the classic `evildiscord.com` bypass — I checked this and it
does **not** match, so the brief's `endsWith` concern does not apply here.

What I could not settle: Java's `$` without `MULTILINE` matches at end of input *or immediately
before a final line terminator*. A host string containing a trailing `\n` (e.g.
`"x.discord.com\n"`) would therefore satisfy the anchor. I could not construct a real URL that makes
`UriHandler.getDomainName` (`:315-324`, which splits only on `://`, `/?#`, `@`, `:`) return such a
host and still reach a live call site, so I am **not** asserting this is exploitable.

If it is, the impact is bounded: `isTrustedDomain` gates only the "this link may not go where it
says" confirmation dialog (`handleOrUntrusted`, `:132-140`), so a bypass suppresses a warning — it
does not change what is opened.

**Suggested hardening regardless:** use `\z` instead of `$`, and prefer `matches` over
`containsMatchIn`.

## U-2 (Low?) — `HCaptchaWebDialog` binds a JavaScript interface with no `WebViewClient`

**File** `app/src/main/java/com/discord/widgets/captcha/HCaptchaWebDialog.kt:75-81`

```kotlin
web.settings.javaScriptEnabled = true
web.settings.domStorageEnabled = true
web.addJavascriptInterface(Bridge({ loading.visibility = View.GONE }), BRIDGE_NAME)
web.loadUrl(FORM_URL)   // "file:///android_asset/hcaptcha-form.html"
```

Confirmed clean parts: the URL is a fixed app-asset constant, never attacker-supplied; `minSdk` is
21 so the pre-17 `addJavascriptInterface` RCE does not apply and the `@JavascriptInterface`
annotations are present; `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` are left
at their API-16+ default of `false`, so the remotely-loaded hCaptcha script cannot read local files;
the solved token is never logged (`Outcome.Solved.toString()` is `"Solved(token=<redacted>)"`,
`:52`) and `onError` logs only an integer code (`:145`); `removeJavascriptInterface` + `destroy()`
on teardown (`:84-92`).

What I could not settle: no `WebViewClient` is set, so I could not determine from the code alone
whether an in-page navigation away from `file://` would keep `JSInterface` bound to a foreign
origin. `addJavascriptInterface` binds to the WebView, not the origin. With no `WebViewClient`,
Android's default behaviour is to hand non-`file` navigations to an external browser Intent rather
than load them in place — which would make this a non-issue — but that is a platform behaviour I did
not verify on device, and it is what the finding turns on. Exposure would in any case be limited to
`getConfig` (leaks the hCaptcha site key and `rqdata`) and forging an `onPass`/`onError`.

**Suggested hardening:** set a `WebViewClient` whose `shouldOverrideUrlLoading` refuses anything
outside `file:///android_asset/` and the hCaptcha origins, so the bridge can never be reached from
elsewhere.

---

# Checked and found clean — do not redo

**Manifest** (`app/src/main/AndroidManifest.xml`) — three exported components, all reviewed:
- `AppActivity$Main` (`:156`) — needs `exported` for `MAIN`/`LAUNCHER`. Its *filters* are fine; the
  defect is what it does with intent payloads (H-1, L-1).
- `AppActionActivity` (`:232`) — needs `exported` for `SEND`/`SEND_MULTIPLE`. Its `SEND` path is
  properly validated (see L-2). Its `discord://sdk` / `manticord://sdk` handler is validated by
  `ShareContractParser.isSdkUri` (`:27-30`), which pins scheme ∈ {discord, manticord}, host == "sdk"
  and an absolute path; the `EXTRA_ACTIVITY` JSON blob is parsed inside `runCatching` and only
  yields a typed `Activity` (`AppIntentParser.kt:66-73`).
- `UserDownloadCompleteReceiver` (`:291`, implemented at
  `utilities/io/UserDownloadManager.kt:97-103`) — **the manifest's claimed mitigation holds, verified
  line by line.** `onReceive` checks `intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE`,
  reads only `EXTRA_DOWNLOAD_ID`, and `handleCompletion` begins
  `val callback = callbacks.remove(requestId) ?: return` — an id this process did not enqueue is
  dropped before any work — then re-queries `DownloadManager` for the real status rather than
  trusting anything on the intent. A spoofed broadcast can at worst resolve a download we started.
- `NotificationActionReceiver` (`:274`) is correctly `exported="false"` despite carrying an
  intent-filter; `androidx.core.content.FileProvider` (`:136`) is `exported="false"` with
  `grantUriPermissions="true"`; `UCropActivity` (`:264`) is `exported="false"`.
- No `android:exported="true"` anywhere that lacks a reason, so no exported flag was changed.
- No `usesCleartextTraffic`, no `android:debuggable`, no debug source-set manifest overlay.
- `PACKAGE_USAGE_STATS` and `SYSTEM_ALERT_WINDOW` are special-access, not silently granted.

**PendingIntent** — exhaustively enumerated (`PendingIntent.get`, `FLAG_MUTABLE`, `FLAG_IMMUTABLE`,
and both helper names across all of `app/src`). Exactly three constructions exist, all in
`utilities/notifications/NotificationRenderer.kt`:
- `:153` `getActivity` and `:162` `getBroadcast` use `immutableFlags()` (`:190-191`,
  `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` from API 23). Correct.
- `:177` `getBroadcast` for the `RemoteInput` reply uses `mutableReplyFlags()` (`:204-205`,
  `FLAG_UPDATE_CURRENT or FLAG_MUTABLE` from API 31). Mutability is genuinely required by
  `RemoteInput`, and the blast radius is bounded exactly as the KDoc claims: the intent names its
  component explicitly and `NotificationActionReceiver` is not exported, so the holder cannot
  redirect it; every field is re-validated by `NotificationActionValidator` and checked against the
  live auth generation (`NotificationActionReceiver.kt:24-33`) before any work is enqueued.
- Nothing in the tree is missing `FLAG_IMMUTABLE`. No PendingIntent fix was needed.
- `StoreNavigation.getPendingIntent` / `WidgetTabsHost` / `AppActivity` matches are the store's own
  "queued deep link" concept, not `android.app.PendingIntent`.

**Host matching** — `DeepLinkParser.isDiscordHost` (`utilities/intent/DeepLinkParser.kt:113-115`)
uses `endsWith(".discord.com")` **with the leading dot** plus exact-match arms, so
`evildiscord.com` does not match. `ExternalUriPolicy.isAllowed` (`:130-139`) additionally requires
`https`, non-opaque, no userinfo, port ∈ {-1, 443}, rejects `localhost`, `.local`, bare IPv4 literals,
whitespace and backslashes. `UriHandler.getDomainName` (`:315-324`) strips userinfo before the host,
which defeats `https://discord.com@evil.com/`. `UriHandler.isSafeScheme` (`:163-179`) rejects
schemeless input and `javascript`/`vbscript`/`data`/`file`/`content`/`intent`/`android-app` using a
hand-rolled scheme parser rather than `Uri.parse`'s lenient one. This surface is in good shape.

**Networking / TLS** — every OkHttp construction reviewed:
`restapi/RestAPIBuilder.kt:30-36` (default TLS, no custom factory),
`utilities/websocket/GatewayWebSocket.kt:27` (bare `OkHttpClient()`),
`rtcconnection/socket/RtcControlSocket.kt:255-260`. The last one calls
`clientBuilder.sslSocketFactory(factory, platformTrustManager())`, and `platformTrustManager()`
(`:556-559`) is the **platform default** `TrustManagerFactory.getDefaultAlgorithm()` — not a
custom or permissive one. The factory it pairs with is `SSLSocketFactory.getDefault()`
(`rtcconnection/VoiceTransport.kt:494`). **No `TrustManager`, `HostnameVerifier`, certificate pin
bypass or signature check bypass anywhere in the tree.** Nothing was weakened.

**`res/xml/network_security_config.xml`** — `cleartextTrafficPermitted="@bool/use_cleartext_traffic"`,
and `res/values/bools.xml:11` sets that to `false` with no qualifier override (grepped). The
`<debug-overrides><certificates src="user"/>` block applies only when `android:debuggable=true`;
no build type sets it and there is no debug manifest overlay, so it cannot ship active. Acceptable
as-is.

**FileProvider paths** — see L-7. `image_share_filepaths.xml` (the only one actually wired) is
correctly scoped. `backup_rules.xml` and `data_extraction_rules.xml` both exclude
`root`/`file`/`database`/`sharedpref`/`external`, and `allowBackup="false"`.

**Secret handling** — the redaction discipline in this project is genuinely good and I found no
second leak beyond M-2:
- `AppLog.redactSecrets` (`app/AppLog.kt:122-164`) masks 18 sensitive keys in `key=value`,
  `key: value` and `"key":"value"` form plus bare `Bearer`/`Bot`/`Basic` values, applied
  unconditionally in `log()`.
- Redacted `toString()` verified on: `AuthState` (`models/authentication/AuthState.kt:9`),
  `MeUser` (`models/user/MeUser.kt:49-54`, redacts email, token and phone),
  `Identify`/`Resume`/`IdentifyClientState` (`gateway/io/GatewayPayloads.kt:44, 57, 65`),
  `IdentifyData` (`gateway/GatewaySocket.kt:54`), the whole MFA family
  (`api/auth/mfa/MfaModels.kt:13, 20, 28, 44, 51`), `VoiceServer`, `AuthUiState` and all 13
  `AuthAction` subclasses (`widgets/auth/AuthUi.kt:72-125`), `OAuth2Authorize` (redacts
  `codeChallenge`), and ~30 `RestAPIParams` bodies carrying passwords, MFA codes, captcha keys,
  purchase tokens and e-mail.
- `HCaptchaWebDialog.Outcome.Solved.toString()` = `"Solved(token=<redacted>)"`.
- `NotificationClient.kt:130` logs only `token?.hashCode()`, never the push token.
- Analytics: `AnalyticsTracker` sends no credential; `appExceptionThrown` sends the stack trace only,
  and `friendRequestFailed` (`:419-424`) deliberately sends `query_length` instead of the reference's
  raw query. The sink is `AnalyticsSink.Disabled` by default, so nothing leaves the device at all.
- Auth token at rest: `stores/authentication/AuthStateCache.kt:25` writes it to SharedPreferences in
  the clear. Standard for this app class, and backup/device-transfer are both excluded. Not raised
  as a finding, recorded so the next reviewer does not re-derive it.

**Hardcoded credentials from the reference APK** — none found.
`utilities/billing/GooglePlayInAppSku.kt` **remediation holds**: `skuId` defaults to `null` (`:23`)
and the four products at `:59-78` resolve through `InAppSkuConfig.skuIdFor`, whose backing map is
`emptyMap()` until `installSkuIds` is called by Manticord-owned configuration (`:33-49`) — it fails
closed. Grepped for `AIza`, Firebase/GCM/Google API keys, `default_web_client_id`, and for
`(siteKey|clientId|clientSecret|apiKey|appId|senderId|adjustToken) = "<8+ chars>"` across
`app/src/main/java`: zero hits. The only Firebase/Adjust mentions are comments explaining that those
identifiers were deliberately *not* reused (`utilities/analytics/AnalyticsTracker.kt:30-34`,
`utilities/fcm/NotificationClient.kt:21-23`, `stores/StoreDynamicLink.kt:28`).

**Other checked-clean spots**
- `utilities/persister/Persister.kt:28` — `require(key == File(key).name && key != "." && key != "..")`
  correctly blocks traversal in persisted-value keys.
- `utilities/io/UserDownloadManager.kt:105-118` — `isValidDownloadUrl` pins `https` + non-blank host;
  `sanitizeDownloadFileName` strips separators, non-printables and leading dots and bounds the length.
- `utilities/intent/ShareContractParser.kt:17-19` — rejects `SEND` with multiple streams and any
  attachment that is not `content://`.
- `stores/StoreStream.kt:1936` — the file delete is guarded by
  `path.startsWith(context.cacheDir.absolutePath)`.
- `AppMediaPicker` / `MediaPicker` / `WidgetChatInput` all go through
  `FileProvider.getUriForFile` with `packageName + ".fileprovider"`, never a raw `file://`.
- `res/xml/shortcuts.xml` declares one `<share-target>` at `AppActionActivity` with no extra
  capability beyond the manifest filter.
- OkHttp 4.8.0 / Retrofit 2.9.0 (`gradle/libs.versions.toml:36, 39`) — no known
  Authorization-on-redirect leak.
- Only one `WebView` exists in the whole tree (`HCaptchaWebDialog` + `res/layout/hcaptcha_fragment.xml`);
  no other `setJavaScriptEnabled`, `addJavascriptInterface`, `setAllowFileAccess` or
  `loadDataWithBaseURL` call site.

---

# NOT COVERED — resume here

This review was wound down before the tree was exhausted. The list is ordered by expected value, so
the next session can continue rather than restart. Nothing below was examined; absence from the
findings above means **not looked at**, not **clean**.

1. **Enumerate which fragments are dangerous when injected via H-1.** This is the single highest
   value item and it is what makes H-1 either a one-screen bug or a general primitive. I found
   `WidgetIncomingShare` by following the share path; I did not sweep the other ~130 widget files
   for the same shape. The pattern to grep is a fragment that reads a `Uri`, a URL, a class name or
   an id out of `arguments` and acts on it without validating provenance. **Specifically flagged and
   NOT verified:** `widgets/bugreports/WidgetBugReport.kt:183` does
   `resolver.openInputStream(uri)?.use { it.readBytes() }` — if that `uri` also originates in
   `arguments`, it is a second exfiltration path with the same reach as H-1's, and possibly one that
   needs fewer user taps. Check it first.
2. **Push/FCM payload handling** — `utilities/fcm/NotificationClient.kt`,
   `utilities/notifications/NotificationWorkers.kt`, `NotificationData.kt`. I reviewed the *render*
   and *action-receiver* sides only. The parse side — what a hostile or spoofed push payload can put
   into a notification, and whether any of it reaches an `Intent`, a `Uri` or a log — is unreviewed.
3. **`workers/**` bodies** — `MessageAckWorker`, `BackgroundMessageSendWorker`,
   `NotificationMarkReadWorker`, `NotificationReplyWorker`. I verified the *enqueue* side in
   `NotificationActionReceiver` (validated, auth-generation checked) but did not read what the
   workers do with their input `Data`. WorkManager input is persisted to disk and replayed.
4. **Kryo / `Persister` deserialization** — `utilities/persister/Persister.kt`. I checked its key
   sanitisation (clean) but not what it deserializes or from where. Kryo on attacker-influenceable
   bytes is a known gadget surface; worth confirming the persisted blobs are only ever written by
   this process.
5. **Contact sync PII** — `widgets/contact_sync/ContactsProvider.kt` and
   `WidgetContactSyncViewModel.kt` (which carries `phoneNumber`, `:161`). Whether harvested phone
   numbers reach a log, a `toString`, or the analytics payload is unchecked. `AppLog`'s redaction
   key list does **not** include `phone` or `phoneNumber`, so a leak here would not be caught by the
   central pass — that is a concrete reason to look.
6. **`res/xml/actions.xml`** — Google App Actions fulfillment, wired from `AndroidManifest.xml:300-302`.
   Not read. App Actions are an externally-triggerable entry point and belong in the exported-surface
   analysis.
7. **`utilities/rest/**` and `utilities/messagesend/**` bodies** — I traced
   `AttachmentRequestBody.kt:29` only as far as H-1 required. Upload path, retry/replay behaviour and
   error bodies are unreviewed.
8. **Gson type adapters** — `models/deserialization/**`, `TypeAdapterRegistrar`. Custom adapters
   parsing server JSON are worth a pass for anything that constructs a `Uri`, a file path or a class
   name from wire data.
9. **`rtcconnection/**` beyond TLS and logging.** Note one thing I saw but did not pursue:
   `socket/RtcControlSocket.kt:264-265` builds the socket URL by string-rewriting a
   **server-supplied** endpoint (`endpoint.replace(".gg", ".media")` then `.replace(":80", ":443")`).
   That is substring replacement on an untrusted host string, not URL parsing. Probably benign since
   the endpoint comes from the authenticated gateway, but it is exactly the shape that goes wrong.
10. **`proguard-rules.pro`** — not read. `isMinifyEnabled = false` on release
    (`app/build.gradle.kts:25`) is itself worth a note: no obfuscation and no shrinking ships.
11. **`app/src/test/` and `app/src/androidTest/`** — outside the briefed scope (`app/src/main/`), but
    `PLAN.md` requires fixtures to be sanitised. A pass for real tokens/accounts in committed
    fixtures is cheap and worth doing.
12. **On-device confirmation of the two UNVERIFIED items** (U-1 regex `$` end-of-input behaviour,
    U-2 default `WebView` navigation with no `WebViewClient`). Both need a runtime check, not more
    reading — that is precisely why they are not asserted above.

---

# Fixes applied

Exactly one, inside the sanctioned "a secret reaching a log call — redact it" class:

| File:line | Change |
| --- | --- |
| `app/src/main/java/com/discord/app/AppLog.kt` (was `:111`, now `:114` after the added comment) | `println(priority, Log.getStackTraceString(it))` → `println(priority, redactSecrets(Log.getStackTraceString(it)))`, with a three-line comment explaining why. Closes M-2. |

Nothing else in the tree was modified. No `android:exported` value was changed (none was
unjustified). No `PendingIntent` flag was changed (none was missing `FLAG_IMMUTABLE`).
