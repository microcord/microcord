# Channel and read-state stores re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References: read-only JADX and Vineflower exports of Discord Android 126.21.
This supersedes the 2026-07-26 report. No production code was modified.

## Current result

| Surface | Classification | Open findings |
| --- | --- | ---: |
| `StoreChannels` / conversions | PARTIAL | 3 MAJOR, 4 MINOR |
| `StoreChannelsSelected` | PARTIAL | 2 MAJOR, 3 MINOR |
| `StoreChannelMembers` | PARTIAL | 2 BLOCKER, 5 MAJOR, 2 MINOR |
| collapsed categories | PARTIAL | 1 MAJOR, 2 MINOR |
| user guild settings | PARTIAL | 1 BLOCKER, 3 MAJOR, 4 MINOR |
| read state / ACK / mentions | PARTIAL | 1 BLOCKER, 3 MAJOR, 3 MINOR |
| adjacent thread stores | PARTIAL | 1 MAJOR |
| **Total** | **PARTIAL** | **4 BLOCKER, 17 MAJOR, 18 MINOR** |

## 1. StoreChannels and StoreChannelConversions

### Resolved since 2026-07-26

- Live `*Internal` and published snapshot accessors are separated and annotated
  (`StoreChannels.kt:21-27,221-281`). The old public-live-map data race is gone.
- Managed-channel filtering is restored for READY and incremental updates
  (`StoreChannels.kt:103-124,140-164`).
- Parent-aware `storeThread`, non-zero nsfw inheritance, recipient updates,
  actual-removal marking, initialization state, and live default-channel
  observation now exist (`StoreChannels.kt:171-219,311-389`).
- Canonical `com.discord.api.channel.ChannelUtils` extension behavior is used for
  display names, sorting and predicates (`StoreChannels.kt:3-18`).
- `StoreChannelConversions` now exists, is constructed, and receives channel and
  selection events (`StoreStream.kt:578-580,2440-2443,2850`).

### Open

- **MAJOR:** `observeChannelPermissionsForGuild` retains its empty default and
  StoreStream does not supply it (`StoreChannels.kt:32-38`; `StoreStream.kt:178-185`).
  `observeDefaultChannel` therefore cannot resolve a visible default in
  production even though its two-source implementation is now correct
  (`StoreChannels.kt:311-326`).
- **MAJOR:** `observeDirectShareCandidates(Context)` and its
  `ChannelFrecencyTracker` dependency remain absent. Current Android direct-share
  support is implemented elsewhere and is not reference StoreChannels parity.
- **MAJOR:** `handleMessagesLoaded` and `handleSearchFinish` accept pre-extracted
  thread collections (`StoreChannels.kt:204-208`) rather than reference messages/
  `SearchState`; callers must preserve all `hits[].thread` inputs. StoreStream
  handles current loader/search sources, but the store contract is reduced.
- **MINOR:** constructor defaults can silently disable permissions, lurking and
  auth inputs (`StoreChannels.kt:29-39`); the reference requires them.
- **MINOR:** `storeGuild` still ignores unavailable guilds
  (`StoreChannels.kt:333-343`), unlike reference `handleGuildAdd`.
- **MINOR:** READY additionally hydrates private recipients from the user table
  (`StoreChannels.kt:108-117`).
- **MINOR:** target-only reset/preload helpers remain and participate in the
  target logout architecture (`StoreChannels.kt:84-97,210-213`).

## 2. StoreChannelsSelected

### Resolved since 2026-07-26

- Both sealed hierarchies and all five resolved states now exist
  (`StoreChannelsSelected.kt:65-155`).
- Reactive validation, cancellation, computation scheduling, first-available
  sorting/access checks, initialization source, thread-draft flow, peeking fields,
  timeout, guild/channel handlers, previous selection and analytics location are
  implemented (`StoreChannelsSelected.kt:157-486`).
- StoreStream observes resolved selection and routes selection side effects
  (`StoreStream.kt:1334-1378,2426-2444`).
- Cold-start selection validation now begins after the persisted channel/guild/permission snapshots are restored, so the
  selected channel and cached message holder can render before Gateway READY; the initial channel open closes the left panel.

### Open

- **MAJOR:** reference channel frecency and `CHANNEL_HISTORY_V3` persistence are
  still absent. `onSelectedChannelResolved` tracks analytics callbacks but no
  frecency (`StoreChannelsSelected.kt:441-468`). Direct-share ranking and
  recency-based behavior therefore remain unavailable.
- **MAJOR:** StoreStream constructs the store without `trackChannelOpened` or
  `trackTextInVoiceOpened` (`StoreStream.kt:227-229`), so both retain no-op
  defaults (`StoreChannelsSelected.kt:55-60`). Channel-open analytics are dead.
