# Guild stores re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References: read-only JADX and Vineflower exports of Discord Android 126.21.
This supersedes the 2026-07-26 report. No production code was modified.

## Current result

| Surface | Classification | Open findings |
| --- | --- | ---: |
| `StoreGuilds` | PARTIAL | 2 BLOCKER, 2 MAJOR, 4 MINOR |
| `StoreGuildsSorted` | PARTIAL | 1 MAJOR, 3 MINOR |
| `StoreGuildSelected` | PARTIAL | 2 MAJOR, 3 MINOR |
| expanded folders | PARTIAL | 3 MINOR |
| guild subscriptions | PARTIAL | 1 BLOCKER, 2 MAJOR, 2 MINOR |
| permissions | PARTIAL | 1 MAJOR, 3 MINOR |
| stage instances | PARTIAL | 3 MINOR |
| adjacent guild stores | PARTIAL | 1 MAJOR |
| **Total** | **PARTIAL** | **3 BLOCKER, 9 MAJOR, 18 MINOR** |

## 1. StoreGuilds

### Resolved since 2026-07-26

- Timeout expiry observer, dispatcher hop, profile-preserving partial member
  merge, permission callback seam, raw/computed update marking, large-guild
  partition copies, lurk-aware persistence, role comparator, non-null joined-at/
  verification observations, channel-to-guild observation, and two-source timeout
  observation are implemented (`StoreGuilds.kt:39-155,170-388`).
- `HUGE_GUILD_SIZE`, shared empty maps, `SnowflakePartitionMap`, `RoleUtils`, and
  joined-at utility now exist (`StoreGuilds.kt:16-18,533-552`).
- Gateway DTO overloads for member chunks/thread updates are present
  (`StoreGuilds.kt:262-288`).

### Open blockers

- **BLOCKER:** production startup calls `guilds.restore()` rather than
  `guilds.init(context)` (`StoreStream.kt:1269-1274`).
  `initClearCommunicationDisabledObserver` therefore never starts
  (`StoreGuilds.kt:79-123`), and expired communication timeouts remain active
  until another member event.
- **BLOCKER:** StoreStream supplies `getLurkingGuildIds` but not
  `onMeCommunicationEnabled` (`StoreStream.kt:172-177`). The callback retains its
  no-op default (`StoreGuilds.kt:53-57`), so even a manually cleared timeout for
  the current user does not recompute permissions (`StoreGuilds.kt:302-315`).

### Other open findings

- **MAJOR:** the target still owns a private `Guild.merge` implementation
  (`StoreGuilds.kt:436-523`) rather than the reference model method. It retains
  API member count/stickers and old-name fallback where 126.21 masks/defaults
  those fields differently.
- **MAJOR:** `Actions.requestMembers` / `requestRoleMembers` are not nested on
  StoreGuilds. Equivalent request paths now exist through
  `StoreGuildMemberRequester` and `StoreGatewayConnection`, but the public store
  API and role-member request behavior are not one-for-one verified.
- **MINOR:** target reset clears live maps and persisters; reference StoreGuilds
  has no reset hook (`StoreGuilds.kt:157-168`).
- **MINOR:** restore merges without clearing, so a second restore can retain stale
  entries (`StoreGuilds.kt:84-89`).
- **MINOR:** target keeps compatibility overloads with the formerly inverted
  `preserveProfile` name (`StoreGuilds.kt:245-251`).
- **MINOR:** target uses global StoreStream in `observeFromChannelId`
  (`StoreGuilds.kt:336-340`) rather than an injected stream dependency.

## 2. StoreGuildsSorted

### Resolved since 2026-07-26

- Lurking, unavailable, muted, joined-at and user positions are real inputs;
  StoreStream passes them at `StoreStream.kt:2875-2886`.
- Rebuilds use a one-second leading-edge throttle and direct user reorders bypass
  it (`StoreGuildsSorted.kt:48-135`).
- Lurked guild placement and folder validation are implemented
  (`StoreGuildsSorted.kt:149-181`).

### Open

- **MAJOR:** fallback joined-at sorting is descending
  (`StoreGuildsSorted.kt:142-147`). Reference decompile appears ascending but
  reuses one operand, so exact behavior remains DEX-unverified. Do not change by
  guess.
- **MINOR:** this remains a push-input store rather than reference dependencies +
  `State` combineLatest (`StoreGuildsSorted.kt:41-46,113-129`).
- **MINOR:** `snapshotData` omits `super` (`StoreGuildsSorted.kt:138-140`).
- **MINOR:** deterministic missing-guild insertion differs from reference HashMap
  iteration (`StoreGuildsSorted.kt:149-165`).

## 3. StoreGuildSelected

### Resolved since 2026-07-26

- `LeastRecentlyAddedSet`, guild-store observation, sample callback seam,
  lurk-filtered persistence and guild-view analytics seam now exist
  (`StoreGuildSelected.kt:24-125`).
- Legacy incompatible cache recovery is explicit (`StoreGuildSelected.kt:127-141`).

### Open

- **MAJOR:** StoreStream supplies the guild store and lurking ids but does not
  supply `trackGuildViewed` or `onSampleGuildIdSelected`
  (`StoreStream.kt:221-226`). Both production callbacks remain no-ops
  (`StoreGuildSelected.kt:27-33`).
- **MAJOR:** target `getSelectedGuildId()` reads the first element of a reversed
  snapshot while internal reads use `lastOrNull` (`StoreGuildSelected.kt:35-41,
  117-125`). Current ordering tests should be compared with reference
  `LeastRecentlyAddedSet` before declaring this equivalent.
