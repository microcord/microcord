# Agent Guide

## Start Here

Read `PLAN.md` before inspecting or changing code. It is the source of truth
for architecture, fixed decisions, phase status, risks, and handoffs.

Then read `docs/parity-audit/README.md` and the report covering your area. It is
the measured gap list between this project and the reference, produced 2026-07-26,
with `file:line` citations. Two rules come from it:

- Every report lists the regions where JADX output is broken and the behavior is
  therefore **unverified**. Never close one of those by guessing — inspect
  DEX/smali or the call sites.
- Coverage figures in `PLAN.md` are measured, not estimated. Do not mark a phase
  `DONE` while its audit report still lists open blockers.

When more than one agent is active, follow the concurrency protocol in the
`PLAN.md` "Reconstruction Campaign" section: exclusive file ownership per agent,
wait on the Gradle lock rather than disabling it, and defer cross-package
cleanups to the owning wave through your handoff report.

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

For unresolved Gateway or HTTP behavior, enable the existing sanitized traffic
logging and inspect live inbound/outbound Gateway payloads or HTTP requests and
responses before changing code. Never guess at wire behavior when runtime logs
can prove it, and never print tokens, message content, cookies, or credentials.

Keep only one phase or narrowly related vertical slice in progress per agent.
Do not mix resource migration, build-system conversion, networking, and feature
work in one change unless the dependency is unavoidable.

## Reference Interpretation

### Available evidence sources

| Source | Path | Use for |
| --- | --- | --- |
| JADX Java export | `C:\Users\manti\StudioProjects\discord-jadx` | Structure, resources, manifest, call sites |
| **Vineflower Kotlin decompile of the DEX** | `C:\Users\manti\Desktop\projeler\discorddecompile\out\**` (4,853 `.kt` files, paths mirror the reference) | Constructors, `equals`/`hashCode`/`toString`, declaration order, nullability — **recovers what JADX drops** |
| **dex2jar JAR** | `C:\Users\manti\Desktop\projeler\discorddecompile\discord126.21-dex2jar.jar` | `javap -p` for exact signatures/arity/field types; `javap -v` for runtime-visible annotations |
| Original DEX | `discord-jadx/app/src/main/classes*.dex` | `dexdump` when even the above disagree |
| Discord Userdoccers | `https://docs.discord.food/llms.txt` | Undocumented modern Discord API fields, payloads, routes, and behavior; verify against runtime/reference evidence |

All are READ-ONLY. **Check the Kotlin decompile before declaring anything
unverifiable** — many findings in `docs/parity-audit/` were marked "JADX broken"
purely because JADX omitted members that Vineflower recovers.

Techniques that work:

- Declaration order — read the synthesized `equals`/`toString` bodies; a Kotlin
  data class emits them in declaration order.
- Nullability — Kotlin-metadata types plus `checkNotNullParameter` guards.
- Defaults — presence/absence of the synthetic
  `(..., int mask, DefaultConstructorMarker)` constructor. **This check matters:
  it catches false "defaults" that decompilers print from `copy$default`.**
- R8-emptied classes — `javap` showing zero members and a `@Metadata` `d2` array
  listing no declarations means nothing is recoverable. Do not invent a body.

### Trust order

1. Runtime behavior and controlled observations.
2. DEX/smali instructions and signatures, including the Vineflower Kotlin
   decompile and `javap` output above.
3. Android manifest, resources, and serialized payloads.
4. Kotlin metadata and unobfuscated declarations.
5. Callers, tests added by this project, and sibling implementations.
6. Decompiled Java bodies (JADX) — the weakest source; it silently drops
   constructors, catch tables, and whole method bodies.

JADX output is not valid source by default. Do not copy synthetic lambda
classes, bridge methods, generated bindings, obfuscated utility packages, or
invalid decompiler constructs into the target.

## Structural Parity Rules

- Before implementing a store, inventory all of its semantic fields, caches,
  indexes, subjects, snapshots, persistence handles, update sources, and store
  dependencies from the reference.