- **MINOR:** target snapshots selected state; the reference reads live state from
  post-dispatch observers (`StoreChannelsSelected.kt:165-166,209-236`).
- **MINOR:** explicit `handlePermissionsChanged` duplicates the reactive source
  (`StoreChannelsSelected.kt:346-355`).
- **MINOR:** target reset clears persisted selections; reference account cleanup
  is driven by global persistence reset (`StoreChannelsSelected.kt:195-207`).

## 3. StoreChannelMembers

The target now has operation logging/error invalidation, throttling support,
channel-based list lookup, non-null fallback, overwrite hashing, and annotated
operation helpers (`StoreChannelMembers.kt:36-374`). These resolve most structural
findings from July.

### Open blockers

- **BLOCKER:** StoreStream constructs `StoreChannelMembers` with only
  `observationDeck` and `getRole` (`StoreStream.kt:243-245`). `dispatcher`,
  `getChannel`, `getGuildRoles`, and `getGuildMemberCount` retain defaults
  (`StoreChannelMembers.kt:36-48`). Production consequences: no one-second
  coalescing, `getChannelMemberList` always computes from a null channel/empty
  role map, and its fallback member count is always zero.
- **BLOCKER:** target member rows still embed raw `GuildMember` and expose only
  `Member`/`GroupHeader`; the reference resolved row carries name, bot/verified
  tag, presence, role color, avatar, owner indicator, premium state, streaming
  and user flags, plus distinct role/status headers. That presentation state is
  not produced by this store (`StoreChannelMembers.kt:85-93,275-301`; target
  `utilities/lazy/memberlist/ChannelMemberList.kt`).

### Other open findings

- **MAJOR:** reference `allowOwnerIndicator` and its administrator/hoisted-role
  rule remain absent.
- **MAJOR:** role updates patch only group-header names and do not rebuild member
  color/crown rows (`StoreChannelMembers.kt:171-187`).
- **MAJOR:** member remove writes a null hole without adjusting group counts
  (`StoreChannelMembers.kt:156-169`). Reference sparse-list operations own this
  consistency.
- **MAJOR:** logger writes only `android.util.Log`, not reference app/crash
  logging (`StoreChannelMembers.kt:59-83`).
- **MAJOR:** target `ChannelMemberList` remains an immutable projection rather
  than the reference sparse mutable list API; behavioral equivalence for large
  windowed updates is not established.
- **MINOR:** target adds direct member-update/remove handlers because rows retain
  raw members; reference rebuilds rows from source stores.
- **MINOR:** reset is target-only.

## 4. StoreCollapsedChannelCategories

Restore now replaces rather than merges, handlers are annotated, actual-change
marking is restored, `super.snapshotData` is called, and reset no longer clears
its own cache (`StoreCollapsedChannelCategories.kt:23-84`).

- **MAJOR:** persistence still uses Kryo `Persister<Map<Long, Set<Long>>>`
  (`StoreCollapsedChannelCategories.kt:15-18`) instead of reference SharedPreferences
  string-entry-set map. Existing 126.21 on-disk data is incompatible.
- **MINOR:** target reset clears in-memory state during logout
  (`StoreCollapsedChannelCategories.kt:65-77`); reference has no reset hook.
- **MINOR:** cache key visibility/constructor injection differ structurally.

## 5. StoreUserGuildSettings

### Resolved since 2026-07-26

- A real REST client binding, `restSubscribeOn().ui().appSubscribe`, non-null
  default observations, thread parent/old flags, complete analytics interface,
  private-channel endpoint/optimism/rollback, update-type fallback and event
  emission now exist (`StoreUserGuildSettings.kt:33-466`).
- StoreStream supplies live channel lookup and joined-thread flags
  (`StoreStream.kt:252-258`).

### Open

- **BLOCKER:** StoreStream never calls `guildSettings.bindApi(api)`. The API field
  remains null (`StoreUserGuildSettings.kt:89-94`), and every production setter
  returns before making a request (`:318-324,383-389`). Guild/channel/thread mute
  and notification writes are still inert despite repaired method bodies.
- **MAJOR:** StoreStream does not supply the analytics adapter, so `Analytics.NONE`
  remains active (`StoreUserGuildSettings.kt:46,63-77`; `StoreStream.kt:252-258`).
- **MAJOR:** private-channel writes are an intentional target extension with
  optimistic mutation and rollback (`StoreUserGuildSettings.kt:326-371`), not
  reference 126.21 ordering. Its endpoint behavior is covered elsewhere but is a
  parity divergence.
- **MAJOR:** target reset clears both persisters on pre-logout
  (`StoreUserGuildSettings.kt:109-120`); reference has no store reset and relies
  on global account cleanup.
- **MINOR:** READY accepts null `userGuildSettings` (`:160-166`); reference requires
  it.
