# Security review: 2026-08-04

Defensive static review of current `app/src/main/` for Manticord (`dev.mantikafasi.manticord`).

Scope: exported manifest components, all `PendingIntent` creation, deep-link/URI handling, fragment/screen routing, secret and private-content handling, `FileProvider`, downloads/uploads, networking/TLS, WebViews/JavaScript bridges, push/notification workers, Kryo persistence, contact-sync PII, App Actions, and hardcoded reference credentials.

No code was changed. Gradle, device, dynamic WebView tests, live backend, and git commands were not run.

## Counts

| Severity | Confirmed open | Unverified |
| --- | ---: | ---: |
| High | 0 | 0 |
| Medium | 0 | 1 |
| Low | 3 | 0 |

The 2026-07-27 High finding and all three Medium findings are fixed. This report remains because one WebView-origin question needs device confirmation and three low/latent issues remain.

## Confirmed open findings

### L-1: exported `Main` can be used to open an arbitrary allowed HTTPS URL

Files:

- `app/src/main/AndroidManifest.xml:169-244`
- `utilities/intent/DeepLinkParser.kt:18-29,147-160`
- `app/AppActivity.kt:506-509`

An explicit intent to exported `AppActivity$Main` bypasses manifest filters. For a non-Discord HTTPS host, `DeepLinkParser` returns `External` whenever `ExternalUriPolicy` accepts a normal public HTTPS hostname, and `AppActivity` opens it through `UriHandler`.

Impact is low: another app can already open a browser. The gain is UI provenance laundering, making the navigation appear to originate from Manticord. The former fragment-injection primitive is gone; arbitrary extras cannot select a fragment on `Main`.

### L-2: Gateway internal logging still bypasses central redaction

File: `gateway/GatewaySocket.kt:486-489`

Four internal helpers call raw `android.util.Log`. Current callers log state/reason strings rather than payloads, and outbound JSON is separately redacted before `GatewaySocketLogger` at `GatewaySocket.kt:473-483`, so no current credential leak was found.

The transport handles auth tokens and private events. Routing these helpers through `AppLog` would make future call sites fail safe rather than relying on every caller to stay payload-free.

### L-3: dead `file_paths.xml` remains dangerously broad

File: `app/src/main/res/xml/file_paths.xml`

The recovered file still exposes broad external and private paths. It is not referenced by the manifest or code; the only provider uses scoped `@xml/image_share_filepaths` at `AndroidManifest.xml:142-150`. Current exploitation is therefore absent.

Keep this as a latent warning: wiring a future provider to `file_paths.xml` would expose much more than intended. Delete it only if resource-retention policy changes; otherwise preserve the explicit non-use.

## Unverified

### U-1 (Medium?): hCaptcha JavaScript bridge has no navigation allowlist

File: `widgets/captcha/HCaptchaWebDialog.kt:86-105,124-174`

Confirmed safe properties:

- Initial URL is fixed `file:///android_asset/hcaptcha-form.html`.
- Minimum SDK is 21, and every bridge method is annotated `@JavascriptInterface`.
- File/universal access from file URLs remains at the platform default false.
- Mixed-content/TLS settings are not weakened.
- The solved token is redacted and never logged.
- The bridge is removed and WebView destroyed on teardown.

Open question: no `WebViewClient` restricts main-frame navigation. `addJavascriptInterface` is WebView-wide, not origin-bound. Android commonly sends non-file navigations to an external handler when no client is installed, but that platform behavior was not verified on supported devices. If a foreign page can load in place, it can call `getConfig` and forge `onPass`/`onError`.

Do not mark this resolved from static assumptions. A device test should attempt in-place navigation from the asset page; a rejecting `WebViewClient` is the fail-closed fix if behavior varies.

## Historical findings rechecked

