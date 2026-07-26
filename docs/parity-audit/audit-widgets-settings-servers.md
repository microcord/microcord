# Parity audit — widgets/settings, widgets/servers, widgets/forums, widgets/mobile_reports

TARGET  `C:\Users\manti\Desktop\projeler\manticordrebuild` (Kotlin reconstruction)
REFERENCE `C:\Users\manti\StudioProjects\discord-jadx` (JADX decompile, Discord Android 126.21)
Scope: 23 target files vs 200 reference non-synthetic classes. Read-only audit.

All target paths below are relative to
`app/src/main/java/com/discord/` unless stated.

---

## 1. Reference inventory (non-synthetic classes, `$`-files excluded)

### `widgets/settings/**` — 69

| subpackage | n | classes |
|---|---|---|
| `settings/` (root) | 16 | MuteSettingsSheetViewModel, SettingsViewModel, WidgetMuteSettingsSheet, WidgetSettings, WidgetSettingsAccessibility, WidgetSettingsActivityStatus, WidgetSettingsAppearance, WidgetSettingsAuthorizedApps, WidgetSettingsBehavior, WidgetSettingsLanguage, WidgetSettingsLanguageSelect, WidgetSettingsMedia, WidgetSettingsNotifications, WidgetSettingsNotificationsOs, WidgetSettingsPrivacy, WidgetSettingsVoice |
| `settings/account` | 12 | WidgetDisableDeleteAccountDialog, WidgetEnableSMSBackupDialog, WidgetSettingsAccount, WidgetSettingsAccountBackupCodes, WidgetSettingsAccountChangePassword, WidgetSettingsAccountContactsNameEdit, WidgetSettingsAccountEmailEdit, WidgetSettingsAccountEmailEditConfirm, WidgetSettingsAccountUsernameEdit, WidgetSettingsBlockedUsers, WidgetSettingsBlockedUsersAdapter, WidgetSettingsBlockedUsersViewModel |
| `settings/account/mfa` | 6 | WidgetEnableMFADownload, WidgetEnableMFAInput, WidgetEnableMFAKey, WidgetEnableMFASteps, WidgetEnableMFASuccess, WidgetEnableMFAViewModel |
| `settings/billing` | 4 | PaymentSourceAdapter, PaymentSourceView, SettingsBillingViewModel, WidgetPaymentSourceEditDialog |
| `settings/connections` | 5 | ConnectionsGuildIntegrationView, WidgetSettingsUserConnections, WidgetSettingsUserConnectionsAdd, WidgetSettingsUserConnectionsAddXbox, WidgetSettingsUserConnectionsViewModel |
| `settings/developer` | 2 | ExperimentOverridesAdapter, WidgetSettingsDeveloper |
| `settings/guildboost` | 4 | SettingsGuildBoostSampleGuildAdapter, SettingsGuildBoostViewModel, WidgetSettingsGuildBoost, WidgetSettingsGuildBoostSubscriptionAdapter |
| `settings/premium` | 14 | ChoosePlanViewModel, ClaimOutboundPromoViewModel, ClaimStatus, OutboundPromosPreviewFeatureFlag, SettingsGiftingViewModel, SettingsPremiumViewModel, WidgetChoosePlan, WidgetChoosePlanAdapter, WidgetClaimOutboundPromo, WidgetOutboundPromoTerms, WidgetSettingsGifting, WidgetSettingsGiftingAdapter, WidgetSettingsGiftingOutboundPromosAdapter, WidgetSettingsPremium |
| `settings/profile` | 6 | EditProfileBannerSheetViewModel, EditUserOrGuildMemberProfileViewModelKt, SettingsUserProfileViewModel, TouchInterceptingCoordinatorLayout, WidgetEditProfileBannerSheet, WidgetEditUserOrGuildMemberProfile |

### `widgets/servers/**` — 108

| subpackage | n | classes |
|---|---|---|
| `servers/` (root) | 28 | NotificationHighlightsSettingsView, NotificationMuteSettingsView, NotificationsOverridesAdapter, SettingsChannelListAdapter, WidgetServerDeleteDialog, WidgetServerNotifications, WidgetServerNotificationsOverrideSelector, WidgetServerSettings, …Bans, …Channels, …ChannelsFabMenuFragment, …ChannelsSortActions, …EditIntegration, …EditMember, …EditMemberRolesAdapter, …EditRole, …Emojis, …EmojisEdit, …InstantInvitesActions, …Integrations, …IntegrationsListItem, …Moderation, …Overview, …Roles, …RolesAdapter, …Security, …TransferOwnership, …VanityUrl |
| `servers/auditlog` | 4 | WidgetServerSettingsAuditLog, …Adapter, …Filter, …FilterAdapter |
| `servers/community` | 12 | CommunityGetStartedHeaderIconView, CommunityGetStartedInformationView, CommunitySelectorView, WidgetConfirmRemoveCommunityDialog, WidgetServerSettingsCommunityFirstStep/SecondStep/ThirdStep, WidgetServerSettingsCommunityOverview(+ViewModel), WidgetServerSettingsEnableCommunity(+Steps, +ViewModel, +ViewModelKt) |
| `servers/creator_monetization_eligibility` (+`/onboarding`) | 6 | CreatorMonetizationCountryAllowlist, EligibilityChecklistView; AcceptTermsStepCard, AnalyticsFormattedMonetizationRequirements, ServerSettingsCreatorMonetizationOnboardingViewModel, WidgetServerSettingsCreatorMonetizationOnboarding |
| `servers/guild_role_subscription` (+`/edit_tier`, `/model`, `/payments`, `/upsell`) | 23 | ServerSettingsEmptyGuildRoleSubscriptionsViewModel, ServerSettingsGuildRoleSubscriptionTierAdapter(+Item, +ListItemGeneratorKt, +ListViewModel), ServerSettingsGuildRoleSubscriptionViewModel, TierViewHolder, WidgetServerSettingsEmptyGuildRoleSubscriptions, …EnableMonetizationUnavailable, …GuildRoleSubscriptionTierList, …GuildRoleSubscriptions; edit_tier: GuildRoleSubscriptionConfirmationDialog, …EditTierAdapter, …EditTierViewModel, Widget…EditTier; model: CurrentMonthEarningMetrics, PayoutStatusMedia, TotalEarningMetrics, TotalPayoutsForPeriod; payments: Widget…Earnings; upsell: GuildRoleSubscriptionOnboardingUpsellManager, Widget…UpsellBottomSheet |
| `servers/guildboost` | 8 | GuildBoostInProgressViewModel, GuildBoostPerkView, GuildBoostPerkViewAdapter, GuildBoostTransferInProgressViewModel, GuildBoostViewModel, WidgetGuildBoost, WidgetGuildBoostConfirmation, WidgetGuildBoostTransfer |
| `servers/member_verification` (+`/form_fields`) | 17 | MemberVerificationPendingDialog(+ViewModel), MemberVerificationRulesAdapter, MemberVerificationSuccessDialog(+ViewModel), MemberVerificationView, WidgetMemberVerification(+ViewModel, +ViewModelKt); form_fields: MemberVerificationItem, …ApproveTerms, …Header, …MultipleChoice, …Paragraph, …Term, …TermsHeader, …TextInput |
| `servers/role_members` | 5 | ServerSettingsRoleMemberAdapterItem, …MembersAdapter, …MembersViewHolder, …MembersViewModel, WidgetServerSettingsRoleMembers |
| `servers/settings/invites`, `servers/settings/members` | 5 | WidgetServerSettingsInstantInvites(+ListItem); WidgetServerSettingsMembers(+Adapter, +Model) |

