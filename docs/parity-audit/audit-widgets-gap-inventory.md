# Missing-Widget-Package Inventory (Manticord vs Discord Android 126.21)

READ-ONLY audit. Reference: `C:\Users\manti\StudioProjects\discord-jadx`.
Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`.

Counting rules used throughout:
- Reference class count = files under `app/src/main/java/com/discord/widgets/<pkg>/**` matching `*.java` with no `$` in the filename (non-synthetic top-level classes).
- Target file count = `*.kt` files under `app/src/main/java/com/discord/widgets/<pkg>/**`.
- Because the Kotlin reconstruction consolidates many types per file, a second column
  (declared top-level/nested `class|object|interface` declarations) is given in Part B
  so file-count coverage is not misread as type-level coverage.

Headline numbers:
- Reference widget packages: **45**. Target has a counterpart directory for **21**.
- **24 reference widget packages have no target directory at all**, covering **161 non-synthetic reference classes**.
- Whole widgets tree: **1155 reference classes vs 132 target Kotlin files (11.4% by file count)**.

---

## Part A — Entirely Missing Reference Widget Packages

For every package below, `grep` across the whole target `app/src` (Kotlin, Java, XML,
tests, androidTest) confirmed **no implementing class exists**; where the target holds a
*store/model/utility* fragment of the feature but no UI, that is called out explicitly.

Phase mapping uses `PLAN.md` → "Delivery Phases" (0-5 DONE, 6-8 IN PROGRESS, 9-14 NOT STARTED).

### 1. `accessibility` — 1 class
- `AccessibilityDetectionNavigator.java`

Feature: enqueues the "allow accessibility detection" `StoreNotices.Notice` dialog when a
screen reader is detected.

Target: `StoreAccessibility.kt`, `StoreUserSettings.observeIsAccessibilityDetectionAllowed()`
and `utilities/accessibility/*` exist, but `isDetectionAllowed` can only ever be set from a
synced user setting — **no navigator, no dialog, and no `StoreNotices` at all** in the target
(`app/src/main/java/com/discord/stores/` contains no `StoreNotices.kt`). Users on a screen
reader are silently stripped of `AccessibilityFeatureFlags.SCREENREADER`.

Phase: **10** (Settings — "Appearance, theme, locale, font scale, and accessibility").

### 2. `announcements` — 3 classes
- `WidgetChannelFollowSheet.java`, `WidgetChannelFollowSheetViewModel.java`, `WidgetChannelFollowSuccessDialog.java`

Feature: "Follow" an announcement/news channel into a channel of another guild, plus the
success confirmation dialog.

Target: zero hits for `ChannelFollow` anywhere in `app/src`.

Phase: **12** (Extended Feature Parity, catch-all channel surface).

### 3. `bugreports` — 6 classes
- `BugReportFeatureAdapter.java`, `BugReportFeatureHeaderViewHolder.java`, `BugReportFeatureViewHolder.java`, `BugReportViewModel.java`, `Priority.java`, `WidgetBugReport.java`

Feature: in-app bug report submission form (feature area picker + priority).

Target: zero hits for `BugReport`.

Phase: **12** ("reports, safety flows").

### 4. `changelog` — 3 classes
- `ChangeLogParser.java`, `WidgetChangeLog.java`, `WidgetChangeLogSpecial.java`

Feature: post-update "What's New" changelog modal, including the special/branded variant.

Target: zero hits for `ChangeLog`/`change_log`.

Phase: **12** (catch-all; depends on the missing `notice` queue).

### 5. `client` — 1 class
- `WidgetClientOutdated.java`

Feature: blocking "your client is out of date, please update" screen driven by the API's
minimum-client-version signal.

Target: zero hits for `ClientOutdated`.

Phase: **12** (catch-all) / **14** (release hardening).

### 6. `contact_sync` — 9 classes
- `AddFriendsFailed.java`, `ContactSyncFlowAnalytics.java`, `ContactSyncFriendSuggestionListAdapter.java`, `ContactSyncMode.java`, `ContactSyncPermissionsSheet.java`, `ContactSyncUpsellSheet.java`, `WidgetContactSync.java`, `WidgetContactSyncViewModel.java`, `WidgetContactSyncViewModelKt.java`

Feature: phone-contact upload, contact permission sheet, upsell sheet and contact-derived
friend suggestions.

Target: **store side partially present but UI absent** —
`StoreUserSettings.getContactSyncUpsellShown()/setContactSyncUpsellShown()` exists and is
unit-tested (`StoreStreamTest.kt:247,271`), and `WidgetFriendsList` inflates a
`R.id.menu_friends_contact_sync` menu item. No `WidgetContactSync` counterpart exists, and
the target manifest does not request `android.permission.READ_CONTACTS`.

Phase: **8** by content (Users and Social Features — "friend suggestions"), which is marked
IN PROGRESS with all bullets checked; this is an unclaimed Phase 8 scope gap. Otherwise **12**.

### 7. `debugging` — 2 classes
- `WidgetDebugging.java`, `WidgetFatalCrash.java`

Feature: developer debug/log screen and the fatal-crash recovery screen.

Target: zero hits for `WidgetDebugging`/`WidgetFatalCrash` (the two `Debugging` word hits are
unrelated Gradle/lint text).

Phase: **10** ("Developer diagnostics without secret leakage").

### 8. `directories` — 16 classes
- `AddServerViewHolder.java`, `DirectoriesSearchArgs.java`, `DirectoryCategoryArgs.java`, `DirectoryChannelItem.java`, `DirectoryChannelItemClickInterface.java`, `DirectoryChannelViewHolder.java`, `DirectoryEntryData.java`, `DirectoryEntryViewHolder.java`, `SectionHeaderViewHolder.java`, `WidgetDirectoriesSearch.java`, `WidgetDirectoriesSearchViewModel.java`, `WidgetDirectoriesViewModel.java`, `WidgetDirectoryCategory.java`, `WidgetDirectoryChannel.java`, `WidgetDirectoryChannelAdapter.java`, `WidgetDirectoryChannelViewPagerAdapter.java`

Feature: the Student/Hub **directory channel** surface — category tabs, directory entries,
directory search, and "add your server" flow.

Target: zero hits for `Directories`; the only `Directory` matches are
`Channel.DIRECTORY = 14` (`api/channel/Channel.kt:55`), a channel-list row type
(`widgets/channels/list/ChannelListRows.kt:24,112`) and unrelated `File`/temp-dir usage.
**Row rendering exists; the screen does not.** See Part D.

Phase: **12** ("Hubs, ... discovery").

### 9. `donut` — 1 class
- `WidgetDonutBetaPopup.java`

Feature: server-subscription ("donut") beta announcement popup.

Target: zero hits for `Donut`.

Phase: **13** (Billing and External Integrations) / **12**.

### 10. `emoji` — 2 classes
- `EmojiSheetViewModel.java`, `WidgetEmojiSheet.java`

Feature: the emoji detail bottom sheet (tap a custom emoji → source guild, join/upsell).

Target: `widgets/chat/input/emoji/WidgetEmojiPicker.kt` and `WidgetEmojiPickerSheet.kt` exist
(picker only, for input and reactions). Zero hits for `EmojiSheet`.

Phase: **12** ("Emoji, stickers, GIFs, embeds, and media viewer/player").

### 11. `feedback` — 2 classes
- `FeedbackSheetViewModel.java`, `WidgetFeedbackSheet.java`

Feature: generic star/NPS feedback bottom sheet (used post-call and post-flow).

Target: zero hits for `FeedbackSheet`.

Phase: **12** (catch-all; the call-quality entry point is **11**).

### 12. `guild_automod` — 3 classes
- `FeedbackType.java`, `ReportIssueWithAutoModViewModel.java`, `WidgetReportIssueWithAutoMod.java`

Feature: "report an issue with AutoMod" flow shown after AutoMod blocks a message.

Target: AutoMod **blocking is implemented** (`MessageResult.AutoModBlock`,
`MessageSendCoordinator.kt:210,245`, `WidgetChatInput.kt:119` shows a
`R.string.guild_automod_blocked_message_send_notice` toast) but the report/appeal UI is absent.

Phase: **12** ("Moderation, audit log, ...").

### 13. `guild_delete_feedback` — 3 classes
- `GuildDeleteFeedbackSheetNavigator.java`, `GuildDeleteFeedbackSheetViewModel.java`, `GuildDeleteFeedbackSubmitter.java`

Feature: the "why are you deleting this server?" sheet fired after guild deletion.

Target: zero hits for `GuildDeleteFeedback`.

Phase: **12** ("server settings").

### 14. `guildcommunicationdisabled` — 7 classes (all under `start/`)
- `start/DisableGuildCommunicationViewModel.java`, `start/GuildCommunicationDisabledBottomSheetViewModel.java`, `start/GuildCommunicationDisabledDateUtils.java`, `start/GuildCommunicationDisabledGuildsFeatureFlag.java`, `start/TimeDurationDisabledCommunication.java`, `start/WidgetDisableGuildCommunication.java`, `start/WidgetEnableGuildCommunication.java`

Feature: member **time-out** (start/end "communication disabled") duration picker and the
"you are timed out" bottom sheet.

Target: **model/permission side present, UI absent** —
`GuildMember.isCommunicationDisabled()`, `StoreGuilds.observeCommunicationDisabledGuildMembers()`,
`PermissionUtils` masking to `COMMUNICATIONS_DISABLED_DEFAULT`, `WidgetChatInput` gating, and a
Time out / Remove time out button in `UserProfileAdminView`. No duration picker, no submitter,
no timed-out bottom sheet.

Phase: **12** ("Moderation").

### 15. `hubs` — 54 classes (largest missing package)
- Root: `AddNameState`, `AddServerConfirmationArgs`, `AuthenticationResult`, `DescriptionResult`, `DiscordHubAddServerViewHolder`, `DomainGuildInfo`, `DomainsState`, `HubAddNameArgs`, `HubAddServerState`, `HubAuthenticationArgs`, `HubAuthenticationState`, `HubDescriptionArgs`, `HubDescriptionState`, `HubDomainArgs`, `HubDomainViewHolder`, `HubEmailArgs`, `HubEmailEntryPoint`, `HubEmailState`, `HubWaitlistArgs`, `HubWaitlistResult`, `HubWaitlistState`, `MultipleDomainResult`, `RadioSelectorItem`, `RadioSelectorItems`, `RadioSelectorViewHolder`, `WidgetHubAddName`, `WidgetHubAddNameViewModel`, `WidgetHubAddServer`, `WidgetHubAddServerConfirmationDialog`, `WidgetHubAddServerViewModel`, `WidgetHubAuthentication`, `WidgetHubAuthenticationViewModel`, `WidgetHubDescription`, `WidgetHubDescriptionViewModel`, `WidgetHubDomainSearch`, `WidgetHubDomains`, `WidgetHubDomainsViewModel`, `WidgetHubEmailFlow`, `WidgetHubEmailViewModel`, `WidgetHubWaitlist`, `WidgetHubWaitlistViewModel`, `WidgetRadioSelectorBottomSheet`
- `events/`: `HubEventsArgs`, `HubEventsEventListener`, `HubEventsPage`, `HubGuildScheduledEventData`, `WidgetHubEventViewHolder`, `WidgetHubEventsPage`, `WidgetHubEventsPageAdapter`, `WidgetHubEventsPageFooterViewHolder`, `WidgetHubEventsPageHeaderViewHolder`, `WidgetHubEventsPageViewHolder`, `WidgetHubEventsState`, `WidgetHubEventsViewModel`

Feature: Student Hubs — school-email verification, domain selection, waitlist, hub naming and
description, adding servers to a hub, and the hub events page.

Target: **API/model plumbing only** — `api/guild/GuildHubType.kt`, `GuildHubTypeTypeAdapter`,
`Guild.hubType`, `Guild.isHub()`, `GuildExperimentFilter.GuildHubTypesFeatureFilter`. No hub UI
whatsoever.

Phase: **12** ("Hubs, reports, safety flows, and remaining manifest routes").

### 16. `media` — 3 classes
- `WidgetMedia.java`, `WidgetMediaViewModel.java`, `WidgetQRScanner.java`

Feature: fullscreen image/video viewer (with download/share) and the QR login scanner.

Target: zero hits for `WidgetMedia`/`QRScanner`. `WidgetChatListAdapter.bindAttachment`
(`widgets/chat/list/adapter/WidgetChatListAdapter.kt:188-195`) renders attachment cards with
**no click listener**, so images/videos cannot be opened — a hard stop, not a dead-end.

Phase: **12** ("media viewer/player"); QR scanner also touches **5**/**10** (remote auth).