| 2026-07-27 finding | Current evidence | Status |
| --- | --- | --- |
| H-1 exported fragment injection/private-file exfiltration | `AppActivity.kt:490-505` ignores `EXTRA_SCREEN` on `Main`; `AppScreenRouter.kt:48-53` targets non-exported base `AppActivity`; legitimate shares require safe `content://` at `ShareContractParser.kt:11-36` | **Fixed** |
| M-1 download filename traversal | `DownloadUtils.kt:35-37,83-90` sanitizes and canonical-confines destination/partial files | **Fixed** |
| M-2 throwable stack trace bypassed redaction | `AppLog.kt:126` redacts `Log.getStackTraceString` | **Fixed** |
| M-3 mutable PendingIntent flag returned zero on API 31+ | `PendingIntentExtensions.kt:29-34` adds `FLAG_MUTABLE` from API 31; focused test exists | **Fixed** |
| L-2 AppAction copied `EXTRA_SCREEN` into Main | Main ignores screen extras, so forwarding no longer creates fragment injection | **Fixed as a security primitive** |
| L-3 dynamic `@Url` clients could leak auth | Live `RtcLatency` client is built without interceptors at `StoreStream.kt:940-948`; `Dynamic` and `Files` have no production construction | **Fixed/currently unreachable** |
| L-4 raw OAuth `ACTION_VIEW` | OAuth completion uses `UriHandler.handle` at `WidgetOauth2Authorize.kt:200-215,261-267` | **Fixed** |
| L-6 MFA ticket/phone token persisted in fragment args | `AuthUi.kt:67-103` stores only an opaque credential handle in arguments; credentials stay in process memory | **Fixed** |
| U-1 Discord-domain regex `$` behavior | Regex was removed; trust uses exact set or `.discord.com`/`.discordapp.com` suffix at `UriHandler.kt:151-156` | **Fixed** |

## Exported components

Exactly three components are exported:

1. `AppActivity$Main` (`AndroidManifest.xml:169-244`): required for launcher/deep links. It refuses internal screen extras and routes only typed `AppIntentContract` input.
2. `AppActionActivity` (`:258-284`): required for SEND/SEND_MULTIPLE and SDK intents. Shares require `content://` with host and no userinfo; SDK URIs require `discord|manticord://sdk/...` and typed JSON parsing.
3. `UserDownloadCompleteReceiver` (`:318-324`): required for the system DownloadManager broadcast. It accepts only IDs this process registered and re-queries DownloadManager rather than trusting broadcast fields.

No unjustified exported service/provider was found. Notification, voice, overlay, FileProvider, UCrop, embedded activity, call, and base screen components are non-exported.

## PendingIntent review

Nine creation sites currently exist:

- Three notification intents in `NotificationRenderer.kt:188,202,218`.
- Two voice notification intents in `VoiceEngineNotificationBuilder.kt:17-23,74-88`.
- Two stage invite intents in `VoiceEngineForegroundService.kt:327-334`.
- Two overlay notification intents in `DiscordOverlayService.kt:62-74`.

All ordinary intents are explicit and immutable. The direct-reply broadcast is the sole mutable intent, required for `RemoteInput`, targets a non-exported receiver, and validates IDs plus the current auth-session hash. No missing API-31 mutability declaration was found.

Correctness caveat, not a security finding: several voice intents reuse request code `0` and `FLAG_UPDATE_CURRENT`; actions/components distinguish most identities, but same-action channel updates should be regression-tested with simultaneous calls/invites.

## URI and routing review

- `DeepLinkParser` uses `java.net.URI`, rejects userinfo/non-443 ports, and recognizes only explicit internal paths.
- `ExternalUriPolicy` requires public-looking HTTPS hostnames and rejects localhost, `.local`, IPv4 literals, whitespace, backslashes, userinfo, and non-443 ports.
- `UriHandler.isSafeScheme` rejects missing schemes and unsafe schemes before platform parsing.
- Trust checks strip userinfo and require exact first-party hosts/suffixes.
- OAuth completion and ordinary external links use `UriHandler`.
- `AppScreenRouter` screen intents target the non-exported base activity.

Residual L-1 is limited to an explicitly targeted exported `Main` opening an otherwise allowed HTTPS URL.

## File and upload handling

- The live FileProvider is non-exported and uses only `files/image_provider/` and external-files `Pictures/`.
- Legitimate share input rejects `file://`, blank-host content URIs, and userinfo.
- `DownloadUtils` sanitizes and canonical-confines filenames.
- `UserDownloadManager` pins HTTPS and sanitizes server-provided names.
- `Persister` requires a basename key at `Persister.kt:26-33` and reads only app-private files it created. No attacker-controlled Kryo byte source was found.
- Bug-report screenshots are opened from a direct-screen intent, but that intent now reaches only non-exported `AppActivity`; the old cross-UID fragment injection route is closed.

