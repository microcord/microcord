# Parity Audit Re-audit - 2026-08-04

Current structural and source-behavior comparison of the reconstruction against
Discord Android 126.21 Stable. All 19 `audit-*.md` reports were refreshed on
2026-08-04 using the current target plus JADX, Vineflower, `javap`, DEX, and call
sites where needed.

`PLAN.md` remains the source of truth for scope and decisions. These reports are
supporting evidence, not a competing roadmap. A present file, routed event, or
passing fixture is not proof of behavioral parity.

Where JADX is broken, behavior remains **unverified** unless Vineflower, bytecode,
DEX, call-site, fixture, device, or runtime evidence settles it. No rollup item
below promotes a broken-decompiler region by inference.

## Headline coverage

| Surface | Reference | Current target | Current classification |
| --- | ---: | ---: | --- |
| Direct top-level stores | 187 | 184 represented; 2 partial without declaration; 1 missing | 98.4% structural; 0 verified complete, 186 partial, 1 missing |
| `StoreStream` graph | 145 instances | 135 mapped/constructed | 93.1% |
| `StoreStream.storesV2` | 106 | 103 | 97.2% |
| Gateway dispatch events | 83 | 83 typed, subject-routed, and listener-installed | Structural only; destination behavior remains partial |
| Widget packages | 45 | 45 represented; 0 zero-coverage packages | Package inventory closed |
| Widget source paths | 1,155 | 1,132 exact paths; 1,247 Kotlin files | 98.0% exact-path lower bound; 23 classified mismatches |
| First-party custom views | 76 | 76 exact classes; 0 layout classes missing | 100% structural; device behavior open |
| `api/**` | 286 | 276 exact declarations | 96.5% structural lower bound |
| `models/**` | 149 | 143 exact declarations | 96.0% structural lower bound |
| Models/API/REST/Gateway total | 460 | 441 exact declarations | 95.9% structural lower bound |
| Historical REST contracts | 264 contracts | 263 exact templates plus one intentional generalized MFA route | 100% effective route coverage |
| `RestAPIParams` request bodies | 107 names | 107 represented | Structural names complete |
| `utilities/**` | 416 FQCNs | 260 exact FQCNs in 287 files | 62.5% structural lower bound; 156 unmatched |

The sole exact endpoint-template mismatch is `POST auth/mfa/totp`; the target's
`POST auth/mfa/{authenticatorType}` reaches the same URL for `totp` but is not the
same Retrofit declaration. The completed `missing_endpoints.txt` inventory was deleted.

## Report status

Every report remains retained. Structural closure does not remove its behavioral,
provider, DEX, device, or live-backend verification gaps.

| Report | Current status |
| --- | --- |
| [audit-stores-core.md](audit-stores-core.md) | PARTIAL: 5 blocker, 14 major, 19 minor findings |
| [audit-stores-gap-inventory.md](audit-stores-gap-inventory.md) | OPEN: 184/187 structurally represented; 1 behavior entirely missing |
| [audit-stores-auth-nav.md](audit-stores-auth-nav.md) | PARTIAL: 4 blocker, 13 major, 17 minor findings |
| [audit-stores-guilds.md](audit-stores-guilds.md) | PARTIAL: 4 blocker, 9 major, 18 minor findings |
| [audit-stores-channels.md](audit-stores-channels.md) | PARTIAL: 4 blocker, 17 major, 18 minor findings |
| [audit-stores-messages.md](audit-stores-messages.md) | PARTIAL: 3 blocker, 17 major, 21 minor findings |
| [audit-stores-users.md](audit-stores-users.md) | PARTIAL: 1 blocker, 19 major, 29 minor findings |
| [audit-stores-voice-gateway.md](audit-stores-voice-gateway.md) | PARTIAL: 10 major, 13 minor findings; live voice open |
| [audit-widgets-gap-inventory.md](audit-widgets-gap-inventory.md) | OPEN: 23 classified path mismatches and cross-package/runtime blockers |
| [audit-widgets-chat.md](audit-widgets-chat.md) | OPEN: overlay sticker suggestions and runtime parity block closure |
| [audit-widgets-channels.md](audit-widgets-channels.md) | OPEN: membership screening and runtime parity block closure |
| [audit-widgets-guilds.md](audit-widgets-guilds.md) | OPEN: 163/166 represented; 3 inert flows |
| [audit-widgets-user-friends.md](audit-widgets-user-friends.md) | OPEN: 68/68 represented; call/user-sheet behavior incomplete |
| [audit-widgets-settings-servers.md](audit-widgets-settings-servers.md) | OPEN: 199/200 represented; store/provider wiring incomplete |
| [audit-widgets-voice-stage.md](audit-widgets-voice-stage.md) | OPEN: 105/105 represented; runtime/store bridge claims need reconciliation and device proof |
| [audit-widgets-shell-auth.md](audit-widgets-shell-auth.md) | OPEN: auth/shell structurally live; SMS auto-fill and end-to-end matrices open |
| [audit-views.md](audit-views.md) | OPEN: 76/76 present; video, reduced-motion, tooltip, overlay, and accessibility behavior open |
| [audit-utilities.md](audit-utilities.md) | OPEN: 156 exact FQCNs unmatched and Android-only behavior unverified |
| [audit-models-api.md](audit-models-api.md) | OPEN: 19 exact FQCNs unmatched; READY parsing and model semantics remain major findings |