### 17. `notice` — 5 classes
- `NoticePopup.java`, `NoticePopupChannel.java`, `WidgetNoticeDialog.java`, `WidgetNoticeNuxOverlay.java`, `WidgetNoticeNuxSamsungLink.java`

Feature: the global in-app notice system — top-of-screen popups (new message in another
channel), generic notice dialogs, the NUX overlay and the Samsung-link notice.

Target: no `StoreNotices` and no notice widgets. This package is the substrate that
`accessibility`, `changelog`, `client`, `donut` and `playstation` all publish into, so those
five cannot land before it.

Phase: **12** (catch-all); the popup-on-new-message part overlaps **9**.

### 18. `nux` — 14 classes
- `GuildCreateArgs`, `GuildTemplate`, `GuildTemplateAnalytics`, `GuildTemplateArgs`, `GuildTemplateCardViewHolder`, `GuildTemplateTextViewHolder`, `GuildTemplateTypes`, `GuildTemplateViewType`, `GuildTemplatesAdapter`, `WidgetGuildTemplates`, `WidgetHubGuildTemplates`, `WidgetNavigationHelp`, `WidgetNuxChannelPrompt`, `WidgetNuxPostRegistrationJoin`

Feature: new-user onboarding — guild template picker (incl. the hub variant), navigation help
overlay, first-channel prompt and post-registration join.

