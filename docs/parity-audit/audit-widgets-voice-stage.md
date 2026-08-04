# Parity re-audit - voice, stage, and status widgets

Date: **2026-08-04**

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

Reference: Discord Android 126.21 Stable under
`C:\Users\manti\StudioProjects\discord-jadx`, supplemented by the Vineflower
DEX decompile under `C:\Users\manti\Desktop\projeler\discorddecompile\out`.

Scope: `widgets/voice/**`, `widgets/stage/**`, and `widgets/status/**` only.

## Result

All **105 of 105** top-level non-synthetic reference classes are now
represented. The 2026-07-26 17.1% inventory and Phase-11-not-started framing are
obsolete: fullscreen/private/stage call UI, controls, sheets, feedback, stage
models/sheets/start flow, and status ViewModels now exist. This report remains
open because the RTC store bridge is not installed by the store graph, stage
lurking and verification gates are incomplete, two stage start actions are
inert, and end-to-end voice/video testing is incomplete.

| Package | Reference classes | Current target files | Reference classes represented | Missing |
| --- | ---: | ---: | ---: | ---: |
| `widgets/voice` | 66 | 67 | 66 | 0 |
| `widgets/stage` | 31 | 32 | 31 | 0 |
| `widgets/status` | 8 | 8 | 8 | 0 |
| **Total** | **105** | **107** | **105** | **0** |

`VoiceStoreAccess` and `StageStoreAccess` are target-only bridge files and do
not increase the represented-class count.

## Resolved Findings

- `CallModel` now has producer/state logic and the voice state stores exist.
- `WidgetVoiceBottomSheet`, fullscreen/preview calls and ViewModels,
  participants, video grids, incoming/failed/inline calls, screen-share
  navigation, controls, settings, noise cancellation, and feedback pipelines
  are present.
- All fullscreen stage rows/holders/adapter, stage model/API/join/notification
  surfaces, stage sheets, moderator start flow, and user-sheet stage actions
  are present.
- `WidgetChatStatus`, `WidgetThreadStatus`, and `WidgetForumPostStatus` now have
  substantive producer/ViewModel behavior; the global status indicator cluster
  is complete structurally.
- The former wrong-icon/string findings in reconstructed controls were addressed
  alongside dedicated control contract tests.
- Vineflower recovers `VoiceControlsSheetView.configureUI` and its helper calls,
  including stage-channel gating via `ChannelUtils.D`, so the old impossible
  JADX ternaries are no longer the sole evidence source.

## Open Findings

### Blockers

1. The RTC transport stores are optional and uninstalled by default.
   `VoiceStoreAccess.kt:11-30` states that `StoreStream` does not construct
   `StoreMediaEngine`, `StoreAudioManagerV2`, or `StoreRtcConnection`; without
   an external `install`, camera, audio routing, and connection-quality actions
   degrade to inert defaults (`VoiceStoreAccess.kt:43-99`). This prevents an
   end-to-end parity claim despite complete widget inventory.
2. Stage lurking is not part of the store graph. `StageStoreAccess.kt:9-24`
   documents that `StoreLurking` is neither constructed nor exposed, and
   `observeIsLurking` always returns false until manually installed
   (`StageStoreAccess.kt:51-58`). Lurker-specific join prompts and state are
   therefore wrong.
3. Member-verification gates are absent from voice/stage entry.
   `VoiceUtils.kt:64-68` and `:109-113` intentionally attempt the join instead
   of presenting the reference verification sheet, causing gated lurkers to be
   refused without the recovery flow.
4. Two moderator-stage actions are inert. In
   `WidgetModeratorStartStage.kt:73-87`, Schedule Event and existing Event rows
   execute `Unit`, despite both referenced scheduled-event destinations now
   existing.

### Significant Residual Risk

- Call failure abort codes collapse to a generic message because the reference
  error resolver is absent (`WidgetCallFailed.kt:72-87`).
- `VoiceUtils.kt:87-88` reproduces stage branch structure locally because the
  reference stage utility is absent; exact utility behavior still needs a DEX
  or runtime comparison.
- No instrumentation test named for voice exists in `app/src/androidTest`.
  Unit tests cover contracts and store logic but cannot validate permissions,
  MediaProjection, audio routes, camera lifecycle, EGL/video sinks, overlay,
  background transitions, or reconnection.
- The current plan records no live-backend or ARM64 end-to-end call exercise and
  leaves the device matrix open. Structural widgets and x86_64 native lifecycle
  checks do not substitute for that evidence.

## Decompiler Verification

The old report correctly refused to guess broken JADX visibility ternaries and
the mangled `CallModel.isVideoCall` loop. Vineflower was checked in this re-audit:
it recovers `VoiceControlsSheetView.configureUI` and its stage-channel argument
flow, reducing that caveat. Exact behavior still not recovered cleanly remains
open rather than inferred. No report finding was closed solely because a class
now exists.

## Test Evidence And Gaps

Current focused unit coverage includes voice stores/gateway, joinability,
foreground service/controller, model/control contracts, feedback, stage roles,
stage adapter spans, and status models/ViewModels. Representative files:

- `app/src/test/java/com/discord/stores/VoiceStoresTest.kt`
- `app/src/test/java/com/discord/widgets/voice/model/VoiceContractTest.kt`
- `app/src/test/java/com/discord/widgets/voice/controls/AnchoredVoiceControlsViewTest.kt`
- `app/src/test/java/com/discord/widgets/voice/feedback/VoiceFeedbackFlowTest.kt`
- `app/src/test/java/com/discord/widgets/voice/fullscreen/stage/StageCallAdapterSpanTest.kt`
- `app/src/test/java/com/discord/widgets/status/WidgetChatStatusModelTest.kt`

No checks were run for this documentation-only re-audit. No available test
closes the default RTC-store wiring, lurking, verification-gate, inert stage
actions, or full device/live-backend matrix.

## Status

**OPEN - 100% physical class coverage, behavioral/runtime parity not verified.**
Do not delete this report until transport/store wiring is unconditional, stage
entry gaps are fixed, remaining decompiler caveats are resolved from DEX or
runtime evidence, and representative end-to-end device tests pass.

## Resumable Task List

- `app/src/main/java/com/discord/widgets/voice/VoiceStoreAccess.kt` | reference `app/src/main/java/com/discord/stores/StoreStream.java` and voice store callers | make RTC media/audio/connection stores part of the live graph and delete fallback wiring.
- `app/src/main/java/com/discord/widgets/stage/StageStoreAccess.kt` | reference `app/src/main/java/com/discord/stores/StoreStream.java` and `StoreLurking.java` | publish live lurking state.
- `app/src/main/java/com/discord/widgets/voice/VoiceUtils.kt` | reference sibling `.java` and stage utility call sites | restore member-verification gates and verify locally reproduced stage branches.
- `app/src/main/java/com/discord/widgets/stage/start/WidgetModeratorStartStage.kt` | reference sibling `.java` | route schedule-event and existing-event rows.
- `app/src/main/java/com/discord/widgets/voice/call/WidgetCallFailed.kt` | reference sibling `.java` | restore abort-code-specific error resolution.
