# User and social stores re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References: read-only JADX and Vineflower exports of Discord Android 126.21.
This supersedes the 2026-07-26 report. No production code was modified.

## Current result

| Surface | Classification | Open findings |
| --- | --- | ---: |
| `StoreUser` | PARTIAL | 3 MAJOR, 5 MINOR |
| profiles and notes | PARTIAL | 4 MAJOR, 4 MINOR |
| relationships | PARTIAL | 4 MINOR |
| presence | PARTIAL | 3 MAJOR, 4 MINOR |
| typing | PARTIAL | 1 BLOCKER, 2 MAJOR, 3 MINOR |
| affinities and suggestions | PARTIAL | 3 MAJOR, 4 MINOR |
| user settings | PARTIAL | 2 MAJOR, 5 MINOR |
| formerly missing stores | PARTIAL | 2 MAJOR |
| **Total** | **PARTIAL** | **1 BLOCKER, 19 MAJOR, 29 MINOR** |

## 1. StoreUser

### Resolved since 2026-07-26

- Snapshot guards now copy/persist only their explicit update sources
  (`StoreUser.kt:144-151`). The former every-dispatch copy bug is fixed.
- READY/user/member/message/relationship/presence handlers, missing-user dedupe,
  current-user preservation, urgent flag update and logout isolation are present
  (`StoreUser.kt:38-183`).
- Partial presence users are guarded by discriminator completeness
  (`StoreUser.kt:75-77,242`).

### Open

- **MAJOR:** reference `updateUser` replaces with `CoreUser`; target merges and
  preserves `MeUser` plus nullable fields (`StoreUser.kt:154-227`). This avoids
  losing private current-user fields but is an observable deliberate divergence.
- **MAJOR:** full user snapshots remain `LinkedHashMap` copies rather than
  reference `SnowflakePartitionMap.fastCopy` (`StoreUser.kt:29,147`). Large user
  sets incur O(n) copy work per UsersUpdate.
- **MAJOR:** message handling additionally ingests author and writes urgent me
  back into the user map (`StoreUser.kt:79-86`); reference handles mentions and
  me update differently. Downstream behavior is improved but not exact.
- **MINOR:** request dedupe has no in-flight subscription cancellation
  (`StoreUser.kt:30,172-182`).
- **MINOR:** DTO-taking handlers remain adapted signatures; StoreStream performs
  current unwrapping, but class API differs.
- **MINOR:** READY user discovery/log-attribution breadcrumbs are absent.
- **MINOR:** structural equality replaces reference identity in empty-me filter
  (`StoreUser.kt:120-123`).
- **MINOR:** reset clears the whole user map; reference only clears me on null
  auth token.

## 2. StoreUserProfile and StoreUserNotes

### Profiles

- StoreStream supplies REST fetch, user propagation and guild-member propagation
  (`StoreStream.kt:318-337`). Loading/error/profile state is operational.
- **MAJOR:** reference profile success fans the complete `UserProfile` through
  `StoreStream.handleUserProfile`; target splits this into user update plus only
  `guildMember` propagation. Mutual/connection/premium fields must be compared
  against the reference fan-out before closure.
- **MAJOR:** target adds observable loading/error state and marks failures; reference
  only clears loading. UI failure behavior differs.
- **MAJOR:** profile field-copy/nulling order needs exact constructor comparison;
  current target uses Kotlin `copy`, while reference positional constructor
  output is decompiler-sensitive.
- **MINOR:** target clears profiles on pre-logout; reference retains them.
- **MINOR:** REST distinct and app logging differ.

### Notes

- Unknown notes now emit `Loading`, not `Empty` (`StoreUserNotes.kt:25-32`).
- **MAJOR:** save still has no `Context` and its failure callback is empty
  (`StoreUserNotes.kt:45-51`), so server errors are silent instead of using the
  reference app error UI.
- **MINOR:** StoreStream sends blank note as null
  (`StoreStream.kt:347-350`), whereas reference sends the string verbatim.
- **MINOR:** target pre-logout clears notes; reference clears on connection open.

## 3. StoreUserRelationships

Keys, state hierarchy, READY/add/remove, persistence and observations remain
substantially implemented (`stores/StoreUserRelationships.kt`).

- **MINOR:** a remove received while Unloaded remains Unloaded; reference's
  `ensureRelationshipLoaded` transitions to Loaded(empty).
- **MINOR:** per-user observation adds distinct suppression absent from reference.
- **MINOR:** persisted maps are copied/ordered differently.
- **MINOR:** pre-logout clear is an additional account-isolation behavior.

## 4. StoreUserPresence

### Resolved since 2026-07-26

- StoreStream injects custom emoji and unicode surrogate resolvers plus gateway
  publish (`StoreStream.kt:301-317`).
- Custom-status expiry uses `TimeUtils.parseUTCDate`; unknown emoji ids do not
  fall through; update-self markUnchanged examines the resulting status
  (`StoreUserPresence.kt:201-265`).
- Activity replacement key/first-match behavior and Vineflower-derived sorting
  are restored (`StoreUserPresence.kt:122-141,184-244`).

### Open

- **MAJOR:** thread handlers accept only `(guildId, presences)` and derive user id
  from each presence (`StoreUserPresence.kt:117-120`). Reference associates each
  presence with the thread member's user id. Payloads omitting both embedded user
  and `userId` are dropped.
- **MAJOR:** local presence publishing collapses null activities to empty at
  StoreStream (`StoreStream.kt:301-305`), and since/afk defaults are not explicitly
  compared with the reference call.
- **MAJOR:** full presence snapshots use `LinkedHashMap` rather than partition
  copies (`StoreUserPresence.kt:44-46,172-176`).