- Implement the complete store state surface, not only fields used by the first
  caller being rebuilt.
- Every `StoreV2` mutation must run on the single `StoreStream` dispatcher that
  is registered with `StoreThreadScheduler`. Do not create standalone
  dispatchers, executors, or schedulers for stores; route all mutation callbacks
  back through the owning store-graph dispatcher.
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
- User-visible flows must reuse existing Discord-themed first-party components
  and styles. Do not introduce stock Android dialogs, buttons, text fields,
  progress indicators, or pickers when a Discord component exists; when none
  exists, create the smallest first-party Discord-themed view.
- Every view created or inflated inside a themed user profile must consume the
  resolved profile palette for surfaces, borders, primary/muted text, icons,
  and controls. Never fall back to global theme attributes for dynamic profile
  children; pass the profile colors into them and restore defaults when the
  profile has no custom theme.
- Match view state, visibility, interaction, scrolling, animation, lifecycle,
  restoration, accessibility, loading, empty, and error behavior.
- Preserve the reference navigation chrome and exit behavior on every screen.
  Nested destinations must expose the same toolbar Up button and Back-stack
  behavior as the original; omit them only when the reference does.
- Do not change an existing view's measured width, height, padding, margins, or
  touch-target geometry for a new decoration or feature unless the change is
  absolutely necessary and supported by reference or runtime evidence. Prefer
  non-measuring overlays; document any unavoidable size change in `PLAN.md`.
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

## API Transport Rules

- All endpoints use the single default `RestAPI.api` transport with
  `ClientMetadataInterceptor`; do not recreate a parallel RN/legacy client split.
- Add a transport test proving every new endpoint receives the default client
  metadata headers.

## Resource Rules

- All recovered assets and layouts must remain represented in the target.
- Import resources in the order defined by `PLAN.md`.
- Let AGP regenerate `R` and view bindings.
- Do not copy decompiled `R`, `BuildConfig`, or binding classes.
- Use generated view bindings whenever a layout provides one. Use `findViewById`
  only when binding is genuinely unavailable or cannot represent the dynamic view.
- When touching a first-party view that still uses manual view lookup, migrate it
  to its generated binding and remove redundant cached view variables and aliases.
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

- Never commit or print tokens, passwords, MFA codes, cookies, or private
  service-account credentials. The approved Discord client Firebase
  configuration exception below does not include server-side keys.
- Never weaken TLS validation or add certificate/signature bypasses.
- Discord's client-side Firebase configuration may be reused for FCM parity by
  explicit user decision recorded in `PLAN.md`; never recover or use Firebase
  Admin/service-account credentials.
- Live tests must be opt-in, narrow, and use approved test accounts.
- Do not perform destructive account/guild actions without explicit approval.
- Sanitize fixtures before committing them.
- Review every exported Android component and mutable PendingIntent.

## Build and Verification

**Always build through `scripts/build.sh`, never `gradlew` directly.** Several agents share this
checkout, and concurrent Gradle invocations fight over the daemon and the build directory until one
of them dies of heap exhaustion. The script takes the same arguments as the wrapper:

```bash
scripts/build.sh :app:assembleDebug --offline
scripts/build.sh :app:testDebugUnitTest --offline
scripts/build.sh :app:installDebug --offline
```

It serialises every invocation and answers a request with any build that started *after* the
request was made, so asking for something another agent is already about to build costs nothing.
A request that arrives while a build is less than `BUILD_YOUNG_SECS` (45) old restarts that build
so it picks up the newer edits; against an older build it queues instead and takes the next slot.
Output streams live for whichever agent is doing the work and is replayed to the others, and the
exit code is the real Gradle exit code.

It also retries once, after `--stop`, when a build dies of `OutOfMemoryError` or a disappeared
daemon — those say nothing about the code and are usually a daemon left over from an earlier
session. A compile failure is never retried.

State lives in the gitignored `.buildlock/`; deleting that directory is always safe when nothing is
building. `connectedDebugAndroidTest` and anything else that needs a device goes through the script
too.