- **MINOR:** restore merges cache without clearing first (`:101-107`), so a second
  restore can retain stale entries.
- **MINOR:** forum notification flags omit the reference experiment/access gate
  (`:237-248`).
- **MINOR:** `observeGuildSettings` adds distinct suppression absent from the
  reference (`:139-149`).

## 6. Read state, StoreMessageAck, and StoreMentions

### Resolved since 2026-07-26

- The domain is again split into reference-shaped stores. `StoreReadStates` is a
  projection (`StoreReadStates.kt:25-217`), `StoreMessageAck` owns the persisted
  ledger/locks/thread synchronization/auto-ack (`StoreMessageAck.kt:42-470`), and
  `StoreMentions` owns live/server mention inputs (`StoreMentions.kt:39-346`).
- All thread/forum/voice unread inputs are populated directly from StoreStream
  accessors (`StoreReadStates.kt:81-106`).
- Mark-unread now uses the requested message boundary and a locked ack
  (`StoreMessageAck.kt:123-149`).
- Auto-ack uses bottom/loading/detached/managed/lock gates and 500 ms debounce
  (`StoreMessageAck.kt:151-193`).
- READY full/partial hydration, thread ack lifecycle, own-message ack, forum
  parent ack, pruning and lock release are present (`StoreMessageAck.kt:195-279,
  379-429`).
- Mention persistence, DMs, role/everyone suppression, blocked users, edits,
  joined threads, selected-channel suppression and mark-unread recount are present
  (`StoreMentions.kt:56-346`).
- Unread markers now use the shared `models.application.Unread`, live loaded
  message counts, locked-ack precedence and unsubscribe clearing
  (`StoreReadStates.kt:53-79,117-146`).

### Open

- **BLOCKER:** `StoreMessageAck.isUpdateRequired` appears directionally inverted:
  it calls `MessageUtils.isNewer(existingAckId, mostRecentId)`
  (`StoreMessageAck.kt:325-333`). Elsewhere the same helper is used as
  "second argument is newer" (`StoreReadStates.kt:178-181`). A focused test is
  required against the reference DEX before changing it; if current semantics are
  as named, normal auto-ack is suppressed and stale cursors are retained.
- **MAJOR:** mark-unread fallback at `StoreMessageAck.kt:142` is documented as DEX
  exact but combines an already epoch-relative snowflake timestamp with another
  epoch subtraction. This expression needs bytecode re-verification; no guess is
  made here.
- **MAJOR:** `StoreReadStates` calls global `StoreStream` throughout
  (`StoreReadStates.kt:61-65,68-138,148-204`) rather than injected reference store
  dependencies. Testability and multi-graph isolation differ.
- **MAJOR:** `StoreMentions` counts only the first mentioned role
  (`StoreMentions.kt:323-327`). If a message lists multiple roles and the user's
  role is not first, the mention is lost; reference logic must be checked in DEX/
  Vineflower before correcting.
- **MINOR:** unread computation deliberately includes parent/category mute chains
  and current target thread stores; exact 126.21 equivalence needs a fixture
  matrix.
- **MINOR:** target account-isolation clear is additional to reference pre-logout
  (`StoreMessageAck.kt:267-279`).
- **MINOR:** mention deletion still clears only private-channel live mentions
  (`StoreMentions.kt:170-181`), matching the recovered design but lacking device/
  restart regression coverage.

## 7. Adjacent thread stores

All nine formerly missing adjacent stores are now present and wired:
`StoreThreadsJoined`, `StoreThreadsActive`, `StoreThreadsActiveJoined`,
`StoreThreadMembers`, `StoreThreadMessages`, `StoreThreadDraft`,
`ArchivedThreadsStore`, `StoreForumPostMessages`, and `StoreForumPostReadStates`
(`StoreStream.kt:186-194,381-395,535-543,817-832,1632-1668`).

- **MAJOR:** this re-audit verified presence and central event wiring, not every
  field/method of all nine stores against the reference. They remain PARTIAL
  until a dedicated field/behavior audit closes them.

## Decompiled-reference caveats

- The old JADX failures in `StoreChannels.handleGroupDMRecipient` and
  `storeThread` are no longer used as guesses: current behavior was checked
  against Vineflower and call sites. No unresolved finding depends solely on the
  broken Java output.
- `StoreMessageAck.handleConnectionOpen` is recovered in Vineflower; current
  target hydration is no longer classified from surrounding calls alone.
- The two read-state findings explicitly marked for DEX re-verification above
  remain open; this report does not infer their intended arithmetic/direction.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend checks ran.
- No production-wiring test catches the missing `guildSettings.bindApi(api)`.
- No startup test catches the unwired StoreChannels permission provider.
- Member-list large-window/desync behavior lacks reference fixture parity.
