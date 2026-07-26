# Parity Audit — shared custom-view packages

TARGET: `C:\Users\manti\Desktop\projeler\manticordrebuild`
REFERENCE: `C:\Users\manti\StudioProjects\discord-jadx` (JADX decompile, Discord Android 126.21)
Scope: `com/discord/views/**`, `com/discord/chips_view/**`, `com/discord/ui/**`, `com/discord/tooltips/**`, `com/discord/embed/**`, `com/discord/rlottie/**`
Method: read-only. No target file modified. Verification for the resource findings was done against the **already-built** `app/build/outputs/apk/debug/app-debug.apk` (binary XML + `resources.arsc`), not by re-running Gradle.

---

## 0. Executive summary

| Question | Answer |
|---|---|
| Layout-referenced view class with **no target implementation** (guaranteed inflation crash) | **NONE.** All 134 fully-qualified `com.discord.*` classes in `res/layout*/` resolve to a target source file, and all declare an `AttributeSet` constructor. |
| `findViewById` on an ID absent from the recovered layout (runtime NPE) | **NONE** in this slice (2 apparent hits are `<include>`-resolved false positives). |
| Coverage | 69 / 76 reference classes present (90.8%). 7 MISSING. |
| Inert shells (empty bodies / no setters) | **0** literal shells. But **32 of 70** target view classes have **zero Kotlin callers** — they inflate from XML and are never driven. |
| Worst finding | **BLOCKER:** `res/values/attrs.xml` has every `<enum>`/`<flag>` child stripped from enum-valued attrs. `CheckedSetting` silently renders as a checkbox in all 39 layouts that ask for radio/switch. |

---

## 1. Coverage table

Reference `$`-named files are synthetic lambdas and are excluded. `com/discord/ui/**` does not exist in either tree (0 classes) — the slice brief's mention of it is vacuous.

### 1a. Present (69) — with line counts (ref .java incl. JADX metadata noise / target .kt)

`views/`: ActiveSubscriptionView 236/277 · ChatActionItem 46/27 · CheckableImageView 61/41 · CheckedSetting 728/247 · CodeVerificationView 290/158 · CustomAppBarLayout 82/39 · CutoutView 322/186 · FailedUploadList 263/99 · FailedUploadView 63/36 · GuildView 98/77 · IconRow 63/34 · JoinVoiceChannelButton 45/20 · LoadingButton 115/114 · MemberVerificationAvatarView 40/19 · NumericBadgingView 178/113 · OverlayMenuView 210/123 · PileView 289/136 · ReactionView 174/129 · ScreenTitleView 68/41 · SearchInputView 169/100 · ServerFolderView 119/62 · StatusView 125/90 · StreamPreviewView 119/72 · TernaryCheckBox 255/110 · UploadProgressView 111/64 · UserListItemView 49/21 · UsernameView 110/143 · VoiceUserLimitView 122/73 · VoiceUserView 307/182

`views/calls/`: AppVideoStreamRenderer 148/125 · CallEventsButtonView 105/121 · SpeakerPulseView 559/96 · StageCallSpeakerView 72/91 · StageCallVisitCommunityView 46/19 · VideoCallParticipantView 562/316 · VoiceCallActiveEventView 46/18 · VolumeSliderView 86/54

`views/channelsidebar/`: GuildChannelSideBarActionsView 109/77 · PrivateChannelSideBarActionsView 70/51
`views/directories/`: ServerDiscoveryHeader 66/37 · ServerDiscoveryItem 107/64
`views/experiments/`: ExperimentOverrideView 187/80
`views/guildboost/`: GuildBoostConfirmationView 139/95 · GuildBoostMarketingView 169/87 · GuildBoostProgressView 82/67 · GuildBoostSubscriptionUpsellView 118/121
`views/guilds/`: ServerMemberCount 100/51
`views/permissions/`: ChannelPermissionOwnerView 109/84
`views/phone/`: PhoneOrEmailInputView 315/163
`views/premium/`: AccountCreditView 106/73 · GiftSelectView 35/73 · ShinyButton 116/69
`views/segmentedcontrol/`: CardSegment 91/67 · SegmentedControlContainer 92/32
`views/stages/`: SpeakersRecyclerView 24/10 · StageCardSpeakersView 71/109
`views/steps/`: StepsProgressIndicatorView 79/61 · StepsView 624/211
`views/sticker/`: StickerView 240/207
`views/user/`: SettingsMemberView 125/156 · UserAvatarPresenceView 297/158 · UserSummaryView 136/92
`chips_view/`: ChipsView 512/418
`tooltips/`: SparkleView 64/42
`embed/`: RenderableEmbedMedia 51/3 (ref is a plain Kotlin data class blown up by JADX — target is at parity)
`rlottie/`: RLottieDrawable 607/267 · RLottieImageView 125/71