## Network and TLS review

- REST and Gateway clients use default platform TLS; no permissive hostname verifier or trust-all manager exists.
- RTC explicitly pairs its socket factory with the platform default X509 trust manager (`RtcControlSocket.kt:751-754`).
- `network_security_config.xml` keeps cleartext disabled; debug user certificates are active only in debuggable builds.
- No certificate/signature bypass was found.
- The RTC endpoint string still originates from the authenticated Gateway. Its URL normalization should remain covered by transport tests, but no TLS bypass is present.

## WebView review

Two WebView surfaces now exist, not one:

1. `HCaptchaWebDialog`: fixed asset plus remote hCaptcha script; U-1 remains.
2. `EmbeddedActivityWebView`: HTTPS host page, sandboxed iframe, random bridge secret, origin-checked `postMessage`, 64 KiB bridge limit, file/content access disabled, mixed content blocked, safe browsing enabled, WebView debugging disabled, main-frame external navigation intercepted, permission origin/resources checked, and teardown removes the bridge (`EmbeddedActivityWebView.kt:57-92,140-169,203-209,434-492`).

Embedded activity URLs are built, not accepted raw: `EmbeddedActivitySessionManager.kt:205-227` pins HTTPS and `${applicationId}.discordsays.com` through `HttpUrl.Builder`.

## Push, notifications, and workers

- Push types are an allowlisted enum; unknown types fail `PushPayload.isValid` (`NotificationData.kt:24-37,108-124`).
- `PushPayload.toString()` redacts all fields (`:126`).
- Sticker JSON parsing is bounded to Gson parsing and does not construct intents/classes/paths.
- Notification actions use explicit non-exported receivers and auth-session hashes.
- Mark-read/ack workers validate positive IDs and current auth generation/session before invoking actions.
- Direct-reply worker validates authentication, connectivity, current auth session, channel ID, blank content, and 2,000-character limit (`MessageSendWorker.kt:35-64`).
- WorkManager persists direct-reply content and an auth-session hash in its private database until completion. This is private user content at rest, not a token; backup is disabled. It is noted, not raised as a vulnerability.

## Contact-sync PII

- Provider failures log exception class names only (`ContactsProvider.kt:67,91`).
- Store/view-state `toString()` redacts phone numbers (`WidgetContactSyncViewModel.kt:132,175-176`).
- Analytics accepts booleans/counts and explicitly avoids phone numbers/contact names (`ContactSyncAnalytics.kt:21`, `ContactSyncFlowAnalytics.kt:89-99`).
- No phone number, e-mail address, or contact name was found entering `AppLog` or analytics payloads.

## App Actions and external entry metadata

`res/xml/actions.xml` contains only HTTPS Discord fulfillment templates for join/select/create/open-feature actions. The manifest wires the App Actions metadata and shortcut metadata. Resulting URLs still pass the same typed deep-link parser before navigation.

## Secrets and credentials

- No recovered Google/Firebase key, client secret, sender ID, Adjust token, or hardcoded Discord credential was found under `app/src/main` using the targeted patterns.
- `AppLog` centrally redacts sensitive key/value/JSON/auth-header forms and throwable strings.
- Auth, MFA, OAuth, Gateway, captcha, notification, contact-sync, and purchase-token models reviewed here redact their `toString()` values.
- Push token logging uses only `hashCode`.
- Auth token persistence remains app-private SharedPreferences with backup/device transfer excluded; this matches the intended app class and is not newly raised.

## Remaining verification gaps

1. Device-test hCaptcha navigation/origin behavior and close U-1 only with observed platform evidence or a rejecting `WebViewClient`.
2. Exercise all nine PendingIntents on API 21, 23, 31, and current Android, including simultaneous voice/stage notifications.
3. Run a focused exported-component fuzz test with malformed Bundles, parcelables, URLs, and SEND payloads.
4. Run repository secret scanning, including tests/fixtures and generated packaging inputs; this pass used targeted source patterns only.
5. Exercise embedded activity bridge/origin/permission behavior with a controlled test activity and renderer crash.

## Top remaining blockers

1. U-1 hCaptcha WebView origin/navigation behavior.
2. Full exported-intent and PendingIntent device matrix.
3. Centralize Gateway internal logs through `AppLog`.
4. Repository-wide secret scan beyond `app/src/main`.
