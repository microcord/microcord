# Parity re-audit: models / API / REST / Gateway

- Date: **2026-08-04**
- Target: `app/src/main/java/com/discord/{api,models,restapi,gateway,nullserializable}`
- Reference: Discord Android 126.21 Stable JADX export, Vineflower DEX decompile, and dex2jar `javap -p`
- Method: exact reference FQCN/source-declaration inventory, normalized Retrofit verb/path comparison, request-body name comparison, and focused field/behavior review of every blocker in the 2026-07-26 report.
- Verification: static audit only. Gradle, device, and live-backend tests were not run.

## Current status

The 2026-07-26 report's 50.3% headline is obsolete. The current exact-FQCN source scan is a conservative structural lower bound because Kotlin file facades and consolidated files can provide equivalent behavior under a different JVM owner.

| Tree | Reference non-synthetic FQCNs | Exact target declarations | Unmatched | Structural lower bound |
| --- | ---: | ---: | ---: | ---: |
| `api/**` | 286 | 276 | 10 | 96.5% |
| `models/**` | 149 | 143 | 6 | 96.0% |
| `restapi/**` | 10 | 7 | 3 | 70.0% |
| `gateway/**` | 12 | 12 | 0 | 100% |
| `nullserializable/**` | 3 | 3 | 0 | 100% |
| **Total** | **460** | **441** | **19** | **95.9%** |

This is not a behavioral-parity percentage. Constructor/default/nullability checks were not repeated for every DTO, and passing fixtures do not prove live backend compatibility.

### Unmatched reference names

`api/**`:

- `api.auth.mfa.DisableMfaRequestBody`
- Helper/file-facade surfaces: `ActivityTimestampsKt`, `ApplicationKt`, `ChannelUtils`, `GuildWelcomeChannelUtilsKt`, `EmbedTypeKt`, `PermissionKt`, `InAppReportsMenuKt`, `StageInstanceUtilsKt`, `VoiceStateUtilsKt`

Several helper names have equivalent logic under another owner: `ChannelUtils.kt:1-234` emits `ChannelUtils` through `@file:JvmName`, and `EmbedType.kt:7` provides the cached enum array. The remaining names require member-by-member comparison before they can be called resolved.

`models/**`:

- `ActionRowMessageComponentKt`, `ButtonMessageComponentKt`, `SelectMessageComponentKt`
- `ModelSkuKt`, `DirectoryEntryCategoryKt`, `MessageKt`

The corresponding data classes are present, often consolidated, but exact helper methods/JVM owners were not all reproduced.

`restapi/**`:

- `BreadcrumbInterceptor`
- `SpotifyTokenInterceptor`
- `utils.RetryWithDelay`

The retry policy exists inline in `utilities/rx/ObservableExtensions.kt:67-109`; that is behavior reuse, not exact package/API parity.

## REST contract

### Endpoint recount

The reference contains 282 Retrofit declarations. Treating body-bearing `@HTTP(DELETE)` as its own annotation kind, as the historical audit did, gives 264 unique annotation-kind/path contracts. Treating it as the actual HTTP verb gives 263 unique verb/path pairs because one body-bearing DELETE shares a path with a regular DELETE.

The current target has 307 declarations and 285 unique actual verb/path pairs. It covers **all 264 historical reference contracts behaviorally**: 263 use the exact historical annotation template and one uses the current generalized MFA template.

The only template-level difference is:

```text
POST|"auth/mfa/totp"
```

The target declares generalized `POST auth/mfa/{authenticatorType}` at `RestAPIInterface.kt:190`, which produces the same URL when `authenticatorType=totp`. This is the current-client form of the route, not a missing endpoint, so the completed `missing_endpoints.txt` inventory was deleted.

The target also contains 23 unique contracts absent from 126.21, including session management, username migration, WebAuthn, settings-proto, application-command-index, profile, and gateway discovery routes. These are current-backend additions and must not be counted as 126.21 reference coverage.

### Request bodies

The reference `RestAPIParams` has 107 top-level request-body names. The target represents all 107 names; `OAuth2Authorize` is an `object` containing response models rather than a class. It also has 10 current-backend bodies plus the `ComponentInteractionData` hierarchy, for 118 top-level class/interface/object declarations.

One first-party body outside `RestAPIParams` remains structurally absent: reference `api.auth.mfa.DisableMfaRequestBody(code: String)`, confirmed by `javap -p`. The target deliberately uses the modern global MFA interceptor and sends no body from `RestAPIInterface.kt:219-221`. This is a documented transport divergence, not proven 126.21 parity.

### Open REST risks

- No live-backend contract run was performed. Modern additions can be structurally correct and still fail current policy, headers, experiments, or signing checks.
- `BreadcrumbInterceptor`, `SpotifyTokenInterceptor`, and `RetryWithDelay` remain absent as exact reference APIs.
- `RestAPIInterface.kt` includes dynamic `@Url` interfaces. The only live construction found is `RtcLatency` at `StoreStream.kt:940-948`, built without auth interceptors; `Dynamic` and `Files` have no production construction.

## Gateway

### Resolved since 2026-07-26