Target: **partial model/auth plumbing only** — `models/domain/ModelGuildTemplate.kt`,
`DeepLinkRoute.GuildTemplate` + `DeepLinkParser` (`discord.new/<code>` and `/template/<code>`),
`AuthLandingContent.GuildTemplate` / `AuthInviteInfoView.configureGuildTemplate`, and
`widgets/guilds/create/GuildTemplateChannelsView.kt`. No template picker, no NUX screens.

Phase: **12** ("Guild creation, invites, templates, onboarding, and discovery").

### 19. `phone` — 4 classes
- `OnCountryCodeSelectedListener.java`, `PhoneCountryCodeAdapter.java`, `PhoneCountryCodeAdapterItem.java`, `WidgetPhoneCountryCodeBottomSheet.java`

Feature: the country-code picker bottom sheet used by every phone-number input.

Target: `models/phone/PhoneCountryCode.kt` exists (with `DEFAULT_COUNTRY_CODE` = US) and
`views/phone/PhoneOrEmailInputView.kt` exposes `onCountryCodeClick` / `setCountryCode`, but
**no caller ever passes a non-null `onCountryCodeClick`**, so the picker row is hidden and the
country code is permanently pinned to `+1`.

Phase: **10** ("Account profile, email, phone, ..."); the auth phone flows shipped in **5**
already depend on it.

