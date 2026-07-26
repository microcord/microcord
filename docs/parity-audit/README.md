# Parity Audit — 2026-07-26

Full structural comparison of every reconstructed store, widget, view, model, and
utility in this project against the reference decompile at
`C:\Users\manti\StudioProjects\discord-jadx` (Discord Android 126.21 Stable).

Produced by 16 parallel read-only audit agents. Each report cites `file:line` in
the target and class/method names in the reference. Where JADX output was broken,
the report says so explicitly instead of guessing — those regions are listed per
report under a "JADX caveats" heading and need DEX/smali inspection before the
affected behavior can be validated.

`PLAN.md` is still the source of truth for scope, status, and decisions. These
documents are the supporting evidence and must not be treated as a competing
roadmap.

## Headline coverage

| Surface | Reference | Target | Coverage |
| --- | --- | --- | --- |
| Stores | 187 non-synthetic | 51 implemented, 37 partial | 27% implemented |
| `StoreStream` store graph | 145 constructed / 106 `storesV2` | 36 / 29 | 25% |
| Gateway dispatch events | 83 | 44 routed, 39 unrouted | 53% |
| Widgets | 1,155 non-synthetic | 132 files / 427 declared types | 11.4% by file |
| Widget packages at 0% | — | 24 of 45 packages | — |
| `api/**` | 286 | 158 | 55% |
| `models/**` | 149 | 59 | 40% |
| REST endpoints | 264 unique verb+path | 42 | 16% |
| `RestAPIParams` bodies | 107 | 17 | 16% |
| `utilities/**` | 416 | 57 files | ~62 of 108 subpackages at 0% |
| First-party custom views | 76 | 69 | 91% present, 32 inert |

## Reports

### Stores
| Report | Scope |
| --- | --- |
| [audit-stores-core.md](audit-stores-core.md) | `Dispatcher`, `Store`, `StoreV2`, `ObservationDeck`, `StoreStream`, `ReadyPayloadHydrator` |
| [audit-stores-gap-inventory.md](audit-stores-gap-inventory.md) | All 187 reference stores classified IMPLEMENTED / PARTIAL / MISSING, mapped to phases |
| [audit-stores-auth-nav.md](audit-stores-auth-nav.md) | `StoreAuthentication`, `StoreNavigation`, `StoreTabsNavigation`, `StoreNotifications` |
| [audit-stores-guilds.md](audit-stores-guilds.md) | Guild stores, folders, permissions, subscriptions, stage instances |
| [audit-stores-channels.md](audit-stores-channels.md) | Channel stores, read states, user guild settings, threads gap-check |
| [audit-stores-messages.md](audit-stores-messages.md) | Message stores, loader, uploads, pins, chat state, `MessageQueue` |
| [audit-stores-users.md](audit-stores-users.md) | User, profile, notes, relationships, presence, typing, settings |
| [audit-stores-voice-gateway.md](audit-stores-voice-gateway.md) | Voice/media stores + the full 83-row gateway event table |

### Widgets and views
| Report | Scope |
| --- | --- |
| [audit-widgets-gap-inventory.md](audit-widgets-gap-inventory.md) | Whole-tree rollup, 24 zero-coverage packages, manifest routes, live dead-ends |
| [audit-widgets-chat.md](audit-widgets-chat.md) | `widgets/chat/**` (277 reference classes) |
| [audit-widgets-channels.md](audit-widgets-channels.md) | `widgets/channels/**` (114) |
| [audit-widgets-guilds.md](audit-widgets-guilds.md) | guilds, scheduled events, role subscriptions, roles (166) |
| [audit-widgets-user-friends.md](audit-widgets-user-friends.md) | user + friends (68) |
| [audit-widgets-settings-servers.md](audit-widgets-settings-servers.md) | settings, servers, forums, mobile reports (200) |
| [audit-widgets-voice-stage.md](audit-widgets-voice-stage.md) | voice, stage, status (105) |
| [audit-widgets-shell-auth.md](audit-widgets-shell-auth.md) | app shell, base classes, auth, home, tabs, share, captcha, bot UI kit |
| [audit-views.md](audit-views.md) | `views/**`, `chips_view`, `tooltips`, `embed`, `rlottie` |

