# Parity re-audit: shared custom views

- Date: **2026-08-04**
- Scope: `com.discord.views/**`, `chips_view/**`, `ui/**`, `tooltips/**`, `embed/**`, `rlottie/**`
- Reference: Discord Android 126.21 Stable JADX plus Vineflower/DEX evidence for formerly skipped methods
- Verification: static audit only. Existing tests/build outputs were inspected by citation but not rerun.

## Current summary

| Question | Current result |
| --- | --- |
| Reference non-synthetic custom-view classes | 76 |
| Exact target classes present | **76/76 (100%)** |
| Layout-referenced first-party class missing | **0** |
| Historical seven missing classes | **All present** |
| Historical 32 zero-caller classes | **Stale**; only five now have no external Kotlin textual reference |
| First-party enum/flag attrs required by target layouts | Restored |
| Full behavioral/device parity | **Not established** |

This report is retained because class presence and successful inflation are not behavioral parity. Voice/video, reduced motion, lifecycle, accessibility, animation, tooltip, and qualifier behavior still lack a fresh device matrix.

## Structural coverage

All 76 exact reference class names are present. The seven historical omissions now exist:

- `views/ToolbarTitleLayout.kt`
- `tooltips/TooltipManager.kt`
- `tooltips/DefaultTooltipCreator.kt`
- `views/RadioManager.kt`
- `views/OverlayMenuBubbleDialog.kt`
- `views/OAuthPermissionViews.kt`
- `views/user/UserAvatarPresenceViewController.kt`

`RadioManager` has 12 production callers; `VideoCallParticipantView` has fullscreen callers; `StickerView` has 14 callers; `ServerMemberCount` has four. The old statement that 32 API-bearing views were never driven is no longer valid.

Five classes have no external Kotlin textual reference: `DefaultTooltipCreator`, `ExperimentOverrideView`, `ShinyButton`, `StageCallVisitCommunityView`, and `VoiceCallActiveEventView`. Four can be self-contained XML children; `DefaultTooltipCreator` needs a real tooltip-flow caller check before being considered live.

## Resource attrs

The historical resource blocker is resolved for first-party attrs:

- Reference merged `attrs.xml`: 518 enums, 130 flags.
- Current target `attrs.xml`: 180 enums, 16 flags.
- The lower target total is intentional: dependency-owned attrs are supplied by their libraries rather than shadowed by empty app declarations.
- `cs_view_type` is restored at `attrs.xml:1165-1167` (`check=0`, `radio=1`, `switchview=2`).
- `cvv_character_input_type` is restored at `attrs.xml:1269-1270`.
- `cutout_style`, `format_type`, `image_style`, `cap_style`, and the first-party flags used by layouts are present.

The prior claim that every settings radio/switch silently inflated as a checkbox is therefore closed. No fresh linked-APK or device inflation check was run on 2026-08-04, so this is source/resource evidence rather than runtime evidence.

## Formerly JADX-broken regions

Stronger evidence was used instead of guessing:

- Vineflower recovers `UserAvatarPresenceView.a(ViewState)` completely, including avatar URL/animation selection, status visibility, MGImages change detection, sizing, and null-safe cutout tint.
- Vineflower recovers `VideoCallParticipantView`'s main configure/render path, stream states, quality pill, cutout constraints, label/content description, and frame subscriptions.
- Vineflower recovers `CheckedSetting`'s constructor behavior and all three view types.
- dex2jar/Vineflower confirm `ClientStatuses` and related view-state constructor order where used.

These bodies are no longer marked unknowable. Remaining differences below are direct target/reference comparisons, not deductions from broken JADX.

## Resolved behavior findings

| Historical finding | Current evidence | Status |
| --- | --- | --- |
| `CheckedSetting` always chose CHECK | Restored attrs plus `CheckedSetting` callers | Resolved structurally |
| Country picker inert | `WidgetPhoneCountryCodeBottomSheet` is present and phone/contact flows bind it | Resolved structurally |
| `VideoCallParticipantView` orphaned | Fullscreen view/view-model callers exist; `configure` preserves prior cutout/insets/control state (`:171-185`) | Resolved reachability; parity still open |
| `OverlayMenuView` had no driver | It implements `AppComponent` and subscribes on attach | Resolved structurally |
| RLottie never cached/destroyed/recolored | `RLottieDrawable.kt:174-213`, `:217-258`, `:357-377`, `:465-498` restore color mutation, parent registry, cache generation, native destroy, and bitmap recycle | Resolved structurally |
| `StreamPreviewView` copy wrong | Correct strings at `StreamPreviewView.kt:49-55` | Resolved |
| `SettingsMemberView` used 84dp inline avatar | 16dp profile-small inline avatar and 46dp large avatar at `SettingsMemberView.kt:62-84` | Resolved |
| I18n plural placeholders shown literally | `ServerMemberCount.kt:37-50` and `FailedUploadList.kt:52-61` use i18n helpers | Resolved |
| Speaker pulse asset/reduced motion/one-shot | White asset, StoreAccessibility gate, and restart loop at `SpeakerPulseView.kt:47-97` | Resolved |
| Reaction emoji plain text | `ReactionView.kt:92-131` renders Unicode/custom emoji through `EmojiNode` spans | Resolved |
| Failed-upload icons inverted | Generic header and file fallback are correct at `FailedUploadList.kt:52-80` | Resolved |
| Sticker stream lacked error funnel | Sticker view now uses shared Rx/UI infrastructure and has production callers | Resolved structurally |
| Guild boost tier-1 heading wrong | Correct secondary heading at `GuildBoostMarketingView.kt:62-79` | Resolved |