Finding totals are report-local and overlap across reports; they must not be
summed into a project-wide defect count.

## Resolved July headlines

The following 2026-07-26 rollup defects are closed by current cited evidence:

- `WidgetTos` exists and required-action routing no longer crashes.
- `EmbedType` is registered with Gson.
- All 83 reference Gateway dispatch events are typed; raw `JsonElement` delivery is gone.
- First-party enum/flag attrs, including `cs_view_type`, are restored.
- Mark-unread uses the requested boundary; thread/forum/voice read-state inputs are populated.
- Voice `ACTIVE_STATES` includes `PAUSED`, not `ENDED`.
- Guild partial-member profile preservation, permission fallback, MFA short-circuit, logout account clearing, off-thread StoreV2 logging, and reduced-motion sticker mapping are corrected.
- `StoreNotifications` is constructed, restored, routed, reset, and exposed.
- Client identify state is sourced from `StoreClientDataState` rather than hardcoded empty state.
- All six July live dead-ends are structurally closed: TOS, Search, directories, dropped deep links, blocked users, and manifest shortcut/App Action metadata.

## Current cross-cutting blockers

- Store graph: 10 reference graph instances remain absent; initialization readiness is hardcoded; pre-logout/full reset ordering diverges; five present store files remain unconstructed.
- Production wiring: age-gate inputs/routing, push token logout, `StorePhone.init`, guild timeout init/callback, guild-settings REST, channel permission/member-list inputs, guild subscriptions, message-loader backgrounding, typing REST/cooldown, and voice-participant init remain open.
- Data: READY still builds a full JSON tree; `ModelPayload.authSessionIdHash` is omitted from copy/value semantics. The generalized modern MFA route is an accepted replacement, not an open parity blocker.
- Widgets: Nearby has no transport; nine reference experiment/feature gates and SMS auto-fill are absent; membership screening, overlay sticker suggestions, directory unread state, several guild/user/settings/stage actions, and broad runtime matrices remain open.
- Utilities/views: 156 exact utility FQCNs remain unmatched; video grid, representative-color, reduced-motion, tooltip, overlay, RTL, large-font, animation, and Android ICU behavior require focused reconciliation or device checks.
- Voice: graph construction is substantially present, but report-local widget bridge comments conflict with current `StoreStream` evidence; do not call the bridge resolved until callers are checked and ARM64/live call, video, screen-share, audio-route, overlay, background, and teardown tests pass.

## Security rollup

The refreshed [security review](security-review.md) has **0 confirmed High, 0
confirmed Medium, 3 confirmed Low, and 1 unverified Medium** finding. The prior
High and all three prior Medium findings are fixed. The unverified hCaptcha
WebView origin/navigation question must remain open until device evidence or a
fail-closed `WebViewClient` settles it.

## Verification boundary

This 2026-08-04 re-audit changed documentation only and ran no Gradle, device, or
live-backend check. The reports cite the latest recorded project-wide evidence of
1,678 unit tests, debug/release assembly, debug lint, release lint-vital,
Android-test compilation, and 59/59 connected tests on Android 16. That prior
evidence does not certify the open runtime matrices above.