- **MINOR:** unavailable guild delete is ignored (`StoreGuildSelected.kt:90-94`);
  reference removes the id.
- **MINOR:** target reset eagerly clears the cache (`StoreGuildSelected.kt:108-114`).
- **MINOR:** `init` omits `super.init` and delegates to target restore
  (`StoreGuildSelected.kt:69-75`).

## 4. StoreExpandedGuildFolders

Field/key/mutation/persistence behavior remains substantially equivalent.

- **MINOR:** observation adds `distinctUntilChanged` absent from reference.
- **MINOR:** restore clears before loading; reference only adds cached values.
- **MINOR:** target reset clears open state on logout; reference has no reset.

Current source: `stores/StoreExpandedGuildFolders.kt`.

## 5. StoreGuildSubscriptions

### Resolved since 2026-07-26

- Full subscription state now covers channel ranges, typing, activities, members,
  threads and thread member lists (`StoreGuildSubscriptions.kt:14-67`).
- Manager/sub-manager state, batching at `snapshotData`, reconnect replay,
  disconnect retain, delayed member unsubscribe, channel LRU, thread/member APIs
  and reference-shaped range computation are implemented
  (`StoreGuildSubscriptions.kt:80-584`).
- GatewayConnection consumes `observeSubscriptions()` and sends opcode 14
  (`StoreGatewayConnection.kt:297-303`).

### Open blockers

- **BLOCKER:** the selected-guild provider and navigation calls are now wired, but
  `getConnectedVoiceGuildId` retains its `0L` default. Disconnect retention can
  still drop the guild of an active voice session.

### Other open findings

- **MAJOR:** helper managers remain nested in the store rather than the reference
  `utilities/lazy/subscriptions` package. Behavior is substantial but a complete
  field/method comparison of all sub-managers has not been performed.
- **MAJOR:** `onSubscription` is a no-op, although the target compensates through
  the `events` subject consumed by GatewayConnection. This is an architectural
  replacement rather than direct constructor parity.
- **MINOR:** target retains panel-specific aliases and observation APIs absent
  from reference.
- **MINOR:** reset alias and immediate delayed-unsubscribe flush differ in
  lifecycle details.

## 6. StorePermissions

### Resolved since 2026-07-26

- Stage-instance and joined-thread dependencies are supplied by StoreStream
  (`StoreStream.kt:194-199`).
- Canonical `PermissionUtils.computeNonThreadPermissions` / `computePermissions`,
  DM `Permission.ALL`, stage inputs and joined-thread input are used
  (`StorePermissions.kt:229-287`).
- Incremental channel/delete/thread/member/search/message/stage handlers and
  reference guild enumeration are implemented (`StorePermissions.kt:90-221`).

### Open

- **MAJOR:** thread member handlers look up the thread via
  `getGuildChannelInternal`, which searches only the guild channel map
  (`StorePermissions.kt:148-166`; `StoreChannels.kt:261-263`). Threads live in
  `threadsById`; these handlers can return without recomputing private-thread
  permissions.
- **MINOR:** `init`/`reset` mutate without `@StoreThread` annotations
  (`StorePermissions.kt:57-70`). Production calls them from dispatcher blocks,
  but the API permits unsafe callers.
- **MINOR:** target reset clears persisters; reference has no reset hook.
- **MINOR:** single-channel recompute marks even when unchanged
  (`StorePermissions.kt:252-260`).

## 7. StoreStageInstances

Nullable stage-end observation is fixed (`StoreStageInstances.kt:25-34`), and
all reference state/handlers remain present.

- **MINOR:** READY takes `List<Guild>` and unconditionally marks after clear,
  fixing a stale-reference edge but changing emission count (`:36-43`).
- **MINOR:** remove/delete also mark on no-op (`:48-65,88-92`).
- **MINOR:** target reset is additional (`:68-74`).

## 8. Adjacent guild-store inventory

The July report listed seven missing stores. Six are now constructed, wired, and
exposed by StoreStream:

- `StoreGuildMemberCounts` (`StoreStream.kt:259,1574,1883,2736,2790`)
- `StoreGuildMemberRequester` (`StoreStream.kt:570-577,1587,1869,2733,2743`)
- `StoreGuildsNsfw` (`StoreStream.kt:230,918-920,1100`)
- `StoreGuildProfiles` (`StoreStream.kt:338,1005`)
- `StoreGuildWelcomeScreens` (`StoreStream.kt:339,908,1006`)
- `StoreLurking` (`StoreStream.kt:171-177,899-917,1007,2717`)

`StoreGuildVoiceRegions` remains **MISSING / MAJOR**. Voice-region REST/UI may
have alternative current paths, but the reference per-guild fetching state store
and observable are absent.

Presence and wiring do not prove full behavioral parity for the six reconstructed
stores; their detailed field/method audits remain a verification gap.

## Decompiled-reference caveats

- Fallback guild ordering remains unresolved because JADX/Vineflower show a
  suspicious same-operand comparison. DEX bytecode is required.
- No finding above closes behavior solely from a broken JADX body. Reconstructed
  permission/subscription logic was checked against Vineflower and current call
  sites.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend checks ran.
- No startup test proves the timeout observer was installed; current wiring shows
  it is not.
- No gateway-frame test proves selecting a guild sends typing/activity/thread
  subscription flags.
- Six newly present adjacent stores need dedicated semantic-field audits.