### 1b. MISSING (7)

| Reference class | Ref lines | Reference consumers | Severity |
|---|---|---|---|
| `views/ToolbarTitleLayout` | 109 | `app/AppActivity`, `app/AppFragment`, `widgets/home/WidgetHomeHeaderManager` | **MAJOR** — home header / toolbar title+subtitle+avatar composition. Target has no `AppFragment` toolbar-title path at all. |
| `tooltips/TooltipManager` | 214 | `ScreenShareManager`, `LiveStageNotificationBadgeTooltip`, `AnchoredVoiceControlsView`, `ScreenShareButtonSparkleTooltip`, `VoiceControlsSheetSwipeTooltip`, `WidgetCallFullscreen`, `NoiseCancellationTooltip`, `WidgetVoiceBottomSheetViewModel` | **MAJOR** — the whole tooltip show/dismiss/cache/quota system (`showToolTip`, `canShowTooltip`, seen-cache in SharedPreferences, concurrent-tooltip cap, `FloatingViewManager` anchoring, rx auto-dismiss). |
| `tooltips/DefaultTooltipCreator` | 16 | consumer of TooltipManager | MAJOR (dependent) |
| `views/OverlayMenuBubbleDialog` | 236 | `utilities/voice/DiscordOverlayService` | MAJOR — overlay bubble container. |
| `views/OAuthPermissionViews` | 133 | `WidgetOauth2Authorize`, `WidgetSettingsAuthorizedApps` | MAJOR |
| `views/RadioManager` | 48 | `WidgetChannelNotificationSettings`, `WidgetCreateChannel`, `WidgetGuildRoleSubscriptionPlanFormat`, `WidgetServerNotifications`, `WidgetServerSettingsEditIntegration`, `WidgetServerSettingsModeration`, `WidgetSettingsAccessibility` | **MAJOR** — mutual-exclusion group for `CheckedSetting` radios. Target's `WidgetServerNotifications.kt:65,74` wires `CheckedSetting`s with no group manager, so radio de-selection is not handled. |
| `views/user/UserAvatarPresenceViewController` | 75 | `widgets/tabs/TabsHostBottomNavigationView` | MINOR — target substitutes a direct `avatar.configure(...)` call at `TabsHostBottomNavigationView.kt:158`, losing the rx `StoreUser`/`StoreUserPresence`/`StreamContextService` combine-latest. |

None of the 7 is referenced anywhere in the target, so none causes a compile or inflation failure.

### 1c. Target-only (1)

`views/segmentedcontrol/SegmentedControlSegment.kt` (5 lines) — the reference's equivalent interface was obfuscated out of the package to `b.a.y.n0.a`. Legitimate reconstruction, not an extra.

---

## 2. HIGHEST VALUE — layout-referenced classes with no implementation

**Result: none.** Exhaustive check performed:

- Extracted all 134 distinct `<com.discord.*` element names from `app/src/main/res/layout/`, `layout-land/`, `layout-ldrtl/`, `layout-sw600dp/`, `layout-v22/`, `layout-v23/`, `layout-v26/`, `layout-watch/`. All 134 resolve to an existing `.kt` under `app/src/main/java/`.
- Also checked `layout_behavior="com.discord.…"` → `com.discord.app.AppScrollingViewBehavior` and `com.discord.app.AppScrollingNestedViewBehavior` both exist.
- Also checked `<view class="…">` — no occurrences.
- Every one of the 134 declares a constructor taking `AttributeSet` (either an explicit 2-arg primary constructor or `@JvmOverloads`), so `LayoutInflater` will find the 2-arg JVM constructor. No `NoSuchMethodException` risk.
- Cross-checked each target view's `R.layout.X` + `findViewById(R.id.Y)` pairs against the recovered layout XML. Two apparent misses (`CheckedSetting.kt` → `setting_label`/`setting_subtext`/`setting_tag`/`setting_drawable_left`; `OverlayMenuView.kt` → `overlay_*`) are supplied by `<include layout="@layout/view_setting_shared"/>` (`res/layout/view_setting_check.xml:4`) and `<include android:id="@+id/content" layout="@layout/view_overlay_menu_content"/>` (`res/layout/view_overlay_menu.xml:3`) respectively. Not crashes.

