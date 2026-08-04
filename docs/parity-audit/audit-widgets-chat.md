# Parity Re-audit - `com.discord.widgets.chat`

Date: **2026-08-04**

Scope:

- Target: `app/src/main/java/com/discord/widgets/chat/**`
- Reference: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\widgets\chat\**`
- Supplemental reference: `C:\Users\manti\Desktop\projeler\discorddecompile\out\com\discord\widgets\chat\**`
- Reference release: Discord Android 126.21 Stable

This supersedes the 2026-07-26 snapshot in this file. It is a structural and source-behavior
re-audit, not a live-backend or full device certification.

## Current counts

| Metric | 2026-07-26 | 2026-08-04 |
| --- | ---: | ---: |
| Reference non-synthetic top-level classes | 277 | 277 |
| Target Kotlin files | 18 | **303** |
| Reference adapter row slots represented | 13/46 | **46/46**, plus target rows for forwarded and activity messages |
| Explicit parity TODOs in this package | not measured | **1** |

The target now has more files than the reference non-synthetic count because reconstructed support
types and consolidated/factored behavior do not map one-for-one to JADX files. File count is therefore
only a structural checkpoint, not a behavioral percentage.

## Resolved findings

The following 2026-07-26 blockers are no longer open:

| Historical finding | Current evidence |
| --- | --- |
| 33 of 46 chat rows missing | `list/adapter/WidgetChatListAdapter.kt:90-150` maps all reference row categories: messages/replies, blocked/search states, embeds, attachments, stickers, invites, gifts, commands, ephemeral messages, threads, bot components, AutoMod, calls and scheduled events. |
| Attachment binder hid `InlineMediaView` | `WidgetChatListAdapterItemAttachment` and `InlineMediaView` are active adapter owners; the old `bindAttachment` hard stub no longer exists. |
| Invented `safeText()` placeholders stood in for rich rows | `list/entries/ChatListEntry.kt` now expands dedicated embed, attachment, activity, sticker, invite, gift, component and thread entries; `WidgetChatListAdapter` binds dedicated owners. |
| Autocomplete/mentions/slash commands absent | `input/autocomplete/**`, `input/applicationcommands/**`, `AutocompleteViewModel`, adapters, source models and selected-command rows now exist and are wired through `WidgetChatInput`. |
| Sticker/GIF/expression surfaces absent | `input/sticker/**`, `input/gifpicker/**` and `input/expression/**` now contain the picker, sheet, adapters and ViewModels. |
| Emoji picker had no ViewModel/categories/modes | `EmojiPickerViewModel`, `EmojiCategory*`, `EmojiPickerMode`, `EmojiPickerContextType`, navigator and listener contracts now exist. |
| Message action rows were hidden and quick reactions were fake | `list/actions/WidgetChatListActions.kt:105-190` binds the full action surface and `:192-210` configures the recent-emoji row. |
| Pinned-message action sheet absent | `WidgetChatListActions.Model.get()` selects `StorePinnedMessages` for `TYPE_PINS`; `showForPin` remains present in the companion API. |
| List lifecycle/highlight/touch/update handlers absent | `WidgetChatListAdapter.kt:80-85,154-169` owns touch, scroll, update and highlight state; `WidgetChatList` drives adapter lifecycle. |
| Thread spine absent | `list/ThreadSpineItemDecoration.kt` exists and the entry/model surface carries thread-spine state. |
| Bot UI kit unreachable | `WidgetChatListAdapter.kt:62` creates `ComponentProvider`; `WidgetChatListAdapterItemBotComponentRow` implements `ComponentActionListener` and launches the select sheet. |
| Overlay typing and old-message models inert | `overlay/WidgetChatOverlay.kt:24-70` uses live `StoreMessages`, `StoreChat`, `ChatTypingModel` and `StoreMessagesLoader`; the old static source is no longer read. |
| Chat input guards hardcoded off | `ChatInputViewModel`, `ChatInputGuardActions` and `WidgetChatInput` now model member verification, lurking, follow, verification level and timeout behavior. |
| Static chat-input collaborators made submit inert | `StoreStream.kt:1137-1145` installs the message coordinator, replies/editing stores and upload handler during initialization. |
| 126.21 developer-only external Share differs | Modern forwarding is the approved current-client replacement and is excluded from open legacy-parity findings. |
| Modern Activity cards treated empty stale instances as joinable | `ActivityCardState.evaluate` now marks an instance with zero participants ended/restartable; the row renders `Activity ended. Start a new one?` with Start while populated instances retain Join/Open. `ActivityMessageEntryTest` covers all branches. |

## Open findings

### 1. Sticker autocomplete strip is still absent from the overlay - BLOCKER

The reference `WidgetChatOverlay` observes autocomplete visibility, FlexInput state and selected
channel state, derives `StickerAutocompleteState`, displays up to four matching stickers, records
display/selection analytics, sends the selected sticker and lets the user disable suggestions:

- Vineflower `WidgetChatOverlay.kt:67-160`
- Vineflower `WidgetChatOverlay.kt:209-231`

The target overlay subscribes only to `OldMessageModel` and `ChatTypingModel`
(`overlay/WidgetChatOverlay.kt:61-70`). There is no `StickerAutocompleteState`,
`getMatchingStickers`, `setExpressionSuggestionsEnabled` or sticker-view binding anywhere in the
target chat package. The recovered `stickers_suggestions` layout is therefore not driven.

### 2. Start-thread action uses the wrong permission predicate - MAJOR

Vineflower recovers the previously JADX-broken `configureUI(Model)` exactly. The reference shows
the reply row for a non-local chat message when it is a user/application message and either the
channel is an ordinary private channel or permissions contain `SEND_MESSAGES | READ_MESSAGE_HISTORY`
(`67584L`), then delegates start-thread visibility to `ThreadUtils.canCreatePublicThread`:

- Vineflower `WidgetChatListActions.kt:470-527`
- `api/permission/Permission.kt:34,39` confirms `2048 | 65536 == 67584`

The target's `canCreatePublicThread()` instead requires both `CREATE_PUBLIC_THREADS` and
`MANAGE_MESSAGES` (`list/actions/WidgetChatListActions.kt:270-278`). Requiring
`MANAGE_MESSAGES` hides thread creation from ordinary members who can create public threads.

### 3. Guild-subscription system-message CTA remains a no-op - MAJOR

For message types 25-28, the target reaches an explicit parity TODO and performs no action
(`list/adapter/WidgetChatListAdapterItemSystemMessage.kt:162-169`). The reference records
`guildBoostPromotionOpened` and opens `WidgetGuildBoost` (Vineflower
`WidgetChatListAdapterItemSystemMessage.kt:453-466`). Both destination and analytics surfaces now
exist, so this is no longer dependency-blocked.

### 4. Five reference experiment gates have no target class - MAJOR

Exact path comparison leaves these reference classes unmatched:

- `list/CreateThreadsFeatureFlag`
- `list/NewThreadsPermissionsFeatureFlag`
- `list/ViewThreadsFeatureFlag`
- `list/TextInVoiceFeatureFlag`
- `input/emoji/EmojiAutocompletePremiumUpsellFeatureFlag`

Vineflower recovers their concrete experiment/feature predicates, including
`2020-09_threads`, `2021-06_threads_rollout`, `2021-08_threads_permissions`,
`2021-02_view_threads`, `2022-03_text_in_voice` and
`2021-03_nitro_emoji_autocomplete_upsell_android`. The target implements the downstream surfaces
without these owners, so availability can differ for accounts/guilds outside the enabled buckets.

### 5. Runtime verification remains incomplete - BLOCKER to declaring parity

Structural closure and unit/build checks do not prove the high-risk paths. The current PLAN records
all 45 reference chat entry types mapped and the queue/send/upload/search/GIF/expression surfaces
present, but still leaves offline/reconnect/live upload and media regressions open
(`PLAN.md:965`). No evidence in this re-audit exercises a live message send, upload, reconnect,
sticker suggestion, interaction component or media-player lifecycle across activity recreation.

## JADX/Vineflower/DEX integrity

- **Resolved:** JADX skipped `WidgetChatListActions.configureUI(Model)` (947 instructions).
  Vineflower recovers the body at `discorddecompile/out/.../WidgetChatListActions.kt:138-529`,
  including every row predicate. Findings above use that body rather than the old inference.
- **Resolved:** JADX printed the reply `shouldMention` predicate as a constant-false ternary.
  Vineflower recovers `replyMessage` at `WidgetChatListActions.kt:666-692`: the reply initially
  mentions a non-webhook, non-self author, and suppresses mention in private/system cases as shown
  by the two booleans passed to `StorePendingReplies`.
- **Resolved:** JADX printed the overlay forum-draft predicate incorrectly. Vineflower recovers the
  third `StickerAutocompleteState` input as `resolvedSelectedChannel is ThreadDraft`
  (`WidgetChatOverlay.kt:209-231`; lambda body represented in bytecode where the Kotlin anonymous
  class cannot be resugared).
- No remaining open chat finding in this report depends solely on broken JADX output.

## Verification boundary

Latest project-wide evidence recorded in `PLAN.md:942-948` is 1,678 unit tests, debug/release
assembly, debug lint, release lint-vital, Android-test compilation and 59/59 connected tests on
Android 16. This documentation re-audit ran no Gradle task, device test or live-backend action.

## Remaining blockers

1. Restore overlay sticker autocomplete/suggestion behavior.
2. Match the recovered start-thread permission predicate/delegation.
3. Decide and document 126.21 Share versus modern forwarding behavior.
4. Wire guild-subscription system-message taps to analytics and `WidgetGuildBoost`.
5. Restore or deliberately retire the five reference experiment gates with runtime evidence.
6. Run live/offline/reconnect/upload/media/component device regression before declaring chat parity.
