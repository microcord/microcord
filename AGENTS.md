# Agent Guide

## Start Here

Read `PLAN.md` before inspecting or changing code. It is the source of truth
for architecture, fixed decisions, phase status, risks, and handoffs.

Target project:
`C:\Users\manti\Desktop\projeler\manticordrebuild`

Read-only reference:
`C:\Users\manti\StudioProjects\discord-jadx`

Never modify the reference project.

## Fixed Project Contract

- Reconstruct Discord Android 126.21 Stable for feature parity.
- Write readable Kotlin, using Android Views and XML.
- Application ID is `dev.mantikafasi.manticord`.
- Android namespace is `com.discord`.
- Minimum SDK is 21.
- Reuse recovered assets, layouts, and `libdiscord.so`.
- Install public dependencies normally.
- Do not copy decompiled public library source.
- Preserve the original store/dispatcher/fragment architecture until parity.
- Recreate every semantic field in first-party stores and data/model classes.
- Recreate every first-party view and screen with complete behavior; do not
  count placeholders or layout-only implementations as parity.
- Use the live backend carefully; fixture tests come first.

Do not revisit these choices unless the user explicitly changes them or
runtime evidence makes one impossible. Record any approved change in the
`PLAN.md` decision log.

## Working Method

1. Check the active phase and immediate next work in `PLAN.md`.
2. Inspect the target flow and all relevant callers in the reference.
3. Note JADX failures; inspect DEX/smali or sibling usage instead of guessing.
4. Implement the smallest complete vertical slice.
5. Add the smallest meaningful automated check for non-trivial behavior.
6. Run relevant Gradle checks.
7. Update phase status, parity evidence, risks, decisions, and progress journal.
8. Return a handoff using the template in `PLAN.md`.

Keep only one phase or narrowly related vertical slice in progress per agent.
Do not mix resource migration, build-system conversion, networking, and feature
work in one change unless the dependency is unavoidable.

## Reference Interpretation

Trust evidence in this order:

1. Runtime behavior and controlled observations.
2. DEX/smali instructions and signatures.
3. Android manifest, resources, and serialized payloads.
4. Kotlin metadata and unobfuscated declarations.
5. Callers, tests added by this project, and sibling implementations.
6. Decompiled Java bodies.

JADX output is not valid source by default. Do not copy synthetic lambda
classes, bridge methods, generated bindings, obfuscated utility packages, or
invalid decompiler constructs into the target.

## Structural Parity Rules

- Before implementing a store, inventory all of its semantic fields, caches,
  indexes, subjects, snapshots, persistence handles, update sources, and store
  dependencies from the reference.
- Implement the complete store state surface, not only fields used by the first
  caller being rebuilt.
- Before implementing a first-party DTO, Gateway payload, domain model, store
  state, or Kotlin data class, inventory every reference field.
- Match field type, generic element type, nullability, default, mutability,
  constructor order, enum value, wire/JSON name, and serialization behavior.
- Preserve data-class equality, copy/component order, hash behavior, and safe
  string rendering. Redact secrets even if the reference generated `toString`
  did not.
- Do not use `Any`, raw maps, or untyped JSON as a shortcut for known fields.
- Synthetic compiler fields are excluded unless JNI, reflection,
  serialization, or observed runtime behavior depends on them.
- Recreate every first-party custom view, fragment, dialog, bottom sheet,
  adapter row, and layout variant used by the feature.
- Match view state, visibility, interaction, scrolling, animation, lifecycle,
  restoration, accessibility, loading, empty, and error behavior.
- A placeholder may exist only during active development and must be recorded
  in `PLAN.md`; no phase can be marked done while one remains.

## Dependency Rules

- Search recovered metadata before selecting a dependency coordinate/version.
- Add dependencies to `gradle/libs.versions.toml`.
- Prefer Google and Maven Central.
- Do not add JCenter without an unavoidable artifact and explicit approval.
- Do not copy dependency Java just to satisfy a missing symbol.
- Resolve resource attribute errors by installing the owning dependency before
  editing recovered XML.
- Record significant version deviations in the `PLAN.md` decision log.

## Resource Rules

- All recovered assets and layouts must remain represented in the target.
- Import resources in the order defined by `PLAN.md`.
- Let AGP regenerate `R` and view bindings.
- Do not copy decompiled `R`, `BuildConfig`, or binding classes.
- Avoid numeric resource IDs in code.
- Preserve qualifiers, accessibility text, RTL behavior, and large-font layouts.
- Record material XML changes made solely for modern AAPT compatibility.

## Native Rules

- Treat `libdiscord.so` as an immutable binary contract.
- Preserve exact JNI-facing package, class, method, and callback descriptors.
- Do not rename `com.hammerandchisel.libdiscord` or
  `co.discord.media_engine` bridge types for style.
- Verify the compatible WebRTC Java API before building voice features.
- Test native creation and disposal, not only `System.loadLibrary`.
- Never claim voice parity while the native spike is incomplete.

## Security and Live Backend

- Never commit or print tokens, passwords, MFA codes, cookies, API keys, or
  recovered official service credentials.
- Never weaken TLS validation or add certificate/signature bypasses.
- Do not reuse Google/Firebase values extracted from the reference APK.
- Live tests must be opt-in, narrow, and use approved test accounts.
- Do not perform destructive account/guild actions without explicit approval.
- Sanitize fixtures before committing them.
- Review every exported Android component and mutable PendingIntent.

## Build and Verification

Use Windows wrapper commands from the project root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

Run `assembleRelease` before closing a release-oriented phase. State explicitly
when a check was not run or no device was available.

## Scope Discipline

- Do not touch files outside this project and the read-only reference.
- Do not rewrite unrelated files or revert concurrent work.
- Do not create speculative modules, interfaces, repositories, or factories.
- Prefer one implementation until a second concrete use demands abstraction.
- Fix shared root causes rather than adding guards to individual screens.
- Keep the project buildable at each handoff.

## Documentation Discipline

Update `PLAN.md` when any of these change:

- Phase or feature status.
- Verified behavior or test evidence.
- Architecture, dependency, or compatibility decision.
- Newly discovered blocker or risk.
- Native/runtime compatibility result.
- Meaningful completed work session.

Do not create competing roadmap or status documents. Add specialized technical
documentation only when implementation produces details too large for the plan,
then link it from `PLAN.md`.
