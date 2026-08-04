# Store gap inventory re-audit - 2026-08-04

Reference: `C:\Users\manti\StudioProjects\discord-jadx` (Discord Android
126.21)

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

This supersedes the 2026-07-26 `51 implemented / 37 partial / 99 missing`
inventory. No production code was modified.

## Method

1. Enumerated the **187** direct, non-`$` Java files under reference
   `com/discord/stores/` (subpackages are counted separately).
2. Compared base names with the current direct target Kotlin files.
3. For every name without an exact target file, searched current target symbols
   and behavior before classifying it missing.
4. Cross-checked graph construction and the detailed store reports assigned in
   this re-audit. A physical file match is not behavioral parity.

## Current counts

### Physical/structural representation

| Classification | Count | Meaning |
| --- | ---: | --- |
| Exact target filename | 147 | A direct `stores/<ReferenceName>.kt` exists. |
| Consolidated / renamed / nested | 37 | The reference declaration is emitted from another target Kotlin file. |
| Partial behavior, no declaration | 2 | Some responsibility exists, but the reference type/complete behavior does not. |
| Missing behavior | 1 | No target counterpart found. |
| **Total** | **187** | |

Thus **184/187 reference top-level store declarations are structurally
represented (98.4%)**, two are partial without their declaration, and one is
missing. This is source-surface coverage only.

### Behavioral status

| Status | Count |
| --- | ---: |
| Verified 100% complete with no caveat/test gap | 0 |
| Represented but PARTIAL or not fully re-audited | 186 |
| MISSING | 1 |

The deliberately conservative behavioral count follows the project contract: no
store is promoted to complete merely because it compiles, has a matching file,
or is present in StoreStream. The detailed reports still contain blockers,
caveats, and runtime test gaps.

### StoreStream graph

- Reference graph: **145** constructed store instances.
- Current target mapping: **135/145 (93.1%)** constructed/obtained.
- Reference `storesV2`: **106** entries.
- Current target `storesV2`: **101** static entries plus two VoiceTransport
  entries added during init = **103/106 (97.2%)**
  (`StoreStream.kt:769-880,976-978`).
- See `audit-stores-core.md` for counting rules and lifecycle caveats.

## No exact target filename: resolved mappings

These **37** declarations are represented despite not having the same target
filename.

### Consolidated command/store types

All are declared in `stores/StoreApplicationCommands.kt`:

- `BuiltInCommands`
- `BuiltInCommandsProvider`
- `CommandAutocompleteState`
- `CommandOptionAutocompleteQuery`
- `DiscoverCommands`
- `LoadState`

Evidence: `StoreApplicationCommands.kt:63-64,92,124-167,1304-1387`.

### Consolidated voice/billing/stage types

- `PendingDowngrade` -> `StoreGooglePlayPurchases.kt:403`
- `RtcLatencyTestResult` -> `StoreRtcRegion.kt:16`
- `StageChannelRoleContext` -> `StoreStageChannels.kt:56`
- `VoiceConfigurationCache` -> `StoreMediaSettings.kt:367`
- `VoicePropsTracker` -> `rtcconnection/VoicePropsTracker.kt:6`

### Core/renamed/nested declarations

- `DispatchHandler` -> `Store.kt:7-9`
- `ReadyPayloadUtils` -> renamed `ReadyPayloadHydrator.kt`
- `StoreLocalMessagesHolder` -> nested/source-consolidated in `StoreMessages.kt`
- `StoreMessagesHolder` -> nested/source-consolidated in `StoreMessages.kt`
- `StoreThread` -> annotation in `Store.kt:11-14`
- `StoreUserTyping` -> renamed `StoreUsersTyping.kt`

### Absorbed Kotlin facades

These reference `*Kt` files contain file-level aliases/helpers emitted by the
corresponding target source; no separate Kotlin file is required:

- `StoreApplicationCommandsKt`
- `StoreAuditLogKt`
- `StoreChangeLogKt`
- `StoreContactSyncKt`
- `StoreEmbeddedActivitiesKt`
- `StoreEmojiCustomKt`
- `StoreFriendSuggestionsKt`
- `StoreGifPickerKt`
- `StoreGuildIntegrationsKt`
- `StoreGuildsKt`
- `StoreGuildStickersKt`
- `StoreMessageReactionsKt`
- `StoreMessageRepliesKt`
- `StoreMessagesKt`
- `StoreMessageStateKt`
- `StoreMessageUploadsKt`
- `StoreNotificationUpsellsKt`
- `StoreUserPresenceKt`
- `StoreUserRelationshipsKt`
- `StoreUserSurveyKt`