### 2b. …but there IS a guaranteed-wrong-behavior resource BLOCKER

`app/src/main/res/attrs.xml` has the `<enum>` / `<flag>` children stripped out of enum-valued attributes (left as blank lines inside the `<attr>` element).

- Reference `attrs.xml`: 518 `<enum>` + 130 `<flag>`.
- Target `attrs.xml`: **34 `<enum>` + 0 `<flag>`.**

Example — `app/src/main/res/values/attrs.xml:1164`:
```xml
    <attr name="cs_view_type">



    </attr>
```
vs reference `attrs.xml:1363-1367` which has `check=0 / radio=1 / switchview=2`.

Because the attr has no `format` and no children, aapt2 stores the layout's value as a **raw string**. Confirmed from the built APK: `res/layout/view_server_settings_notifications.xml` inside `app-debug.apk` has `"radio"` in its string pool as a string value, not an enum int. At runtime `TypedArray.getInt()` logs `Converting to int: radio` and returns the default.

First-party enum attrs that are used symbolically in target layouts and are therefore **silently broken**:

| attr | values used | # layouts | Consumer | Effect | Severity |
|---|---|---|---|---|---|
| `cs_view_type` | check / radio / **switchview** | **39** | `views/CheckedSetting.kt:50` `getInt(...,0)` | Every radio and switch settings row renders as a **checkbox**; a11y `roleDescription` also wrong (`CheckedSetting.kt:81-84`). Hits every settings screen. | **BLOCKER** |
| `cvv_character_input_type` | alphanumeric | 2 | `views/CodeVerificationView.kt:42` | Xbox-connect and hub-auth code fields stay NUMERIC — alphanumeric codes cannot be typed. | **BLOCKER** |
| `cutout_style` | badge | 2 | `views/CutoutView.kt:138` `getInt(...)==1` | XML-declared circular badge cutout never applied. | MAJOR |
| `format_type` | entire_server / some_channels | 1 | `widgets/guild_role_subscriptions/setup/…` | wrong default branch | MAJOR |
| `image_style` | rounded | 1 | `widgets/guild_role_subscriptions/tier/create/…` | falls back to circle | MINOR |
| `cap_style` | cap_long | 1 | thread embed | MINOR |
| `force_mode` | phone | 2 | `views/phone/PhoneOrEmailInputView.kt:54-58` | **Works** — target reads it with `getString()` and accepts both `"phone"` and `"2"`. Deliberate workaround. | none |

(`orientation`, `flow_verticalAlign`, `layout_constraintWidth_default`, `layout_constraintHeight_default` are also childless in the target `attrs.xml`, but those are AndroidX/framework attrs whose real definition comes from the library — harmless forward declarations.)

---

## 3. Inert shells

### 3a. Literal shells (right base class + layout, empty method bodies, no setters)

**None.** Scans for `TODO(`, `NotImplementedError`, `fun x() { }`, and no-op bodies over all 70 files returned nothing. The following are *thin* but are **at parity** — the reference class is equally thin (constructor + view binding only, no public API):

| file | ref counterpart | verdict |
|---|---|---|
| `views/MemberVerificationAvatarView.kt:9-18` | `MemberVerificationAvatarView.java:21-39` | parity |
| `views/UserListItemView.kt:9-20` | `UserListItemView.java:22-48` | parity |
| `views/ChatActionItem.kt:11-26` | `ChatActionItem.java:22-45` | parity (incl. styled attrs) |
| `views/calls/StageCallVisitCommunityView.kt:9-18` | `.java:23-45` | parity |
| `views/calls/VoiceCallActiveEventView.kt:9-17` | `.java:23-45` | parity |
| `views/stages/SpeakersRecyclerView.kt:8-10` | `.java:13-23` | parity |
| `embed/RenderableEmbedMedia.kt:3` | `.java` (data class) | parity |
| `views/segmentedcontrol/SegmentedControlSegment.kt:3-5` | `b.a.y.n0.a` | parity |