### 20. `playstation` — 3 classes
- `PlaystationExperimentUtilsKt.java`, `PlaystationUpsellManager.java`, `WidgetPlaystationIntegrationUpsellBottomSheet.java`

Feature: PlayStation account-connection upsell bottom sheet + experiment gate.

Target: zero hits for `Playstation`.

Phase: **13** (External integrations).

### 21. `quickmentions` — 1 class
- `WidgetQuickMentionsDialog.java`

Feature: the quick "recent mentions" dialog reachable from the mentions surface.

Target: zero hits for `QuickMentions`. (`widgets/user/WidgetUserMentions` exists as the tab.)

Phase: **12** (catch-all).

### 22. `search` — 4 classes
- `WidgetSearch.java`, `results/WidgetSearchResults.java`, `suggestions/WidgetSearchSuggestions.java`, `suggestions/WidgetSearchSuggestionsAdapter.java`

Feature: **message search** — the search input with filter/token suggestions and the paginated
results list. (Distinct from the quick switcher, which the target has as
`widgets/user/search/WidgetGlobalSearch`.)

Target: zero hits for `WidgetSearch`/`SearchSuggestions`. The channel sidebar exposes a Search
button anyway — see Part D.

Phase: **12** ("Search and quick switcher").

### 23. `stickers` — 9 classes
- `GuildStickerSheetViewModel`, `StickerPremiumUpsellDialog`, `StickerPurchaseLocation`, `StickerSheetViewModel`, `UnsendableStickerPremiumUpsellDialog`, `WidgetGuildStickerSheet`, `WidgetStickerPackDetailsDialog`, `WidgetStickerSheet`, `WidgetUnknownStickerSheet`

