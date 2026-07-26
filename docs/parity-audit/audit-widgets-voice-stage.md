# Parity Audit — `widgets/voice/**`, `widgets/stage/**`, `widgets/status/**`

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`
Reference: `C:\Users\manti\StudioProjects\discord-jadx` (Discord Android 126.21, JADX)
Date: 2026-07-26. Read-only audit; no target file modified.

Phase context (`PLAN.md:640`): **Voice/calls/video = NOT STARTED**. `PLAN.md:634` Guilds/channels = IN PROGRESS,
`PLAN.md:635` Messages/uploads = IN PROGRESS, `PLAN.md:636` Users/relationships = IN PROGRESS.
Gaps that only Phase 11 would fill are marked *expected (Phase 11 not started)*.
BLOCKER is reserved for surfaces an already-IN-PROGRESS phase depends on.

---

## 1. Reference inventory (non-synthetic; `$`-named files excluded)

### `widgets/voice/**` — 66 classes

| Subpackage | Classes |
| --- | --- |
| `voice/` (1) | `VoiceUtils` |
| `voice/call/` (4) | `PrivateCallLaunchUtilsKt`, `WidgetCallFailed`, `WidgetVoiceCallIncoming`, `WidgetVoiceCallInline` |
| `voice/controls/` (6) | `AnchoredVoiceControlsView`, `ScreenShareButtonSparkleTooltip`, `VoiceControlsOutputSelectorState`, `VoiceControlsSheetSwipeTooltip`, `VoiceControlsSheetView`, `WidgetScreenShareNfxSheet` |
| `voice/feedback/` (8) | `FeedbackIssue`, `FeedbackRating`, `FeedbackSubmitter`, `FeedbackSubmitterFactory`, `FeedbackView`, `IssueDetailsFormViewModel`, `PendingFeedback`, `WidgetIssueDetailsForm` |
| `voice/feedback/call/` (3) | `CallFeedbackSheetNavigator`, `CallFeedbackSheetViewModel`, `CallFeedbackSubmitter` |
| `voice/feedback/stream/` (3) | `StreamFeedbackSheetNavigator`, `StreamFeedbackSheetViewModel`, `StreamFeedbackSubmitter` |
| `voice/fullscreen/` (11) | `CallParticipant`, `ParticipantsListItemGenerator`, `WidgetCallFullscreen` (1417 L), `WidgetCallFullscreenKt`, `WidgetCallFullscreenViewModel` (3147 L), `WidgetCallFullscreenViewModelKt`, `WidgetCallPreviewFullscreen`, `WidgetCallPreviewFullscreenViewModel`, `WidgetGuildCallOnboardingSheet`, `WidgetGuildCallOnboardingSheetViewModel`, `WidgetStartCallSheet` |
| `voice/fullscreen/grid/` (6) | `PrivateCallBlurredGridView`, `PrivateCallGridView`, `ResizingGridLayoutManager`, `VideoCallGridAdapter`, `VideoCallGridLayoutManager`, `VideoCallGridViewHolder` |
| `voice/fullscreen/stage/` (12) | `AudienceHeaderViewHolder`, `AudienceViewHolder`, `DetailsViewHolder`, `DividerViewHolder`, `MediaViewHolder`, `PrestartDetailsViewHolder`, `SpeakerViewHolder`, `StageCallAdapter`, `StageCallBottomSheetManager`, `StageCallItem` (828 L), `StageCallParticipantItem`, `StageCallViewHolder` |
| `voice/model/` (2) | `CallModel` (782 L), `CameraState` |
| `voice/settings/` (1) | `WidgetVoiceChannelSettings` |
| `voice/sheet/` (8) | `CallParticipantsAdapter` (1140 L), `NoiseCancellationTooltip`, `WidgetNoiseCancellationBottomSheet`, `WidgetNoiseCancellationBottomSheetViewModel`, `WidgetVoiceBottomSheet` (1012 L), `WidgetVoiceBottomSheetViewModel` (1420 L), `WidgetVoiceSettingsBottomSheet`, `WidgetVoiceSettingsBottomSheetViewModel` |
| `voice/stream/` (1) | `StreamNavigator` |

### `widgets/stage/**` — 31 classes

| Subpackage | Classes |
| --- | --- |
| `stage/` (6) | `GuildIdsWithVisibleStageInstanceModel`, `StageCardSpeaker`, `StageChannelAPI`, `StageChannelJoinHelper`, `StageChannelNotifications`, `StageRoles` |
| `stage/model/` (6) | `AnalyticsDiscoverySourceValues`, `DiscoveryModelKt`, `StageAnalyticsRequestToSpeakState`, `StageCallModel` (398 L), `StageChannel`, `StageMediaParticipant` |
| `stage/settings/` (2) | `LiveStageNotificationBadgeTooltip`, `LiveStageNotificationBadgeTooltipKt` |
| `stage/sheet/` (12) | `StageAudienceBlockedBottomSheetAdapter`, `StageAudienceBlockedBottomSheetViewModel`, `WidgetEndStageBottomSheet`, `WidgetStageAudienceBlockedBottomSheet`, `WidgetStageAudienceNoticeBottomSheet`, `WidgetStageAudienceNoticeManager`, `WidgetStageModeratorJoinBottomSheet`, `WidgetStageRaisedHandsBottomSheet`, `WidgetStageRaisedHandsBottomSheetAdapter`, `WidgetStageRaisedHandsBottomSheetViewModel` (670 L), `WidgetStageStartEventBottomSheet`, `WidgetStageStartEventBottomSheetViewModel` (559 L) |
| `stage/start/` (4) | `ModeratorStartStageAdapter`, `ModeratorStartStageItem`, `ModeratorStartStageViewModel`, `WidgetModeratorStartStage` |
| `stage/usersheet/` (1) | `UserProfileStageActionsView` |

### `widgets/status/**` — 8 classes

`WidgetChatStatus`, `WidgetForumPostStatus`, `WidgetForumPostStatusViewModel` (795 L),
`WidgetGlobalStatusIndicator` (463 L), `WidgetGlobalStatusIndicatorState`,
`WidgetGlobalStatusIndicatorViewModel` (1107 L), `WidgetThreadStatus`, `WidgetThreadStatusViewModel` (585 L).

---

## 2. Coverage summary

| Package | Reference classes | Covered in target | Coverage |
| --- | --- | --- | --- |
| `widgets/voice/**` | 66 | 13 (11 files; `CameraState` and `VoiceControlsOutputSelectorState` are co-located in other files) | **19.7 %** |
| `widgets/stage/**` | 31 | 2 | **6.5 %** |
| `widgets/status/**` | 8 | 3 | **37.5 %** |
| **Total** | **105** | **18** | **17.1 %** |

Target file → co-located extra classes:
- `voice/model/CallModel.kt:96` also declares `CameraState`.
- `voice/controls/VoiceControlsSheetView.kt:294` also declares `VoiceControlsOutputSelectorState`.
- `voice/controls/AnchoredVoiceControlsView.kt:218-258` adds a target-only helper
  (`AnchoredVoiceControlsPresentation` / `anchoredVoiceControlsPresentation`) with no reference counterpart.
- `voice/fullscreen/grid/PrivateCallGridView.kt:85-90` adds a target-only `companion object` with
  `columnCount/maxShownParticipants/visibleParticipantCount` (test seams; not in the reference).

---

## 3. Per-file classification and concrete gaps

Legend: **REAL** = behavior matches the reference for what the class itself does;
**PARTIAL** = renders/behaves but with missing branches, wrong resources, or no producer;
**INERT-PLACEHOLDER** = compiles and is referenced by a layout, but does nothing observable.

### 3.1 `widgets/voice/**` (11 files)

#### `voice/model/CallModel.kt` (100 L) — **PARTIAL**
- Field set is a faithful match to the reference's 22 constructor fields
  (ref `model/CallModel.java:46-69` vs target `CallModel.kt:20-42`). Derived properties
  `isConnected/isMuted/isSelfDeafened/isServerDeafened/isMeMutedByAnySource/isStreaming/isSuppressed/dmRecipient/getCallDurationMs`
  are semantically equivalent to ref `CallModel.java:667-730`.
- **The entire producer is MISSING.** The reference `Companion` provides
  `Chunk` (ref `CallModel.java:79-238`), `observeChunk(Channel)` (ref `:268`) and
  `get(channelId): Observable<CallModel>` (ref `:294`) — an 8-way `Observable.combineLatest` over
  `getUsers().observeMeId()`, `getVoiceChannelSelected().observeTimeSelectedMs()`,
  `getMediaSettings().getVoiceConfiguration()`, `getVoiceParticipants().get(channelId)` (250 ms leading-edge throttle),
  `getPermissions().observePermissionsForChannel()`, `getMediaEngine().getVideoInputDevices()`,
  `getMediaEngine().getSelectedVideoInputDevice()`, `getAudioManagerV2().observeAudioManagerState()`.
  The target `CallModel.kt` has no `companion object` producer at all — nothing in the repo ever constructs a `CallModel`
  (verified: no reference to `CallModel(` outside `widgets/voice/**`).
- The stores it would need are private-constructor data shells with **no observables and no state**:
  `stores/VoiceStoreContracts.kt:7` (`StoreApplicationStreaming`), `:30` (`StoreAudioManagerV2`),
  `:36` (`StoreMediaSettings`); `rtcconnection/RtcConnection.kt:3` (11 L, only `Metadata`);
  `rtcconnection/mediaengine/MediaEngineConnection.kt:3` (8 L, only `InputMode`);
  `stores/StoreVoiceParticipants.kt:8` (an interface only, no store).
  There is no `StoreVoiceChannelSelected`, `StoreMediaEngine`, `StoreRtcConnection`,
  or `StoreAudioManagerV2` instance anywhere in `stores/` (directory listing).
- Divergence: target `numUsersConnected` and `isMyHandRaised` are constructor inputs, matching the reference
  shape, but the reference derives them in `Companion.create` (ref `:245-262`:
  `numUsersConnected` = count of `isConnected()` participants; `isMyHandRaised` = `voiceUser.isRequestingToSpeak()`;
  `guildMaxVideoChannelMembers` falls back to `GuildMaxVideoChannelUsers.Unlimited`). None of that derivation exists.
- Divergence: `canManageEvent()` (`CallModel.kt:71`) re-implements permission masks inline
  (`MANAGE_EVENTS_BASE/MANAGE_STAGE_EVENT/MANAGE_VOICE_EVENT`, `:90-92`); the reference delegates to
  `GuildScheduledEventUtilities.Companion.canStartEventInChannel` (ref `:396`). Behaviour is plausible but unverified
  against the utility's real bitmask; treat as unproven.
- `canInvite()` (`CallModel.kt:61`) is the **only** part of this class that reaches a live store
  (`StoreStream.getStageInstances()`, a real `StoreV2` at `stores/StoreStageInstances.kt:9`).
- Severity: **MAJOR, expected (Phase 11 not started)**.

#### `voice/controls/AnchoredVoiceControlsView.kt` (258 L) — **PARTIAL**
- View-level parity is good: PTT visibility by `InputMode.PUSH_TO_TALK`, camera show/tint, speaker icon
  bluetooth/earpiece/wired/speakerphone branches, mute activated + content description, screen-share icon/description,
  disconnect click — all present (`:70-109`) and match ref `AnchoredVoiceControlsView.java:150-247`.
- **Gap — tooltip infrastructure replaced.** Reference routes the sparkle through `TooltipManager` +
  `ScreenShareButtonSparkleTooltip` (ref `:112-152`, `FloatingViewGravity.CENTER`, anchored via
  `tooltipManager.d(anchor, sparkle, tooltip, gravity, 0, 0, false, unsubscribeSignal)`) so it is deduped across the app
  and dismissed centrally. Target hand-rolls it with `PreferenceManager.getDefaultSharedPreferences` and manual
  `view.x/y` positioning (`:148-182`). `ScreenShareButtonSparkleTooltip` class is **MISSING**.
- **Gap — wrong content descriptions (inverted).** Reference sets `camera_on` when `CameraState.CAMERA_ON`
  (ref `:167`) and `camera_off` otherwise; target does the opposite (`:239`
  `if (cameraState == CameraState.CAMERA_ON) R.string.camera_off else R.string.camera_on`).
  Accessibility label is wrong in both states. Severity **MINOR**.
- **Gap — wrong string.** Reference non-streaming screen-share description is `R.string.screenshare_screen`
  (ref `:230`); target uses `R.string.mobile_stream_screen_share` (`:256`). **MINOR**.
- **Gap — no caller.** Present only in `res/layout/widget_voice_bottom_sheet.xml:8`; the owning fragment
  (`WidgetVoiceBottomSheet`) is an 8-line stub, so `configureUI` is never invoked and `setOnPttPressedListener`
  is never registered. The view is therefore inert at runtime despite being individually well-built.
- Severity: **MAJOR, expected (Phase 11 not started)** for the missing caller; MINOR for the two string bugs
  (those are cheap to fix now and are genuine defects, not phase gaps).

#### `voice/controls/VoiceControlsSheetView.kt` (304 L) — **PARTIAL**
- All 24 `configureUI` parameters and all sub-configure methods present and mapped to the reference
  (`configureMute/configureOutput/configureVideo/configureScreenShare/configureInvites/configureStreamVolume/
  configureMoveToAudience/configureRaiseHand`, `:153-268` vs ref `VoiceControlsSheetView.java:65-414`).
  `getPeekHeight`, `handleSheetState`, `setOnPTTListener`, `onAttachedToWindow` width-clamp all present
  and match ref `:418-448`.
- **JADX caveat**: reference visibility ternaries are decompiled as `... ? false : false ? 0 : 8`
  (e.g. ref `:84`, `:172`, `:209`, `:243`) — broken output. The target's resolutions look correct but are
  **not verifiable from this decompile**; do not treat those specific branches as confirmed.
- **Gap — wrong drawables/attrs (5 confirmed divergences):**
  - muted mic icon: ref `ic_mic_mute_red_strike_24dp` (ref `:127`) vs target `ic_mute_v2_muted` (`:160`).
  - streaming screen-share icon: ref `ic_mobile_screenshare_end_24dp` (ref `:243`) vs target `ic_stop_stream` (`:199`).
  - secondary screen-share label when streaming: ref `R.string.stop_streaming` (ref `:239`) vs target
    `R.string.mobile_stream_stop_sharing` (`:207`).
  - moderator raise-hand icon: ref `ic_stage_raised_hand_list` + `R.string.request_to_speak_area_title` (ref `:199`)
    vs target `ic_stage_invite_speak` + `R.string.request_to_speak_accessibility_text` (`:253`, `:261`).
  - active-button icon tint attr: ref `R.attr.call_controls_active_button_icon_color` (ref `:329`) vs target
    `R.attr.colorBackgroundPrimary` (`:278`).
- **Gap — "is stage" is derived from the wrong source.** Reference gates the output selector, the screen-share
  buttons and the invite buttons on `ChannelUtils.D(callModel.getChannel())` i.e. *channel type is stage*
  (ref `:411`, `:237`, `:415`). Target derives `isStage = stageRoles != null` (`:113`) and passes that to
  `configureOutput/configureScreenShare/configureInvites`. When `stageRoles` is null on a stage channel
  (or non-null on a non-stage channel) the visibility set diverges. **MAJOR** correctness gap.
- **Gap — `VoiceControlsSheetSwipeTooltip` MISSING** (ref `controls/VoiceControlsSheetSwipeTooltip.java`),
  so the first-run "swipe up for more controls" tooltip does not exist.
- **Gap — no caller.** Only reference is `res/layout/widget_call_fullscreen.xml:40` (and the `layout-v22` twin);
  `WidgetCallFullscreen` does not exist in the target, so `configureUI` is never invoked.
- Severity: **MAJOR, expected (Phase 11 not started)**; the resource divergences are **MINOR** but are real defects.

#### `voice/controls/VoiceControlsOutputSelectorState` (in `VoiceControlsSheetView.kt:294`) — **PARTIAL**
- All five enum constants present with the right `isButtonActive`/`showMoreOptions` flags.
- **Gap — wrong drawable on two constants.** Reference `SPEAKER_OFF` and `SPEAKER_OFF_AND_MORE` use
  `ic_sound_quiet_24dp` (ref `VoiceControlsOutputSelectorState.java:12,15`); target uses `ic_sound_24dp`
  for both (`VoiceControlsSheetView.kt:300,303`). The "speaker off" state renders the wrong icon. **MINOR**.

#### `voice/controls/WidgetScreenShareNfxSheet.kt` (66 L) — **PARTIAL**
- Argument keys and `VoiceBottomSheetParams` shape match the reference exactly
  (`:42-44`, `:36-39` vs ref `WidgetScreenShareNfxSheet.java:164-174`, `:76-82`).
- **Gap — `canShow()` is not persisted.** Reference reads/writes
  `SharedPreferencesProvider.get().getBoolean("CACHE_KEY_SCREEN_SHARE_NFX_SHEET_SHOWN")` (ref `:52-54`, `:64-67`);
  target uses an in-memory `private var shown` (`:46-48`), so the NFX sheet reappears after every process restart.
- **Gap — `maybeNavigateToVoiceBottomSheet()` MISSING.** Reference navigates back to
  `WidgetCallPreviewFullscreen.launch(...)` when `TextInVoiceFeatureFlag` is on, else
  `WidgetVoiceBottomSheet.show(...)` — invoked from both `onCancel` and the cancel-button click
  (ref `:176-190`, `:204-207`, `:219-226`). Target's cancel button just `dismiss()`es (`:25`) and there is no `onCancel`.
- **Gap — screen-capture flow is hand-rolled.** Reference calls `StreamNavigator.requestStartStream(this)` and
  `StreamNavigator.handleActivityResult(...)` (ref `:213`, `:200`). Target directly builds a
  `MediaProjectionManager.createScreenCaptureIntent()` and, on `RESULT_OK`, only `dismiss()`es (`:20-34`) —
  **no stream is ever started**. `voice/stream/StreamNavigator` is MISSING.
- Severity: **MAJOR, expected (Phase 11 not started)**.

#### `voice/call/WidgetVoiceCallInline.kt` (63 L) — **INERT-PLACEHOLDER**
- Evidence: `companion object { @JvmField var stateSource; @JvmField var onPushToTalkChanged }` (`:60-61`)
  are **never assigned anywhere in the repo** (grepped `app/src/main/java`; only `WidgetChatStatus.onJumpToMessage`
  is wired, at `stores/StoreStream.kt:416`). Consequently `stateSource?.invoke() ?: Observable.just(State())`
  (`:34`) always yields `State(activeCall=false, pushToTalk=false)`, so `updateVisibility` (`:53-57`)
  keeps the fragment `GONE` forever, and the PTT touch listener (`:22-28`) invokes a null callback and
  reports `isEnabled = false`.
- Missing vs reference (`call/WidgetVoiceCallInline.java`):
  `Model` / `Model.VoiceConnected` producer combining `getVoiceChannelSelected().observeSelectedChannel()`,
  `getMediaSettings().getInputMode()` and `StreamContextService().getForActiveStream()` (ref `:90-131`);
  `ViewVisibilityObserverProvider.get(INLINE_VOICE_FEATURE).updateVisibility(...)` (ref `:290`);
  the real PTT press wiring via `OnPressListener` plus PTT-button registration/deregistration with the
  global PTT registrar `b.C0038b.a().b(button)` / `.c(button)` (ref `:299-302`, `:328`);
  `setWindowInsetsListeners()` bottom-inset padding (ref `:306-315`).
- Referenced from `res/layout/widget_home_panel_center_chat.xml:12`, so it *is* on-screen and does nothing.
- Severity: **MAJOR, expected (Phase 11 not started)**.

#### `voice/feedback/FeedbackRating.kt` (8 L) — **REAL**
- Exact match with ref `feedback/FeedbackRating.java` (4 constants, same `analyticsValue` strings).

#### `voice/feedback/FeedbackIssue.kt` (37 L) — **REAL** (one structural note)
- All 24 constants and all `reasonStringRes` values match ref `feedback/FeedbackIssue.java:10-33` exactly.
- Note: reference implements `MGRecyclerDataPayload` (ref `:10`, `getKey()`, `getType()`); target exposes plain
  `val key`/`val type` (`:35-36`) because the target has no `MGRecycler*` infrastructure. Cosmetic. **MINOR**.

#### `voice/feedback/FeedbackView.kt` (108 L) — **PARTIAL**
- `updateView(...)` signature and every branch (rating container visibility, prompt text, three rating
  click handlers, `isSelected` per rating, issue header/card/recycler visibility, issue list) match
  ref `feedback/FeedbackView.java:145-186`.
- **Gap — issue rows are not the reference layout.** Reference `IssueViewHolder` inflates
  `R.layout.selectable_list_item` (ref `:96`); target constructs a bare `TextView` with
  `R.style.UiKit_Settings_Item` (`:86`). Row chrome (selectable background, padding, dividers) differs. **MINOR**.
- **Gap — no diffing.** Reference uses `MGRecyclerAdapterSimple.setData` (diffed); target uses
  `notifyDataSetChanged()` (`:103`). **MINOR**.
- **Gap — no caller.** `res/layout/widget_feedback_sheet.xml:8` hosts it, but no fragment/ViewModel drives it —
  the whole `voice/feedback` pipeline (`FeedbackSubmitter`, `FeedbackSubmitterFactory`, `PendingFeedback`,
  `IssueDetailsFormViewModel`, `WidgetIssueDetailsForm`, and both `call/` and `stream/` submitter trios) is MISSING.
- Severity: **MAJOR, expected (Phase 11 not started)**.

#### `voice/fullscreen/grid/PrivateCallGridView.kt` (91 L) — **REAL**
- Row chunking, order-change rebuild, per-user `VoiceUserView` caching, avatar size 92 dp, 2 dp margins,
  6 dp ring margin, `setAnimateAvatarWhenRinging(true)`, `setFadeWhenDisconnected(true)`, `isSelected = false`,
  the one-on-one spacer at index 1, the `+N` overflow tile, portrait 3-col/9-max and landscape 6-col/6-max
  all match ref `fullscreen/grid/PrivateCallGridView.java:57-170`.
- **Gap — wrong avatar dimen.** Reference passes `R.dimen.avatar_size_unrestricted` to `VoiceUserView.a(...)`
  (ref `:80`); target passes `R.dimen.avatar_size_large` (`:58`). **MINOR**.
- No caller (`res/layout/widget_call_fullscreen_private_call.xml:6`,
  `res/layout/widget_voice_call_incoming.xml:9`) — both owning fragments are MISSING.
  Expected (Phase 11 not started).

#### `voice/fullscreen/grid/PrivateCallBlurredGridView.kt` (70 L) — **REAL**
- Grid + `RealtimeBlurView` overlay stack, 64 dp blur radius, alpha 204 overlay, 2-col portrait / 3-col landscape,
  4/6 max participants, full-width span for the trailing odd tile and for the 2-participant case, order-change
  rebuild, per-user `SimpleDraweeView` cache — all match ref
  `fullscreen/grid/PrivateCallBlurredGridView.java:36-101`.
- **Gap — wrong overlay colour source.** Reference uses `R.color.primary_dark_600` (ref `:45`);
  target resolves `?attr/colorBackgroundPrimary` (`:61`). Theme-dependent, so the blurred backdrop tint differs. **MINOR**.
- **Gap — avatar loading.** Reference uses `IconUtils.setIcon$default(view, user, ...)` (ref `:91`), which supplies
  the default-avatar fallback; target uses `avatarUrl(user, guildMember)` + `setImageURI` (`:44`) with no fallback
  for a null/absent avatar. **MINOR**.
- No caller (layouts only). Expected (Phase 11 not started).

#### `voice/sheet/WidgetVoiceBottomSheet.kt` (8 L) — **INERT-PLACEHOLDER**
- The file contains only `enum class FeatureContext { HOME, FULLSCREEN_CALL }` (`:4-7`), which does match
  ref `sheet/WidgetVoiceBottomSheet.java:457-460`. Everything else is absent.
- Missing: the entire 1012-line bottom sheet (`ViewState`/`CenterContent`/`BottomContent` interfaces at
  ref `:465-472`, participant list, connection-status header, invite option, deafen toggle,
  noise-cancellation indicator, `Companion.show(fragmentManager, channelId, forwardToFullscreen, featureContext)`)
  plus `WidgetVoiceBottomSheetViewModel` (1420 L) and `CallParticipantsAdapter` (1140 L).
- Because `WidgetScreenShareNfxSheet.maybeNavigateToVoiceBottomSheet` and
  `AnchoredVoiceControlsView` both depend on this sheet, the whole `voice/controls` surface is unreachable.
- Severity: **MAJOR, expected (Phase 11 not started)**.

### 3.2 `widgets/stage/**` (2 files)

#### `stage/StageRoles.kt` (27 L) — **REAL**
- Faithful `@JvmInline value class` reconstruction of the reference's inline-class bit flags:
  `IS_SPEAKER=1`, `IS_MODERATOR=2`, `IS_INVITED_TO_SPEAK=4` (`:23-25` vs ref `stage/StageRoles.java:81-83`),
  `AUDIENCE` constant (`:11`), the `operator fun invoke(isSpeaker, isModerator, isInvitedToSpeak)` factory
  (`:13-21` vs ref `:113-122`), and `isSpeaker/isModerator/isInvitedToSpeak/isAudience` accessors.
- Only consumed by `VoiceControlsSheetView.kt` and `UserProfileStageActionsView.kt`; nothing in the target
  ever *computes* a `StageRoles` from voice state (the reference derives it in `StageCallModel`/
  `StageMediaParticipant`, both MISSING).

#### `stage/usersheet/UserProfileStageActionsView.kt` (51 L) — **PARTIAL → effectively inert**
- `ViewState` field set matches ref `usersheet/UserProfileStageActionsView.java:32-40` exactly
  (8 fields, same names). `updateView` root-visibility gate
  (`userInSameVoiceChannel && canMuteMembers`, `:39`) matches ref `:242`.
  Invite/move visibility rules match ref `:245-251`.
- **Gap — wrong string for the "is me" case.** Reference uses `R.string.moderator_raise_own_hand`
  (ref `:247`); target uses `R.string.stage_channel_user_invited_to_speak` (`:43`).
  The moderator's own row shows the wrong label. **MINOR**.
- **Gap — never driven.** `WidgetUserSheet.kt:135` hard-sets
  `findViewById(R.id.user_sheet_profile_stage_actions_view).visibility = View.GONE` and never calls
  `updateView`, `setOnInviteToSpeak` or `setOnMoveToAudience`. The view is dead code at runtime even though
  it is in `res/layout/widget_user_sheet.xml:25`.
- Severity: the *view* gap is expected (Phase 11/12), but "the user sheet permanently hides a whole
  action block with a hardcoded `GONE`" lands inside the IN-PROGRESS Users/relationships phase →
  **BLOCKER for Users/relationships** (see §5, gap #1).

### 3.3 `widgets/status/**` (3 files)

#### `status/WidgetChatStatus.kt` (95 L) — **PARTIAL** (the most-wired file in this slice)
- Genuinely wired: `defaultStateSource()` (`:78-81`) combines the real
  `StoreStream.getChannelsSelected().observeSelectedChannel()` and
  `StoreStream.getReadStates().getUnreadMarkerForSelectedChannel()`; the jump-to-message callback is installed
  at `stores/StoreStream.kt:416`; mark-as-read falls back to the real
  `StoreStream.getReadStates().markAsRead(channelId)` (`:35`).
- **Gap — the unread-validity gate is missing.** Reference `Model.Companion.get()` computes `isUnreadValidObs`:
  for the selected channel it compares `StoreMessageAck.observeForChannel(id)` (200 ms debounce) against
  `StoreMessagesMostRecent.observeRecentMessageIds(id)` (200 ms debounce, `take(1)`) and only emits `true`
  once the last ack is older than the most recent message; `unreadVisible` is
  `isUnreadValid && count > 0 && channelId > 0` (ref `WidgetChatStatus.java:79-105`, `:61-70`).
  Target's visibility is just `channelId > 0 && messageId > 0 && unreadCount > 0` (`:58`), with the
  channel match done as `unread.marker.channelId != selectedChannelId` (`:90`). The banner will flash
  for already-acked channels.
- **Gap — detached-message cap is missing.** Reference passes `StoreMessages.getAllDetached()` and clamps the
  count to **25** when the channel is detached, **50** otherwise, setting `isUnreadEstimate = count >= cap`
  (ref `:64-70`). Target hardcodes 50 (`:92`).
- **Gap — throttling missing.** Reference wraps the combine in
  `ObservableWithLeadingEdgeThrottle.combineLatest(..., 500 ms)` + `computationLatest()` (ref `:100-106`);
  target only `distinctUntilChanged()` (`:42`).
- **Gap — timestamp rendering diverges.** Reference uses
  `TimeUtils.renderUtcDate(TimeUtils.parseSnowflake(messageId), context)` (ref `:130`); target hand-rolls the
  snowflake epoch and uses `DateUtils.getRelativeTimeSpanString(..., MINUTE_IN_MILLIS)` (`:65-69`), producing
  "5 minutes ago"-style text instead of the reference's rendered UTC date. Visible text difference.
- **Gap — `onMarkRead` is never assigned** (only `onJumpToMessage` is, at `stores/StoreStream.kt:416`);
  it falls back correctly, so this is benign, but the two-callback design is unused surface.
- The `{count}` / `{timestamp}` manual substitution (`:73-75`) matches the actual resource strings
  (`res/values/strings.xml:6806`, `:6808`) and the `new_messages_count` plural
  (`res/values/plurals.xml:1027`), so that part is correct.
- Severity: **MAJOR (Messages/uploads is IN PROGRESS)** — see §5 gap #2.

#### `status/WidgetThreadStatus.kt` (75 L) — **PARTIAL → interaction INERT**
- Renders the three notice strings (`thread_header_notice_locked/archived/join`, `:61-64`) matching
  ref `WidgetThreadStatus.java:159-166`, and the Join/Unarchive button labels (`:66`).
- **Gap — no ViewModel.** `WidgetThreadStatusViewModel` (585 L) is MISSING: no
  `StoreThreadsActiveJoined` / `StorePermissions` / `StoreExperiments` store-state observation
  (ref `WidgetThreadStatusViewModel.java:76`), no `Status.Hide/Archived/Unjoined` sealed model
  (ref `:143-144`), no `ViewState(isLoading, threadStatus)` (ref `:402-404`).
- **Gap — joined-state is a placeholder.** Target derives `joined` as `channel.member != null` in its default
  state source (`:31-32`) instead of consulting the active-joined-threads store.
- **Gap — `Status.Archived.canArchive` is missing.** Reference hides the button unless the user may unarchive
  (ref `:157`); target shows it purely on whether a static callback was assigned (`:67`).
- **Gap — no REST, no error events.** Reference `onUnarchiveTapped()` issues
  `api.editThread(channelId, ThreadSettings(archived=false, locked=… if moderator))` with loading state and
  emits `Event.Error(code)` rendered through `RestAPIAbortMessages.getAbortCodeMessageResId` as a toast
  (ref `:563-575`, `WidgetThreadStatus.java:118-123`). None of that exists; `onJoin`/`onUnarchive` are
  `@JvmField var`s (`:72-73`) **never assigned anywhere**, so `button.visibility` is always `GONE` (`:67`)
  and the widget can never be actioned.
- **Gap — missing button tints.** Reference tints the button `?attr/colorBackgroundAccent` when archived and
  `?attr/color_brand_500` when unjoined (ref `:167`, `:181`); target sets neither.
- Severity: **MAJOR (Messages/uploads IN PROGRESS)**.

#### `status/WidgetForumPostStatus.kt` (89 L) — **PARTIAL → interaction INERT**
- Message-count text, reaction visibility, separator visibility, follow/unfollow label and the loading
  spinner swap are present (`:66-80`) and structurally mirror ref `WidgetForumPostStatus.java:78-96`, `:134-149`.
- **Gap — no ViewModel.** `WidgetForumPostStatusViewModel` (795 L) is MISSING, so none of
  `isFollowedThread`, `followRequestInProgress`, `guildId`, `channelId`, `isArchivedThread`, `messageCount`,
  `reaction`, `messageId`, `isDefaultReaction`, `hasAddReactionPermission`, `myUserId` is computed
  (ref `ViewState.Valid` accessors used throughout `WidgetForumPostStatus.java:48-113`).
- **Gap — follow state is a placeholder** (`channel.member?.let { true }`, `:44`), not the followed-thread store.
- **Gap — follow button styling missing.** Reference switches icon `ic_check_16dp` ↔ `ic_bell_16dp` and colour
  `R.color.brand_500` ↔ `?attr/primary_300`, and tints the compound drawable (ref `:50-60`).
  Target sets only the text (`:78`).
- **Gap — reaction styling/permission missing.** Reference applies `meTextColor` (default vs custom reaction),
  and sets a `bg_pressed_highlight` background only when `hasAddReactionPermission` (ref `:100-105`).
  Target sets neither and gates `isEnabled` purely on whether a static callback exists (`:70`).
- **Gap — no archived-thread handling.** Reference's `onFollowPress`/`onReactionPress` take
  `isArchivedThread` and go through the ViewModel (which unarchives / prompts) (ref `:63-70`, `:106-113`).
- **Gap — callbacks never assigned.** `onFollowChanged`, `onReaction`, `onReactionLongPress` (`:85-87`)
  are **never set anywhere in the repo**, so both the follow row and the reaction are dead.
- Severity: **MAJOR (Messages/uploads IN PROGRESS)**.

---

## 4. Full coverage table (reference → target)

### 4.1 `widgets/voice/**`

| Reference class | Target | Notes |
| --- | --- | --- |
| `voice/VoiceUtils` | **MISSING** | `handleCallChannel` / `handleConnectToEventChannel`; consumed by `WidgetChannelsList$onViewBound$3` in the reference |
| `voice/call/PrivateCallLaunchUtilsKt` | **MISSING** | |
| `voice/call/WidgetCallFailed` | **MISSING** | |
| `voice/call/WidgetVoiceCallIncoming` | **MISSING** | its layout `widget_voice_call_incoming.xml` exists with no owner |
| `voice/call/WidgetVoiceCallInline` | `voice/call/WidgetVoiceCallInline.kt` | INERT-PLACEHOLDER |
| `voice/controls/AnchoredVoiceControlsView` | `voice/controls/AnchoredVoiceControlsView.kt` | PARTIAL |
| `voice/controls/ScreenShareButtonSparkleTooltip` | **MISSING** | replaced by a raw SharedPreferences key |
| `voice/controls/VoiceControlsOutputSelectorState` | `VoiceControlsSheetView.kt:294` | PARTIAL (2 wrong drawables) |
| `voice/controls/VoiceControlsSheetSwipeTooltip` | **MISSING** | |
| `voice/controls/VoiceControlsSheetView` | `voice/controls/VoiceControlsSheetView.kt` | PARTIAL |
| `voice/controls/WidgetScreenShareNfxSheet` | `voice/controls/WidgetScreenShareNfxSheet.kt` | PARTIAL |
| `voice/feedback/FeedbackIssue` | `voice/feedback/FeedbackIssue.kt` | REAL |
| `voice/feedback/FeedbackRating` | `voice/feedback/FeedbackRating.kt` | REAL |
| `voice/feedback/FeedbackSubmitter` | **MISSING** | |
| `voice/feedback/FeedbackSubmitterFactory` | **MISSING** | |
| `voice/feedback/FeedbackView` | `voice/feedback/FeedbackView.kt` | PARTIAL |
| `voice/feedback/IssueDetailsFormViewModel` | **MISSING** | |
| `voice/feedback/PendingFeedback` | **MISSING** | |
| `voice/feedback/WidgetIssueDetailsForm` | **MISSING** | |
| `voice/feedback/call/CallFeedbackSheetNavigator` | **MISSING** | |
| `voice/feedback/call/CallFeedbackSheetViewModel` | **MISSING** | |
| `voice/feedback/call/CallFeedbackSubmitter` | **MISSING** | |
| `voice/feedback/stream/StreamFeedbackSheetNavigator` | **MISSING** | |
| `voice/feedback/stream/StreamFeedbackSheetViewModel` | **MISSING** | |
| `voice/feedback/stream/StreamFeedbackSubmitter` | **MISSING** | |
| `voice/fullscreen/CallParticipant` | **MISSING** | |
| `voice/fullscreen/ParticipantsListItemGenerator` | **MISSING** | |
| `voice/fullscreen/WidgetCallFullscreen` | **MISSING** | layout `widget_call_fullscreen.xml` exists with no owner |
| `voice/fullscreen/WidgetCallFullscreenKt` | **MISSING** | |
| `voice/fullscreen/WidgetCallFullscreenViewModel` | **MISSING** | 3147 L |
| `voice/fullscreen/WidgetCallFullscreenViewModelKt` | **MISSING** | |
| `voice/fullscreen/WidgetCallPreviewFullscreen` | **MISSING** | |
| `voice/fullscreen/WidgetCallPreviewFullscreenViewModel` | **MISSING** | |
| `voice/fullscreen/WidgetGuildCallOnboardingSheet` | **MISSING** | |
| `voice/fullscreen/WidgetGuildCallOnboardingSheetViewModel` | **MISSING** | |
| `voice/fullscreen/WidgetStartCallSheet` | **MISSING** | |
| `voice/fullscreen/grid/PrivateCallBlurredGridView` | `…/grid/PrivateCallBlurredGridView.kt` | REAL |
| `voice/fullscreen/grid/PrivateCallGridView` | `…/grid/PrivateCallGridView.kt` | REAL |
| `voice/fullscreen/grid/ResizingGridLayoutManager` | **MISSING** | |
| `voice/fullscreen/grid/VideoCallGridAdapter` | **MISSING** | |
| `voice/fullscreen/grid/VideoCallGridLayoutManager` | **MISSING** | |
| `voice/fullscreen/grid/VideoCallGridViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/AudienceHeaderViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/AudienceViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/DetailsViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/DividerViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/MediaViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/PrestartDetailsViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/SpeakerViewHolder` | **MISSING** | |
| `voice/fullscreen/stage/StageCallAdapter` | **MISSING** | |
| `voice/fullscreen/stage/StageCallBottomSheetManager` | **MISSING** | |
| `voice/fullscreen/stage/StageCallItem` | **MISSING** | 828 L |
| `voice/fullscreen/stage/StageCallParticipantItem` | **MISSING** | |
| `voice/fullscreen/stage/StageCallViewHolder` | **MISSING** | |
| `voice/model/CallModel` | `voice/model/CallModel.kt` | PARTIAL (data only, no producer) |
| `voice/model/CameraState` | `CallModel.kt:96` | REAL |
| `voice/settings/WidgetVoiceChannelSettings` | **MISSING** | |
| `voice/sheet/CallParticipantsAdapter` | **MISSING** | 1140 L |
| `voice/sheet/NoiseCancellationTooltip` | **MISSING** | |
| `voice/sheet/WidgetNoiseCancellationBottomSheet` | **MISSING** | |
| `voice/sheet/WidgetNoiseCancellationBottomSheetViewModel` | **MISSING** | |
| `voice/sheet/WidgetVoiceBottomSheet` | `voice/sheet/WidgetVoiceBottomSheet.kt` | INERT-PLACEHOLDER (enum only) |
| `voice/sheet/WidgetVoiceBottomSheetViewModel` | **MISSING** | 1420 L |
| `voice/sheet/WidgetVoiceSettingsBottomSheet` | **MISSING** | |
| `voice/sheet/WidgetVoiceSettingsBottomSheetViewModel` | **MISSING** | |
| `voice/stream/StreamNavigator` | **MISSING** | |

### 4.2 `widgets/stage/**`

| Reference class | Target |
| --- | --- |
| `stage/GuildIdsWithVisibleStageInstanceModel` | **MISSING** |
| `stage/StageCardSpeaker` | **MISSING** |
| `stage/StageChannelAPI` | **MISSING** |
| `stage/StageChannelJoinHelper` | **MISSING** |
| `stage/StageChannelNotifications` | **MISSING** |
| `stage/StageRoles` | `stage/StageRoles.kt` — REAL |
| `stage/model/AnalyticsDiscoverySourceValues` | **MISSING** |
| `stage/model/DiscoveryModelKt` | **MISSING** |
| `stage/model/StageAnalyticsRequestToSpeakState` | **MISSING** |
| `stage/model/StageCallModel` | **MISSING** (398 L) |
| `stage/model/StageChannel` | **MISSING** |
| `stage/model/StageMediaParticipant` | **MISSING** |
| `stage/settings/LiveStageNotificationBadgeTooltip` | **MISSING** |
| `stage/settings/LiveStageNotificationBadgeTooltipKt` | **MISSING** |
| `stage/sheet/StageAudienceBlockedBottomSheetAdapter` | **MISSING** |
| `stage/sheet/StageAudienceBlockedBottomSheetViewModel` | **MISSING** |
| `stage/sheet/WidgetEndStageBottomSheet` | **MISSING** |
| `stage/sheet/WidgetStageAudienceBlockedBottomSheet` | **MISSING** |
| `stage/sheet/WidgetStageAudienceNoticeBottomSheet` | **MISSING** |
| `stage/sheet/WidgetStageAudienceNoticeManager` | **MISSING** |
| `stage/sheet/WidgetStageModeratorJoinBottomSheet` | **MISSING** |
| `stage/sheet/WidgetStageRaisedHandsBottomSheet` | **MISSING** |
| `stage/sheet/WidgetStageRaisedHandsBottomSheetAdapter` | **MISSING** |
| `stage/sheet/WidgetStageRaisedHandsBottomSheetViewModel` | **MISSING** (670 L) |
| `stage/sheet/WidgetStageStartEventBottomSheet` | **MISSING** |
| `stage/sheet/WidgetStageStartEventBottomSheetViewModel` | **MISSING** (559 L) |
| `stage/start/ModeratorStartStageAdapter` | **MISSING** |
| `stage/start/ModeratorStartStageItem` | **MISSING** (583 L) |
| `stage/start/ModeratorStartStageViewModel` | **MISSING** |
| `stage/start/WidgetModeratorStartStage` | **MISSING** |
| `stage/usersheet/UserProfileStageActionsView` | `stage/usersheet/UserProfileStageActionsView.kt` — PARTIAL |

### 4.3 `widgets/status/**`

| Reference class | Target |
| --- | --- |
| `status/WidgetChatStatus` | `status/WidgetChatStatus.kt` — PARTIAL |
| `status/WidgetForumPostStatus` | `status/WidgetForumPostStatus.kt` — PARTIAL |
| `status/WidgetForumPostStatusViewModel` | **MISSING** (795 L) |
| `status/WidgetGlobalStatusIndicator` | **MISSING** (463 L) |
| `status/WidgetGlobalStatusIndicatorState` | **MISSING** |
| `status/WidgetGlobalStatusIndicatorViewModel` | **MISSING** (1107 L) |
| `status/WidgetThreadStatus` | `status/WidgetThreadStatus.kt` — PARTIAL |
| `status/WidgetThreadStatusViewModel` | **MISSING** (585 L) |

### 4.4 MISSING grouped by feature cluster

| Cluster | Missing count | Classes |
| --- | --- | --- |
| **Fullscreen call UI + video grid** | 15 | `WidgetCallFullscreen(+Kt/ViewModel/ViewModelKt)`, `WidgetCallPreviewFullscreen(+ViewModel)`, `WidgetGuildCallOnboardingSheet(+ViewModel)`, `WidgetStartCallSheet`, `CallParticipant`, `ParticipantsListItemGenerator`, `ResizingGridLayoutManager`, `VideoCallGridAdapter/LayoutManager/ViewHolder` |
| **Stage call rendering (fullscreen)** | 12 | all of `voice/fullscreen/stage/**` |
| **Stage sheets/start/model/notifications** | 29 | all of `widgets/stage/**` except `StageRoles`, `UserProfileStageActionsView` |
| **Voice bottom sheets + participants** | 7 | `WidgetVoiceBottomSheetViewModel`, `CallParticipantsAdapter`, `WidgetVoiceSettingsBottomSheet(+ViewModel)`, `WidgetNoiseCancellationBottomSheet(+ViewModel)`, `NoiseCancellationTooltip` (+ the body of `WidgetVoiceBottomSheet`) |
| **Call feedback pipeline** | 12 | `FeedbackSubmitter`, `FeedbackSubmitterFactory`, `PendingFeedback`, `IssueDetailsFormViewModel`, `WidgetIssueDetailsForm`, `voice/feedback/call/**` (3), `voice/feedback/stream/**` (3) |
| **Private call entry/incoming/failed** | 3 | `PrivateCallLaunchUtilsKt`, `WidgetCallFailed`, `WidgetVoiceCallIncoming` |
| **Voice entry points / navigation** | 2 | `VoiceUtils`, `StreamNavigator` |
| **Tooltips** | 3 | `ScreenShareButtonSparkleTooltip`, `VoiceControlsSheetSwipeTooltip`, `NoiseCancellationTooltip` |
| **Voice channel settings** | 1 | `WidgetVoiceChannelSettings` |
| **Status ViewModels + global indicator** | 5 | `WidgetChatStatus`'s producer logic, `WidgetForumPostStatusViewModel`, `WidgetThreadStatusViewModel`, `WidgetGlobalStatusIndicator(+State/ViewModel)` |

---

## 5. Top 12 gaps by severity

1. **`WidgetUserSheet` hard-hides the stage actions block.**
   `widgets/user/usersheet/WidgetUserSheet.kt:135` sets `user_sheet_profile_stage_actions_view` to `GONE`
   unconditionally and never calls `UserProfileStageActionsView.updateView` / `setOnInviteToSpeak` /
   `setOnMoveToAudience`. Users/relationships is IN PROGRESS and the user sheet is its primary surface.
   → **BLOCKER (Users/relationships)**.
2. **Voice/stage channel rows in the channel list have no voice behaviour.**
   `widgets/channels/list/WidgetChannelsList.kt:199` routes `ChannelListRow.Voice` and `ChannelListRow.Stage`
   through the same `click(guildId, channelId)` text-channel selection path. The reference routes them through
   `VoiceUtils.handleCallChannel` (`widgets/channels/list/WidgetChannelsList$onViewBound$3.java`), which connects
   to voice and opens `WidgetVoiceBottomSheet` / `WidgetCallPreviewFullscreen`. `VoiceUtils` is MISSING.
   Guilds/channels is IN PROGRESS. → **BLOCKER (Guilds/channels)**.
3. **`WidgetChatStatus` unread banner has no ack-validity gate and the wrong count cap.**
   `status/WidgetChatStatus.kt:58`, `:90-92` vs ref `WidgetChatStatus.java:61-105`: missing
   `StoreMessageAck`×`StoreMessagesMostRecent` validity observable, missing `StoreMessages.getAllDetached()`
   25-vs-50 cap, missing 500 ms leading-edge throttle. Messages/uploads is IN PROGRESS.
   → **BLOCKER (Messages/uploads)**.
4. **`WidgetThreadStatus` join/unarchive is unclickable and unbacked.**
   `status/WidgetThreadStatus.kt:67`, `:72-73` — button is `GONE` because `onJoin`/`onUnarchive` are never
   assigned; no `WidgetThreadStatusViewModel` (585 L), no `editThread` REST call, no `canArchive` permission
   gate, no error toast. Messages/uploads IN PROGRESS. → **BLOCKER (Messages/uploads)**.
5. **`WidgetForumPostStatus` follow + reaction are unbacked.**
   `status/WidgetForumPostStatus.kt:44`, `:84-87` — `followed` is faked from `channel.member`, all three
   callbacks are never assigned, and `WidgetForumPostStatusViewModel` (795 L) with its 11-field `ViewState.Valid`
   is MISSING. Messages/uploads IN PROGRESS. → **BLOCKER (Messages/uploads)**.
6. **`CallModel` has no producer and the voice stores are empty shells.**
   No `Companion.get/observeChunk/create` (`voice/model/CallModel.kt` has no companion producer);
   `stores/VoiceStoreContracts.kt:7,30,36`, `rtcconnection/RtcConnection.kt:3`,
   `rtcconnection/mediaengine/MediaEngineConnection.kt:3`, `stores/StoreVoiceParticipants.kt:8` are all
   private-constructor / interface-only shells with zero state or observables. Every voice view in the target
   is therefore unfeedable. → **MAJOR, expected (Phase 11 not started)**.
7. **`WidgetVoiceBottomSheet` is an 8-line enum stub.**
   `voice/sheet/WidgetVoiceBottomSheet.kt:3-8` vs a 1012-line sheet + 1420-line ViewModel + 1140-line
   participants adapter. Nothing can surface `AnchoredVoiceControlsView`. → **MAJOR, expected (Phase 11)**.
8. **`WidgetVoiceCallInline` is fully inert.**
   `voice/call/WidgetVoiceCallInline.kt:60-61` statics never assigned → always `GONE`, PTT no-op; missing the
   `VoiceConnected` producer, the PTT-button registrar, `ViewVisibilityObserver`, and window-insets padding.
   It is mounted in `res/layout/widget_home_panel_center_chat.xml:12`. → **MAJOR, expected (Phase 11)**.
9. **`WidgetScreenShareNfxSheet` never starts a stream and never returns to the sheet.**
   `voice/controls/WidgetScreenShareNfxSheet.kt:20-34` (raw `MediaProjectionManager`, `RESULT_OK` → just
   `dismiss()`), `:46-48` (non-persistent `canShow`); missing `StreamNavigator` and
   `maybeNavigateToVoiceBottomSheet`. → **MAJOR, expected (Phase 11)**.
10. **`VoiceControlsSheetView` derives "is stage" from `stageRoles` instead of channel type.**
    `voice/controls/VoiceControlsSheetView.kt:113` (`stageRoles != null`) vs ref
    `VoiceControlsSheetView.java:411/237/415` (`ChannelUtils.D(callModel.getChannel())`). Diverges the visibility
    of the output selector, screen-share buttons and invite buttons whenever roles are null on a stage channel.
    → **MAJOR (behavioural), expected (Phase 11) for exposure**.
11. **Confirmed resource/string divergences in already-reconstructed views** (each `MINOR`, each independently fixable now):
    - `AnchoredVoiceControlsView.kt:239` camera content descriptions inverted vs ref `:167`.
    - `AnchoredVoiceControlsView.kt:256` uses `mobile_stream_screen_share` vs ref `screenshare_screen`.
    - `VoiceControlsSheetView.kt:160/199/207/253/261/278` — 5 wrong drawables/strings/attrs (see §3.1).
    - `VoiceControlsSheetView.kt:300,303` — `SPEAKER_OFF*` uses `ic_sound_24dp` vs ref `ic_sound_quiet_24dp`.
    - `PrivateCallGridView.kt:58` — `avatar_size_large` vs ref `avatar_size_unrestricted`.
    - `PrivateCallBlurredGridView.kt:61` — themed `colorBackgroundPrimary` vs ref `R.color.primary_dark_600`.
    - `UserProfileStageActionsView.kt:43` — `stage_channel_user_invited_to_speak` vs ref `moderator_raise_own_hand`.
12. **`WidgetGlobalStatusIndicator` cluster is entirely MISSING** (`WidgetGlobalStatusIndicator` 463 L,
    `WidgetGlobalStatusIndicatorState`, `WidgetGlobalStatusIndicatorViewModel` 1107 L). This is the persistent
    connection/voice/stream banner shown app-wide (connection state, ongoing call, active stream). It is the
    single largest source of missing *connection-state UI* in this slice and straddles Phase 11 and the
    IN-PROGRESS navigation surfaces. → **MAJOR, expected (Phase 11)**, but it should be re-checked once
    Phase 11 starts because the connection-state half is not voice-RTC-specific.

---

## 6. JADX reliability notes

- `voice/controls/VoiceControlsSheetView.java` visibility ternaries decompile to nonsense
  (`... ? false : false ? 0 : 8`, e.g. `:84`, `:172`, `:209`, `:243`). The intended boolean is **not**
  recoverable from this decompile; the target's readings are plausible but unverified. Flagged, not asserted.
- `voice/model/CallModel.java:319-350` (`isVideoCall` computation) has an unreachable-`continue`/`break`
  pattern that JADX mangled; the semantics (`isStreaming || any participant selfVideo`) are inferable from the
  surrounding code and match the target's `CallModel.kt:45`, but the loop structure itself is broken output.
- `stage/StageRoles.java` is an inline-class dump (`m26constructorimpl`, `m33isSpeakerimpl`, …); the flag
  constants (`:81-83`) and factory (`:113-122`) are readable and the target matches them.
- Everything else cited above was read from clean, unambiguous JADX output.