## Open findings

### MAJOR

1. **`VideoCallParticipantView` still does not implement the reference grid contracts.** The reference implements `FrameGridLayout.DataView`, and `ParticipantData` implements `FrameGridLayout.Data`; the target class at `VideoCallParticipantView.kt:27-68` exposes similarly named plain members but implements neither interface, despite `utilities/view/grid/FrameGridLayout.kt` now existing. The current fullscreen host may work, but exact reusable-grid lifecycle parity is open.
2. **Quality-pill visibility remains stricter than the reference.** Target `VideoCallParticipantView.kt:269-276` requires stream state `ACTIVE` or `PAUSED`; Vineflower's reference check is only full-screen + host flag + non-null quality. Connecting/inactive transitions can hide information the reference shows.
3. **Representative-color extraction remains synchronous on the UI thread.** Target `VideoCallParticipantView.kt:349-363` samples the avatar bitmap directly. Vineflower shows an observable cached `UserRepresentativeColors` subscription. Large/repeated avatar binds can add main-thread work and lose cache sharing.
4. **`UserAvatarPresenceView` still uses system animator scale rather than Discord reduced-motion state** at `UserAvatarPresenceView.kt:81-90`. The reference calls `AccessibilityUtils.isReducedMotionEnabled()`.
5. **`UserAvatarPresenceView` default cutout color differs.** Target `:62-68` defaults to `colorBackgroundTertiary`; Vineflower resolves the reference attr to `primary_700`. This affects unstyled instances.

### MINOR

6. `SettingsMemberView.kt:70-75` still marks system/Clyde rows verified in addition to the reference verified-bot flag predicate.
7. `JoinVoiceChannelButton.kt:11` still uses `R.string.join_voice`; the reference uses `connect_to_voice`.
8. `CheckedSetting`, `TernaryCheckBox`, and some other leaf views still use platform `Toast`/plain `getText` where the reference uses themed toast and markdown/i18n formatting. This is visual/span behavior, not an inflation defect.
9. `DefaultTooltipCreator` has no confirmed production caller. TooltipManager itself is implemented, but tooltip quota/ack/anchor/autodismiss behavior was not exercised on device.

## Behavior not freshly re-audited

The following should remain open even though their classes exist:

- Tooltip quota persistence, concurrent tooltip cap, anchor placement, rotation, and pause auto-dismiss.
- Overlay bubble drag, selector, permission denial, service recreation, and process death.
- Voice/video tile layout on cutout devices, landscape, tablets, and picture-in-picture.
- RLottie cache creation/destruction under recycled rows and rapid color changes.
- Accessibility roles/focus/announcements with TalkBack and large fonts.
- RTL and all recovered layout qualifiers.
- Reduced-motion behavior across every animated custom view.

## Confirmed clean in this pass

- Exact class presence: 76/76.
- `ClientStatuses(desktop, web, mobile)` order used by status/mobile indicators is confirmed by Vineflower.
- `StreamPreviewView` overlay copy.
- `SettingsMemberView` avatar dimensions.
- `ServerMemberCount` i18n placeholder substitution.
- `ReactionView` image-span rendering.
- `FailedUploadList` icon selection.
- `SpeakerPulseView` white asset and in-app reduced-motion gate.
- RLottie cache/color/parent/destroy plumbing.

## Top remaining blockers

1. Bring `VideoCallParticipantView`/`ParticipantData` onto `FrameGridLayout` interfaces and reconcile quality/representative-color behavior with Vineflower.
2. Use `AccessibilityUtils` and the reference default color in `UserAvatarPresenceView`.
3. Exercise tooltip, overlay, video/cutout, RLottie recycle, accessibility, RTL, and large-font behavior on device.
4. Verify the five no-external-caller classes, especially `DefaultTooltipCreator`, are either intentionally XML-only or genuinely connected.
