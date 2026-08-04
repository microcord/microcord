# Parity re-audit - user and friends widgets

Date: **2026-08-04**

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

Reference: Discord Android 126.21 Stable under
`C:\Users\manti\StudioProjects\discord-jadx`, supplemented by the Vineflower
DEX decompile under `C:\Users\manti\Desktop\projeler\discorddecompile\out`.

Scope: `widgets/user/**` and `widgets/friends/**` only.

## Result

All **68 of 68** top-level non-synthetic reference classes are now represented.
The 2026-07-26 report's 38 missing classes, nearby hard-error stub, absent
user-sheet state layer, absent rich presence, and alphabetical quick-switcher
inventory are structurally resolved. This is **100% physical class coverage,
not 100% parity**: current source records live call, spectate, selector, store,
and error-mapping gaps, and no complete device/live-backend matrix exists.

| Package | Reference classes | Current target files | Reference classes represented | Missing |
| --- | ---: | ---: | ---: | ---: |
| `widgets/user` | 55 | 58 | 55 | 0 |
| `widgets/friends` | 13 | 13 | 13 | 0 |
| **Total** | **68** | **71** | **68** | **0** |

Target-only files such as `ProfileEffectView`, `WidgetGlobalSearchViewModel`,
and `WidgetGroupDmPickerDialog` do not increase the numerator.

## Resolved Findings

- User account/e-mail/phone verification, private-call launcher, rich-presence
  models/holders, moderation dialogs, and profile formatting helpers now exist.
- `WidgetUserSheetViewModel`, its store-state model/helper, and the expanded
  `WidgetUserSheet` now exist. The old nullable-static-hook-only architecture is
  no longer an accurate description.
- User-sheet rich presence, voice, stage, guild roles, developer actions,
  moderation, and relationship destinations are represented.
- Search now has the model, adapter, guild rail/model, dismiss model, scoring
  strategy, frecency tracker, and a separate group-DM picker.
- Vineflower recovers `WidgetGlobalSearchScoreStrategy` scoring bodies that the
  old JADX-only report called unrecoverable; the target implementation can be
  compared against DEX-derived code rather than guessed weights.
- Friends now has the full ViewModel, adapters/loading holder, nearby manager
  and screen/ViewModel behavior, add-by-id flow, and add-user adapter.
- Existing tests cover friends section construction, exact user paths, profile
  contracts, user-sheet actions, custom status, mutual guild management, and
  navigation.

## Open Findings

### Blockers

1. `PrivateCallLauncher` remains unwired by default. Its `StoreCalls`,
   `StoreVoiceChannelSelected`, and `StoreMediaEngine` constructor defaults are
   null because `StoreStream` exposes no accessors
   (`PrivateCallLauncher.kt:24-37`). Voice/video calls invoked through this
   class can receive permissions yet never connect or ring.
2. User-sheet move-user and spectate events have no destinations.
   `WidgetUserSheet.kt:680-696` handles moderation and not-found events, then
   logs the remaining selector/spectate events because those surfaces are not
   wired. Those are reference-visible actions, not optional helpers.
3. Relationship failure messages lose abort-code semantics.
   `WidgetUserSheet.kt:700-708` always returns the generic failure because
   `RestAPIAbortMessages.ResponseResolver.getRelationshipResponse` is absent.

### Significant Residual Risk

- `WidgetUserAccountVerify.kt:78` documents a required-action/captcha state for
  which this build has no exact screen and falls back to offering both methods.
- `ModelRichPresence.kt:34` and platform rich-presence code document missing
  store/application-link access; cards fall back rather than reproducing all
  reference assets/actions.
- User-sheet store state still reports no stream when `StreamContextService` is
  unavailable (`WidgetUserSheetViewModelStoreState.kt:63`), directly affecting
  spectate/stream-preview behavior.
- Badge premium upsell currently depends on a reconstructed destination path;
  exact product/provider behavior remains bounded by project-owned billing
  configuration.
- Physical coverage does not prove nearby Bluetooth/location behavior across
  Android versions, captcha retry, call/video launch, rich presence timers,
  user-sheet restoration, RTL/large-font accessibility, or live REST results.

## Decompiler Verification

JADX still has broken regions in `WidgetUserSheet.configureProfileActionButtons`
and several rich-presence text layouts. Vineflower was checked before retaining
those caveats: it recovers the quick-switcher scoring functions, but not every
compiler-damaged UI branch with enough confidence to close runtime behavior.
This report therefore does not infer exact action visibility or rich-presence
text rules from the old nonsense output.

## Test Evidence And Gaps

Representative current checks:

- `app/src/test/java/com/discord/widgets/friends/FriendsUiTest.kt`
- `app/src/test/java/com/discord/widgets/user/UserExactPathContractsTest.kt`
- `app/src/test/java/com/discord/widgets/user/profile/UserProfileContractsTest.kt`
- `app/src/test/java/com/discord/widgets/user/usersheet/WidgetUserSheetActionsTest.kt`
- `app/src/androidTest/java/com/discord/widgets/user/MutualNavigationTest.kt`

No checks were run for this documentation-only re-audit. Existing tests do not
exercise a real private call, spectate/move-user destinations, nearby-device
matrix, provider-backed captcha, or live backend.

## Status

**OPEN - 100% physical class coverage, behavioral parity not verified.** The
report cannot be deleted while default private-call wiring and user-sheet
destinations are inert, decompiler caveats remain, and runtime/test gaps exist.

## Resumable Task List

- `app/src/main/java/com/discord/widgets/user/calls/PrivateCallLauncher.kt` | reference `app/src/main/java/com/discord/widgets/user/calls/PrivateCallLauncher.java` and `widgets/voice/call/PrivateCallLaunchUtilsKt.java` | replace null store defaults with the live voice/call graph.
- `app/src/main/java/com/discord/widgets/user/usersheet/WidgetUserSheet.kt` | reference sibling `.java` plus Vineflower equivalent | route move-user and spectate events and restore abort-code-specific relationship errors.
- `app/src/main/java/com/discord/widgets/user/usersheet/WidgetUserSheetViewModelStoreState.kt` | reference sibling `.java` plus Vineflower equivalent | provide live stream context and verify compiler-damaged state branches.
- `app/src/main/java/com/discord/widgets/user/account/WidgetUserAccountVerify.kt` | reference sibling `.java` | restore the exact captcha-required action destination.
