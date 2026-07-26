# Reconstruction Status

Last updated: 2026-07-26, mid-Wave-4.

Live working report: what is done, what is in flight, what is known broken, and
**what is still missing**. `PLAN.md` remains the roadmap; this is the running
account. Supporting evidence is in the other files in this directory.

---

## 1. Build state

| Check | State |
| --- | --- |
| `compileDebugKotlin` | **RED — 100 errors**, all in files 12 agents are actively writing |
| Last green | Wave 2 boundary (before Wave 3/4 started) |
| `testDebugUnitTest` | **NEVER RUN this session** |
| `lintDebug` | **NEVER RUN this session** |
| `connectedDebugAndroidTest` | **NEVER RUN this session** |
| Device check | **NONE this session** |

**Every parity number below is structural — fields, endpoints, events, classes
that exist and type-check. None of it is verified behavior.** Hundreds of unit
tests were written this session and not one has been executed.

### Current error distribution (all in-flight, none in settled code)

| Package | Errors | Owner |
| --- | --- | --- |
| `widgets/chat/list/**` | 79 | chat-list agent |
| `widgets/chat/input/autocomplete/**` | 13 | chat-input agent |
| `stores/**` | 5 | messages / users / voice agents |
| `widgets/channels/list/items` | 3 | channels agent |

Cause is uniform: adapters referencing ViewHolder classes their agent has not
created yet, plus three RxJava-1 `Subject`-vs-`Observable` mistakes. Each agent
has been sent its own list.

---

## 2. Coverage

| Surface | Session start | Now | Remaining |
| --- | --- | --- | --- |
| REST endpoints | 42/264 (16%) | **264/264 (100%)** | — |
| `RestAPIParams` bodies | 17/107 | **108/108** | — |
| Gateway dispatch events | 44/83 (53%) | **83/83 (100%)** | 37 route to a subject with a named TODO pending their store |
| `api/**` | 158/286 (55%) | **281/286 (98%)** | 5 are R8-emptied in the reference — nothing to recover |
| `models/**` | 59/149 (40%) | **143/149 (96%)** | 6 blocked on widget/store types |
| Gson type adapters | 20/39 | **39 + 3 factories** | — |
| `textprocessing` | 1 regex | **22/22 rules, 15/15 nodes** | — |
| App base classes | 0 | **4 + router + back registry** | — |
| `attrs.xml` enums / flags | 34 / 0 | **180 / 16** | — |
| First-party custom views | 69/76 | **76/76**, 13 defects fixed | 32 still inert (no Kotlin drives them) |
| `utilities/**` | 57 files vs 416 classes | ~10 subpackages rebuilt | **~50 subpackages still 0%** |
| Stores | 51/187 (27%) | 9 thread stores added; 5 agents in flight | see §4 |
| Widgets | 132 files vs 1,155 classes (11%) | 7 agents in flight | see §4 |
| Fragments / sheets / dialogs | 31 / 4 / **0** | in flight | reference: 203 / 70 / 34 |
| ViewModels | **2** | in flight | reference: **139** |

---

## 3. Completed this session

**Wave 1 — foundation.** App framework (`AppFragment`, `AppBottomSheet`,
`AppDialog`, `AppLog`/`AppLogger` with mandatory secret redaction,
`AppScreenRouter`, `BackPressHandlerRegistry`, permissions, media-picker demux);
31 fragments + 4 sheets migrated. Store utilities (`RestCallState`,
`Batched`/`BatchManager`, `LeadingEdgeThrottle`). Gateway: all 83 events typed,
`StoreGatewayConnection` with 79 subjects and voice-state batching, plus
`StoreConnectivity`, `StoreConnectionOpen`, `StoreClientDataState`,
`NetworkMonitor`.