Feature: sticker detail sheets (guild sticker, pack details, unknown sticker) and the Nitro
sticker upsells.

Target: **API/settings only** — `api/sticker/*` (`Sticker`, `StickerPartial`,
`StickerFormatType`, adapters), `StickerAnimationSettingsStore.kt`, the sticker animation and
suggestion toggles in `WidgetSettingsApp`, and `[Sticker: name]` text fallbacks in
`ChatListRows`/`ThreadBrowserThreadView`/`ForumBrowserPostView`. No sticker picker and no
sticker sheets.

Phase: **12** ("Emoji, stickers, GIFs, ...").

### 24. `tos` — 5 classes
- `WidgetTos.java`, `WidgetTosKt.java`, `WidgetTosReportViolation.java`, `WidgetTosReportViolationReasonView.java`, `WidgetTosReportViolationViewModel.java`

Feature: the forced Terms-of-Service acceptance screen (required action) **and** the
"report a ToS violation" flow.

Target: no `com.discord.widgets.tos` package. `AppActivity.kt:152` nevertheless routes to it
by string class name — see Part D, item 1.

Phase: **5** (Authentication and Navigation — "Required-action and age-gate routing",
currently marked **DONE**) for `WidgetTos`; **12** ("safety flows") for the report flow.

---

## Part B — Whole-Tree Rollup

`Ref` = non-synthetic reference classes. `Kt files` = target `*.kt` files.
`Kt types` = declared top-level/nested `class`/`object`/`interface` in the target package
(shown because the reconstruction packs many types per file; file-count coverage understates
packages like `auth`, `friends` and `user`).

| Package | Ref classes | Target .kt files | Coverage (files) | Target declared types |
|---|---:|---:|---:|---:|
| accessibility | 1 | 0 | **0%** | 0 |
| announcements | 3 | 0 | **0%** | 0 |
| auth | 21 | 3 | 14% | 47 |
| botuikit | 21 | 5 | 24% | 6 |
| bugreports | 6 | 0 | **0%** | 0 |
| captcha | 4 | 1 | 25% | 1 |
| changelog | 3 | 0 | **0%** | 0 |
| channels | 114 | 15 | 13% | 66 |
| chat | 277 | 18 | 6% | 64 |
| client | 1 | 0 | **0%** | 0 |
| contact_sync | 9 | 0 | **0%** | 0 |
| debugging | 2 | 0 | **0%** | 0 |
| directories | 16 | 0 | **0%** | 0 |
| donut | 1 | 0 | **0%** | 0 |
| emoji | 2 | 0 | **0%** | 0 |
| feedback | 2 | 0 | **0%** | 0 |
| forums | 16 | 3 | 19% | 5 |
| friends | 13 | 5 | 38% | 38 |
| guild_automod | 3 | 0 | **0%** | 0 |
| guild_delete_feedback | 3 | 0 | **0%** | 0 |
| guild_role_subscriptions | 42 | 4 | 10% | 4 |
| guildcommunicationdisabled | 7 | 0 | **0%** | 0 |
| guilds | 75 | 8 | 11% | 25 |
| guildscheduledevent | 47 | 10 | 21% | 14 |
| home | 11 | 2 | 18% | 4 |
| hubs | 54 | 0 | **0%** | 0 |
| media | 3 | 0 | **0%** | 0 |
| mobile_reports | 7 | 3 | 43% | 7 |
| notice | 5 | 0 | **0%** | 0 |
| nux | 14 | 0 | **0%** | 0 |
| phone | 4 | 0 | **0%** | 0 |
| playstation | 3 | 0 | **0%** | 0 |
| quickmentions | 1 | 0 | **0%** | 0 |
| roles | 2 | 2 | 100% | 2 |
| search | 4 | 0 | **0%** | 0 |
| servers | 108 | 9 | 8% | 13 |
| settings | 69 | 8 | 12% | 14 |
| share | 1 | 1 | 100% | 7 |
| stage | 31 | 2 | 6% | 3 |
| status | 8 | 3 | 38% | 6 |
| stickers | 9 | 0 | **0%** | 0 |
| tabs | 6 | 2 | 33% | 6 |
| tos | 5 | 0 | **0%** | 0 |
| user | 55 | 17 | 31% | 76 |
| voice | 66 | 11 | 17% | 19 |
| **TOTAL** | **1155** | **132** | **11.4%** | **427** |