One real defect in this group: `views/JoinVoiceChannelButton.kt:11` sets `R.string.join_voice`; the reference (`JoinVoiceChannelButton.java:38`) sets `R.string.connect_to_voice`. **MINOR**. Also `disableWithToast` is public and takes the button as a parameter (`JoinVoiceChannelButton.kt:14`) where the ref has it private as a receiver extension — cosmetic.

### 3b. Effectively inert — inflates from XML, **never driven from Kotlin**

32 of 70 target classes in this slice have **zero** references from any `.kt` file other than themselves, while appearing in N layouts. Views with a real `configure`/`setX` API that is never called are functionally inert on screen.

| class | # layouts | note |
|---|---|---|
| `views/sticker/StickerView.kt` | **22** | full impl, no caller |
| `views/SearchInputView.kt` | **14** | full impl incl. a11y content-description swap, no caller |
| `views/segmentedcontrol/SegmentedControlContainer.kt` | 8 | `setup()` never called ⇒ **segments get no click listeners at all** |
| `views/calls/VideoCallParticipantView.kt` | 4 | orphaned — see §4 |
| `views/steps/StepsView.kt` | 4 | |
| `views/premium/ShinyButton.kt` | 4 | |
| `views/calls/CallEventsButtonView.kt` | 4 | |
| `views/CustomAppBarLayout.kt` | 3 | `setOnPercentCollapsedCallback` never called |
| `views/OverlayMenuView.kt` | 2 | see §4 gap #4 |
| `views/TernaryCheckBox.kt` | 2 | |
| `views/StreamPreviewView.kt` | 2 | |
| `views/stages/StageCardSpeakersView.kt` | 2 | |
| `views/guildboost/{Confirmation,Marketing,Progress,SubscriptionUpsell}View.kt` | 2 each | |
| `views/MemberVerificationAvatarView.kt`, `views/calls/StageCallVisitCommunityView.kt`, `views/calls/VoiceCallActiveEventView.kt`, `views/ChatActionItem.kt` | 2 each | self-configuring, ok |
| `views/{AccountCreditView, ActiveSubscriptionView, ExperimentOverrideView, FailedUploadList, GiftSelectView, IconRow, JoinVoiceChannelButton, UserListItemView, VoiceUserLimitView}`, `views/directories/{ServerDiscoveryHeader, ServerDiscoveryItem}`, `views/calls/StageCallSpeakerView` | 1 each | |

Severity: **BLOCKER** for `SegmentedControlContainer` (a tab control that cannot be tapped), **MAJOR** for the rest of the API-bearing ones.

---

## 4. Top behaviour gaps (deep reads)

25 classes were deep-read: CheckedSetting, TernaryCheckBox, CodeVerificationView, SearchInputView, LoadingButton, PhoneOrEmailInputView, VoiceUserView, UsernameView, ReactionView, StreamPreviewView, UserSummaryView, SettingsMemberView, FailedUploadList, VoiceUserLimitView, RLottieDrawable, RLottieImageView, SpeakerPulseView, StepsView, StickerView, VideoCallParticipantView, StatusView, UserAvatarPresenceView, TypingDot/TypingDots, CutoutView, PileView, GuildView, ServerFolderView, NumericBadgingView, ChipsView, OverlayMenuView, SparkleView, both channelsidebar views, both segmentedcontrol views, ServerMemberCount, CustomAppBarLayout, IconRow, ScreenTitleView, UploadProgressView, GuildBoostMarketingView, ExperimentOverrideView.

### Ranked gaps