**Wave 2 — utilities and data.** `textprocessing` (22 rules, 15 nodes, span
corruption structurally eliminated); `IconUtils` + `MGImages` + real Fresco
config; `PermissionUtils` (4 → 20 functions, every bit verified) + `RoleUtils`,
`UserUtils`, `PresenceUtils`, `ChannelUtils`; send pipeline (`MessageQueue`,
`Backoff`, `MessageResult` with payloads, `appSubscribe` error funnel);
`mg_recycler` + `DiffCreator` + validators + `UriHandler` + NTP clock;
`attrs.xml` restoration; 7 missing view classes; 13 view defects; REST 264/264;
`api/**` 98%; `models/**` 96%.

**Wave 3 (partial).** 9 thread/forum stores with full wiring instructions.

**Wave 4 (partial).** Back navigation fixed.

---

## 4. STILL MISSING — the honest list

### Stores (5 agents in flight, outcome unknown)
Messages send pipeline, `StoreChat`, reactions, replies, slow mode, mentions;
guild stores (`StoreLurking`, member requester, permissions algorithm); channel
stores (`markUnread`, read-state inputs, `StoreChannelsSelected` rewrite);
user/social (settings writes, typing-send, MFA, required actions, experiments);
12 voice stores.

**Not assigned to anyone yet:** `StoreAnalytics` (and the whole analytics layer —
absent by design decision, not yet ratified), `StoreNotices`, `StoreBans`,
`StoreGuildIntegrations`, `StoreApplicationCommands`, `StoreApplicationInteractions`,
`StoreGuildJoinRequests`, `StoreGuildScheduledEvents`, `StoreEmbeddedActivities`,
`StoreSubscriptions`, `StorePaymentSources`, `StoreEmoji`, `StoreGuildStickers`,
`StoreSearch*`, `StoreAutocomplete`, `StoreLibrary`, `StoreGifting`,
`StoreEntitlements`, `StoreNux`, `StoreChangeLog`, `StoreAuditLog`,
`StoreDirectories`, `StoreHubs`. **37 gateway events currently route to a subject
with nothing listening**, one per missing store above.

### Widgets — 24 packages still at 0%
`hubs` (54 classes), `directories` (16), `nux` (14), `contact_sync` (9),
`stickers` (9), `guildcommunicationdisabled` (7), `bugreports` (6), `notice` (5),
`tos` (5), `search` (4), `phone` (4), `media` (3), `playstation` (3),
`announcements` (3), `changelog` (3), `guild_automod` (3),
`guild_delete_feedback` (3), `emoji` (2), `feedback` (2), `debugging` (2),
`accessibility` (1), `client` (1), `donut` (1), `quickmentions` (1).
Wave 4 covers `tos`, `notice`, `changelog`, `nux` only.

### Whole modules absent
`com.discord.floating_view_manager`, `com.discord.overlay`,
`com.discord.media_picker`, `com.discord.dialogs`, `com.discord.primitives`,
`com.discord.i18n` (partially created), analytics/science generated traits.

### Platform / manifest
- **No runtime permissions declared** — `RECORD_AUDIO`, `CAMERA`,
  `READ`/`WRITE_EXTERNAL_STORAGE`, `READ_CONTACTS`. Every permission request is
  auto-denied. 20 of 21 `uses-permission`, all `uses-feature`, and the whole
  `<queries>` block are missing (breaks package visibility on API 30+).
- 11 first-party manifest components missing: `AppActivity$Call`,
  `AppActivity$IncomingCall`, `VoiceEngineForegroundService`,
  `DiscordOverlayService`, `SamsungConnectActivity`, `UCropActivity`,
  `DiscordConnectService`, FCM messaging service, `NotificationActions` receiver,
  `NetworkDownloadReceiver`.
- `res/xml/shortcuts.xml` and `res/xml/actions.xml` exist but are never
  referenced — Direct Share and all Google App Actions are dead.