**0% packages (24):** accessibility, announcements, bugreports, changelog, client,
contact_sync, debugging, directories, donut, emoji, feedback, guild_automod,
guild_delete_feedback, guildcommunicationdisabled, hubs, media, notice, nux, phone,
playstation, quickmentions, search, stickers, tos — **161 reference classes, 14% of the
reference widget surface, with no target directory at all.**

Lowest non-zero coverage (largest remaining work inside existing packages):
`chat` 6%, `stage` 6%, `servers` 8%, `guild_role_subscriptions` 10%, `guilds` 11%,
`settings` 12%, `channels` 13%, `auth` 14% (file count; 47 declared types).
Only `roles` (2/2) and `share` (1/1) reach 100% by file count.

---

## Part C — Manifest Routes

Reference: `discord-jadx/app/src/main/AndroidManifest.xml` (298 lines, merged manifest).
Target: `manticordrebuild/app/src/main/AndroidManifest.xml` (123 lines, source manifest).
Third-party auto-merged components (Firebase, Play Services, WorkManager, Billing,
datatransport, Room, Flipper) are excluded as merge-time artifacts.

### C1. First-party components with no target counterpart (11)

| Kind | Reference component | Intent filters / notes |
|---|---|---|
| activity | `com.discord.samsung.SamsungConnectActivity` | `@style/AppTheme.Translucent`; Samsung Game/Link integration |
| activity | `com.discord.app.AppActivity` (base, `AppTheme.Dark`) | non-exported base entry; target declares only `AppActivity$Main` |
| activity | `com.discord.app.AppActivity$Call` | `excludeFromRecents`, `singleTask` — ongoing-call activity |
| activity | `com.discord.app.AppActivity$IncomingCall` | `showOnLockScreen`/`showWhenLocked`/`turnScreenOn` — ringing over lock screen |
| activity | `com.yalantis.ucrop.UCropActivity` | avatar/banner crop (first-party-driven) |
| service | `com.discord.utilities.fcm.NotificationClient$FCMMessagingService` | `com.google.firebase.MESSAGING_EVENT`, `com.google.firebase.INSTANCE_ID_EVENT` |
| service | `com.discord.app.DiscordConnectService` | exported, `android:permission="com.discord.permission.CONNECT"`, action `com.discord.intent.action.CONNECT`, data `discord://app/connect/*` |
| service | `com.discord.utilities.voice.DiscordOverlayService` | voice overlay bubble |
| service | `com.discord.utilities.voice.VoiceEngineForegroundService` | `foregroundServiceType="mediaProjection"` — screen share |
| receiver | `com.discord.utilities.fcm.NotificationActions` | `com.discord.intent.action.ENQUEUE_WORK`, `.NOTIFICATION_DELETED`, `.NOTIFICATION_CANCEL` |
| receiver | `com.discord.utilities.io.NetworkUtils$NetworkDownloadReceiver` | `android.intent.action.DOWNLOAD_COMPLETE` |

