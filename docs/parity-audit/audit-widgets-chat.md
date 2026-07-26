# Parity Audit — `com.discord.widgets.chat` (PLAN Phase 7)

- **Target**: `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\widgets\chat\**` — **18 Kotlin files, 2 678 lines**
- **Reference**: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\widgets\chat\**` — **277 non-synthetic Java classes**
- **PLAN status**: Phase 7 `IN PROGRESS`, all 8 checkboxes ticked, changelog line 755 claims *"Completed Phase 7 … ordinary chat UI/input/actions …"*. Gaps below in ticked areas are therefore **BLOCKER**.

## 0. Headline numbers

| Metric | Count |
|---|---|
| Reference non-synthetic classes | **277** |
| Reference classes with any target representation | **40** (14.4 %) |
| Reference classes **MISSING** | **237** (85.6 %) |
| Target Kotlin files | 18 |
| Target files that are placeholders/stubs (see §5) | 4 partial + 1 hard-stub region |

Note on counting: several reference classes are folded into one Kotlin file in the target
(e.g. 38 `list/entries/*Entry` classes → one sealed class `ChatListEntry.kt`; 44
`list/adapter/WidgetChatListAdapterItem*` classes → one `when` in `WidgetChatListAdapter.kt`).
Folding is legitimate architecture, so those reference classes are credited as
"implemented" when the target actually *renders* the row; they are counted MISSING when no
row type, no entry variant, and no binding logic exists at all.

---

## 1. Reference class inventory by subpackage

| Subpackage | Ref classes | Represented | Missing |
|---|---:|---:|---:|
| `list/adapter` | 44 | 11 | 33 |
| `list/entries` | 38 | 12 | 26 |
| `input/autocomplete` (+`/adapter`,`/sources`,`/commands`) | 39 | 0 | 39 |
| `input/models` | 25 | 0 | 25 |
| `input/sticker` | 23 | 0 | 23 |
| `input` (root) | 20 | 2 | 18 |
| `input/gifpicker` | 19 | 2 | 17 |
| `input/emoji` | 14 | 3 | 11 |
| `list` (root) | 13 | 4 | 9 |
| `input/expression` | 8 | 0 | 8 |
| `list/actions` | 6 | 1 | 5 |
| `chat` (root) | 6 | 0 | 6 |
| `managereactions` | 5 | 0 | 5 |
| `input/applicationcommands` | 5 | 0 | 5 |
| `list/sheet` | 4 | 0 | 4 |
| `list/model` | 3 | 1 | 2 |
| `overlay` | 2 | 1 | 1 |
| `pins` | 1 | 1 | 0 |
| `list/utils` | 1 | 0 | 1 |
| `input/attachments` | 1 | 0 | 1 |
| **Total** | **277** | **40** | **237** |

---

## 2. Per-file findings (target → reference)

### 2.1 `input/WidgetChatInput.kt` (430 L) → `input/WidgetChatInput.java` (916 L)

Architecture divergence: the reference is `AppFragment` + `ChatInputViewModel`
(`ChatInputViewModel.ViewState.Loading | Loaded`, `ChatInputViewModel.Event`); the target is a
plain `Fragment` with a hand-rolled `State` data class and mutable static hooks
(`WidgetChatInput.kt:398-403` — `coordinatorProvider`, `draftsProvider`, `pendingRepliesStore`,
`editingStore`, `uploadHandler`, `stateSource` are all `@JvmField var` statics defaulting to
`null`/no-op). Every collaborator is nullable and silently no-ops when unset.

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **Member-verification guard is hardcoded `GONE`** — never consults `shouldShowVerificationGate`, no CTA click, no `guard_member_verification` binding | `WidgetChatInput.java:171-179`, `:271-284` | `WidgetChatInput.kt:302` | **BLOCKER** |
| **Lurker-mode guard absent** — no `isLurking()`, no `lurker_mode_chat_input_message`, no `lurkGuild()` action button | `WidgetChatInput.java:214-253` | `WidgetChatInput.kt:305-311` (`chat_input_guard_action` forced `GONE`) | **BLOCKER** |
| **Announcement-channel "Follow" guard absent** — no `shouldShowFollow`, no `showFollowSheet()` | `WidgetChatInput.java:200-213`, `:868-875` | absent | **BLOCKER** |
| **Guild verification-level gate absent** — `getVerificationText`/`getVerificationActionText` (account-claim, account-age, member-age, phone-verified) not ported | `WidgetChatInput.java:639-668` | absent | **BLOCKER** |
| **Communication-disabled guard has no countdown timer and no support-article link** — target only toggles visibility | `WidgetChatInput.java:254-270` (`humanizeCountdownDuration`, `UriHandler.handle`) | `WidgetChatInput.kt:303-304` | MAJOR |
| **DM-verification-blocked hint variant missing** (`dm_verification_text_blocked`) | `WidgetChatInput.java:623-628` | `WidgetChatInput.kt:316-318` (only two hint states) | MAJOR |
| **No autocomplete wiring at all** — `chat_input_mentions_recycler`, `chat_input_categories_recycler`, `application_commands_root`, `chat_input_stickers_container` exist in `widget_chat_input.xml` but are never bound | `input/autocomplete/**` (39 classes), `input/WidgetChatInputMentionsTags.java` | none | **BLOCKER** |
| **No attachments UI** — `WidgetChatInputAttachments.configureFlexInputContentPages(showCreateThreadOption)` and `AttachmentBottomSheet` not ported | `WidgetChatInput.java:591-596`, `input/attachments/AttachmentBottomSheet.java` | none | **BLOCKER** |
| **No `Loading` view state** — reference hides all four guards while loading; target has no loading branch and immediately renders `State()` defaults | `WidgetChatInput.java:571-589` | `WidgetChatInput.kt:292-319` | MAJOR |
| **No window-insets / IME handling** (`setWindowInsetsListeners`) | `WidgetChatInput.java:789-860` | absent | MAJOR |
| **No direct-share population** (`populateDirectShareData`) | `WidgetChatInput.java:759-788` | absent | MINOR |
| **No `ChatInputViewModel.Event` handling** (~75 lines of toasts/nav/dialogs) | `WidgetChatInput.java:677-751` | partial: only 3 send-failure toasts at `:109-125` | MAJOR |
| **Expression tray is a hand-rolled emoji-only panel** — no GIF tab, no sticker tab, no `ExpressionTrayTab`/`ExpressionPickerEventBus`, no badge | `input/expression/**` (8 classes), `WidgetChatInput.java:603` (`setShowExpressionTrayButtonBadge`) | `WidgetChatInput.kt:198-231` | **BLOCKER** |
| `WidgetChatInputEditText` (custom `EditText` with backspace listener / content-URI commit) replaced by raw `FlexEditText` | `input/WidgetChatInputEditText.java`, `input/OnBackspacePressedListener.java` | `WidgetChatInput.kt:180-189` | MAJOR |
| `WidgetChatInputTruncatedHint` not ported | `input/WidgetChatInputTruncatedHint.java` | absent | MINOR |
| `SmoothKeyboardReactionHelper` not ported (keyboard animation) | `input/SmoothKeyboardReactionHelper.java` | absent | MINOR |

Positive: reply/edit context bar (`WidgetChatInput.kt:329-368`) is a genuinely faithful port —
mention toggle tint, `reply_mention_on/off` strings + tooltips, jump-to-message click, member
colour span. Draft save/restore on channel switch (`:292-327`) matches reference semantics.

### 2.2 `list/WidgetChatList.kt` (204 L) → `list/WidgetChatList.java` (179 L)

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **`ThreadSpineItemDecoration` never added** — thread spines are a visible chat affordance | `WidgetChatList.java:50-52`, `list/ThreadSpineItemDecoration.java` | `WidgetChatList.kt:56-68` | MAJOR |
| **No item-animator control** — `disableItemAnimations()`/`enableItemAnimations()` and `defaultItemAnimator` capture missing; callers (scroll-to-message, thread transitions) have no way to suppress animations | `WidgetChatList.java:41,124-134,177` | absent | MAJOR |
| **`onPause` does not call `adapter.onPause()`/`disposeHandlers()`**; `onResume` does not call `setHandlers()`/`onResume()` — inline media players and touch/scroll handlers are never torn down | `WidgetChatList.java:136-147,163-172` | `WidgetChatList.kt:94-97` (only unsubscribes) | MAJOR |
| **`scrollTo` has no highlight and no completion callback** — reference uses `scrollToMessageId(id, Action0)` backed by `ScrollToWithHighlight` | `WidgetChatList.java:106-122`, `WidgetChatListAdapter.java:482` | `WidgetChatList.kt:109-118` | MAJOR |
| **Static mutable seams** — `stores`, `actionCoordinator`, `selectedChannels`, `onMessageClick`, `onMessageLongClick`, `onReactionLongClick` are `@Volatile var` companion fields; if `installStores` was never called the whole list renders empty with no error state | — | `WidgetChatList.kt:187-203`, guarded at `:75-81` | MAJOR |
| **Pagination is one-directional** — only `loadBefore` on scroll; no `loadAfter`/`loadMessages` when scrolled up from a jump target | `WidgetChatListAdapter.java:400` (`HandlerOfScrolls`) | `WidgetChatList.kt:59-67` | MAJOR |
| No `AppFlexInputViewModel` (thread-draft form, sticker suggestions depend on it) | `WidgetChatList.java:47,84` | absent | MAJOR |
| `FragmentLifecycleListener` not ported | `list/FragmentLifecycleListener.java` | absent | MINOR |
| Feature flags (`CreateThreadsFeatureFlag`, `NewThreadsPermissionsFeatureFlag`, `TextInVoiceFeatureFlag`, `ViewThreadsFeatureFlag`) not ported | `list/*FeatureFlag.java` | absent | MINOR |

### 2.3 `list/adapter/WidgetChatListAdapter.kt` (392 L) → `list/adapter/WidgetChatListAdapter.java` (941 L) + 43 item classes

The reference `onCreateViewHolder` switch covers **view types 0–45** (46 slots,
`WidgetChatListAdapter.java:843-938`). The target covers **13** (`WidgetChatListAdapter.kt:325-360`)
and `error("Unknown chat row type")` on anything else.

**Missing row types (BLOCKER — each is a user-visible message category that simply will not render):**

| Ref type | Ref item class | Feature |
|---|---|---|
| 21 | `WidgetChatListAdapterItemEmbed` | **rich embeds** (link previews, bot embeds) |
| 28 | `WidgetChatListAdapterItemAttachment` | attachment card w/ `InlineMediaView`, download, spoiler (target stub, §5) |
| 31 | `WidgetChatListAdapterItemSticker` | stickers in messages |
| 41/42 | `…ItemStickerGreet`, `…ItemStickerGreetCompact` | greet-sticker CTAs |
| 36 | `WidgetChatListAdapterItemBotComponentRow` | **bot UI components / buttons / selects** |
| 33 | `WidgetChatListAdapterItemApplicationCommand` | slash-command invocation header |
| 34 | `WidgetChatListAdapterItemEphemeralMessage` | ephemeral (interaction) messages |
| 19 | `WidgetChatListAdapterItemCallMessage` | call messages + join CTA |
| 24/39/43 | `…ItemInvite`, `…ItemStageInvite`, `…ItemGuildScheduledEventInvite` (+`ItemInviteBase`) | **invite embeds** |
| 22 | `WidgetChatListAdapterItemGameInvite` | game invites (`ViewEmbedGameInvite` exists but is never inflated) |
| 23 | `WidgetChatListAdapterItemSpotifyListenTogether` | Spotify listen-along |
| 26 | `WidgetChatListAdapterItemGift` | Nitro gift embeds |
| 30 | `WidgetChatListAdapterItemGuildTemplate` | guild template embeds |
| 25 | `WidgetChatListAdapterItemGuildWelcome` | new-member welcome CTA |
| 38 | `WidgetChatListAdapterItemGuildInviteReminder` | invite-reminder card |
| 35 | `WidgetChatListAdapterItemThreadEmbed` | **thread embeds under starter messages** |
| 37 | `WidgetChatListAdapterItemThreadDraftForm` | thread-draft name form |
| 40 | thread-starter divider | thread starter separation |
| 10 | `WidgetChatListAdapterItemBlocked` | **blocked-message collapse/expand group** |
| 44/45 | `…ItemAutoModBlocked`, `…ItemAutoModSystemMessageEmbed` | AutoMod blocked notice + report CTA |
| 11/12/13/14 | `…ItemSearchResultCount` + indexing/empty/error layouts | **search results in chat list** |
| 17 | `WidgetChatListAdapterItemMessageHeader` | pinned/search context header |
| 18 | `WidgetChatListAdapterItemMentionFooter` | mention-footer jump row |

**Other adapter gaps:**

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **`EventHandler` interface (25 callbacks) reduced to 8 lambdas** — missing `onMessageAuthorLongClicked`, `onMessageBlockedGroupClicked`, `onOpenPinsClicked`, `onQuickDownloadClicked`, `onDismissClicked`, `onDismissLocalMessageClicked`, `onCallMessageClicked`, `onCommandClicked`, `onStickerClicked`, `onSendGreetMessageClicked`, `onSystemMessageCtaClicked`, `onThreadClicked`/`onThreadLongClicked`, `onShareButtonClick`, `onBotUiComponentClicked`, `onRoleSubscriptionPurchaseTierClick`, `onReportIssueWithAutoMod`, `onInteractionStateUpdated`, `onListClicked`, `onOldestMessageId` | `WidgetChatListAdapter.java:203-399` | `WidgetChatListAdapter.kt:36-45` | **BLOCKER** |
| **`HandlerOfTouches` missing** — no long-press-drag, no touch interception for spoilers/links | `WidgetChatListAdapter.java:423-449` | absent | MAJOR |
| **`HandlerOfUpdates` missing** — no batched/`Runnable` update coalescing; target calls `submitList` directly on every emission | `WidgetChatListAdapter.java:450-481` | `WidgetChatList.kt:87` | MAJOR |
| **`ScrollToWithHighlight` missing** — jump-to-message has no highlight flash or retry-until-loaded | `WidgetChatListAdapter.java:482-633`, `:782-796` | absent | MAJOR |
| **`mentionMeMessageLevelHighlighting` missing** — `chat_list_adapter_item_highlighted_bg` and `…_gutter_bg` are unconditionally forced `GONE` | `WidgetChatListAdapter.java:703,812-816` | `WidgetChatListAdapter.kt:92-93,165-166,189-190,199-200` | MAJOR |
| **`publishInteractionState()` / `StoreChat.InteractionState` missing** | `WidgetChatListAdapter.java:647-655` | absent | MAJOR |
| **`ComponentProvider` (bot UI kit) not wired** | `WidgetChatListAdapter.java:671-674,707-713` | absent | MAJOR |
| **Author name is raw `username`** — no nickname, no guild role colour, no bot/system tag, no role icon, no communication-disabled icon (all three views forced `GONE`) | reference `WidgetChatListAdapterItemMessage`/`UsernameView` | `WidgetChatListAdapter.kt:95,98-101` | MAJOR |
| **Timestamp formatting is `DateFormat.SHORT` + literal `"edited"`** rather than localized `R.string.message_edited` / relative-time rules | — | `WidgetChatListAdapter.kt:316-320,339` | MINOR |
| **Avatar URL hardcoded to `avatars/{id}/{hash}.webp`** — no default avatar for null hash, no guild-member avatar, no animated avatars | — | `WidgetChatListAdapter.kt:383-384` | MAJOR |
| `ChatListItemMessageAccessibilityDelegate` not ported — reference exposes per-message accessibility *actions* (reply/react/…); target only sets `contentDescription` | `list/ChatListItemMessageAccessibilityDelegate.java` | `WidgetChatListAdapter.kt:290-297` | MAJOR |
| `WidgetChatListAdapterEventsHandler` (the concrete 12-dependency handler) not ported | `list/adapter/WidgetChatListAdapterEventsHandler.java` | absent | **BLOCKER** |

### 2.4 `list/entries/ChatListEntry.kt` (54 L) → `list/entries/ChatListEntry.java` (360 L) + 37 entry classes

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **`shouldShowThreadSpine` flag absent** on the base class — thread spine rendering impossible | `ChatListEntry.java:97,349-358` | `ChatListEntry.kt:9-13` | MAJOR |
| **`isInExpandedBlockedMessageChunk()` absent** | `ChatListEntry.java:353-355` | absent | MAJOR |
| **Entry factories absent**: `createEmbedEntries`, `createInviteEntries`, `createGiftEntries`, `createGuildTemplateEntries`, `createGameInviteEntries`, `createSpotifyListenTogetherEntries`, `createStickerEntries`, `createBotComponentEntries`, `createThreadEmbedEntries`, `createEntryForInvite` | `ChatListEntry.java:106-342` | none | **BLOCKER** |
| 26 of 38 entry variants missing (list in §4) | `list/entries/*` | — | **BLOCKER** |
| Entry does **not** carry `StoreMessageState.State`, `animateEmojis`, `autoPlayGifs`, `nickOrUsernames`, `guildRoles`, `isThreadStarterMessage` | `ChatListEntry.java:152-158` | `ChatListEntry.kt:25-53` | MAJOR |
| `stableId` is an FNV-1a hash of a string key — collision-prone versus reference `MGRecyclerDataPayload.getKey()` string identity | — | `ChatListEntry.kt:10-12` | MINOR |

### 2.5 `list/model/ChatListRows.kt` (128 L) → `list/model/WidgetChatListModel.java` (566 L) + `…ModelMessages`, `…ModelTop`

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **`safeText()` renders embeds/stickers/components as literal placeholder strings** — `"[Embed: …]"`, `"[Sticker: …]"`, `"[Interactive content]"`, `"[Call]"`, `"[Activity]"`, `"Message content unavailable"`. These are **not Discord strings** and are not in the reference at all; they are a stand-in for the missing embed/sticker/component rows | — | `ChatListRows.kt:98-112` | **BLOCKER** |
| **`ChatListState` enum missing** (`LOADING`/`CHAT`/`SEARCH…`) — no search mode, no indexing/empty/error state | `WidgetChatListModel.java:67-115` | absent | MAJOR |
| **Model fields missing**: `guild`, `guildId`, `userId`, `channelNames`, `myRoleIds`, `newestKnownMessageId`, `isSpoilerClickAllowed`, `oldestMessageId` | `WidgetChatListModel.java:380-537` | `ChatListRows.kt:15-23` | MAJOR |
| **`WidgetChatListModelMessages` (message→entry expansion incl. blocked-chunk grouping, thread starters, mention footer) not ported** | `list/model/WidgetChatListModelMessages.java` | absent | **BLOCKER** |
| **`WidgetChatListModelTop` (top-of-list: start-of-chat vs. loading vs. welcome vs. guild-invite-reminder) not ported** | `list/model/WidgetChatListModelTop.java` | `ChatListRows.kt:28-33` (only loading + start) | MAJOR |
| Message grouping constants hardcoded (7 min / 5 messages) with no reference basis; reference groups on `MessageUtils` rules | — | `ChatListRows.kt:12-13,88-96` | MINOR |
| No blocked-message collapsing, no ephemeral filtering, no `renderEmbeds` gate | `ChatListEntry.java:152-202` | absent | MAJOR |

### 2.6 `list/actions/WidgetChatListActions.kt` (178 L) → `list/actions/WidgetChatListActions.java` (658 L)

> **JADX BROKEN**: `WidgetChatListActions.configureUI(Model)` is **not decompiled** —
> `WidgetChatListActions.java:494-503` contains `Method dump skipped, instructions count: 947` and
> `throw new UnsupportedOperationException("Method not decompiled")`. The exact
> visibility/enablement rules for each menu row cannot be read from the reference. The gaps below
> are derived from the *layout IDs* the target itself references and from the reference's
> surviving helper methods (`confirmPublishMessage`, `removeAllReactions`, `toggleMessagePin`,
> `editMessage`, `replyMessage`, `deleteMessage`, `addReaction`) and `Model` fields.

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **9 of 17 menu rows are unconditionally hidden** — `start_thread`, `profile`, `manage_reactions`, `remove_all_reactions`, `publish`, `share`, `mark_unread`, `copy_id`, `report` are force-`isVisible = false` regardless of state | `WidgetChatListActions.java:508-513` (publish), `:574-582` (remove all reactions), `managereactions/WidgetManageReactions.java`, `list/PublishActionDialog.java` | `WidgetChatListActions.kt:118-126` | **BLOCKER** |
| **Recent-emoji quick-reaction row replaced by a single "Add Reaction" text cell** — no `WidgetChatListActionsEmojisAdapter`, no `EmojiItem`/`EmojiViewHolder`/`MoreEmojisViewHolder`, no dynamic column count, no `PaddedItemDecorator` | `WidgetChatListActions.java:439-476,630-657` | `WidgetChatListActions.kt:91-99,142-161` | **BLOCKER** |
| **No reactive `Model`** — target reads a `Serializable` `MessageActions.Input` snapshot from arguments at `onCreateDialog`; reference subscribes to `Model.Companion.get()` in `onResume` and re-renders on every store change (message deleted while sheet open → sheet is stale) | `WidgetChatListActions.java:620-627` | `WidgetChatListActions.kt:32-47` | MAJOR |
| **`Model` fields dropped**: `guild`, `messageAuthorName`, `messageContent`, `manageMessageContext`, `isDeveloper`, `recentEmojis`, `me`, `type` (chat vs. pin) | `WidgetChatListActions.java:117-131` | `MessageActions.kt:12-22` | MAJOR |
| **`showForPin` entry point missing** — target has one `show()` and no chat/pin `type` discrimination; pinned-message long-press (`WidgetChannelPinnedMessages.kt:36`) therefore has *no* action sheet | `WidgetChatListActions.java:596-602`, `:167-175` | `WidgetChatListActions.kt:168-176` | **BLOCKER** |
| **Edit does not unparse content** — reference runs `MessageUnparser.unparse(content, guild, channels, users, emojiSet)` to convert mentions/emoji back to source form before populating the input; target passes `message.content` raw | `WidgetChatListActions.java:527-566`, and `WidgetChatInput.kt:322` | `WidgetChatListActions.kt:52-54` | MAJOR |
| **Confirmation dialogs use `MaterialAlertDialogBuilder` instead of `MessageActionDialogs`/`WidgetNoticeDialog`** — different visuals, no `MessageActionDialogs.showPinMessageConfirmation` "don't ask again" behaviour | `list/actions/MessageActionDialogs.java`, `WidgetChatListActions.java:508-524,604-613` | `WidgetChatListActions.kt:132-139` | MAJOR |
| **No MFA/2FA elevation check** — `ManageMessageContext.from(..., guild.getMfaLevel(), ...)` gates delete/pin on MFA; target's `MessageActions.evaluate` has no MFA input | `WidgetChatListActions.java:148` | `MessageActions.kt:24-44` | MAJOR |
| **Cancel row is a programmatically constructed bare `TextView`** appended to the container, not a styled `ChatActionItem` | — | `WidgetChatListActions.kt:80-89` | MINOR |
| **No hide-keyboard on resume** | `WidgetChatListActions.java:623` | absent | MINOR |
| **Sheet dismisses silently when message is null** rather than showing an error | `WidgetChatListActions.java` (model-driven) | `WidgetChatListActions.kt:42-45` | MINOR |

`list/actions/MessageActions.kt` and `list/actions/MessageActionCoordinator.kt` have **no reference
counterpart** in this package — they are target-invented seams that partially replace
`ManageMessageContext` (in `com.discord.utilities.messagesend`/`permissions` in the reference) and
the inline REST calls inside `WidgetChatListActions`. Optimistic reaction/pin rollback in
`MessageActionCoordinator.kt:36-67` is good work, but it is not a port of anything.

### 2.7 `overlay/WidgetChatOverlay.kt` (105 L) → `overlay/WidgetChatOverlay.java` (528 L)

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **Sticker suggestions are hardcoded `GONE`** — `stickers_suggestions` exists in `widget_chat_overlay.xml`, has a 4-slot `StickerView` row, an analytics-tracked display event, a per-sticker send path (`ChatInputViewModel.sendSticker`), and a dismiss button that flips `setExpressionSuggestionsEnabled(false)`. None of it is ported | `WidgetChatOverlay.java:400-461`, `:484-496`, `:202-296` (`StickerAutocompleteState`) | `WidgetChatOverlay.kt:30` | **BLOCKER** |
| **`ChatTypingModel` not ported** — target's `State.typingUsers: List<String>` has no store binding; `stateSource` defaults to `observeId().map { State(it) }`, i.e. **the typing indicator never shows in the default configuration** | `overlay/ChatTypingModel.java`, `WidgetChatOverlay.java:506-508` | `WidgetChatOverlay.kt:38,102` | **BLOCKER** |
| **`OldMessageModel` not ported** — target's `viewingOldMessages` is likewise never fed; jump-to-present FAB is dead unless `onJumpToPresent` static is assigned | `WidgetChatOverlay.java:75-201`, `:497-505` (`requestNewestMessages()`) | `WidgetChatOverlay.kt:31-33,103` | **BLOCKER** |
| **Slowmode text uses `DateUtils.formatElapsedTime`** instead of `TimeUtils.toFriendlyStringSimple` | `WidgetChatOverlay.java:334-346` | `WidgetChatOverlay.kt:73-77` | MINOR |
| **Slowmode icon visibility rule differs** — reference keys on `channelRateLimit > 0` only; target ORs in `slowmodeSeconds > 0` | `WidgetChatOverlay.java:330-331` | `WidgetChatOverlay.kt:82-83` | MINOR |
| **Typing string built with `String.replace("!!{a}!!", …)`** instead of the reference's formatter-args path — will silently produce wrong output if the string resource uses `%1$s` | `WidgetChatOverlay.java:348-370` | `WidgetChatOverlay.kt:86-99` | MAJOR |
| No `TypingIndicatorViewHolder` extraction / no `ChatTypingModel.Hide` vs `.Typing` split | `WidgetChatOverlay.java:297-382` | inlined at `:54-84` | MINOR |

### 2.8 `pins/WidgetChannelPinnedMessages.kt` (92 L) → `pins/WidgetChannelPinnedMessages.java` (702 L)

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **`ChannelPinnedMessagesAdapterEventHandler` (a full `WidgetChatListAdapter.EventHandler` impl, ~200 L) not ported** — long-press does nothing (`onPinnedMessageLongPress` defaults `false`), no unpin action, no author sheet, no reactions | `WidgetChannelPinnedMessages.java:88-291` | `WidgetChannelPinnedMessages.kt:24,36` | **BLOCKER** |
| **No `Model` implementing `WidgetChatListAdapter.Data`** — target builds `List<ChatListEntry>` directly, so pinned rows lack guild/roles/nickname/permission context | `WidgetChannelPinnedMessages.java:311-627` | `WidgetChannelPinnedMessages.kt:79-87` | MAJOR |
| **No thread spine decoration** | `WidgetChannelPinnedMessages.java:633-637` | absent | MINOR |
| **No `onPause`/`onDestroy` adapter teardown** (inline media leaks) | `WidgetChannelPinnedMessages.java:655-672` | `WidgetChannelPinnedMessages.kt:61-65` | MAJOR |
| **Error path collapses to the empty state** — a REST failure renders "no pins" instead of an error | `WidgetChannelPinnedMessages.java:692+` | `WidgetChannelPinnedMessages.kt:57` | MAJOR |
| **`Host` interface is a target invention** requiring the parent fragment/activity to implement it; `error(...)` throw if not (`:67-68`) | — | `WidgetChannelPinnedMessages.kt:20-25,67-68` | MINOR |
| Toolbar found by recursive `findDescendant(Toolbar::class)` rather than by binding id | — | `WidgetChannelPinnedMessages.kt:39,70-75` | MINOR |

### 2.9 `input/emoji/WidgetEmojiPicker.kt` (225 L) → `input/emoji/WidgetEmojiPicker.java` (746 L) + 11 support classes

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **No `EmojiPickerViewModel`** — the target loads unicode emoji by parsing `assets/data/emojis.json` itself and builds the custom category by iterating `StoreStream.getGuilds().getGuilds()` synchronously on the UI thread | `input/emoji/EmojiPickerViewModel.java`, `WidgetEmojiPicker.java:280-295` | `WidgetEmojiPicker.kt:106-149` | **BLOCKER** |
| **No `EmojiPickerMode` (INLINE vs BOTTOM_SHEET) and no `EmojiPickerContextType`** — the sheet cannot configure the picker | `WidgetEmojiPicker.java:265-295`, `input/emoji/EmojiPickerMode.java`, `EmojiPickerContextType.java`, `EmojiPickerNavigator.java` | absent | **BLOCKER** |
| **Custom-emoji categories are flattened into a single `"custom"` bucket** — reference renders one category per guild with `GuildIcon` headers (`EmojiCategoryItem`, `EmojiCategoryViewHolder`, `EmojiCategoryAdapter`). `GuildIcon.kt` exists but is **never instantiated anywhere in the target** | `input/emoji/EmojiCategoryItem.java` etc. | `WidgetEmojiPicker.kt:106-112,174-208` | **BLOCKER** |
| **Category selection does not sync with scroll** — no `handleNewEmojiRecyclerScrollPosition`, `onSelectedCategoryAdapterPositionUpdated`, `selectCategoryByItemId`, `subscribeToCategoryRecyclerScrolls` | `WidgetEmojiPicker.java:360-371,540-572,675-681` | `WidgetEmojiPicker.kt:82-87` | MAJOR |
| **`WidgetEmojiAdapter` (sectioned, header-aware, sticky) replaced by a flat `GridLayoutManager(8)` with `notifyDataSetChanged()` on every change** | `input/emoji/WidgetEmojiAdapter.java` | `WidgetEmojiPicker.kt:45-48,151-164` | MAJOR |
| **No Nitro/premium upsell** (`EmojiAutocompletePremiumUpsellFeatureFlag`, `checkUpsellHeaderVisibility`, `onPremiumCtaClicked`) | `WidgetEmojiPicker.java:126-155,535-539` | absent | MAJOR |
| **No unicode-shortcut button** — forced `GONE` | `WidgetEmojiPicker.java:203-251`, `:573-588` | `WidgetEmojiPicker.kt:66` | MAJOR |
| **No inline search button** — forced `GONE`; search is always-on | `WidgetEmojiPicker.java:417-513` | `WidgetEmojiPicker.kt:53` | MINOR |
| **Search is a naive `contains` over all names** — no ranking, no diversity/skin-tone, no `EmojiPickerViewModel` query pipeline, no debounce | `WidgetEmojiPicker.java:325-359` | `WidgetEmojiPicker.kt:89-99` | MAJOR |
| **`EmojiPickerListener` / `OnEmojiSelectedListener` / `OnBackspacePressedListener` interfaces not ported** — replaced by a `Host` interface resolved via `parentFragment?.parentFragment as? Host` (`:75`), which silently no-ops if the hierarchy differs | `WidgetEmojiPicker.java:693-698,739-746` | `WidgetEmojiPicker.kt:27-30,75` | MAJOR |
| **No window-insets handling / bottom-bar visibility** | `WidgetEmojiPicker.java:573-588,660-674` | absent | MINOR |
| Category icons resolved by a hardcoded `when(String)`; reference uses `EmojiCategoryItem` typed identity | `input/emoji/EmojiCategoryItem.java` | `WidgetEmojiPicker.kt:213-223` | MINOR |
| `ExpressionPickerItemDecoration` not applied | `input/ExpressionPickerItemDecoration.java` | absent | MINOR |

### 2.10 `input/emoji/WidgetEmojiPickerSheet.kt` (46 L) → `input/emoji/WidgetEmojiPickerSheet.java` (148 L)

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **Listener is a non-persisted instance field** — `WidgetEmojiPickerSheet.kt:12,42` sets `listener` before `show()`. On process death / config change the fragment is recreated with `listener == null` and picking an emoji silently does nothing | `WidgetEmojiPickerSheet.java:145-147` (also a field, but the ref sheet is driven by `EmojiPickerListener` set by the parent in `onViewCreated`) | `WidgetEmojiPickerSheet.kt:12,41-44` | MAJOR |
| **No `EmojiPickerMode.BOTTOM_SHEET` / `EmojiPickerContextType` argument passed to the child picker** | `WidgetEmojiPickerSheet.java:106-116` | `WidgetEmojiPickerSheet.kt:19-31` | MAJOR |
| **`onCancel` does not clear the picker's search input** | `WidgetEmojiPickerSheet.java:70-77,84-89` | absent | MINOR |
| **Does not extend `WidgetExpressionPickerSheet`** — no GIF/sticker tab siblings | `WidgetEmojiPickerSheet.java:1` (extends `WidgetExpressionPickerSheet`), `input/expression/WidgetExpressionPickerSheet.java` | `WidgetEmojiPickerSheet.kt:11` | MAJOR |

### 2.11 `list/InlineMediaView.kt` (236 L) → `list/InlineMediaView.java` (570 L)

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **Uses raw `ExoPlayer.Builder(context).build()` per view instead of the shared `AppMediaPlayer`** — no player pooling, no `AppMediaPlayer.Event` stream, so every visible GIFV allocates its own decoder | `InlineMediaView.java:265-287` (`handlePlayerEvent(AppMediaPlayer.Event)`), `:235-240`, `:451-454` (`getUnsubscribeSignal`) | `InlineMediaView.kt:151-177` | MAJOR |
| **`ViewParams.description`/alt-text (`String str2`) dropped** — reference `updateUI(preview, uri, type, w, h, description)` sets a content description on the media; target's `ViewParams` has no description field and the view has **no `contentDescription` at all** except the volume toggle | `InlineMediaView.java:348-429` | `InlineMediaView.kt:100-119,228-235` | MAJOR |
| **Preview URL built by naive string concat** — `"$url?width=…&height=…&format=webp"`; no handling of URLs that already carry a query string, no `IconUtils`/media-proxy sizing | `InlineMediaView.java:303-329` | `InlineMediaView.kt:202-206` | MAJOR |
| **No `resetViews()` equivalent for the error state** — `onPlayerError` just calls `resetCurrentEmbed()`, which re-renders and can loop | `InlineMediaView.java:303-329` | `InlineMediaView.kt:171` | MINOR |
| **No unsubscribe signal / RxJava lifecycle** | `InlineMediaView.java:451-454` | absent | MINOR |
| Volume toggle state is recomputed via a nested closure that re-registers the click listener on every call | — | `InlineMediaView.kt:179-194` | MINOR |
| **Never instantiated** — `chat_list_item_attachment_inline_media` is forced `GONE` at `WidgetChatListAdapter.kt:191`, so this class is dead code in the running app | — | — | **BLOCKER** |

### 2.12 `list/ViewEmbedGameInvite.kt` (189 L) → `list/ViewEmbedGameInvite.java` (611 L)

| Gap | Ref cite | Target cite | Sev |
|---|---|---|---|
| **Never instantiated** — no `WidgetChatListAdapterItemGameInvite` in the target, so game-invite embeds never appear | `WidgetChatListAdapter.java:889-890` | — | **BLOCKER** |
| **Action-button enablement logic is inverted/garbled** — target sets `isEnabled = model.isInParty \|\| full \|\| creatorId == me.id` and then labels a *joinable* invite with `game_action_button_play_disabled_desktop_app`; the `full` branch shows "Ask to Join" while `isInParty` shows "Joined" (both non-actionable) | `ViewEmbedGameInvite.java:518-555` | `ViewEmbedGameInvite.kt:93-110` | MAJOR |
| **Asset URLs hand-rolled** — `assetUrl()` re-implements spotify/youtube/CDN routing and a `highestOneBit` size rounder inline instead of `IconUtils` | `ViewEmbedGameInvite.java:422-477` | `ViewEmbedGameInvite.kt:169-183` | MINOR |
| **Party user avatars are bare `SimpleDraweeView`s, not `PileView`** — no overflow "+N" chip, no rounding/border | reference `configureUI` uses the pile/recycler with overlap decoration | `ViewEmbedGameInvite.kt:137-167` | MAJOR |
| `Model.gPlayPackageNames` computed but never used (no Play-Store install intent) | `ViewEmbedGameInvite.java:518-555` | `ViewEmbedGameInvite.kt:126` | MINOR |

### 2.13 `list/ViewReplySpline.kt` (106 L) → `list/ViewReplySpline.java` (174 L) — **AT PARITY**

Stroke width (6 px), half-stroke translate (3), arc percent (0.25), both orientations, both cap
styles, `IllegalStateException` messages, and the `onSizeChanged` matrix math all match
(`ViewReplySpline.java:59-96` vs `ViewReplySpline.kt:32-97`). Only cosmetic difference: target
reads styleables by name, reference by index. Not instantiated anywhere in the target, but that is
a consequence of the missing reply-header row, already counted in §2.3.

### 2.14 `input/gifpicker/GifLoadingView.kt` (88 L) → `input/gifpicker/GifLoadingView.java` (168 L) — **AT PARITY**

Staggered/non-staggered heights (96/160 dp), `numRows` default 4, `GridColumnSpaceItemDecoration`
at 8 dp, `glv_is_staggered` styleable all match (`GifLoadingView.java:97-145` vs
`GifLoadingView.kt:27-60`). `GifLoadingViewKt` (the default-arg shim) is folded in via
`@JvmOverloads`. **However**: the entire GIF picker it serves (17 other classes) is missing, so this
view is dead code.

### 2.15 `input/MessageDraftsRepo.kt` (32 L) → `input/MessageDraftsRepo.java` (59 L) — **AT PARITY (+)**

`getTextChannelInput` / `setTextChannelInput` / `Provider.get()` / `@MainThread` all match. The
target additionally persists drafts to `SharedPreferences` (`MessageDraftsRepo.kt:19-22`), which the
reference does *not* do — an intentional improvement, not a gap. Note the target stores
`CharSequence` as `String`, losing spans on restore (reference keeps the `CharSequence`) — MINOR.

### 2.16 `input/emoji/GuildIcon.kt` (28 L) → `input/emoji/GuildIcon.java` (51 L) — **AT PARITY**

`hasIcon()` guard, `getMediaProxySize(layoutParams.width)`, `?size=` suffix all match
(`GuildIcon.java:23-32` vs `GuildIcon.kt:13-19`). Differences: target hardcodes the CDN URL and
`.webp` rather than going through `IconUtils.getForGuild` (no animated-icon support) — MINOR; and
the target inlines the media-proxy size table rather than calling `IconUtils` — MINOR. **Never
instantiated** (see §2.9).

---

## 3. Coverage table — reference class → target class

### 3.1 Represented (40)

| Reference class | Target |
|---|---|
| `input/MessageDraftsRepo` | `input/MessageDraftsRepo.kt` |
| `input/WidgetChatInput` | `input/WidgetChatInput.kt` (partial) |
| `input/emoji/GuildIcon` | `input/emoji/GuildIcon.kt` |
| `input/emoji/WidgetEmojiPicker` | `input/emoji/WidgetEmojiPicker.kt` (partial) |
| `input/emoji/WidgetEmojiPickerSheet` | `input/emoji/WidgetEmojiPickerSheet.kt` (partial) |
| `input/gifpicker/GifLoadingView` + `GifLoadingViewKt` | `input/gifpicker/GifLoadingView.kt` |
| `list/InlineMediaView` | `list/InlineMediaView.kt` (partial, dead) |
| `list/ViewEmbedGameInvite` | `list/ViewEmbedGameInvite.kt` (partial, dead) |
| `list/ViewReplySpline` | `list/ViewReplySpline.kt` (dead) |
| `list/WidgetChatList` | `list/WidgetChatList.kt` (partial) |
| `list/actions/WidgetChatListActions` | `list/actions/WidgetChatListActions.kt` (partial) |
| `list/adapter/WidgetChatListAdapter` | `list/adapter/WidgetChatListAdapter.kt` (partial) |
| `list/entries/ChatListEntry` | `list/entries/ChatListEntry.kt` (partial) |
| `list/model/WidgetChatListModel` | `list/model/ChatListRows.kt` (partial) |
| `overlay/WidgetChatOverlay` | `overlay/WidgetChatOverlay.kt` (partial) |
| `pins/WidgetChannelPinnedMessages` | `pins/WidgetChannelPinnedMessages.kt` (partial) |
| `list/entries/LoadingEntry` | `ChatListEntry.Loading` (`ChatListEntry.kt:15`) |
| `list/entries/EmptyPinsEntry` | `ChatListEntry.EmptyPins` (`:16`) |
| `list/entries/DividerEntry` | `ChatListEntry.Divider` (`:17`) |
| `list/entries/SpacerEntry` | `ChatListEntry.Spacer` (`:19`) |
| `list/entries/TimestampEntry` | `ChatListEntry.DayDivider` (`:20`) |
| `list/entries/NewMessagesEntry` | `ChatListEntry.UnreadDivider` (`:22`) |
| `list/entries/StartOfChatEntry` | `ChatListEntry.Start` (`:18`) |
| `list/entries/StartOfPrivateChatEntry` | `ChatListEntry.Start` (type-branched, `WidgetChatListAdapter.kt:346-350`) |
| `list/entries/MessageEntry` | `ChatListEntry.Header`/`Minimal`/`System`/`Failed` (`:31-38`) |
| `list/entries/AttachmentEntry` | `ChatListEntry.Attachment` (`:39-43`) — **stub binder** |
| `list/entries/ReactionsEntry` | `ChatListEntry.Reactions` (`:44-51`) |
| `list/entries/UploadProgressEntry` | `ChatListEntry.UploadProgress` (`:52-53`) |
| `list/adapter/WidgetChatListItem` | `WidgetChatListAdapter.Holder` (`WidgetChatListAdapter.kt:322`) |
| `list/adapter/…ItemMessage` | `bindHeader`/`bindMinimal`/`bindFailed` (`:90-186`) |
| `list/adapter/…ItemStart` | `bindStart` (`:265-288`) |
| `list/adapter/…ItemPrivateChannelStart` | `bindStart` private branch (`:267-278`) |
| `list/adapter/…ItemReactions` | `bindReactions` (`:198-233`) |
| `list/adapter/…ItemSystemMessage` | `bindSystem` (`:171-176`) |
| `list/adapter/…ItemUploadProgress` | `bindUploadProgress` (`:235-255`) |
| `list/adapter/…ItemEmptyPins` | `onBindViewHolder` case (`:65-66`) |
| `list/adapter/…ItemAttachment` | `bindAttachment` (`:188-196`) — **stub binder** |
| `list/adapter/…ItemTimestamp` | `bindDivider(date)` (`:68`) |
| `list/adapter/…ItemNewMessages` | `bindDivider(unread)` (`:69`) |

### 3.2 MISSING (237), grouped by feature cluster

**Autocomplete / mentions / slash commands — 64 classes, entirely absent**
`AutocompleteSelectionTypes`, `AutocompleteTypes`, `input/MentionUtilsKt`,
`input/WidgetChatInputMentionsTags`, `input/WidgetChatInputDiscoveryCommandsModel`,
`input/ChatInputApplicationsAdapter`, `input/ApplicationCategoryViewHolder`,
`input/CommandCategoryItem`, `input/AutocompleteStickerItem`,
`input/WidgetChatInputAutocompleteStickerAdapter`, `input/StickerViewHolder`,
all 26 `input/autocomplete/*`, all 7 `input/autocomplete/adapter/*`,
all 5 `input/autocomplete/sources/*`, `input/autocomplete/commands/AutocompleteCommandUtils`,
all 25 `input/models/*`, all 5 `input/applicationcommands/*`.

**Sticker picker + sticker store — 23 classes, entirely absent**
all `input/sticker/*` (`WidgetStickerPicker`, `WidgetStickerPickerSheet`,
`WidgetStickerPackStoreSheet`, `StickerFullSizeDialog`, `WidgetStickerAdapter`,
`StickerCategoryAdapter`/`Item`/`ViewHolder`, `HeaderItem`/`HeaderType`/`OwnedHeaderViewHolder`/
`StoreHeaderItem`/`StoreHeaderViewHolder`, `StickerItem`, `StickerViewHolder`,
`StickerPicker{,Inline,Sheet}ViewModel`, `StickerPickerListener`, `StickerPickerMode`,
`StickerPackStoreSheetViewModel`/`ViewType`/`Analytics`).

**GIF picker — 17 classes**
`GifAdapter`, `GifAdapterItem`, `GifCategoryAdapter`/`Item`/`ViewHolder`/`ViewModel`,
`GifPickerViewModel`, `GifSearchViewModel`, `GifViewHolder`, `ViewScalingOnTouchListener(+Kt)`,
`WidgetGifCategory`, `WidgetGifPicker(+Kt)`, `WidgetGifPickerSearch(+Kt)`, `WidgetGifPickerSheet`.

**Expression picker / tray — 8 classes**
all `input/expression/*` (`WidgetExpressionTray`, `WidgetExpressionPickerSheet`,
`WidgetExpressionPickerAdapter`, `ExpressionTrayViewModel`, `ExpressionTrayTab`,
`ExpressionDetailPage`, `ExpressionPickerEvent`, `ExpressionPickerEventBus`),
plus `ExpressionSelectionTypes`, `input/ExpressionPickerItemDecoration`.

**Emoji picker support — 11 classes**
`EmojiPickerViewModel`, `WidgetEmojiAdapter`, `EmojiCategoryAdapter`/`Item`/`ViewHolder`,
`EmojiPickerMode`, `EmojiPickerContextType`, `EmojiPickerNavigator`, `EmojiPickerListener`,
`OnEmojiSelectedListener`, `EmojiAutocompletePremiumUpsellFeatureFlag`.

**Chat-input infrastructure — 8 classes**
`input/ChatInputViewModel`, `input/AppFlexInputViewModel`, `input/WidgetChatInputAttachments`,
`input/attachments/AttachmentBottomSheet`, `input/WidgetChatInputEditText`,
`input/OnBackspacePressedListener`, `input/SmoothKeyboardReactionHelper`,
`input/SourcedAttachment`, `input/WidgetChatInputTruncatedHint`.

**Embeds & attachments in the list — 6 classes**
`…ItemEmbed`, `EmbedEntry`, `list/utils/EmbeddedMessageParser`,
`…ItemAutoModSystemMessageEmbed(+Kt)`, `AutoModSystemMessageEmbedEntry`.
(`…ItemAttachment`/`AttachmentEntry` are counted as present-but-stub.)

**Invites / gifts / templates / scheduled events — 11 classes**
`…ItemInvite`, `…ItemInviteBase`, `…ItemStageInvite`, `…ItemGuildScheduledEventInvite`,
`…ItemGuildTemplate`, `…ItemGift`, `…ItemGuildInviteReminder`,
`InviteEntry`, `StageInviteEntry`, `GuildScheduledEventInviteEntry`, `GuildTemplateEntry`,
`GiftEntry`.

**Stickers / greet in the list — 6 classes**
`…ItemSticker`, `…ItemStickerGreet`, `…ItemStickerGreetCompact`,
`StickerEntry`, `StickerGreetEntry`, `StickerGreetCompactEntry`.

**Threads — 9 classes**
`…ItemThreadEmbed`, `…ItemThreadDraftForm`, `ThreadEmbedEntry`, `ThreadDraftFormEntry`,
`ThreadStarterDividerEntry`, `list/ThreadSpineItemDecoration`, `CreateThreadsFeatureFlag`,
`NewThreadsPermissionsFeatureFlag`, `ViewThreadsFeatureFlag`.

**Bot UI kit / application commands in the list — 8 classes**
`…ItemBotComponentRow(+Kt)`, `BotUiComponentEntry`, `…ItemApplicationCommand`,
`…ItemEphemeralMessage`, `EphemeralMessageEntry`, all 4 `list/sheet/*`
(`WidgetApplicationCommandBottomSheet(+Kt)`, `…ViewModel(+Kt)`).

**Message actions & reaction management — 10 classes**
`list/actions/MessageActionDialogs`, `WidgetChatListActionsEmojisAdapter`, `EmojiItem`,
`EmojiViewHolder`, `MoreEmojisViewHolder`, all 5 `managereactions/*`
(`WidgetManageReactions`, `ManageReactionsModel`, `ManageReactionsModelProvider`,
`ManageReactionsEmojisAdapter`, `ManageReactionsResultsAdapter`).

**Publish / crosspost — 2 classes**
`list/PublishActionDialog`, `list/PublishActionDialogViewModel`.

**Blocked & AutoMod — 4 classes**
`…ItemBlocked`, `BlockedMessagesEntry`, `…ItemAutoModBlocked`, `AutoModSystemMessageEmbedEntry`.

**Search in chat list — 5 classes**
`…ItemSearchResultCount`, `SearchResultCountEntry`, `SearchEmptyEntry`, `SearchErrorEntry`,
`SearchIndexingEntry`.

**Calls / activities in the list — 6 classes**
`…ItemCallMessage(+Kt)`, `…ItemGameInvite`, `GameInviteEntry`,
`…ItemSpotifyListenTogether`, `SpotifyListenTogetherEntry`.

**Welcome / mention footer / headers — 6 classes**
`…ItemGuildWelcome(+Kt)`, `GuildWelcomeEntry`, `…ItemMentionFooter`, `MentionFooterEntry`,
`…ItemMessageHeader`, `MessageHeaderEntry`.

**Adapter/model plumbing — 6 classes**
`WidgetChatListAdapterEventsHandler`, `WidgetChatListItemKt`,
`list/model/WidgetChatListModelMessages`, `list/model/WidgetChatListModelTop`,
`list/ChatListItemMessageAccessibilityDelegate`, `list/FragmentLifecycleListener`.

**Overlay — 1 class**: `overlay/ChatTypingModel`.

**Chat root utilities — 4 classes**: `MessageManager`, `MessageContent`, `WidgetUrlActions`,
`list/TextInVoiceFeatureFlag`.

---

## 4. Placeholders / stubs / hardcoded state (explicit list)

| Location | What it does | Sev |
|---|---|---|
| `WidgetChatListAdapter.kt:188-196` `bindAttachment` | **PLACEHOLDER.** Forces `chat_list_item_attachment_inline_media` to `GONE`, always shows the card, sets description to the literal string `"${row.attachment.size} bytes"`, and hardcodes the English content description `"Attachment ${filename}"`. No image, no video, no spoiler, no download, no size formatting, no localization. | **BLOCKER** |
| `ChatListRows.kt:98-112` `safeText` | **PLACEHOLDER.** Substitutes non-Discord literals `"[Embed: …]"`, `"[Sticker: …]"`, `"[Interactive content]"`, `"[Call]"`, `"[Activity]"`, `"Attachment"`, `"System message"`, `"Message content unavailable"` for entire missing row types. | **BLOCKER** |
| `WidgetChatListAdapter.kt:265-288` `bindStart` | **PLACEHOLDER.** Hardcoded English copy: `"Welcome to the beginning of this group."`, `"This is the beginning of your direct message history."`, `"This is the start of the $name channel."`, `"Direct Message"`, `"Start of …"`. Forces `subheader1`, `edit_channel`, and `adapter_tags` to `GONE`. | MAJOR |
| `WidgetChatListActions.kt:118-126` `hideUnused` | **PLACEHOLDER.** Nine menu rows force-hidden regardless of permissions/state. | **BLOCKER** |
| `WidgetChatOverlay.kt:30` | **HARDCODED.** `stickers_suggestions` set `GONE` in `onViewCreated`, never touched again. | **BLOCKER** |
| `WidgetChatInput.kt:302,310-311` | **HARDCODED.** `guard_member_verification`, `chat_input_guard_action`, `chat_input_guard_action_secondary` set `GONE` unconditionally. | **BLOCKER** |
| `WidgetChatListAdapter.kt:91-93,98-101,165-166,189-190,199-200,232` | **HARDCODED.** Thread-embed spine, highlighted bg, gutter bg, communication-disabled icon, role icon, bot tag, decorator, and reaction-row content description all forced `GONE`/`null` on every bind. | MAJOR |
| `WidgetChatList.kt:187-203` companion statics | **STORE-IGNORING SEAM.** `stores`/`actionCoordinator` default to `null`; `observeState` short-circuits to `Observable.just(null)` (`:77-78`) and renders an empty list with no error/loading state if a caller forgot `installStores`. | MAJOR |
| `WidgetChatInput.kt:398-403` companion statics | Same pattern: six mutable static seams, all nullable/no-op defaults. `submit()` returns `false` silently when `coordinatorProvider()` is null (`:260`). | MAJOR |
| `WidgetChatOverlay.kt:102-103` | `stateSource`/`onJumpToPresent` are null-defaulted statics; default `stateSource` maps a channel id to `State(it)` with **empty typing list and zero slowmode**, so the overlay is inert out of the box. | **BLOCKER** |
| `WidgetEmojiPicker.kt:135-149` `EmojiCatalog` | Reads `assets/data/emojis.json` with `runCatching { … }.getOrDefault(emptyList())` — a missing/malformed asset silently yields **zero emoji** with no error surface. | MAJOR |
| `WidgetChatListAdapter.kt:339` | `messageTextEdited = "edited"` — hardcoded English constant instead of a string resource. | MINOR |
| `WidgetChatListAdapter.kt:290-297` `accessibility` | Hardcoded English `", failed to send"` and `"System"`. | MINOR |

Dead-but-correct code (implemented, never reachable): `ViewReplySpline.kt`, `GifLoadingView.kt`,
`GuildIcon.kt`, `InlineMediaView.kt`, `ViewEmbedGameInvite.kt`.

---

## 5. JADX-integrity notes

- `list/actions/WidgetChatListActions.java:494-503` — **`configureUI(Model)` is not decompiled**
  (`Method dump skipped, instructions count: 947`, followed by
  `throw new UnsupportedOperationException("Method not decompiled")`). The precise per-row
  visibility and enablement matrix for the 17-item action sheet **cannot be recovered from this
  decompile**. The gaps reported in §2.6 are inferred from the surviving helper methods, the
  `Model` field set, and the layout IDs the target itself enumerates — they are not guesses about
  the missing method body, and the exact rules must be re-derived from another source before
  implementing.
- `overlay/WidgetChatOverlay.java:522` — the ternary
  `(threadDraft == null || … || !ChannelUtils.q(parentChannel)) ? false : false` decompiles to a
  constant `false` on both branches. This is almost certainly a JADX artifact of an inverted
  boolean; the real predicate is unknown. Flagged, not guessed.
- `list/actions/WidgetChatListActions.java:593` — `(B || isWebhook || z3) ? false : false`, same
  artifact, in `replyMessage`'s `shouldMention` argument. The target's
  `MessageActionCoordinator.reply` (`MessageActionCoordinator.kt:69-73`) makes a reasonable
  inference (`!ownOrWebhook && !privateChannel`) but it is **unverified against the reference**.
- Reference field/method names are heavily obfuscated in places (`binding.f2342b`, `ChannelUtils.q`,
  `messageEmbed.l()`, `a.o`/`a.q`/`a.r` regex constants). Where a semantic reading was not certain,
  the finding above states the observable behaviour rather than the internal name.

---

## 6. Top gaps, ranked

1. **BLOCKER** — 33 of 46 chat row types are unimplemented; embeds, stickers, invites, bot
   components, threads, calls, blocked messages, AutoMod, search results, and ephemeral messages
   do not render at all. `WidgetChatListAdapter.kt:325-378` vs `WidgetChatListAdapter.java:843-938`.
2. **BLOCKER** — `ChatListRows.safeText` (`ChatListRows.kt:98-112`) substitutes invented English
   literals for those missing rows, so the app *appears* to work while showing `[Embed: …]` text.
3. **BLOCKER** — Autocomplete is entirely absent (64 classes): no @mentions, #channels, :emoji:,
   /commands, or sticker suggestions, despite the recyclers existing in `widget_chat_input.xml`.
4. **BLOCKER** — Sticker picker (23 classes), GIF picker (17), and expression tray (8) are absent;
   `WidgetChatInput.kt:198-231` hand-rolls an emoji-only panel in their place.
5. **BLOCKER** — `WidgetChatOverlay` is inert by default: `ChatTypingModel` and `OldMessageModel`
   are not ported, so the typing indicator and jump-to-present FAB never activate
   (`WidgetChatOverlay.kt:38,102-103`); sticker suggestions are forced `GONE` at `:30`.
6. **BLOCKER** — 9 of 17 message-action rows are force-hidden (`WidgetChatListActions.kt:118-126`):
   start thread, profile, manage reactions, remove all reactions, publish, share, mark unread,
   copy id, report.
7. **BLOCKER** — `showForPin` has no equivalent; pinned-message long-press opens nothing
   (`WidgetChannelPinnedMessages.kt:36` → `onPinnedMessageLongPress` defaults to `false`).
8. **BLOCKER** — Quick-reaction emoji strip in the action sheet replaced by a single "Add Reaction"
   text row (`WidgetChatListActions.kt:91-99,142-161`); `WidgetChatListActionsEmojisAdapter`,
   `EmojiItem`, `EmojiViewHolder`, `MoreEmojisViewHolder` all missing.
9. **BLOCKER** — Chat-input guards are hardcoded: member verification, lurker mode, follow-channel,
   and guild verification-level are all unimplemented (`WidgetChatInput.kt:302,305-311` vs
   `WidgetChatInput.java:165-284,639-668`).
10. **BLOCKER** — `WidgetChatListAdapter.EventHandler`'s 25 callbacks reduced to 8 lambdas
    (`WidgetChatListAdapter.kt:36-45`); `WidgetChatListAdapterEventsHandler` not ported at all.
11. **BLOCKER** — Attachment rendering is a stub: `bindAttachment` hides `InlineMediaView` and shows
    `"<size> bytes"` (`WidgetChatListAdapter.kt:188-196`), making `InlineMediaView.kt` dead code.
12. **BLOCKER** — `WidgetChannelPinnedMessages` has no `EventHandler` and no `Data` model
    (`WidgetChannelPinnedMessages.kt:34-37` vs `WidgetChannelPinnedMessages.java:88-627`); errors
    render as the empty state (`:57`).
13. **BLOCKER** — Emoji picker has no ViewModel, no per-guild custom categories (so `GuildIcon.kt` is
    never used), no `EmojiPickerMode`/`ContextType`, and parses `emojis.json` on the UI thread with
    a silent `getOrDefault(emptyList())` (`WidgetEmojiPicker.kt:106-149`).
14. **MAJOR** — No scroll-to-message highlight, no `HandlerOfUpdates` coalescing, no
    `HandlerOfTouches`, no item-animator control, no `onPause`/`onResume` adapter lifecycle
    (`WidgetChatList.kt:94-118`; `WidgetChatListAdapter.java:400-633`, `:714-747`).
15. **MAJOR** — Message author rendering loses nickname, role colour, bot/system tag, role icon, and
    communication-disabled icon; avatar URLs are hand-built with no default-avatar fallback
    (`WidgetChatListAdapter.kt:95,98-101,383-384`).
