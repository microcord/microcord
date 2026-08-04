# Parity re-audit: `com.discord.utilities`

- Date: **2026-08-04**
- Target: `app/src/main/java/com/discord/utilities/**`
- Reference: Discord Android 126.21 Stable JADX, Vineflower, and dex2jar evidence
- Verification: static source/reference audit only; no Gradle, device, or live-backend run

## Current inventory

The 2026-07-26 baseline of 57 target files and roughly 62 empty subpackages is obsolete.

| Metric | Current |
| --- | ---: |
| Reference non-synthetic classes/FQCNs | 416 |
| Target Kotlin files | 287 |
| Exact reference FQCNs found in target declarations/file facades | 260 |
| Exact unmatched reference FQCNs | 156 |
| Exact structural lower bound | 62.5% |

The exact-FQCN count is deliberately conservative. It treats relocations and descriptive renames as unmatched even when behavior exists, including `utilities.error.Error` -> `RestApiError`, FCM classes moved under `utilities.notifications`, and some top-level helpers consolidated into objects. It is not a behavioral-parity percentage.

No report deletion is justified: 156 names remain structurally unmatched and broad runtime/device behavior is untested.

## Historical blockers now resolved

| 2026-07-26 finding | Current evidence | Status |
| --- | --- | --- |
| Permission `@everyone` fallback returned `NONE` | `PermissionUtils.kt` now implements the reference three-way permission pipeline, MFA elevation, lurker/stage masks, and thread membership inputs | Resolved structurally |
| Missing `Permission` masks and context classes | `api/permission/Permission.kt:20-132`; `permissions/PermissionsContexts.kt`; `PermissionUtils.kt` exposes 46 symbols | Resolved structurally |
| Attachments bypassed `MessageQueue` | `MessageQueue.kt:210-280` validates, preprocesses, uploads, and sends text/multipart in one per-channel queue | Resolved with Vineflower/DEX evidence |
| `MessageResult.Success` dropped the message | `MessageResult.kt:24-77` carries all reference payloads | Resolved |
| `MessageRequest` was a thin shell | `MessageRequest.kt:26-105` has `Send`, `Edit`, and `SendApplicationCommand`, attachments, callbacks, timestamps, and validation | Resolved |
| Missing `Backoff`/`NetworkMonitor` | `networking/Backoff.kt`, `networking/NetworkMonitor.kt`; queue gating at `MessageQueue.kt:129-183` | Resolved structurally |
| One-regex markdown renderer | `DiscordParser.kt:18-167`, `Rules.kt`, `AstRenderer.kt`, `MessagePreprocessor.kt`, and 15 node classes provide the SimpleAST pipeline | Resolved structurally |
| No `IconUtils`/`MGImages`/Fresco configuration | `icon/IconUtils.kt`; `images/{MGImages,MGImagesConfig,MGImagesBitmap,RoundAsCirclePostprocessor}.kt` | Resolved structurally |
| No `TimeUtils`/NTP clock | `time/TimeUtils.kt`; `ClockFactory.kt:13-33` installs Kronos `NtpClock` | Resolved |
| Missing recycler infrastructure | All eight `mg_recycler` reference class names and six main recycler helpers are present | Resolved structurally |
| Missing channel/guild/user/presence helpers | `ChannelSelector`, `GuildChannelIconUtils`, `RoleUtils`, `UserUtils`, `PresenceUtils`, `GuildMemberUtils`, `MessageUtils` are present and have production callers | Resolved structurally |
| Missing URI handler | `UriHandler.kt:97-321` enforces schemes, trust warning, Custom Tabs, and external fallback | Resolved structurally |
| Missing Rx/error/logging funnel | `utilities/rx/ObservableExtensions.kt`, `Logger.kt`, `LoggingProvider.kt`, `AppGatewaySocketLogger.kt`, complete `RestApiError.Type` | Resolved structurally |
| Missing auth validators | `auth/AuthUtils.kt` and `view/validators/**` exist | Resolved structurally |
| Phone validation required libphonenumber | `javap -c` proves the reference uses the same `^[-() \\d]+$` regex and has no libphonenumber classes; target `PhoneUtils.kt:10-21` matches | Historical finding was wrong |

Vineflower recovers the full reference `MessageQueue.doSend`: validation precedes preprocessing, `SendUtils` emits preprocessing then ready payload, attachments use multipart, nonce-bearing sends enable safe transport retry, and completion carries the server message. The target order at `MessageQueue.kt:192-266` now follows that stronger evidence, so this formerly JADX-broken region is no longer inferred.

## Current high-value coverage

### Text processing

`DiscordParser.kt:56-110` installs escapes, block quotes, fenced/inline code, spoilers, subtext, masked/no-embed/bare links, mail/telephone links, custom/named/unicode emoji, user/role/channel/command mentions, timestamps, headers, lists, and standard markdown. `Rules.kt:202-225` routes fenced code through SimpleAST's language map and recovered code styles. Focused tests exist at `app/src/test/java/com/discord/utilities/textprocessing/DiscordMarkdownRulesTest.kt`.

Open divergence: `MessageTextRenderer.createRenderContext` still reads process-global `StoreStream` snapshots at `MessageTextRenderer.kt:91-122`, whereas the reference receives a fully caller-built context. Rendering is much more complete, but this convenience bridge remains harder to isolate and can couple background rendering to mutable global state.

### Message send pipeline

`MessageQueue.kt:129-183` preserves FIFO/requeue order, one-hour timeout, rate-limit delay, network backoff, cancellation, and reconnect drain. `:210-317` covers ordinary sends, multipart sends, application commands, and edits. `SendUtils.kt:41-145` provides preprocessing and ready payloads.