**1. BLOCKER — `CheckedSetting` view type never arrives from XML.**
`views/CheckedSetting.kt:50` `getInt(R.styleable.CheckedSetting_cs_view_type, 0)`. Because of §2b, this always returns 0 ⇒ `ViewType.CHECK` ⇒ `R.layout.view_setting_check` (`CheckedSetting.kt:57-60`). 39 layouts ask for `radio`/`switchview`. Every switch in Settings is a checkbox; radios lose their round button *and* the `isChecked=true` (non-toggle) semantics at `CheckedSetting.kt:72`; the a11y role announced is "checkbox" (`CheckedSetting.kt:81-84`). The class itself is otherwise at parity (save/restore via `dispatchFreezeSelfOnly` + `STATE_SUPER`/`STATE_CHECKED`, full `AccessibilityDelegateCompat`, all setters).

**2. BLOCKER — `PhoneOrEmailInputView` country-code picker is inert.**
Reference `PhoneOrEmailInputView.java:192-202` unconditionally wires the country-code `EditText` to `WidgetPhoneCountryCodeBottomSheet.show(...)`. Target makes it an optional param defaulting to `null` (`views/phone/PhoneOrEmailInputView.kt:74-78`) and both call sites (`widgets/auth/AuthFragments.kt:146` and `:209`) call `bind(this)` with no callback. `com.discord.widgets.phone` does not exist in the target at all. The `+1` prefix hardcoded in `res/layout/view_phone_or_email_input.xml:4` can never be changed → phone login/registration is only usable for US numbers.
Also **MAJOR**: reference sets `setLayoutTransition(new LayoutTransition())` (`.java:152`) so the country field animates in/out; target never does (`PhoneOrEmailInputView.kt:39-69`).

**3. BLOCKER — `VideoCallParticipantView` is orphaned; its configure path is undecompilable.**
Reference implements `FrameGridLayout.DataView` and `ParticipantData implements FrameGridLayout.Data` (`.java:37,334,481-501`). `FrameGridLayout` **does not exist anywhere in the target tree**; target's `onBind`/`onRemove`/`dataId` are plain members (`views/calls/VideoCallParticipantView.kt:86,157-158`) and nothing references the class. Additionally, **JADX could not decompile the reference's main configure method** (`.java:279-285`, "Method dump skipped, instructions count: 1590") nor the 4-arg constructor (`.java:187-193`, count 459) — all `obtainStyledAttributes` handling and all content-description work live there and **cannot be compared**. Confirmed downstream consequences: target reads no styled attributes at all (`matchVideoOrientation`, `animateLabelFades`, `livePillBg`, `liveSplitPillBg` all absent); `configure` defaults cutout/insets/controls-visible to `null`/`false` instead of re-using stored state (`kt:142-155`) so `onBind` wipes them; live-pill background swap missing (`kt:245-250`); quality-pill visibility adds a required `applicationStreamState ∈ {ACTIVE,PAUSED}` the ref does not have (`kt:230-234`); representative-colour extraction is a synchronous 8×8 `getPixel` loop on the UI thread (`kt:298-313`) replacing the ref's cached rx subscription.

**4. BLOCKER/MAJOR — `OverlayMenuView` lost its self-subscription and has no driver.**
Reference implements `AppComponent`, subscribes in `onAttachedToWindow` to `StoreStream.getVoiceChannelSelected().observeSelectedChannel()` and renders itself (`OverlayMenuView.java:143-155`), unsubscribing via `getUnsubscribeSignal()` on detach. Target replaces this with a pull-based `updateView(state)` (`views/OverlayMenuView.kt:85`) — and **no file in the target calls it**. The overlay menu shows empty guild/channel names, no network-quality icon, no mute state. Also missing: `AppComponent`/`getUnsubscribeSignal` (`OverlayMenuView.kt:15`).