### `widgets/forums/**` — 16
ForumBrowserItem, ForumBrowserLayoutManager, ForumBrowserPostView, ForumBrowserScrollListener, ForumGuidelinesManager, ForumPostCreateManager, ForumPostReadState, ForumPostReadStateManager, ForumPostTagView, ForumUtils, PostData, PostTagData, WidgetForumBrowser, WidgetForumBrowserAdapter, WidgetForumBrowserViewModel, WidgetForumGuidelinesBottomSheet

### `widgets/mobile_reports/**` — 7
MobileReportArgs, MobileReportsBottomButton, MobileReportsBreadcrumbs, MobileReportsViewModel, ReportsFeatureFlag, ReportsMenuNode, WidgetMobileReports

---

## 2. Per-file findings (every target file read in full)

### 2.1 `widgets/settings/WidgetSettings.kt` (167 lines) — **PARTIAL** — reachable now
Host: `widgets/tabs/WidgetTabsHost.kt:108-116` wires `onSetStatus`, `onBlockedUsers`, `onLogout`.
Reference: `widgets/settings/WidgetSettings.java` (643 lines) + `SettingsViewModel.java` (600 lines).

Concrete gaps:
1. **`onBlockedUsers` is dead code — BLOCKER.** Declared at `WidgetSettings.kt:151`, assigned at `WidgetTabsHost.kt:109-114`, but never invoked anywhere in `WidgetSettings.kt`. The `blocked_users` row (`res/layout/widget_settings.xml:22`) is additionally force-hidden by `hideUnavailableRows` (`WidgetSettings.kt:114-125`, keep-set at `:143-147` excludes `R.id.blocked_users`). Net effect: `WidgetSettingsBlockedUsers` — which *is* fully state-wired at `stores/StoreStream.kt:787-804` — has **no reachable entry point in the shipping app**. A complete feature is orphaned by a one-line omission.
2. **`hideUnavailableRows` (`:114-125`) is a placeholder mechanism with no reference counterpart.** Reference shows all rows and gates only `developer_options*` (staff, `WidgetSettings.java:107-120`), `nitro_settings_container` (verified, `:131-133`), `role_subscriptions` (`:145-147`), `nitro_gifting_badge` (promo count, `:148-164`), `notification_upsell` (`:165-167`). Target hides 20+ rows unconditionally: account, profile, privacy, authorized_apps, connections, qr_scanner, nitro/boost/gifting/role_subscriptions, voice, notifications, appearance, language, changelog, support, upload_debug_logs, acknowledgements, developer_options. **MAJOR** (mostly future-phase, but see #1 and #3).
3. **No `SettingsViewModel` equivalent.** Target `State` (`:26`) carries only `user` + `presence`. Missing: `isStaff`, `hasSubscription`, `promoCount`, `showRoleSubscriptionsButton`, `pushNotificationUpsellDismissed`, `refreshSubscriptions()` on tab select. MAJOR (phase 10).
4. **Notification upsell banner inert.** `res/layout/widget_settings.xml:11-14` contains `notification_upsell` + `notification_upsell_enable` + `notification_upsell_dismiss`; nothing in `WidgetSettings.kt` binds them (reference: `WidgetSettings.java:611-626`). They are hidden by #2 so no visible bug today, but the views are dead. MINOR.
5. **App-info header string.** Reference sets `"$app_information - 126.21 - Stable (126021)"` (`WidgetSettings.java:263-266`); target never touches `R.id.app_info_header`. MINOR.
6. **Menu divergence.** Target inflates `R.menu.menu_settings_logout` with only log-out (`:51`); reference `R.menu.menu_settings` also has `menu_settings_debugging` → `WidgetDebugging.launch` (`WidgetSettings.java:179-183`). MINOR.
7. **Logout dialog uses `MaterialAlertDialogBuilder` (`:54-62`) instead of `WidgetNoticeDialog` with `notice_theme_positive_red`** (`WidgetSettings.java:228`). Behavior equivalent; styling divergent. MINOR.
8. Header accessibility: target marks only `user_settings_header` (`:35`); reference marks 5 headers (`WidgetSettings.java:627-631`). MINOR.
9. `UserProfileHeaderView` is fed a hand-built `ViewState` with hardcoded `isMeUserPremium = false`, `userProfile = UserProfile()`, `guildMembersForAka = emptyList()`, `reducedMotionEnabled = false` (`:90-108`); reference binds the real `UserProfileHeaderViewModel.observeViewState()` (`WidgetSettings.java:637-640`) and wires `setOnBadgeClick` / `setOnBannerPress` (`:257-262`). Badge taps and banner taps are inert. MAJOR.

### 2.2 `widgets/settings/WidgetSettingsApp.kt` (153 lines) — **PARTIAL** — reachable now
Contains 4 fragments; maps to 4 reference files.

**`WidgetSettingsMedia` (`:27-45`)** vs `WidgetSettingsMedia.java`:
- All 6 toggles present and persisted through `StoreStream.getUserSettings()`. **REAL core.**
- Missing `inline_attachment_media_help` subtext with `PREVIEW_MAX_SIZE_MB` (ref `:99`). MINOR.
- Missing the Nitro upsell `LinkifiedTextView` (`image_compression_nitro_upsell`, `getNitro` link, ref `:127`). MINOR.
- Reference passes `appActivity` to `setIsAttachmentMediaInline` / `setIsEmbedMediaInlined` / `setIsRenderEmbedsEnabled` / `setIsSyncTextAndImagesEnabled` (used for the "restart required"/toast path); target calls the 1-arg overloads (`:33,37,39,41`). MINOR.

**`WidgetSettingsBehavior` (`:47-58`)** vs `WidgetSettingsBehavior.java`:
- 3 toggles present and persisted. **REAL core.**
- Missing developer-mode subtext (`developer_mode_help_text` + `API_LINK`) and `setSubtextOnClickListener` that opens the API docs (ref `:91-104`). MINOR.

**`WidgetSettingsAccessibility` (`:61-129`)** vs `WidgetSettingsAccessibility.java`:
- **Reduced-motion override bug — MAJOR.** `bindSticker` (`:99-105`) calls `render(false, …)` with `reduced` hardcoded to `false`. Tapping any sticker-animation radio while reduced motion is ON re-enables and re-checks the emoji/GIF/sticker rows, contradicting the state that `onStart` (`:82-90`) just rendered. State is only corrected on the next reduced-motion emission, which the tap does not produce.
- Missing the disabled-explanation strings the reference sets when reduced motion overrides a row: `accessibility_reduced_motion_settings_override` on the emoji and GIF rows (ref `:234`, `:259`) and `stickers_auto_play_help_disabled` on the sticker rows (ref `:239`). Target only greys them via `isEnabled=false` (`:111,115,124`) with no explanation. MAJOR (user cannot tell why the row is dead).
- Missing `accessibility_prefers_reduced_motion_description` linkified help text (ref `:330`). MINOR.
- Sticker radios are wired with `setOnClickListener` on the `CheckedSetting` root (`:100`) rather than `setContainerClickListener`; reference uses `CheckedSetting.e(...)` = container click (ref `:215`). The root `CheckedSetting` does have a click target, but the container child in `views/CheckedSetting.kt:216` is the one styled for touch. MINOR.

**`WidgetSettingsActivityStatus` (`:131-152`)** vs `WidgetSettingsActivityStatus.java`: **REAL.** Observes `observeIsShowCurrentGameEnabled()`, binds, persists. Only gap: no `setActionBarSubtitle(R.string.user_settings)` (ref `:80`) — applies to all four fragments (`bindToolbar`, `:14-19`, sets title only). MINOR.

### 2.3 `widgets/settings/WidgetSettingsNotifications.kt` (61 lines) — **INERT-PLACEHOLDER**
- `stateSource` (`:58`) and `onSettingsChanged` (`:59`) are `@JvmField var … = null` and **are never assigned anywhere in the repo** (verified: `grep -rn "stateSource *=\|onSettingsChanged *=" app/src` returns only `WidgetChatOverlay`, `WidgetFriendsList`, `WidgetSettingsBlockedUsers`, `WidgetUserSheet`, `WidgetUserMutualGuilds`, `WidgetUserMutualFriends`, `WidgetUserStatusSheet`).
- Consequence: `onStart` (`:27`) falls back to `Observable.just(NotificationSettingsV2())` — a default-constructed object, not the real store — and every toggle calls `onSettingsChanged?.invoke(...)` (`:52`) on a null hook. **All six switches are no-ops that also never reflect real state.**
- Reference `WidgetSettingsNotifications.java:72-112` writes each toggle straight into `StoreNotifications` (`setEnabled`, `setEnabledInApp`, `setNotificationLightDisabled`, `setNotificationsVibrateDisabled`, `setNotificationSoundDisabled`) and renders from `StoreStream.getNotifications().getSettings()` (`:118`).
- `stores/StoreNotifications.kt:25-42` in the target **already exposes** `getSettings()`, `observeSettings()`, `setEnabled`, `setEnabledInApp`, `setWake`, `setNotificationLightDisabled`, `setNotificationSoundDisabled`, `setNotificationsVibrateDisabled`. The wiring is simply absent.
- The `settings_notifications_wake` row (`:44`) has no reference counterpart in the 126.21 `onViewBound` (reference never binds a wake toggle) — extra, harmless.
- **Severity: MAJOR** — the class is not reachable today (the `notifications` row at `res/layout/widget_settings.xml:44` is hidden by `hideUnavailableRows`, and no code references the class), so it is not a live regression; but it is a placeholder, not parity.

### 2.4 `widgets/settings/WidgetSettingsNotificationsOs.kt` (80 lines) — **INERT-PLACEHOLDER**
- Same null-hook problem: `stateSource` / `onSettingsChanged` / `onOpenSystemSettings` (`:73-76`) never assigned. Switches at `:55-68` do nothing; state comes from `Observable.just(NotificationSettingsV2())` (`:42`).
- Class is never referenced (no `show(...)` call site anywhere).
- Reference `WidgetSettingsNotificationsOs.java:82-108`: writes to `StoreStream.getNotifications()`, opens `SystemSettingNavigators.openSystemNotification(context)` then dismisses.
- Additions over reference: the `POST_NOTIFICATIONS` runtime-permission flow (`:23-26`, `:59-61`) and `latest` caching. Reference is `@RequiresApi(26)` with no permission request (targets pre-Android-13). Not a parity gap, but a divergence.
- Severity: **MAJOR** (unreachable placeholder).

### 2.5 `widgets/settings/OptimisticMutation.kt` (38 lines) — **REAL (target-invented)**
No reference counterpart — the reference has no optimistic-update helper in these packages (it writes through `StoreUserGuildSettings` / `StoreNotifications` and lets the store's own optimistic apply + revert handle it). The implementation is coherent (revision guard at `:20`/`:25`, rollback at `:26-28`, exception path at `:30-35`) and is exercised by `app/src/test/java/com/discord/widgets/settings/OptimisticMutationTest.kt`. Consumers: `WidgetServerNotifications.kt`, `widgets/channels/settings/WidgetChannelNotificationSettings.kt`.

### 2.6 `widgets/settings/account/WidgetSettingsBlockedUsers.kt` (92 lines) — **REAL (but unreachable)**
- vs reference `WidgetSettingsBlockedUsers.java` (149) + `WidgetSettingsBlockedUsersAdapter.java` + `WidgetSettingsBlockedUsersViewModel.java`. Target folds all three into one file: `ListAdapter` + `DiffUtil` + stable ids (`:62-83`), loading/empty/loaded flipper (`:50-60`), avatar + name + unblock button binding (`:68-75`).
- State source **is** wired (`stores/StoreStream.kt:787-802`) and `onUnblock` → `removeRelationship` (`:804`), `onProfile` → user sheet (`WidgetTabsHost.kt:116`).
- **Unreachable**: see 2.1 #1. Nothing navigates here. **BLOCKER** (a finished feature is dark).
- Minor: no unblock confirmation / error toast; reference's ViewModel surfaces a rest error. MINOR.

### 2.7 `widgets/settings/billing/PaymentSourceView.kt` (89 lines) — **REAL (orphan view)**
- Matches reference `PaymentSourceView.java` behavior: icon, invalid badge, premium badge, PayPal vs card branches.
- Divergence: reference delegates the icon to `PremiumUtilsKt.getPaymentSourceIcon`; target inlines the mapping in a companion (`:78-86`). Functionally equivalent, wrong home (a shared util is now private to the view). MINOR.
- Divergence: reference uses `TimeUtils.getLocalizedMonthName(month, context)`; target uses `DateFormatSymbols(locale).months[…]` (`:53`). Equivalent output, but `expiresMonth - 1` will throw `ArrayIndexOutOfBoundsException` on a malformed `expiresMonth == 0`; reference's helper does not index raw. MINOR.
- Only consumers are layouts (`payment_method_list_item.xml`, `widget_payment_source_edit_dialog.xml`) — no Fragment inflates them. `PaymentSourceAdapter`, `SettingsBillingViewModel`, `WidgetPaymentSourceEditDialog` all MISSING. Expected (phase 10).

### 2.8 `widgets/settings/profile/TouchInterceptingCoordinatorLayout.kt` (18 lines) — **REAL**
Byte-for-byte behavioral match with reference (`:28-39`). Used only by `widget_settings_user_profile.xml`, whose host `WidgetEditUserOrGuildMemberProfile` is MISSING. Expected (phase 10).

### 2.9 `widgets/servers/WidgetServerNotifications.kt` (103 lines) — **INERT-PLACEHOLDER**
Reference: `WidgetServerNotifications.java` (587 lines).

1. **Never renders — BLOCKER-class defect (mitigated only by being unreachable).** `onStart` (`:41`) is `subscription = stateSource?.invoke(...)?...` — `stateSource` (`:95`) is never assigned anywhere, so the whole chain short-circuits to `null`, `optimistic` is never created, and `render` (`:49`) is never called. The screen displays raw layout defaults. `onMutation` (`:96`), `onAddOverride` (`:97`), `onError` (`:98`) are likewise unassigned, so `update()` (`:80-84`) silently completes with `Result.success(Unit)` and persists nothing.
2. **No navigation to it.** `grep -rn WidgetServerNotifications app/src --include=*.kt` outside the file itself: zero hits. Not a live regression; a placeholder.
3. **Hardcoded English strings — MAJOR.** `:55` builds `"Mute ${state.guildName}"` / `"Unmute ${state.guildName}"` as Kotlin string literals. Reference formats `R.string.form_label_mute_server` / `form_label_unmute_server` with the guild name (`WidgetServerNotifications.java:277-278`). Untranslatable.
4. **Mute has no duration picker — MAJOR.** Target's mute callback is `update(state.copy(muted = true))` (`:58`). Reference opens `WidgetMuteSettingsSheet.showForGuild(guildId, parentFragmentManager)` (`WidgetServerNotifications$configureUI$onMute$1.java:22-24`) for the 15-min/1-hr/8-hr/24-hr/until-I-turn-it-back-on choice; unmute goes through `StoreUserGuildSettings.setGuildMuted(context, guildId, false)`. `WidgetMuteSettingsSheet` and `MuteSettingsSheetViewModel` are both MISSING.
5. **Highlights section never bound — MAJOR.** `res/layout/widget_server_notifications.xml:17` declares `server_notifications_highlights` (a `NotificationHighlightsSettingsView`) with `android:visibility="gone"` hardcoded, plus `server_notifications_highlights_divider`. `WidgetServerNotifications.kt` never touches either id. Reference gates on `model.getHighlightsEnabled()` and calls `updateView(viewState, …)` with `isAboveHighlightsSize` (`WidgetServerNotifications.java` ~`:288-294`). **`NotificationHighlightsSettingsView.kt` therefore has zero live callers in the entire repo.**
6. **Channel-override list absent — MAJOR.** Layout has `guild_notifications_override_list`, `server_notifications_override_title`, `server_notifications_override_divider`; none are bound. Reference attaches `NotificationsOverridesAdapter` + a swipe-to-delete `ItemTouchHelper` (`createSwipeableItemTouchHelper`, `:377-395`) and routes `add_override` to `WidgetServerNotificationsOverrideSelector`. Both classes MISSING; target's `add_override` handler (`:36`) calls a null hook.
7. **No hub handling.** Reference `configureForHub()` (`:180-220`) hides frequency wrap/divider, everyone switch, roles switch+divider, push switch, the whole override block, and highlights for hub guilds. Absent. MAJOR.
8. **No large-guild gating.** Reference `configureRadio` (`:222-247`) disables the "All messages" radio with the `large_guild_notify_all_messages_description` tooltip when `isAboveNotifyAllSize`. Target's `bind` (`:73-78`) has no such gate.
9. **Radios wired via `setOnCheckedListener` (`:60-62`)** rather than a container click listener; reference uses `CheckedSetting.e(...)`. With `views/CheckedSetting.kt:72` (`RADIO` → `isChecked = true`) re-tapping the already-selected radio produces no event and the other two radios are never programmatically cleared except by a fresh `render`. Because `render` never runs (#1), the radio group is visually unmanaged. MAJOR.
10. Push switch (`:65-70`): `setDisabledTooltip(null)` when muted (`:68`) shows an empty toast and dims to 0.3α (`views/CheckedSetting.kt:207-212`); reference passes a real message. MINOR.
11. `State` (`:17-26`) omits `guildProfile`, `approximateMemberCount`, `highlightsEnabled`, `isAboveHighlightsSize`, `isAboveNotifyAllSize`, `notifyHighlights` — the fields items 5–8 need.

### 2.10 `widgets/servers/NotificationMuteSettingsView.kt` (100 lines) — **REAL**
Faithful to `NotificationMuteSettingsView.java:226-273`: mute/unmute label swap, click routing, three status branches (not-muted → `""`, muted-no-end-time → raw status, muted-with-end-time → formatted), status visibility, description visibility. Divergences, both MINOR:
- Date formatting is re-implemented with `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", UTC)` + `DateFormat.getDateTimeInstance(MEDIUM, SHORT)` (`:70-76`) instead of `TimeUtils.renderUtcDateTime(value, context, dateFormat=2, timeFormat=3)`. `parse` failure silently falls back to *now* (`:75`) rather than propagating.
- The `$[**…**](endTimeHook)` markdown is stripped by literal `String.replace` (`:79-82`) instead of the markdown renderer; the bold+link-color spans are then re-applied manually (`:90-98`). Output matches for the known string, brittle if the string changes.
Live caller: `widgets/channels/settings/WidgetChannelNotificationSettings.kt:55` (other slice) and `WidgetServerNotifications.kt:52`.

### 2.11 `widgets/servers/NotificationHighlightsSettingsView.kt` (66 lines) — **REAL but DEAD**
Behavior matches `NotificationHighlightsSettingsView.java:146-187`: beta tag, checked state, muted → disabled tooltip, learn-more link, warning card gating.
- **No caller** (see 2.9 #5). BLOCKER for the highlights feature, MAJOR for this file.
- `HIGHLIGHTS_DISABLED = 1` / `HIGHLIGHTS_ENABLED = 2` are re-declared privately (`:62-63`) while `models/domain/ModelNotificationSettings.kt:132-133` already defines them. Duplication; reference uses `ModelNotificationSettings.HIGHLIGHTS_*`. MINOR.
- Target does **not** call `toggle()` before invoking `onToggled` (`:44-49`); reference toggles the switch optimistically first (`:166`). Because there is no caller, no visible effect today; once wired, the switch will not move until the state round-trips. MINOR.
- The learn-more URL is a hardcoded literal (`:64`); reference resolves article `5304469213079` through the help-center helper. MINOR.
- Note on the reference: `NotificationHighlightsSettingsView.java:187` decompiles to `(… ) ? false : false ? 0 : 8`, i.e. JADX mangled the ternary. The target's condition (`serverTooLarge && !isMuted && notifyHighlights != DISABLED`, `:55-58`) is the only reading consistent with the operands; **not verifiable from this JADX output.**

### 2.12 `widgets/servers/community/CommunitySelectorView.kt` (47) — **REAL**
Matches `CommunitySelectorView.java:initAttrs` exactly (title/subtitle/description text, `csv_is_dropdown` hides subtitle + dropdown icon, `setSubtitle`). Orphan: only `widget_server_settings_community_overview.xml` / `…_setup_second_step.xml` reference it; hosts MISSING. Expected (phase 12).

### 2.13 `widgets/servers/community/CommunityGetStartedInformationView.kt` (84) — **REAL**
Matches reference plus an explicit bold/link markdown renderer (`:57-74`) standing in for the reference's `b.d(...)`. Orphan (host `WidgetServerSettingsEnableCommunity` MISSING). Expected.

### 2.14 `widgets/servers/community/CommunityGetStartedHeaderIconView.kt` (37) — **REAL**
Matches reference. Orphan. Expected.

### 2.15 `widgets/servers/creator_monetization_eligibility/EligibilityChecklistView.kt` (54) — **REAL**
Matches `EligibilityChecklistView.java:136-181`: early-return on unchanged list, `removeAllViews`, 1dp themed dividers with 16dp side margins between rows, `isActivated` indicator, title/description.
- Comment at `:41` correctly documents that the reference never writes `lastRequirements` after construction (ref `:204` sets it once to `emptyList()`), so the early-return at `:31` is dead in both. Faithful bug-for-bug.
- Missing the `isInEditMode()` preview seeding (ref `:188-197`). MINOR (IDE-preview only).
- Row `description` is read as `TextView` (`:38`); reference binds a `LinkifiedTextView`. If the target's `view_eligibility_checklist_row.xml` uses a plain `TextView`, links in requirement descriptions will not be clickable. MINOR.
Orphan (host `WidgetServerSettingsCreatorMonetizationOnboarding` MISSING). Expected.

### 2.16 `widgets/servers/creator_monetization_eligibility/onboarding/AcceptTermsStepCard.kt` (105) — **REAL**
Matches `AcceptTermsStepCard.java`: required-attr throwing reads (`:68-76` ≈ `TypedArrayKt.getIntOrThrow`/`getResourceIdOrThrow`), `onFinishInflate` capture of custom children before inflating the card, and the constraint re-parenting chain (`topToBottom` = previous, `startToEnd` = image padding, `endToEnd` = parent). Own markdown-link parser (`:79-102`) replaces `b.m(...)`. Orphan. Expected.

### 2.17 `widgets/servers/member_verification/MemberVerificationView.kt` (44) — **REAL**
Matches `MemberVerificationView.java:configure` exactly (icon, title, button click, verified-check vs button visibility swap). Orphan — `WidgetMemberVerification`, its ViewModel, `MemberVerificationRulesAdapter`, the 8 `form_fields` items, and both pending/success dialogs are MISSING. Expected (phase 12).

### 2.18 `widgets/forums/ForumBrowserPostView.kt` (249) — **PARTIAL**
vs `ForumBrowserPostView.java` (417).
1. **First-message preview is not parsed — MAJOR.** `:110` sets `description.text = message.content` raw. Reference builds a `MessageRenderContext` (nick/username map, channel names, guild roles, spoiler bg) and runs `DiscordParser.parseChannelMessage(context, content, ctx, MessagePreprocessor, ParserOptions.FORUM_POST_FIRST_MESSAGE, isEdited)` into a `DraweeSpanStringBuilder`. Mentions, custom emoji, markdown, spoilers and the edited marker are all lost in the target.
2. **Wrong placeholder strings.** `:106-107` use `R.string.sticker` / `R.string.embed_links`; reference uses `reply_quote_sticker_mobile` / `reply_quote_no_text_content_mobile`. MINOR.
3. **Blank-content branch missing.** Reference only parses when `!content.isBlank()`; target always assigns. MINOR.
4. **Read-state colors diverge.** `:196-201`: read posts use `colorInteractiveNormal` for both title and description; reference uses `R.attr.primary_300` for both (`configureReadState`). Unread path matches. MINOR.
5. **Attachment preview re-implemented — MINOR/MAJOR.** `:126-148` hand-builds `"${proxyUrl}?width=&height="` plus a `webp`/`png` API-28/29 fork and, for `MessageAttachmentType.FILE`, substitutes a file-type icon (`fileDrawable`, `:150-166`). Reference uses `EmbedResourceUtils.createRenderableEmbedMediaFromAttachment` + `getPreviewUrls(…, 64dp, 64dp, false)` + `MGImages.setImage`, and **hides** the preview entirely when the attachment yields no renderable media. The file-icon behavior is an invention not present in 126.21.
6. **Unread-count string wrong — MINOR.** `:185` uses `R.string.forum_post_message_count_short`; `ForumUtils.java:191` uses `forum_post_message_count_short_parenthetical`.
7. Pinned uses `channel.flags and FLAG_PINNED` (`ForumPostModels.kt:32`) vs reference `ChannelUtils.A(channel)`; likely equivalent, not verified.
8. Timestamp/message-count/reaction/typing/read-state logic otherwise matches, including the `(avatarSize - overlap) * min(n,3) - 8dp` typing-dots margin (`:209`) and the `coerceAtLeast(0)` guard the reference lacks.
Orphan: `forum_browser_post_item.xml` has no host — `WidgetForumBrowser`, `WidgetForumBrowserAdapter`, `WidgetForumBrowserViewModel` MISSING.

### 2.19 `widgets/forums/ForumPostModels.kt` (67) — **PARTIAL**
Covers reference `PostData`, `PostTagData`, `ForumPostReadState` faithfully (`isActivePost`/`isPinned`/`isMe` derived fields at `:31-33`; `PostTagData.fromTag` at `:43`). Also inlines 2 of `ForumUtils`' ~15 members (`messageCountText` `:61`, `unreadCountText` `:63`). Missing from `ForumUtils`: `canAccessForumChannels`, `canAccessRedesignedForumChannels`, `observeCanCreate/AccessForumChannels`, `observeSelectedPostChannel`, `maybeLoadAndObserveThreadListing`, `getPlaceholderMessageReaction`, `getPlaceholderReactionName`, `getMessageReactionComparator`, `mostCommonReaction`, `isDefaultPostReaction`, `getActiveAgoTimestamp` (re-implemented privately in the view instead). Expected (forums phase not started).

### 2.20 `widgets/forums/ForumPostTagView.kt` (40) — **REAL**
Matches reference (8dp/2dp padding, themed `theme_forum_post_tag_background`, 12dp emoji render, tag name). Uses `renderReactionEmoji` in place of `EmojiNode.Companion.renderEmoji`. Constants are instance-level `dp()` calls rather than static `DimenUtils` values — cosmetic.

### 2.21 `widgets/mobile_reports/MobileReportsBottomButton.kt` (74) — **REAL** (one color bug)
Matches `MobileReportsBottomButton.java:setup`: listener reset, Submit/Cancel/Next/Done visibility algebra, loading + disabled + error-text states, per-variant text and click routing.
- **Wrong submit-button color.** `:58` uses `R.color.brand_500`; reference uses `R.color.status_red` (`MobileReportsBottomButton.java`, Submit branch). The destructive-report CTA renders blurple instead of red. MINOR (visual), concrete.
- Submit description uses `Html.fromHtml` after a regex markdown→anchor conversion (`:54-56`) instead of the markdown renderer. Equivalent for the known string. MINOR.
Orphan: `view_reports_menu_node.xml` has no host — `WidgetMobileReports`, `ReportsMenuNode`, `MobileReportArgs`, `ReportsFeatureFlag` MISSING.

### 2.22 `widgets/mobile_reports/MobileReportsBreadcrumbs.kt` (39) — **REAL**
Matches the reference `setup` semantics: keep child 0, drop the rest via `removeViewsInLayout(1, n-1)`; per `NodeResult`, emit `[joinToString(", ") of element-result labels (null when empty), child title]`; filter nulls/empties; inflate one item per surviving title. Reference JADX output for `setup` is a raw-bytecode dump (`MobileReportsBreadcrumbs.java:60-178`, "Method not decompiled") but the register flow is unambiguous and the target matches it.

### 2.23 `widgets/mobile_reports/MobileReportsViewModel.kt` (9) — **INERT-PLACEHOLDER**
A 9-line shell exposing only `SubmitState.{Error,Loading,None}` so `MobileReportsBottomButton` compiles. Reference is **1961 lines**: report-menu node graph traversal, breadcrumb history, element selection state, submit REST call, error handling, analytics. Expected (mobile reports phase not started) — but it must not be counted as coverage.

---

## 3. Coverage table — reference class → target class

Legend: ✅ real / 🟡 partial / ⛔ inert placeholder / ❌ MISSING.

### `widgets/settings/**` — 10 of 69 have any counterpart

| reference | target |
|---|---|
| settings/WidgetSettings | 🟡 `settings/WidgetSettings.kt` |
| settings/SettingsViewModel | ❌ (state folded into `WidgetSettings.State`, 2 of ~7 fields) |
| settings/WidgetSettingsMedia | 🟡 `settings/WidgetSettingsApp.kt:27` |
| settings/WidgetSettingsBehavior | 🟡 `settings/WidgetSettingsApp.kt:47` |
| settings/WidgetSettingsAccessibility | 🟡 `settings/WidgetSettingsApp.kt:61` |
| settings/WidgetSettingsActivityStatus | ✅ `settings/WidgetSettingsApp.kt:131` |
| settings/WidgetSettingsNotifications | ⛔ `settings/WidgetSettingsNotifications.kt` |
| settings/WidgetSettingsNotificationsOs | ⛔ `settings/WidgetSettingsNotificationsOs.kt` |
| settings/account/WidgetSettingsBlockedUsers (+Adapter, +ViewModel) | ✅ `settings/account/WidgetSettingsBlockedUsers.kt` (all three folded in) |
| settings/billing/PaymentSourceView | ✅ `settings/billing/PaymentSourceView.kt` |
| settings/profile/TouchInterceptingCoordinatorLayout | ✅ `settings/profile/TouchInterceptingCoordinatorLayout.kt` |
| — | `settings/OptimisticMutation.kt` (no reference counterpart) |

**MISSING (59), by cluster**
- **Mute-settings sheet (2)** — MuteSettingsSheetViewModel, WidgetMuteSettingsSheet. *Blocks server/channel mute-duration UX; see 2.9 #4.*
- **App settings screens (5)** — WidgetSettingsAppearance, WidgetSettingsLanguage, WidgetSettingsLanguageSelect, WidgetSettingsPrivacy, WidgetSettingsVoice.
- **Authorized apps (1)** — WidgetSettingsAuthorizedApps.
- **Account management (11)** — WidgetSettingsAccount, …AccountChangePassword, …AccountEmailEdit, …AccountEmailEditConfirm, …AccountUsernameEdit, …AccountContactsNameEdit, …AccountBackupCodes, WidgetDisableDeleteAccountDialog, WidgetEnableSMSBackupDialog (+ the Adapter/ViewModel folded above are covered).
- **MFA enrollment (6)** — WidgetEnableMFADownload/Input/Key/Steps/Success/ViewModel.
- **Billing (3)** — PaymentSourceAdapter, SettingsBillingViewModel, WidgetPaymentSourceEditDialog.
- **Connections (5)** — ConnectionsGuildIntegrationView, WidgetSettingsUserConnections(+Add, +AddXbox, +ViewModel).
- **Developer options (2)** — ExperimentOverridesAdapter, WidgetSettingsDeveloper.
- **Nitro / boost / gifting / premium (18)** — settings/guildboost ×4, settings/premium ×14.
- **Profile editing (5)** — EditProfileBannerSheetViewModel, EditUserOrGuildMemberProfileViewModelKt, SettingsUserProfileViewModel, WidgetEditProfileBannerSheet, WidgetEditUserOrGuildMemberProfile.

### `widgets/servers/**` — 9 of 108 have any counterpart

| reference | target |
|---|---|
| servers/WidgetServerNotifications | ⛔ `servers/WidgetServerNotifications.kt` |
| servers/NotificationMuteSettingsView | ✅ `servers/NotificationMuteSettingsView.kt` |
| servers/NotificationHighlightsSettingsView | ✅ (dead) `servers/NotificationHighlightsSettingsView.kt` |
| servers/community/CommunitySelectorView | ✅ `servers/community/CommunitySelectorView.kt` |
| servers/community/CommunityGetStartedInformationView | ✅ `…/CommunityGetStartedInformationView.kt` |
| servers/community/CommunityGetStartedHeaderIconView | ✅ `…/CommunityGetStartedHeaderIconView.kt` |
| servers/creator_monetization_eligibility/EligibilityChecklistView | ✅ `…/EligibilityChecklistView.kt` |
| servers/creator_monetization_eligibility/onboarding/AcceptTermsStepCard | ✅ `…/onboarding/AcceptTermsStepCard.kt` |
| servers/member_verification/MemberVerificationView | ✅ `…/MemberVerificationView.kt` |

**MISSING (99), by cluster**
- **Server-notification completion (3)** — NotificationsOverridesAdapter, WidgetServerNotificationsOverrideSelector, WidgetServerDeleteDialog. *Directly blocks 2.9 #6.*
- **Server settings shell + pages (24)** — WidgetServerSettings, …Overview, …Channels(+FabMenuFragment, +SortActions, SettingsChannelListAdapter), …Roles(+Adapter, …EditRole), …EditMember(+RolesAdapter), …Emojis(+Edit), …Bans, …Moderation, …Security, …VanityUrl, …TransferOwnership, …Integrations(+EditIntegration, +IntegrationsListItem), …InstantInvitesActions.
- **Audit log (4)** — WidgetServerSettingsAuditLog, …Adapter, …Filter, …FilterAdapter.
- **Community enablement (9)** — WidgetConfirmRemoveCommunityDialog, …CommunityFirstStep/SecondStep/ThirdStep, …CommunityOverview(+ViewModel), …EnableCommunity(+Steps, +ViewModel, +ViewModelKt).
- **Creator monetization onboarding (4)** — CreatorMonetizationCountryAllowlist, AnalyticsFormattedMonetizationRequirements, ServerSettingsCreatorMonetizationOnboardingViewModel, WidgetServerSettingsCreatorMonetizationOnboarding.
- **Guild role subscriptions (23)** — the whole `guild_role_subscription` tree incl. `edit_tier`, `model`, `payments`, `upsell`.
- **Guild boost (8)** — the whole `guildboost` tree.
- **Member verification flow (16)** — WidgetMemberVerification(+ViewModel, +ViewModelKt), MemberVerificationRulesAdapter, MemberVerificationPendingDialog(+ViewModel), MemberVerificationSuccessDialog(+ViewModel), all 8 `form_fields` items.
- **Role members (5)** — the whole `role_members` tree.
- **Invites & members lists (5)** — `settings/invites` ×2, `settings/members` ×3.

### `widgets/forums/**` — 5 of 16 have any counterpart

| reference | target |
|---|---|
| ForumBrowserPostView | 🟡 `forums/ForumBrowserPostView.kt` |
| ForumPostTagView | ✅ `forums/ForumPostTagView.kt` |
| PostData | ✅ `forums/ForumPostModels.kt:13` |
| PostTagData | ✅ `forums/ForumPostModels.kt:36` |
| ForumPostReadState | ✅ `forums/ForumPostModels.kt:50` |
| ForumUtils | 🟡 2 of ~15 members (`ForumPostModels.kt:61,63`) + `getActiveAgoTimestamp` re-implemented privately at `ForumBrowserPostView.kt:213` |

**MISSING (10)** — one cluster, *forum browser screen*: ForumBrowserItem, ForumBrowserLayoutManager, ForumBrowserScrollListener, ForumGuidelinesManager, ForumPostCreateManager, ForumPostReadStateManager, WidgetForumBrowser, WidgetForumBrowserAdapter, WidgetForumBrowserViewModel, WidgetForumGuidelinesBottomSheet.

### `widgets/mobile_reports/**` — 3 of 7 have any counterpart

| reference | target |
|---|---|
| MobileReportsBottomButton | ✅ `mobile_reports/MobileReportsBottomButton.kt` |
| MobileReportsBreadcrumbs | ✅ `mobile_reports/MobileReportsBreadcrumbs.kt` |
| MobileReportsViewModel (1961 lines) | ⛔ `mobile_reports/MobileReportsViewModel.kt` (9 lines, `SubmitState` only) |

**MISSING (4)** — one cluster, *report flow*: MobileReportArgs, ReportsFeatureFlag, ReportsMenuNode, WidgetMobileReports.

---

## 4. Classification summary (all 23 target files)

| target file | class | evidence |
|---|---|---|
| `settings/WidgetSettings.kt` | 🟡 PARTIAL | `:151` dead `onBlockedUsers`; `:114-125` mass row hiding; `:90-108` synthetic profile ViewState |
| `settings/WidgetSettingsApp.kt` | 🟡 PARTIAL | `:100-104` reduced-motion render bug; `:111-125` no override tooltips; toggles persist correctly |
| `settings/WidgetSettingsNotifications.kt` | ⛔ INERT | `:58-59` hooks never assigned; `:27` `Observable.just(NotificationSettingsV2())` |
| `settings/WidgetSettingsNotificationsOs.kt` | ⛔ INERT | `:73-76` hooks never assigned; no call site |
| `settings/OptimisticMutation.kt` | ✅ REAL | `:18-36`, unit-tested |
| `settings/account/WidgetSettingsBlockedUsers.kt` | ✅ REAL (unreachable) | `:39` wired at `stores/StoreStream.kt:787`; no navigation |
| `settings/billing/PaymentSourceView.kt` | ✅ REAL (orphan) | `:40-58` |
| `settings/profile/TouchInterceptingCoordinatorLayout.kt` | ✅ REAL (orphan) | `:16-17` |
| `servers/WidgetServerNotifications.kt` | ⛔ INERT | `:41` null `stateSource` ⇒ `render` never runs; `:55` hardcoded strings; `:80-84` no-op mutation |
| `servers/NotificationMuteSettingsView.kt` | ✅ REAL | `:52-68` |
| `servers/NotificationHighlightsSettingsView.kt` | ✅ REAL, DEAD | `:36-58`; no caller repo-wide |
| `servers/community/CommunitySelectorView.kt` | ✅ REAL (orphan) | `:28-41` |
| `servers/community/CommunityGetStartedInformationView.kt` | ✅ REAL (orphan) | `:30-54`, `:57-74` |
| `servers/community/CommunityGetStartedHeaderIconView.kt` | ✅ REAL (orphan) | `:21-35` |
| `servers/creator_monetization_eligibility/EligibilityChecklistView.kt` | ✅ REAL (orphan) | `:30-41` |
| `…/onboarding/AcceptTermsStepCard.kt` | ✅ REAL (orphan) | `:39-65` |
| `servers/member_verification/MemberVerificationView.kt` | ✅ REAL (orphan) | `:32-42` |
| `forums/ForumBrowserPostView.kt` | 🟡 PARTIAL | `:110` unparsed content; `:126-148` invented attachment path; `:185` wrong string |
| `forums/ForumPostModels.kt` | 🟡 PARTIAL | models complete; `ForumUtils` 2/15 |
| `forums/ForumPostTagView.kt` | ✅ REAL (orphan) | `:34-37` |
| `mobile_reports/MobileReportsBottomButton.kt` | ✅ REAL (orphan) | `:35-72`; `:58` wrong color |
| `mobile_reports/MobileReportsBreadcrumbs.kt` | ✅ REAL (orphan) | `:20-37` |
| `mobile_reports/MobileReportsViewModel.kt` | ⛔ INERT | whole file, 9 lines vs 1961 |

Totals: **REAL 14** (11 of them orphaned/unreachable), **PARTIAL 5**, **INERT-PLACEHOLDER 4**.

---

## 5. Ranked gaps

| # | sev | gap | evidence |
|---|---|---|---|
| 1 | **BLOCKER** | Blocked-users screen unreachable: `onBlockedUsers` hook is assigned but never invoked, and the row is force-hidden. A fully-implemented, store-wired feature is dark in the shipping build. | `settings/WidgetSettings.kt:151` + `:114-125` + `:143-147`; assigned `widgets/tabs/WidgetTabsHost.kt:109` |
| 2 | **BLOCKER** | `WidgetServerNotifications` can never render: `stateSource` is null ⇒ `subscription` null ⇒ `render()` never called; all mutations resolve `Result.success(Unit)` without persisting. | `servers/WidgetServerNotifications.kt:41-44`, `:80-84`, `:95-98` |
| 3 | MAJOR | `WidgetSettingsNotifications`: six inert toggles, no store read/write, despite `stores/StoreNotifications.kt:37-42` already exposing every setter. | `settings/WidgetSettingsNotifications.kt:27`, `:52`, `:58-59` |
| 4 | MAJOR | `WidgetSettingsNotificationsOs`: two inert toggles + inert "open system settings" hook. | `settings/WidgetSettingsNotificationsOs.kt:33`, `:55-68`, `:73-76` |
| 5 | MAJOR | Server mute has no duration picker; reference opens `WidgetMuteSettingsSheet.showForGuild`. `WidgetMuteSettingsSheet` + `MuteSettingsSheetViewModel` MISSING. | `servers/WidgetServerNotifications.kt:58` vs `WidgetServerNotifications$configureUI$onMute$1.java:22` |
| 6 | MAJOR | Hardcoded English mute/unmute labels instead of `form_label_mute_server`/`form_label_unmute_server`. | `servers/WidgetServerNotifications.kt:55` |
| 7 | MAJOR | Highlights section never bound; `NotificationHighlightsSettingsView` has zero callers and its layout node is hardcoded `visibility="gone"`. | `res/layout/widget_server_notifications.xml:17`; no id reference in `WidgetServerNotifications.kt` |
| 8 | MAJOR | Channel notification overrides: list, add-override target, and swipe-to-delete all absent; `NotificationsOverridesAdapter` + `WidgetServerNotificationsOverrideSelector` MISSING. | layout ids `guild_notifications_override_list`/`server_notifications_override_title` unbound; `WidgetServerNotifications.kt:36` calls a null hook |
| 9 | MAJOR | Accessibility: tapping a sticker-animation radio re-enables the reduced-motion-disabled rows because `render` is called with `reduced = false`. | `settings/WidgetSettingsApp.kt:100-104` vs `:107-127` |
| 10 | MAJOR | Accessibility: disabled rows show no reason — `accessibility_reduced_motion_settings_override` / `stickers_auto_play_help_disabled` never set. | `settings/WidgetSettingsApp.kt:111,115,124` vs `WidgetSettingsAccessibility.java:234,239,259` |
| 11 | MAJOR | Forum post preview renders raw text: no mention/emoji/markdown/spoiler/edited parsing. | `forums/ForumBrowserPostView.kt:110` vs `ForumBrowserPostView.java` `configureFirstMessage` |
| 12 | MAJOR | Settings profile header is fed a synthetic ViewState (`isMeUserPremium=false`, empty `UserProfile`), and badge/banner press handlers are absent. | `settings/WidgetSettings.kt:90-108` vs `WidgetSettings.java:257-262, 637-640` |
| 13 | MAJOR | No large-guild gating on the "All messages" radio (`large_guild_notify_all_messages_description`); no hub-guild `configureForHub` layout collapse. | `servers/WidgetServerNotifications.kt:60-62,73-78` vs `WidgetServerNotifications.java:180-247` |
| 14 | MINOR | Mobile reports submit button is blurple, not red. | `mobile_reports/MobileReportsBottomButton.kt:58` (`brand_500`) vs reference `R.color.status_red` |
| 15 | MINOR | Forum unread-count uses `forum_post_message_count_short` instead of `…_short_parenthetical`; read-post title/desc use `colorInteractiveNormal` instead of `primary_300`. | `forums/ForumBrowserPostView.kt:185`, `:196-201` vs `ForumUtils.java:191`, `ForumBrowserPostView.java` `configureReadState` |

Also noted, all "expected (phase not started)": 59 missing settings classes, 99 missing servers classes, 10 missing forums classes, 4 missing mobile_reports classes — see §3 clusters. Eleven of the fourteen REAL target files are custom Views whose host Fragments/adapters do not exist yet; they compile and are referenced only from layouts.

## 6. JADX reliability notes
- `NotificationHighlightsSettingsView.java:187` and `WidgetSettings.java:167` both decompile to the impossible `(cond) ? false : false ? 0 : 8`. The intended visibility predicate is **not recoverable** from this output; the target's reading is plausible but unverified.
- `MobileReportsBreadcrumbs.java:60-178` is an undecompiled bytecode dump ("Method not decompiled"). The register trace is unambiguous and the target matches it, but this was read as bytecode, not source.
- `WidgetServerNotifications.java` was read via a filtered grep of its 587 lines (large synthetic-lambda fan-out); the Model field list, `configureForHub`, `configureRadio`, `configureUI` and `createSwipeableItemTouchHelper` bodies were confirmed directly, and the mute/unmute lambdas were read from their `$`-files.