Open transport difference: captcha handling moved to the global OkHttp interceptor and deprecated body fields were removed. This is an intentional current-backend divergence; 126.21's per-message captcha body behavior is not literally reproduced.

### Permissions and entities

The shared `PermissionUtils`, `Permission`, `ChannelUtils`, `ChannelSelector`, `RoleUtils`, `UserUtils`, `PresenceUtils`, and `GuildMemberUtils` surfaces now exist. The prior one-line fallback, MFA, lurker, stage, and thread defects are no longer present in those shared implementations.

Destination-store and screen correctness is outside this report: having the helper does not prove every caller supplies stage instances, joined-thread state, or current guild MFA state.

### Images, time, recycler, URI, and logging

- Avatar/guild/media URLs now have one `IconUtils` owner with default avatars, animation gating, and proxy-size quantization.
- Fresco has `MGImagesConfig` and shared change detectors.
- `TimeUtils` and NTP-backed `ClockFactory` are production-used.
- `MGRecyclerAdapter`, drag/drop helpers, `DiffCreator`, `SelfHealingLinearLayoutManager`, and scroll helpers exist.
- `UriHandler` is production-used, including OAuth completion.
- `Logger` delegates to centrally redacted `AppLog` (`Logger.kt:1-46`).

## Open findings

### BLOCKER/MAJOR

1. **156 exact reference FQCNs remain unmatched.** This includes real missing behavior, relocations, and generated/top-level facades. The largest unresolved groups are lazy subscriptions/member lists (11), Rx (8), view extensions/text watchers/layout helpers (8), debug (6), view utilities (6), auth/Smart Lock (5), guild helpers (5), FCM (5), coroutines (4), text-processing support classes (4), and WebSocket wrappers (4). Each needs member-level classification before the count can be reduced honestly.
2. **`restapi.utils.RetryWithDelay` is still absent.** Equivalent policy is inline at `utilities/rx/ObservableExtensions.kt:67-109`, and `StoreGooglePlayPurchases.kt:413-416` carries another local version. Duplication can drift; exact API parity remains open.
3. **READY/message rendering still has global coupling.** `MessageTextRenderer.kt:101-116` reaches into `StoreStream` while rendering rather than consuming only caller-provided immutable data.
4. **No fresh device matrix exists for ICU regexes, styled spans, drag/drop, animation/reduced-motion, image caching, Custom Tabs, or accessibility.** JVM tests cannot validate several of these Android-only contracts.

### MINOR/STRUCTURAL

5. `PersisterConfig.kt:10-15` passes `Observable.never()` into `Persister.init` and separately subscribes to background/minute persistence without retaining the subscription. It produces the intended process-lifetime trigger, but differs from the reference ownership/lifecycle contract.
6. `BreadcrumbInterceptor` and `SpotifyTokenInterceptor` remain absent from `restapi`; the latter is relevant if Spotify linking/token refresh is re-enabled.
7. Exact missing names include `RingAnimator`, `RepresentativeColors`, `AttachmentUtilsKt`, `FileUtilsKt`, `LocalMessageCreatorsKt`, `StageChannelUtils`, `UserRequestManager`, `PaddedItemDecorator`, `FragmentLifecycleTextWatcher`, `ZLibWebSocketListener`, and multiple Smart Lock classes. Some behavior has local replacements; none was blanket-closed.
8. `utilities.error.Error` remains deliberately renamed to `RestApiError`. This is not a functional blocker but prevents exact FQCN parity and must remain documented.

## Exact unmatched roll-up

The 156 unmatched names group as follows. Counts are exact reference FQCNs, not estimated behavior:

| Group | Count | Examples |
| --- | ---: | --- |
| Root utilities | 8 | `ChannelShortcutInfo`, `Quad`, `StoreUIEventHandler`, `UtcDateTimeParceler` |
| Lazy member/subscription | 11 | `MemberList`, `ThreadMemberList`, subscription managers |
| Rx | 8 | `LeadingEdgeThrottle`, `OperatorBufferedDelay`, coroutine bridges |
| View/extensions/text | 16 | image/recycler/scroll extensions, text watcher, binding delegates, visibility observers |
| Auth/Smart Lock | 5 | `GoogleSmartLockManager`, repo, registration flow |
| Debug/system | 13 | debug printable family, process exit reason, procfs/system log/service helpers |
| FCM/notifications | 5 | names relocated under `utilities.notifications` |
| Guild/channel/user/presence | 16 | a mix of helper facades, local replacements, and real omissions |
| Other feature helpers | 74 | analytics providers, activities, contacts, premium, stage, SMS, WebSocket, and extended features |

The table is a prioritization aid only; the exact list should be regenerated from source when implementation resumes rather than copied from the 2026-07-26 report.

## Evidence caveats

- The 416-class reference denominator excludes `$`-named synthetic files, as before.
- Kotlin consolidation means file count and exact FQCN count both understate semantic coverage.
- JADX failures were not treated as resolved without Vineflower/DEX evidence. `MessageQueue.doSend` and `PhoneUtils` received stronger verification; unexamined missing classes remain open.
- No broad caller-by-caller utility audit or device test was run on 2026-08-04.

## Top remaining blockers

1. Classify the 156 unmatched FQCNs into true missing behavior, deliberate relocation, generated facade, and obsolete/provider-owned surface.
2. Restore one canonical `RetryWithDelay` API and remove duplicate local policies only after callers are verified.
3. Remove `StoreStream` reads from the message renderer's core path by using caller-built immutable render context.
4. Run focused device tests for markdown spans/ICU, URI handling, drag/drop, animations/reduced motion, and Fresco memory behavior.
5. Re-audit destination callers of permissions/channel/presence helpers; structural presence alone does not prove correct inputs.