### Data and platform
| Report | Scope |
| --- | --- |
| [audit-models-api.md](audit-models-api.md) | `api/**`, `models/**`, `restapi/**`, `gateway/**`, type adapters |
| [missing_endpoints.txt](missing_endpoints.txt) | The 222 missing REST endpoints |
| [audit-utilities.md](audit-utilities.md) | `utilities/**` (416 reference classes) |

## Cross-cutting defects found

These are correctness bugs, not missing features. They are live in surfaces that
phases already marked `DONE` or `IN PROGRESS`.

| Defect | Location | Effect |
| --- | --- | --- |
| `WidgetTos` route target does not exist | `app/AppActivity.kt:152` | Crash on the main route transaction for any account with a pending TOS/agreements required action |
| `EmbedType` never registered with Gson | `api/.../TypeAdapterRegistrar.kt:42-62` | Every embed's `type` deserialises to `null` |
| Untyped gateway fallback | `gateway/io/IncomingParser.kt:91` | 44 events delivered as raw `JsonElement` and discarded; the reference has no untyped fallback |
| `<enum>`/`<flag>` children stripped from `attrs.xml` | `res/values/attrs.xml` | `cs_view_type` dead across 39 layouts — every settings radio/switch renders as a checkbox with the wrong accessibility role |
| `markUnread` acks the most recent message | `StoreReadStates.kt:162`, `StoreStream.kt:236-245` | Mark-unread tells the server the channel is fully read |
| Four of ten `StoreReadStates.Inputs` never populated | `StoreStream.kt:1584-1593` | All thread and voice unreads silently discarded |
| `ACTIVE_STATES` contains `ENDED` instead of `PAUSED` | `VoiceStoreContracts.kt:23` | `isStreamActive()` inverted in both directions |
| Guild member profile-preserve flag inverted | `StoreGuilds.kt:111-116` | Member `bio`/`banner` clobbered on partial member updates |
| @everyone permission fallback inverted | `utilities/permissions/PermissionUtils.kt:22` | Returns `NONE` where reference returns `DEFAULT`; can empty the channel list during partial hydration |
| MFA challenge treated as successful login | `StoreAuthentication.kt:83` | Reference short-circuits on `loginResult.getMfa()` |
| Logout does not wipe prior account state | `StoreAuthentication.kt:171-174` | Reference calls `Persister.reset()` + `prefs.clear()` |
| Off-thread `markChanged` crashes | `StoreV2` / `StoreThreadScheduler` | Target throws where the reference only logs |
| Reduced motion maps to `NEVER_ANIMATE` | `StoreUserSettings.kt:88-90` | Reference maps only `ALWAYS_ANIMATE` → `ANIMATE_ON_INTERACTION` |
| `StoreNotifications` is dead code | — | Never constructed in `StoreStream`, never reset on logout |
| `IdentifyClientState` hardcoded empty | `StoreStream.kt:442` | Guild-hash resume permanently disabled |

## Live dead-ends

User-reachable paths in the shipping build that lead nowhere:

1. Channel sidebar **Search** button is drawn for every guild channel and DM; `onSearch` is never assigned.
2. `Channel.DIRECTORY` (type 14) rows are clickable and open the ordinary chat surface.
3. Deep links for `/template/`, `discord.new`, `/discovery`, and `/query/` are parsed and consumed, then dropped.
4. **Blocked users** settings row is force-hidden and its callback never invoked, despite the feature being fully store-wired.
5. `res/xml/shortcuts.xml` and `res/xml/actions.xml` exist but are never referenced from the manifest — Direct Share and all Google App Actions are dead.