- All 12 non-synthetic reference Gateway classes are present.
- `IncomingParser.kt:105-200` has typed branches for all 83 reference dispatch names. Unknown events return a private sentinel rather than raw `JsonElement` delivery.
- The parser additionally handles four current events: `AUTH_SESSION_CHANGE`, `USER_SETTINGS_PROTO_UPDATE`, `GUILD_APPLICATION_COMMAND_INDEX_UPDATE`, and `EMBEDDED_ACTIVITY_UPDATE_V2`.
- Reaction add/remove/remove-all/remove-emoji all deserialize to `MessageReactionUpdate` at `IncomingParser.kt:171-175`, matching Vineflower's recovered reference switch.
- `OutgoingPayload` and its 16 nested payload types are represented at `GatewayPayloads.kt:23-171`.
- `IdentifyClientState` now emits the reference's unusual `useruserGuildSettingsVersion` field at `GatewayPayloads.kt:106-133`.
- Gateway discovery classes `gateway/rest/{RestApi,RestClient,RestConfig}` and `GatewaySocketLogger` exist.

Vineflower recovered the previously JADX-skipped `IncomingParser.assignField` switch, so the event/type mapping is no longer inferred only from store subjects.

### Remaining Gateway caveats

- Typed parsing does not prove every event reaches and mutates the correct destination store. That belongs to the store/gateway behavior audit.
- `GatewaySocket.kt:486-489` still uses raw `android.util.Log` for internal status/errors, bypassing central `AppLog` redaction. Serialized outbound payload logging is separately redacted at `GatewaySocket.kt:482`.
- Gateway/socket behavior has unit fixtures but was not exercised against a live account in this re-audit.

## Core model checks

### Confirmed resolved

- `Channel.guildHashes` is mutable (`Channel.kt:35`), and `appliedTags` retains `ForumTagId` (`:37`).
- `ChannelRecipient`, `ChannelUtils`, `MessageFlags`, `MessageTypes`, emoji models, thread payloads, call/stream payloads, command models, and the 39 adapter registrations are present.
- `EmbedTypeTypeAdapter` is registered at `TypeAdapterRegistrar.kt:118`; wire values no longer deserialize to null.
- `ClientStatuses` order is `desktop, web, mobile` in both target (`ClientStatuses.kt:3-10`) and Vineflower (`ClientStatuses.kt:3, 9-11`). The old unverified blocker is closed.
- `ModelPayload.Hello` contains both `heartbeatInterval` and `trace`; dex2jar `javap -p` confirms those two reference fields. The old claim that `trace` was target-only was wrong.
- `Model`, `Model.JsonReader`, and `Model.TypeAdapterFactory` exist at `models/domain/Model.kt:19-208`.

### Open findings

1. **MAJOR: READY still materializes a full JSON tree.** `ModelPayloadAdapter.read` calls `JsonParser.parseReader(reader).asJsonObject` at `ModelPayload.kt:226-230`. The reference `ModelPayload` implements streaming `Model.assignField`; large-account READY memory behavior is therefore still divergent even though the shared streaming framework now exists.
2. **MAJOR: `ModelPayload` has an extra `authSessionIdHash` with incomplete value semantics.** The field is declared at `ModelPayload.kt:50-52`, but the copy constructor (`:78-104`), `fields()` equality/hash list (`:135-140`), and `toString()` (`:125-133`) omit it. Copies can silently lose the value and two payloads differing only by session hash compare equal.
3. **MINOR: `ForumTag.customEmojiId` is still a body initializer** at `Channel.kt:65-72`. The reference stores it as a body-level backing field. Gson does not run Kotlin body initializers, so a reflectively deserialized `ForumTag` can observe null rather than `emojiId.toString()`; use of the property remains unsafe until fixture/device evidence proves its adapter path.
4. **STRUCTURAL: 19 reference FQCNs remain unmatched** as listed above. Consolidated behavior must be checked before deleting or recreating any helper facade.
5. **DELIBERATE SECURITY DIVERGENCE:** redacted `toString()` implementations remain on credential/content-bearing models. Keep them; the project security contract outranks generated reference output.

## Adapter registration

`TypeAdapterRegistrar.kt:88-130` now registers four factories and 39 concrete adapters, including `EmbedType`, component runtime types, command/permission enums, OAuth scope, reporting nodes, payout types, localized strings, and analytics schema. The 2026-07-26 20/39 finding is resolved.

Open limitation: registration count is not a field inventory. Later DTO additions and every custom adapter still need fixture coverage for nulls, unknown enums, and malformed payloads.

## Evidence caveats

- JADX remains unreliable for constructor/default/nullability details. Vineflower and `javap` were used for `ClientStatuses`, `ModelPayload.Hello`, `DisableMfaRequestBody`, `IncomingParser`, and `MessageQueue.doSend`.
- No claim above treats a formerly JADX-broken body as resolved solely from target similarity.
- This re-audit did not perform a fresh field-by-field comparison of all 435 reference API/model classes. The structural percentage is therefore a lower-bound inventory, not completion.

## Top remaining blockers

1. Convert READY/`ModelPayload` back to true streaming parsing or prove the tree allocation acceptable on large sanitized fixtures.
2. Fix `ModelPayload.authSessionIdHash` copy/equality/hash semantics.
3. Audit the 19 unmatched helper/interceptor FQCNs member by member.
4. Run sanitized malformed/null/unknown-enum fixtures and the opt-in live transport matrix before claiming behavioral parity.