- **MINOR:** public handler parameter order is `(userId, guildId)` rather than
  reference `(guildId, userId)` (`StoreUserPresence.kt:79-82`). Current callers
  match target order, but this remains a footgun.
- **MINOR:** target normalizes null client statuses before comparison, suppressing
  repeat emissions the reference produces.
- **MINOR:** target accepts `presence.userId` fallback where reference sometimes
  requires embedded user.
- **MINOR:** target reset clears presence state; reference has no reset hook.

## 5. StoreUsersTyping

The API method now exists, including cooldown extraction
(`StoreUsersTyping.kt:78-100`), and inbound typing state is wired through
StoreStream (`StoreStream.kt:457,1700-1703,1900`).

- **BLOCKER:** StoreStream constructs `StoreUsersTyping` without `api` or
  `onCooldown` (`StoreStream.kt:457`). Both retain no-op defaults
  (`StoreUsersTyping.kt:22-36`), so `setUserTyping` returns at `api() ?: return`.
  The local user still sends no typing indicators and typing cannot probe slow
  mode.
- **MAJOR:** target uses a dedicated scheduled executor instead of Rx computation
  delay (`StoreUsersTyping.kt:141-150`). Lifetime/shutdown and timing differ.
- **MAJOR:** standalone constructor/publish mode bypasses normal dispatcher end
  handling (`StoreUsersTyping.kt:42-45,134-139`). Production StoreStream does not
  use it, but public behavior remains divergent.
- **MINOR:** class name remains pluralized from reference `StoreUserTyping`.
- **MINOR:** target cancels timeout tasks on message create and cleans task maps,
  fixing reference leakage.
- **MINOR:** logout clear is additional.

## 6. Affinities and friend suggestions

### Affinities

State, READY fetch, observation and snapshot behavior exist.

- **MINOR:** visibility/named private fetch method differ.
- **MINOR:** target adds pre-logout clear and getter.

### Friend suggestions

- StoreStream supplies REST fetch and user hydration
  (`StoreStream.kt:353-369`). Gateway create/delete and READY are wired.
- **MAJOR:** reference exposes `FriendSuggestionsFetcher` with in-flight
  subscription dedupe; target lambda/loading implementation has no equivalent
  reusable fetcher contract.
- **MAJOR:** pull-to-refresh/concurrent-fetch behavior is not verified against the
  reference fetcher.
- **MAJOR:** target adds explicit loading state and cross-store hydration inside
  the store, changing emission topology.
- **MINOR:** target clears suggestions on pre-logout.
- **MINOR:** additional observation API differs from reference deck usage.

## 7. StoreUserSettings

### Resolved since 2026-07-26

- Server-write APIs, text/image sync tails, privacy setters, restricted guilds,
  consents, contact-sync shown, custom-status serialize-null update and expiry are
  implemented (`StoreUserSettings.kt:254-509`).
- Production binds the API (`StoreStream.kt:1003-1005`).
- Reduced-motion sticker mapping now changes only ALWAYS to ON_INTERACTION
  (`StoreUserSettings.kt:174-187`).
- Observables use `computationLatest` where reference does
  (`StoreUserSettings.kt:131-192`).
- Custom status expiry uses injected clock and `TimeUtils.parseUTCDate`, and clears
  server-side (`StoreUserSettings.kt:477-508`).

### Open

- **MAJOR:** most setters only make a server write when passed an `AppActivity`
  (`StoreUserSettings.kt:40-46,457-475`). Existing call sites that use the
  legacy one-argument Kotlin overload remain local-only; a complete call-site
  audit is required before claiming the server-write half is reachable everywhere.
- **MAJOR:** consent handling is a richer target state machine rather than
  reference `observeConsents().onErrorReturn(DEFAULT)`
  (`StoreUserSettings.kt:55-59,189-192,404-447`). UI terminal behavior differs.
- **MINOR:** overlay analytics remains TODO (`StoreUserSettings.kt:246-250`).
- **MINOR:** inbound payload additionally handles accessibility detection.
- **MINOR:** local custom status backing/getter and reset are target extensions.
- **MINOR:** setter parameter order differs from reference for source compatibility.
- **MINOR:** expiry timer uses default Rx timer scheduler rather than an injected
  scheduler, limiting deterministic timing tests.

## 8. Formerly missing store inventory

All six stores listed as missing in July are now constructed in StoreStream:

- `StoreUserConnections` (`StoreStream.kt:581-590`)
- `StoreUserSurvey` (`StoreStream.kt:560-569`)
- `StoreSpotify` (`StoreStream.kt:591-597`)
- `StoreGameParty` (`StoreStream.kt:556`)
- `StoreContactSync` (`StoreStream.kt:598-606`)
- `StoreMaskedLinks` (`StoreStream.kt:242`)

READY/gateway/logout wiring is present across `StoreStream.kt:1524-1815,
2535-2638,2694-2778`.

- **MAJOR:** this pass verified construction and central wiring, not every
  semantic field/method of all six reconstructed stores.
- **MAJOR:** new Spotify account linking still depends on project-owned OAuth;
  current store behavior covers existing linked accounts only. This is a provider
  blocker, not a missing-store classification.

## Decompiled-reference caveats

- Formerly broken presence flatten/update methods were checked against
  Vineflower; current expiry/activity findings are not guesses from broken JADX.
- Enum/wire behavior used by typing cooldown and settings remains pinned by
  current model constants; no ordinal was inferred from alphabetic decompile.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend checks ran.
- No production test proves typing REST/cooldown wiring; current construction
  proves it is absent.
- No full call-site audit proves every server-owned user setting passes an
  activity and reaches REST.
- Six newly present social stores need dedicated semantic-field audits.