Plus `androidx.sharetarget.ChooserTargetServiceCompat` (declared explicitly in the reference and
referenced by `AppAction`'s `chooser_target_service` meta-data) — absent from the target.

### C2. Declarations (non-component) with no target counterpart

- `<permission android:name="com.discord.permission.CONNECT" protectionLevel="dangerous">` — gate for `DiscordConnectService`.
- `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts"/>` on the launcher activity. **`app/src/main/res/xml/shortcuts.xml` exists in the target** (declaring a `share-target` for `com.discord.app.AppActionActivity`) but is never referenced from the manifest, so Direct Share targets are dead.
- `<meta-data android:name="com.google.android.actions" android:resource="@xml/actions"/>`. **`app/src/main/res/xml/actions.xml` exists in the target** and defines `custom.actions.intent.JOIN_SERVER`, `SELECT_SERVER`, `SELECT_CHANNEL`, `SELECT_USER`, `actions.intent.CREATE_MESSAGE`, all fulfilled via `https://discord.com/query/?type=…`. Not wired → no Google Assistant App Actions.
- `<meta-data android:name="android.service.chooser.chooser_target_service" …>` on the share activity.
- `<meta-data android:name="com.discord.features.FLAG" android:value="1"/>`, `libdiscord_version`, Firebase default notification icon/color, `firebase_crashlytics_collection_enabled`, Nearby Messages API key (must **not** be copied — reference credential).
- 20 of 21 `uses-permission` entries. Target declares only `INTERNET`. Missing and load-bearing for the missing components: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `CAMERA`, `FOREGROUND_SERVICE`, `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `READ_CONTACTS` (contact_sync), `READ/WRITE_EXTERNAL_STORAGE` (media/download), `BLUETOOTH`, `ACCESS_NETWORK_STATE`, `GET_ACCOUNTS`, `PACKAGE_USAGE_STATS`, `RECEIVE_BOOT_COMPLETED`, `com.google.android.c2dm.permission.RECEIVE`, `com.android.vending.BILLING`.
- All `<uses-feature>` (camera, microphone, bluetooth, faketouch) and the entire `<queries>` block (Android 11 package visibility: Spotify, Samsung Game Tools/Home, Authy, Google Authenticator, CustomTabsService, chooser/pick/get-content intents). Without `<queries>` the target cannot resolve those apps on API 30+.

### C3. Deep-link / intent routes

HTTPS and scheme routes: the target manifest is a **superset** of the reference path set —
it keeps `/app`, `/invite/`, `/gifts/`, `/template/`, `/channels/`, `/users/`, `/feature/`,
`/discovery`, `/query/`, `/ra/`, `/events/`, `/connect/authorize`, adds `/settings`, adds the
`manticord` scheme, and collapses `discord://action/oauth2/authorize` +
`discord://action/connect/authorize` into `host=action` + `pathPrefix=/`. No HTTPS route is
missing.

Route-level gaps:
1. **`com.discord.intent.action.CONNECT` / `discord://app/connect/*`** — the exported
   `DiscordConnectService` route (third-party "Connect to Discord" SDK) is entirely absent.
2. **`android.intent.action.DOWNLOAD_COMPLETE`** — no receiver; downloads never report completion.
3. **FCM `MESSAGING_EVENT` / `INSTANCE_ID_EVENT`** — no push entry point at all.
4. **Notification action broadcasts** (`ENQUEUE_WORK`, `NOTIFICATION_DELETED`, `NOTIFICATION_CANCEL`) — absent.
5. **App shortcuts / Direct Share** (`@xml/shortcuts`) — resource present, manifest hook absent.
6. **Google App Actions** (`@xml/actions`) — resource present, manifest hook absent.
7. **App Links verification regressed**: reference declares `android:autoVerify="true"` on the
   `discord.gg` / `discord.gift` / `discord.new` filter; the target sets
   `android:autoVerify="false"` on both HTTPS filters. Correct for a non-owned domain, but it
   means those links always show a disambiguation dialog (tracked by Phase 13, "App Links for
   the new application ID").

Gap count: **11 first-party components + 6 declaration groups + 7 route-level gaps**.

---

## Part D — Live Dead-Ends (reachable from shipping target navigation)

These are **not** future work: shipping target UI already leads here.

### D1. CRASH — Terms of Service required action (`widgets/tos`)
`app/src/main/java/com/discord/app/AppActivity.kt:152`

```kotlin
RequiredAction.AGREEMENTS, RequiredAction.TOS_UPDATE_ACKNOWLEDGMENT -> "com.discord.widgets.tos.WidgetTos"
```

`createRouteFragment` passes this string to
`supportFragmentManager.fragmentFactory.instantiate(classLoader, className)`
(`AppActivity.kt:159-160`). `com.discord.widgets.tos.WidgetTos` **does not exist in the target**
— it is the only string-named route in the codebase without a counterpart (the other four,
`WidgetFriendsList`, `WidgetGlobalSearch`, `WidgetUserMentions`, `WidgetSettings`, all resolve).
Any account the API returns `AGREEMENTS` or `TOS_UPDATE_ACKNOWLEDGMENT` for will hit
`Fragment.InstantiationException` / `ClassNotFoundException` on the main route transaction —
an unrecoverable launch crash, not a missing screen. Phase 5 is marked **DONE** with this
route live.

### D2. Silent no-op — channel sidebar "Search" button (`widgets/search`)
`widgets/channels/WidgetChannelSidebarActions.kt:35,41,51`,
`views/channelsidebar/GuildChannelSideBarActionsView.kt:50-54`,
`views/channelsidebar/PrivateChannelSideBarActionsView.kt`

`GuildChannelSideBarActionsView.configure(showSearch = true)` is the default, so the Search
button is drawn and clickable for **every guild channel and every DM/group DM**. The click
routes to `WidgetChannelSidebarActions.onSearch`, and a whole-`src` grep shows
`WidgetHome.kt:61-62` assigns only `onPins` and `onNotifications` — `onSearch`, `onSettings`
and `onCall` are never assigned. Tapping Search does nothing at all, with no feedback.

### D3. Wrong screen — directory channels in the channel list (`widgets/directories`)
`widgets/channels/list/ChannelListRows.kt:112`,
`widgets/channels/list/WidgetChannelsList.kt:143,169,215` and the adapter's
`root.setOnClickListener { click(row.channel.guildId, row.channel.id) }` (`canOpen = true`
for every non-category, non-voice row).

`Channel.DIRECTORY` (type 14) channels are projected into `ChannelListRow.Directory`, inflated
with `R.layout.widget_channels_list_item_directory`, and made clickable like a text channel.
Selecting one therefore drives the ordinary chat surface for a channel that has no messages
and no directory UI — the user lands on a blank/incorrect chat instead of the hub directory.

### D4. Advertised-but-unconsumed deep links (`nux`, `directories`, `search` and others)
`widgets/tabs/WidgetTabsHost.kt:173-191`

`consumeOwnedRoute` handles only `DeepLinkRoute.User`, `Home`, `Friends` and `Settings`;
everything else hits `else -> return` **without** calling
`StoreNavigation.consumePendingIntent`, so the pending intent stays queued indefinitely. The
target manifest nevertheless advertises and accepts these routes, and `DeepLinkParser` parses
them successfully:
- **`/template/…` and `https://discord.new/<code>`** → `DeepLinkRoute.GuildTemplate` (needs `nux/WidgetGuildTemplates`) — parsed, unit-tested (`DeepLinkParserTest.kt:28`), never handled.
- **`/discovery`** → `DeepLinkRoute.Discovery` (needs directory/discovery UI) — parsed, never handled.
- **`/query/…`** → `DeepLinkRoute.Query` (the App Actions fulfillment target for
  `SELECT_SERVER`/`SELECT_CHANNEL`/`SELECT_USER`/`CREATE_MESSAGE`; needs message/global search) — parsed, never handled.

The app opens (stealing the link from the browser) and then shows the last-used tab.

### D5. Latent, NOT live (documented so they are not double-counted)
- **`contact_sync`**: `WidgetFriendsList.kt:39` hides `R.id.menu_friends_contact_sync` when `onContactSync == null`, and it is never assigned → menu item invisible. Safe today.
- **`guildcommunicationdisabled`**: `UserProfileAdminView` "Time out" is wired to `AdminAction.TIMEOUT`, but `WidgetUserSheet.onAdminAction` is never assigned **and** `State.Loaded.admin` is never populated, so `showAdmin()` keeps the whole admin card `GONE`. Becomes a live dead-end the moment the admin state producer lands.
- **`phone`**: `PhoneOrEmailInputView.onCountryCodeClick` defaults to `null` and no caller passes one → the country-code row stays hidden; the code is pinned to `PhoneCountryCode.DEFAULT_COUNTRY_CODE` ("+1").
- **`media`**: chat attachment cards have no click listener, so there is no path to a viewer — a missing capability rather than a dead-end.