**5. BLOCKER — `RLottieDrawable` never pre-caches.**
Reference posts a runnable that submits to a dedicated single-thread executor calling `createCache(ptr,w,h)` when `metadata[2] != 0` (`RLottieDrawable.java:237-254`). Target declares the native `createCache` (`rlottie/RLottieDrawable.kt:248`) and **never calls it**; the `precache` constructor argument (`kt:72,80`) is only forwarded to `create()`. Every frame of every animated sticker/emoji is re-rasterized. JNI bindings themselves are at parity (same 6 externals, `libdsti.so` loaded at `app/App.kt:32`).
Same class, **MAJOR**: no layer/colour mutation (ref `java:148-162` keeps `pendingColors` and calls `setLayerColor`/`replaceColors`; target's `kt:264-265` are dead and `kt:75,80` ignores the colour array); no parent-view registry / auto-stop (ref `java:331-367,411-424` tracks `WeakReference<View>`s and stops when none are alive — target only calls `invalidateSelf()` at `kt:160,214`, so a callback-less drawable reschedules frames forever); no bitmap recycling or `nativePtr` destruction (ref `java:310-330`).

**6. BLOCKER — `StreamPreviewView` shows the wrong copy.**
`views/StreamPreviewView.kt:51` uses `R.string.watch_stream` ("Watch Stream") where the ref uses `watch_stream_watching` ("Watching Stream"); `:54` uses `R.string.no_screenshare_permission_dialog_body` where the ref uses `R.string.channel_locked_short` ("Channel Locked"). Also MINOR: ref renders all four overlay strings through Discord's `b.a.k.b.d(...)` formatter (styled `CharSequence`); target uses raw `context.getString` and loses spans.

**7. BLOCKER — `SettingsMemberView` inline guild-avatar span is 84dp.**
`views/user/SettingsMemberView.kt:69` uses `R.dimen.avatar_size_huge` (84dp) for the image span embedded in the subtitle line; ref uses `R.dimen.avatar_size_profile_small` (16dp). Also MAJOR: `:58` requests the large avatar at `avatar_size_extra_large_account` (72dp) vs ref's `avatar_size_large` (46dp). MINOR: verified-bot rule inlines `1<<16` plus `|| user.isSystemUser || user.id == -1L` (`:64-65`), so Clyde/system rows gain a badge the ref never shows.

**8. MAJOR — Discord i18n placeholder substitution skipped in plurals.**
`views/guilds/ServerMemberCount.kt:47` calls `resources.getQuantityString(R.plurals.members_header_members, it, it)`. The target's plural is `<item quantity="other">{members} Members</item>` (`res/values/plurals.xml:1009`) — a Discord `{name}` placeholder, not `%d` — so the format arg is ignored and the literal string **"{members} Members"** is rendered. The reference used `StringResourceUtilsKt.getI18nPluralString`, which performs the substitution. Other views in the same tree do it correctly by hand (`views/premium/AccountCreditView.kt:63-66` uses `.replace("{#}", …)`), so this is an isolated miss. `setOnline` on the same class *is* correct (`:38-40`).
Related **MAJOR** in `views/FailedUploadList.kt:52-55`: `getQuantityString` with no args + `.replace("{count}", …)` returns a plain `String`, losing the ref's styled `CharSequence` (bold count).

**9. MAJOR — `SpeakerPulseView` geometry, asset and reduced-motion gate all wrong.**
`views/calls/SpeakerPulseView.kt:79-80` sizes each ring `childWidth + paddingLeft*(index+1)`; ref uses `childWidth + 2*(paddingLeft/2f)*(index+1)` (`.java:313-317`) — half the intended growth. `:83` uses `R.drawable.drawable_circle_brand`; ref uses `drawable_circle_white` (`.java:319`). `:91-95` gates on `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` where ref uses `AccessibilityUtils.INSTANCE.isReducedMotionEnabled()` (`.java:524`) — Discord's in-app reduced-motion preference is ignored. MINOR: ref runs both rings as `async` jobs joined by `awaitAll` inside `while(isActive)` so they restart in phase; target chains each animator independently (`:55-68`) and they desynchronise.
The same `AccessibilityUtils` substitution appears in `views/ServerFolderView.kt:57-61` and `views/user/UserAvatarPresenceView.kt:70-74` — MINOR there.

**10. MAJOR — `UserAvatarPresenceView.ViewState` dropped `streamContext`.**
Reference `ViewState` is `(user, presence, streamContext, showPresence, guildMember)` (`.java:176-240`); target's is `(user, presence, showPresence, guildMember)` (`views/user/UserAvatarPresenceView.kt:30-35`). **JADX could not decompile the reference's `a(ViewState)` render method** (`.java:100-112`, "Method not decompiled", 272 instructions), so the exact use of `streamContext` is unverifiable — but the field's absence is a hard structural gap and the accompanying `UserAvatarPresenceViewController` (which supplies it via `StreamContextService`) is MISSING entirely.
Two more defects in the same file: `setAvatarBackgroundColor` calls `avatarCutout.drawable.mutate()` (`:95`) and will NPE if the cutout `ImageView` has no `src` — the ref used `ColorCompatKt.tintWithColor(view, color)` which is null-safe; and the default background attr is `R.attr.colorBackgroundTertiary` (`:55`) where the ref uses `R.attr.primary_700` (`.java:86`), resolved through a hand-rolled `themedColor()` (`:149-153`) that returns `value.data` and will yield a resource **id** rather than a colour whenever the theme attr points at a colour resource instead of a literal.

**11. MAJOR — Fresco/`IconUtils`/`MGImages` pipeline replaced by raw controllers across the avatar views.**
`views/VoiceUserView.kt:89-101`, `views/user/UserSummaryView.kt:58-63`, `views/user/SettingsMemberView.kt:83-85`, `views/PileView.kt:102`, `views/sticker/StickerView.kt:78,135,142` all build a bare Fresco controller or call `setImageURI` where the reference used `IconUtils.setIcon` / `MGImages.setImage`. Lost in every case: placeholder drawable, rounding params, and `MGImages.DistinctChangeDetector` (re-request suppression). `views/VoiceUserView.kt:79` additionally uses `avatar_size_standard` (32dp) instead of the ref's `avatar_size_unrestricted` (0dp ⇒ unresized full-quality CDN image). `views/GuildView.kt:57` sets `setAutoPlayAnimations(true)` where the ref passes `false`, and both `GuildView.kt:71` and `PileView.kt:130` inline a local copy of the media-proxy size table instead of calling `IconUtils.getMediaProxySize` (and `first { it >= n }` will throw `NoSuchElementException` above 4096).

**12. MAJOR — ring/pulse and fade animations degraded to one-shot or snap.**
`views/VoiceUserView.kt:46-50,171-176`: bare `ObjectAnimator`, no `AccelerateDecelerateInterpolator`, no `onAnimationEnd → onUpdate()` re-trigger, so the RINGING avatar pulses once and stops; ref delegates to `RingAnimator` which pulses continuously and cancels on every state transition (`.java:305`). `views/VoiceUserView.kt:143-156` `setSelected` flips `visibility` GONE/VISIBLE where ref used `ViewExtensions.fadeBy` (alpha only). `views/calls/VideoCallParticipantView.kt:235` snaps the quality container where ref uses `fadeBy(..., 200L)`.

**13. MAJOR — `ReactionView` emoji rendering is plain text.**
`views/ReactionView.kt:101-104` sets raw text for unicode emoji; the reference routes through `EmojiNode.Companion.renderEmoji` (sprite/CDN image spans, surrogate-pair handling). Custom emoji at `:106,118` hardcode `?size=32&quality=lossless` with an empty `GenericDraweeHierarchy` (no placeholder/failure image).

**14. MAJOR — `FailedUploadList` icon attributes are inverted.**
`views/FailedUploadList.kt:75` uses `R.attr.ic_uploads_generic` where ref's `getIconForFiletype` fallback is `ic_uploads_file`; `:57` uses `ic_uploads_file` for the many-uploads header where ref uses `ic_uploads_generic`. Both attrs exist so it fails silently. Also missing the sealed `FewFailedUploads`/`ManyFailedUploads` + `SingleFailedUpload` view-state model, and the `AttachmentUtilsKt.toAttachment` / mime-fallback / `computeFileSizeBytes` resolution chain (`:36-38`).

**15. MAJOR — `StickerView` subscription plumbing downgraded.**
`views/sticker/StickerView.kt:84-96` uses `subscribeOn(Schedulers.io()).subscribe { }` + `post{}`; the reference used `ObservableExtensionsKt.ui(...)` + `appSubscribe(..., StickerView.class, ...)` with onError/onCompleted (`.java:186,207`). A stream error now becomes an uncaught `OnErrorNotImplementedException`. MINOR: unhandled sticker format silently no-ops (`:59`) instead of `Logger.e`.

**16. MAJOR — `StepsView` fragments lost `AppFragment`.**
The reference's inner step fragment extends `com.discord.app.AppFragment` (`.java:335-339`), inheriting lifecycle logging, rx auto-unsubscribe, `onViewBound` and back handling. Target's `LayoutFragment` (`views/steps/StepsView.kt:204-210`) is a bare `androidx.fragment.app.Fragment`. NOTE: the reference's `FragmentStep` default label resources sit inside a constructor JADX marked **"Method not decompiled"** (`.java:137-151`), so the target's `R.string.next/cancel/done` assumption (`kt:148-150`) is plausible but **unverifiable**; the `ViewStep` defaults class decompiled to all-zero fields (`.java:299-318`, visibly broken output) and likewise cannot be confirmed.

**17. MINOR — `GuildBoostMarketingView` maps the wrong heading for one tier.**
`views/guildboost/GuildBoostMarketingView.kt:64-69` gives `PREMIUM_GUILD_SUBSCRIPTION_ONLY` (ordinal 1) the string `guild_settings_premium_upsell_heading_secondary_premium_user`; the reference's ordinal-1 branch uses `guild_settings_premium_upsell_heading_secondary` (`.java:88-98`). The other four tier branches match.

**18. MINOR — assorted formatter/toast/markdown substitutions.**
`CheckedSetting.kt:209,214`, `TernaryCheckBox.kt:46,107-109` use `Toast.makeText` and `context.getText()` where the reference uses Discord's themed toast helper and the markdown renderer `b.a.k.b.g/b`, dropping bold/link spans in subtexts and disabled-tooltips. `views/IconRow.kt:25` takes a non-null `String` where the ref's helper handled null.

---

## 5. Confirmed at parity (no action)

`CutoutView` (all four styles incl. RTL arc geometry and the `isLtr` inversion), `NumericBadgingView` (badge rect, "99+", styleable index mapping), `TypingDot` / `TypingDots` (stagger = `animation_time_standard / 1.5`, restart chain, detach cancel), `SparkleView` (500 ms restart delay confirmed against the obfuscated `b/a/v/d.java` callback; target additionally cancels on detach), `ChipsView` (chip add/prune/select/delete, `InputConnectionWrapper` DEL handling, `LeadingMarginSpan` re-layout, `onRequestFocusInDescendants`), `CodeVerificationView`, `LoadingButton` (the 6 decompilable ref members are semantically identical), `SearchInputView` (incl. contentDescription/`importantForAccessibility` swap), `TernaryCheckBox`, `UsernameView`, `VoiceUserLimitView`, `UploadProgressView`, `ScreenTitleView`, `CustomAppBarLayout`, `ServerFolderView`, `StatusView`, both `channelsidebar` views, `CardSegment`, `RLottieImageView`, `ExperimentOverrideView`.

---

## 6. Where the JADX output is broken (do not trust reconstructions here)

| Reference member | JADX state |
|---|---|
| `views/user/UserAvatarPresenceView.a(ViewState)` (`.java:100-112`) | "Method not decompiled", 272 instructions |
| `views/calls/VideoCallParticipantView` main configure (`.java:279-285`) | "Method dump skipped", 1590 instructions |
| `views/calls/VideoCallParticipantView` 4-arg ctor (`.java:187-193`) | 459 instructions, skipped |
| `views/CheckedSetting` ctor (`.java:424-430`) | "Method not decompiled", 446 instructions — **all** styled-attribute handling |
| `views/LoadingButton` ctor (`.java:33-39`) | "Method not decompiled", 380 instructions — **all** 11 `lb_*` attrs + default `disabledAlpha` |
| `views/steps/StepsView.FragmentStep` ctor (`.java:137-151`) | "Method not decompiled" — default label string ids |
| `views/steps/StepsView.ViewStep` defaults (`.java:299-318`) | decompiled to all-zero fields — visibly wrong |
| `views/calls/SpeakerPulseView.d()/e()` (`.java:344,438`) | "Code decompiled incorrectly" (instruction dump readable) |
| `views/experiments/ExperimentOverrideView.a.a(int,View)` (`.java:27-57`) | "Method not decompiled" (instruction dump readable) |
| `views/VoiceUserView` / `views/ReactionView` ctors | "Method not decompiled" (instruction dumps readable) |
| `tooltips/SparkleView` inner classes `c`/`d` | relocated out of the package to `b/a/v/{c,d}.java` — resolved manually |