### Phases not started at all
Phase 9 notifications/FCM, Phase 10 settings/account, Phase 11 voice/calls/video
(store layer only, in flight), Phase 12 extended parity (threads/forums partly),
Phase 13 billing/integrations, Phase 14 release hardening.

---

## 5. Known broken, user-visible

| Symptom | Cause | Status |
| --- | --- | --- |
| Back closed the app | Activity's `OnBackPressedCallback` registered with the activity as `LifecycleOwner`; from **API 29** the activity's `ON_START` arrives *after* every fragment's, and the dispatcher iterates last-added-first, so the activity swallowed every press. Works on API < 28, which is why it looked correct | **FIXED** |
| Crash for accounts with a pending TOS action | `AppActivity.kt:152` routes to `"com.discord.widgets.tos.WidgetTos"`, which does not exist | Wave 4, in flight |
| Sidebar Search does nothing | `onSearch` never assigned | Wave 4, in flight |
| Blocked-users row invisible and dead | Force-hidden; callback never invoked. Feature is fully store-wired | Wave 4, in flight |
| Directory channels open an empty chat screen | `Channel.DIRECTORY` rows clickable, no directory UI | Wave 5 |
| `/template/`, `discord.new`, `/discovery`, `/query/` consumed then dropped | Owning packages at 0% | Wave 4/5 |
| Settings radios/switches rendered as checkboxes | `attrs.xml` enums stripped | **FIXED** |
| Embeds never rendered | `EmbedType` unregistered with Gson | **FIXED** |
| Voice/threads/calls/interactions had no state | 39 gateway events discarded as raw JSON | **FIXED** (parsing; stores in flight) |
| Typing indicators never sent | `setUserTyping` missing from the store | Wave 3, in flight |
| Settings changes never reach Discord | `StoreUserSettings` had no server-write half | Wave 3, in flight |

---

## 6. Decisions still owed by the user

1. **Reference bugs: reproduce or fix?** Several were faithfully reproduced and
   flagged. The consequential one is `SparseMutableList`'s iterator, which never
   visits the final element, making `contains`/`remove` unreliable for the last
   slot. Also: `StoreThreadMessages.updateFromThread` loses the message count;
   `StoreThreadsJoined.handleSearchFinish` never calls `markChanged()`;
   `StoreForumPostMessages.handleReactionAdd` has no me-guard;
   `Histogram.getReport` uses integer division. Current stance: keep, documented.
2. **`canAccessForumChannels`** is hardcoded `{ false }` pending `StoreExperiments`
   — forum channels are invisible until that lands.
3. **Analytics layer** is entirely absent (`StoreAnalytics` + generated traits).
   Deliberate so far; reference call sites on state transitions simply do not
   exist.
4. **The stray commit.** An agent committed 286 files / 30,766 insertions early
   on, mixing its work, twelve other agents' in-flight code, and documentation
   into one snapshot. Left untouched.

---

## 7. Process notes

Two rules learned the hard way, now in the `PLAN.md` concurrency protocol:

- **Only the coordinator runs Gradle.** The Gradle lock serializes task execution
  but does not protect `app/build` from concurrent writers; 13 agents produced 22
  daemons and repeated `DirectoryNotEmptyException` failures.
- **Only the coordinator runs git.**

File-ownership partitioning worked: ~20,000 lines across 14 parallel Wave-2
slices compiled with two type-inference errors between them and no cross-agent
collisions.

**The most valuable discovery:** `C:\Users\manti\Desktop\projeler\discorddecompile\out\**`
holds 4,853 Vineflower-decompiled Kotlin files, and `discord126.21-dex2jar.jar`
supports `javap`. These recover constructors, `equals`/`toString`, declaration
order, nullability, and annotations that JADX silently drops. Now the #2 trust
rank in `AGENTS.md`. It resolved `MessageQueue.doSend`, the Retrofit verb
mapping, `ClientStatuses` ordering, and several others outright — and produced
**six corrections to the original audit**, listed in `followups.md`.
