# Reconstruction Status

Last updated: **2026-08-04 parity re-audit**.

This is the current audit rollup. `PLAN.md` remains the roadmap and decision log.
The 19 detailed `audit-*.md` reports are authoritative for findings and citations.

## Build and verification state

| Evidence | State |
| --- | --- |
| Documentation re-audit | Completed 2026-08-04; no production code changed |
| Checks run by this re-audit | None |
| Latest recorded unit evidence | 1,678 tests |
| Latest recorded build/lint evidence | Debug/release assembly, debug lint, release lint-vital, Android-test compilation |
| Latest recorded connected evidence | 59/59 on Android 16 |
| Live-backend evidence for this re-audit | None |

The old 2026-07-26 `compileDebugKotlin` RED/in-flight-agent snapshot is retired.
Prior green evidence is not proof of the open device, provider, live-backend, or
DEX-sensitive behaviors.

## Current metrics

| Surface | Current rollup |
| --- | --- |
| Stores | 184/187 structurally represented (98.4%); 186 partial/not fully audited, 1 missing, 0 verified complete |
| Store graph | 135/145 instances (93.1%); `storesV2` 103/106 (97.2%) |
| Gateway | 83/83 reference dispatches typed and structurally routed; destination parity incomplete |
| Widgets | 1,132/1,155 exact paths (98.0%); 1,247 target files; all 45 packages represented |
| Custom views | 76/76 exact classes; 0 layout-referenced classes missing |
| Models/API/REST/Gateway | 441/460 exact FQCNs (95.9% structural lower bound) |
| REST | 264/264 effective historical routes (263 exact templates, one generalized MFA route); 107/107 `RestAPIParams` names |
| Utilities | 260/416 exact FQCNs (62.5% structural lower bound); 156 unmatched |
| Security | 0 High, 0 confirmed Medium, 3 Low, 1 unverified Medium |

Structural counts are inventories, not behavioral completion percentages.

## Report status

All 19 detailed audit reports remain open or partial. No report is eligible for
deletion because each retains at least one blocker, behavioral caveat,
broken-decompiler boundary, provider dependency, or runtime/test gap.

Store report-local findings:

| Report | Blocker | Major | Minor |
| --- | ---: | ---: | ---: |
| Core | 5 | 14 | 19 |
| Auth/navigation | 4 | 14 | 17 |
| Guilds | 4 | 9 | 18 |
| Channels/read state | 4 | 17 | 18 |
| Messages | 3 | 17 | 21 |
| Users/social | 1 | 19 | 29 |
| Voice/gateway | 0 | 10 | 13 |

These counts overlap across reports and are not project totals.

Widget/view/data reports remain OPEN despite high physical coverage:

- Widgets: 23 classified path mismatches, nine missing experiment gates, inert cross-package actions, provider gaps, and incomplete device/live matrices.
- Views: 76/76 present, but video-grid contracts, reduced motion, tooltip/overlay behavior, accessibility, RTL, and animation remain open.
- Utilities: 156 unmatched FQCNs require classification; Android-only regex/span/image/URI/drag behavior remains unverified.
- Models/API: 19 unmatched FQCNs, READY tree parsing, `authSessionIdHash` value semantics, and exact legacy MFA transport remain open.

## Resolved since the July audit

- REST grew from 42 endpoints to all but one exact historical contract template.
- Gateway grew from 44/83 routed events to 83/83 typed structural routes.
- Widget zero-coverage packages fell from 24 to 0; exact path coverage is now 98.0%.
- Store structural representation is now 184/187, and the graph maps 135/145 instances.
- All 76 first-party custom-view classes are present and first-party enum/flag attrs are restored.
- TOS, sidebar Search, directory navigation, formerly dropped deep links, blocked users, shortcuts, and App Actions are structurally reachable.
- MFA challenge handling, logout clearing, mark-unread boundary, read-state inputs, notification construction, guild profile preservation, permission fallback, reduced-motion mapping, and StoreV2 off-thread behavior are corrected.
- Former JADX blockers for notification policy, message queue ordering, chat action visibility, client status order, READY hydration branches, and multiple store handlers were resolved with Vineflower/bytecode evidence.

## Highest-priority blockers

1. Complete the production StoreStream wiring ledger: readiness, age gate, push token, phone init, guild timeout/callback, guild settings API, channel/member inputs, guild subscriptions, loader backgrounding, typing API/cooldown, and voice participants.
2. Construct or reconcile the 10 absent graph responsibilities and the five present-but-dead store files without duplicating existing substitutes.
3. Fix READY custom emoji/sticker carry-over, streaming allocation, and `ModelPayload.authSessionIdHash` value semantics.
4. Close user-reachable widget gaps: membership screening, overlay sticker suggestions, directory unread state, Nearby transport decision, remaining guild/user/settings/stage actions, experiment gates, and SMS auto-fill decision.
5. Run the missing ARM64 voice/video/screen-share/overlay and broad route, lifecycle, accessibility, RTL, large-font, malformed-input, provider, and live-backend matrices.
6. Device-test the hCaptcha WebView origin/navigation behavior; keep it unverified until observed or fail-closed.

## Evidence rule

JADX-broken output is not a basis for closure. Check Vineflower first, then
`javap`/DEX, call sites, fixtures, or runtime evidence. Items still named as
DEX-unverified in the refreshed reports remain blockers; do not replace them with
an inferred implementation or mark them resolved from target similarity.