These are counted as represented, but their parent store remains PARTIAL when a
detailed report lists open behavior.

## Partial without the reference declaration

### `FailedMessageResolutionType` - PARTIAL

The enum/type itself is absent. Failed-message result, retry, cancel, AutoMod,
captcha and validation behavior exists in `StoreMessages.kt`,
`MessageResult.kt`, and `MessageSendCoordinator.kt`, but reference
`StoreMessages.trackFailedLocalMessageResolved` analytics remains un-rederived.
Vineflower/DEX is required for its payload before this can be absorbed safely.

### `NotificationTextUtils` - PARTIAL

The class is absent. Its in-app notification decision is reimplemented across
`NotificationPolicy.kt` and `StoreNotifications.kt`. Vineflower recovers the
reference call inputs, and the target currently lacks joined-thread and selected
voice-channel inputs. See `audit-stores-auth-nav.md`.

## Missing

### `ConnectionTimeStats` - MISSING

No target class or equivalent listener aggregation was found. Reference
StoreStream owns it and registers RTC, stream RTC, and media-engine listeners.
Consequences include missing connection timing/state diagnostics and startup
telemetry. This is **MAJOR**, not a user-flow blocker by itself, but it is the
only top-level reference store declaration with no current target behavior.

## Present files not constructed in StoreStream

Physical existence does not close graph parity. Ten reference StoreStream
instances are not constructed/obtained by the current graph:

- `StoreAutocomplete`
- `StoreChannelFollowerStats`
- `StoreClientVersion`
- `StoreDirectories`
- `StoreGifting`
- `StoreGuildRoleMemberCounts`
- `StoreGuildVoiceRegions`
- `StoreLibrary`
- `StoreOutboundPromotions`
- `StoreDynamicLink`

Several have target files (`StoreGifting`, `StoreGuildRoleMemberCounts`,
`StoreGuildVoiceRegions`, `StoreLibrary`, `StoreOutboundPromotions`), but they are
dead with respect to StoreStream until constructed, initialized, routed and
published. Others are represented by differently named feature stores only
partially (`StoreDirectories` vs `StoreApplicationDirectory`). See
`audit-stores-core.md`.

## Reference subpackages

### `stores/updates`

Both reference files are present:

- `ObservationDeck`
- `ObservationDeckProvider`

Behavior remains PARTIAL due logger/diagnostic plumbing; see core report.

### `stores/authentication`

`AuthStateCache` is present and verified structurally/behaviorally in
`audit-stores-auth-nav.md`.

### `stores/utilities`

The July "100% missing" finding is resolved. Nine reference compiled declarations
are represented by five Kotlin sources:

- `BatchManager.kt`
- `Batched.kt`
- `RestCallState.kt` (including Default/Loading/Success/Failure)
- `LeadingEdgeThrottle.kt`
- `StoreUtilities.kt`

These are used by gateway batching and sorted-store throttling. Package/file
layout differs from compiled nested-class output but behavior is no longer absent.

## Current blocker summary by detailed report

The following are representative top blockers, not a duplicate 187-row backlog:

- Core: readiness is hard-coded true; pre-logout and target reset are conflated;
  ten graph instances are absent; READY custom emoji/sticker carry-over is stale.
- Auth/navigation: age-gate sources are not injected or routed; logout sends no
  push token; StorePhone is constructed but never initialized.
- Channels/guilds: guild settings REST is never bound; channel/member-list inputs
  remain unwired; guild timeout observer is never started; guild subscriptions
  do not follow selection.
- Messages/users: message-loader background state is not forwarded; duplicate
  edit store bypasses StoreChat; typing REST/cooldown providers are not injected.
- Voice/gateway: StoreVoiceParticipants init is not called; no ARM64/live
  end-to-end voice validation; destination behavior behind the 83 typed events is
  not fully re-audited.

## Classification cautions

- `StoreGuildVoiceRegions`, `StoreLibrary`, `StoreGifting`,
  `StoreOutboundPromotions`, and `StoreGuildRoleMemberCounts` are **files present,
  graph missing**. Do not call them implemented from filename counts.
- `StoreUserTyping` and `ReadyPayloadUtils` are deliberate name mappings.
- `StoreThread` is the runtime annotation, not a thread-domain store.
- Consolidating multiple reference declarations into one Kotlin file is allowed;
  it does not excuse missing fields or behavior.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend check ran.
- Exact filename comparison was measured; all 184 represented declarations were
  not individually field-audited in this pass.
- No assigned detailed report is eligible for deletion: each retains blockers,
  caveats, or runtime/test gaps.