Run `assembleRelease` before closing a release-oriented phase. State explicitly
when a check was not run or no device was available.

- Do not run unnecessary, repetitive, or broad test suites. Run the smallest
  focused check that covers the changed behavior, then one final integration
  build/test batch after related edits are complete. Do not rerun unchanged
  checks after every small edit.

## Git Workflow

- Merge commits are forbidden. Integrate upstream only with `git rebase`.
- With an uncommitted worktree, stash tracked and untracked changes, rebase onto
  the upstream branch, reapply the stash, resolve conflicts, and verify before
  dropping the temporary stash. Never use `git merge` for upstream updates.
- Commit messages are a single conventional line, lowercase, no body and no
  trailing period: `fix: adding friends`, `chore: remove dead samsung link stuff`.
  Never write a paragraph, a bullet list, or a co-author trailer.
- One concern per commit. Stage only that concern's hunks, even when a file also
  carries unrelated concurrent work.

## Scope Discipline

- Do not touch files outside this project and the read-only reference.
- Do not rewrite unrelated files or revert concurrent work.
- Do not create speculative modules, interfaces, repositories, or factories.
- Prefer one implementation until a second concrete use demands abstraction.
- Fix shared root causes rather than adding guards to individual screens.
- Keep the project buildable at each handoff.

## Interruption Discipline

Agents in this project are routinely killed mid-task by session limits. Assume you
will be interrupted without warning and work so that an interruption costs nothing.

- **One file at a time.** Finish a file and make it self-consistent before opening
  the next. Never leave two files half-written.
- **Stop between files, never inside one.** A truncated file is worse than a missing
  one: it breaks the build for every other agent, not just your own work. A single
  stray `}` in `CallModel.kt` once produced 22 cascading errors across the tree.
- **Deliver the highest-value slice first**, verify it, and report. Do not spread
  effort thinly across a whole cluster hoping to finish it. Finishing one third of
  an assignment properly beats starting all of it.
- **Refuse work that can only produce a placeholder.** If a class's collaborators do
  not exist yet, say so and build the collaborators, or hand back a spec. An inert
  stub reads as progress and is worse than an honest gap.
- **End every report with a resumable task list** — one line per remaining file,
  naming the target path, the reference source path, and what it needs. It must be
  actionable by a fresh agent that has none of your context, because that is exactly
  who will pick it up.

## Recurring Traps

Every one of these has cost this project a build or a device crash at least once. They are cheap
to avoid and expensive to diagnose, because none of them looks wrong when you read it back.

- **Kotlin block comments nest.** A slash-star anywhere inside a KDoc block — including in prose,
  e.g. writing an endpoint path with a wildcard — opens a second comment, and the closing delimiter
  then closes only the inner one. The file runs unterminated to EOF and the compiler reports the
  error hundreds of lines away. Equally, a KDoc containing a star-slash *terminates early*; that
  once produced 43 syntax errors from one comment.
- **Android's ICU regex engine rejects patterns the JVM accepts.** A bare `}` is the usual one —
  escape both braces. A JVM unit test structurally cannot catch this; only a device/instrumentation
  test can. This has caused a fatal crash on device.
- **`BehaviorSubject.create<T?>(null)` silently binds to the wrong inherited overload** —
  `Observable.create(OnSubscribe)` — and then `onNext` is unresolved. Write `create(null as T?)`.
- **Decompilers print enum constants alphabetically.** Ordinals are wire values in this codebase;
  an ordinal read from decompiled source rather than `<clinit>` bytecode is a guess. This has bitten
  several separate reconstructions.
- **Gson never runs Kotlin body initialisers.** A `val x = expr` in a model body is null or zero
  after deserialisation. Use `get()` and document it as a deliberate divergence.
- **Store state must be mutated on the dispatcher thread.** Route through `dispatcher.schedule { }`.
  Violating this caused a fatal crash in `App.onCreate`.
- **Hiding a UI entry point is not gating it.** If a screen must not ship, gate the screen itself,
  not the menu row that opens it.

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
