# Microcord Plugin API

## Status

This document records the agreed architecture for bundled and externally installed Microcord
plugins. The `:plugin-api` manifest/lifecycle contract, global loading policy, dependency resolver,
priority ordering, blocking-chain promotion, external APK loader, lifecycle execution, header,
composer, bottom-tab, and profile-popout hosts, schema/custom settings, settings cards, signer approval, and
namespaced storage are implemented. `PLAN.md` remains the project roadmap and links here for the detailed
design.

## Goals

- Support bundled plugins and externally installed plugin APKs through one API.
- Let plugins add lifecycle-aware components to typed UI surfaces: chat headers, message composers,
  bottom tabs, and profile popouts.
- Give every plugin a consistent settings entry while permitting an XML-backed custom settings
  page when the schema API is insufficient.
- Resolve plugin requirements and deterministic priority without delaying hot UI paths.
- Support lifecycle work that completes immediately or asynchronously.
- Keep the no-plugin and disabled-plugin startup paths close to their current cost.
- Isolate ordinary plugin failures and provide a safe recovery path after startup crashes.

## Non-Goals For The First Version

- Loading arbitrary uninstalled APK/resource tables from a custom archive.
- Cross-process UI plugins. Android views cannot be contributed across a process boundary.
- Asynchronous UI factories or event decisions.
- A security sandbox. Enabled external plugins execute in Microcord's process.
- Generic hooks into every application method. Hosts are added as typed extension points when a
  concrete use needs them.

## Packaging

### Bundled Plugins

Bundled plugins are registered explicitly at build time. There is no reflection or classpath scan.
They use the host classloader and host resources but otherwise follow the same manifest, dependency,
lifecycle, settings, and contribution contracts as external plugins.

### External Plugins

An external plugin is an installed Android APK with no launcher activity required. APK packaging is
the supported external format because it is the only public, reliable Android 21+ mechanism that
can ship compiled binary XML, `resources.arsc`, qualified drawables, strings, fonts, raw files,
assets, and native libraries.

A custom archive can contain AAPT2 output, but loading an uninstalled compiled resource table uses
hidden platform APIs before Android 11. Shipping source XML would require a custom layout language
or bundling a runtime resource compiler. Neither is an acceptable compatibility or startup tradeoff.

The plugin APK advertises a no-op discovery component with the intent action:

```text
dev.mantikafasi.manticord.PLUGIN
```

Plugin authors configure one typed Gradle extension with IDE autocomplete:

```kotlin
plugins {
    id("dev.mantikafasi.manticord.plugin")
}

manticordPlugin {
    id.set("example.plugin")
    entrypoint.set("example.plugin.ExamplePlugin")
    description.set("Example plugin")
    manticordVersionMinimum.set(1)
    manticordVersionMaximumExclusive.set(2)
    priority.set(0)
    usesResources.set(true)
    async() // or blocking()
    requires("example.library", minimumVersionCode = 1)
}
```

The `:plugin-gradle` plugin validates that DSL and generates manifest placeholders for ID, entrypoint,
description, Microcord version range, requirements, priority, load mode, and resource mode. The fixed manifest
snippet consumes those placeholders; plugin Kotlin code does not declare a duplicate manifest.
`usesResources` defaults to `false`.

`manticordVersionMinimum` is checked against `PLUGIN_API_LEVEL`, the plugin API this Microcord build
implements (2 since message delete/update interceptors and decorators). The maximum is ignored: a
plugin built for an older API still loads, and if it calls something since removed, only its own
start fails.

## Discovery

Microcord performs one targeted `PackageManager` query for components matching the plugin intent. It
does not enumerate every installed application.

- Known package names and enabled state are cached in private preferences.
- Startup performs a package-scoped intent query only for each enabled package; zero enabled external
  packages means zero startup PackageManager query.
- Opening Plugins settings performs the full targeted intent query on an IO scheduler to list newly
  installed packages without blocking that screen's first frame.
- Removed packages simply produce no scoped match; signer approval is retained but cannot authorize a
  replacement unless its SHA-256 fingerprint still matches.
- Package discovery, dependency sorting, and class loading never occur in a UI render path.

## Global Loading Switch

The Plugins settings screen owns one master **Load Plugins** switch, enabled by default. The manager
checks it before invoking any plugin source. When disabled, Microcord performs no bundled discovery,
PackageManager query, class loading, resource context creation, dependency resolution, or plugin
lifecycle work. This zero-discovery behavior is enforced by `PluginManager` and a focused regression
test.

Disabling the switch at runtime stops running plugins in reverse dependency order and removes their
contributions. Android cannot reliably unload classes from an existing process, so reclaiming already
loaded plugin classloaders requires an application restart; the next startup takes the complete
no-loading path.

## External Code And Resource Loading

All external plugin code uses a direct `PathClassLoader` whose parent is Microcord's classloader:

```kotlin
PathClassLoader(
    applicationInfo.sourceDir,
    applicationInfo.nativeLibraryDir,
    hostContext.classLoader,
)
```

Using the host classloader as parent ensures a plugin compiled with the plugin API as `compileOnly`
shares Microcord's API classes instead of loading duplicate interfaces with incompatible identity.

For `usesResources=false`, the entrypoint is instantiated directly from that classloader. No package
resource context is created.

For `usesResources=true`, Microcord additionally calls:

```kotlin
hostContext.createPackageContext(
    packageName,
    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
)
```

The resulting package context supplies the plugin's resources. Microcord wraps it so
`getClassLoader()` returns the direct plugin classloader above. The wrapped context and an inflater
cloned into it are required when plugin XML names plugin-owned custom view classes. The package
context's own classloader is not used as the API boundary.

The resource path is deliberately opt-in because opening a package resource table, creating a
resource context, and preparing an inflater are unnecessary for code-only plugins.

## API Artifact

The published `dev.mantikafasi.manticord:plugin-api` compile-only JAR combines the stable plugin
contracts, every compiled Microcord class, and `com.discord.R`. Plugins may directly import
`StoreStream`, stores, models, widgets, utilities, and any other app code; the host-parented
classloader resolves those imports to Microcord's runtime classes.

Public plugin records are read-only interfaces; factory/DSL functions return private
implementations, so adding implementation fields does not alter plugin constructor ABI. Direct app
internals are intentionally less stable, so compatibility is governed only by the Gradle DSL's
mandatory Microcord app-version range and checked before class loading.

Version 1 is intentionally a Kotlin/JVM API and exposes Kotlin function types plus RxJava 1. Those
types and the compiler's pinned JVM-default mode are frozen for the full v1 line. Java-first support,
replacing RxJava, adding setting kinds, or adding enum constants requires a new Microcord compatibility range.

## Plugin Manifest

For external plugins, Microcord constructs the read-only `PluginManifest` entirely from generated APK
metadata and package version/label information. Bundled plugins use the stable `pluginManifest(...)`
factory and builder. No external entrypoint implements or returns a manifest.

Plugin IDs and contribution IDs are stable persistence keys. They must not be translated or derived
from display names.

## Lifecycle

RxJava 1 is already part of Microcord and is the lifecycle completion primitive. No coroutine
dependency is added solely for plugins.

```kotlin
interface MicrocordPlugin {
    fun start(scope: PluginScope): Completable = Completable.complete()
    fun stop(): Completable = Completable.complete()
}
```

`ASYNC` is the default load mode. Metadata is checked synchronously, then its classloader, optional
resource context, constructor, and lifecycle all run after first UI on the plugin coordinator and its
dedicated executor.

`BLOCKING` is an explicit manifest opt-in. Microcord invokes it on the plugin executor and waits for
its start `Completable` before showing UI. It may therefore deliberately delay startup. The manager
still owns timeout, failure reporting, and disposal so a broken blocking plugin cannot hold startup
forever.

Lifecycle work never runs on the store dispatcher. Every plugin has a dedicated disposable executor;
reloads are serialized through one coordinator so old instances finish stopping before replacements
start.
UI factories and bind callbacks remain synchronous, main-thread-only, and lifecycle-bound.

## Requirements And Priority

The loader validates unique IDs, Microcord compatibility, requirement versions, and dependency cycles
before starting a plugin.

1. Requirements form a directed acyclic graph.
2. A plugin starts only after every requirement has completed successfully.
3. A missing, incompatible, disabled, or failed requirement makes the dependent plugin `BLOCKED`.
4. Among otherwise eligible plugins, higher priority is invoked first; equal priority sorts by ID.
5. Independent asynchronous plugins run concurrently on dedicated per-plugin executors.
6. Plugins stop in reverse dependency order.

A blocking plugin promotes its complete requirement chain to blocking for that startup. Otherwise
the loader could not satisfy the guarantee that every requirement is running before the dependent
starts. Settings warn when selecting blocking mode or enabling a plugin whose dependency chain is
promoted by another blocking plugin.

Priority is a deterministic preference, not a substitute for a requirement. A plugin using a library
must declare that library as a requirement; setting a high library priority alone is insufficient.

## Runtime States

The manager publishes immutable snapshots containing these states:

- `DISCOVERED`
- `STARTING`
- `RUNNING`
- `FAILED`
- `BLOCKED`
- `DISABLED`

Failure of one plugin removes its contributions and blocks only its dependents. Disabling a running
plugin stops dependents first and removes all registrations owned by the plugin scope.

## Startup Performance Contract

The current profile-compiled application can display in roughly 100-150 ms, so plugin work must not
become an unbounded main-thread extension of `Application.onCreate`.

Expected device-dependent costs are approximately:

| Work | Typical added time |
| --- | ---: |
| Targeted package query | 3-15 ms |
| Query on a cold or slower OEM package service | 20-50 ms |
| Load one installed, optimized code-only plugin | 5-20 ms |
| First load after plugin install or update | 20-100+ ms |
| Lightweight synchronous plugin initialization | 1-10 ms |

The startup path therefore follows these rules:

1. Read cached enabled package names; do not run discovery before every first frame.
2. Validate enabled plugin metadata, app-version ranges, requirements, priority, and blocking-chain
   promotion before class loading.
3. Code-only plugins use the direct classloader path.
4. Resource context creation is restricted to `usesResources=true`.
5. Cache immutable ordered contribution snapshots when registrations change.
6. Start `ASYNC` plugins without gating first UI and publish registry updates when ready.
7. Fully await explicitly `BLOCKING` plugins and their promoted requirement chains before first UI.
8. Apply a fixed lifecycle timeout even to blocking plugins.
9. Never perform network work in a blocking startup unless the plugin author deliberately accepts
   that startup risk.

The default path never waits for arbitrary third-party lifecycle code. Authors may deliberately opt
into blocking startup when a plugin must be ready for the first frame, accepting that its class load,
resource setup, initialization, and promoted requirements are added directly to launch time. Runtime
metrics record metadata discovery, class/resource loading, dependency resolution, and each lifecycle
start separately.

## UI Contribution API

Plugins register through their `PluginScope`. Every registration returns an owned handle and is also
removed automatically when the scope stops. Registries publish pre-sorted immutable snapshots only
when registrations change;
rendering does not acquire a registry lock, read preferences, query packages, or sort dependencies.

Hosts use typed contracts rather than exposing arbitrary internal `ViewGroup`s.

### Header

Header contributions support:

- A custom action view inflated from plugin XML or created programmatically.
- DM, guild channel, thread, and channel-type visibility predicates.
- Stable contribution ID and priority.
- A click callback receiving an immutable channel snapshot.

`WidgetHomeHeaderManager` appends the immutable plugin snapshot after configuring built-in actions.
Runtime menu IDs are allocated by the host and mapped back to stable plugin contribution IDs.

Each contribution supplies a user-facing `title`. Header Customizations lists active plugin actions
under **Plugins** on both the Direct Messages and Channels tabs. Visibility is stored independently
per surface and reflected immediately in the existing preview and live toolbar.

### Message Composer

Composer contributions support these existing zones:

- `LEFT`
- `INSIDE_START`
- `INSIDE_END`
- `RIGHT`

A contribution declares a stable ID, title, accessibility description, default zone, priority, view
factory, state bind callback, and optional cleanup callback. The bind context is an immutable snapshot
of relevant channel/composer state; plugins do not receive `WidgetChatInput` or its binding.

Plugin views are installed after built-in `ChatInputCustomization` placement. They are tagged and
removed independently, so refreshing plugin contributions cannot detach built-in composer controls.
Views are created once per host view lifecycle and rebound rather than recreated for every store
emission.

### Bottom Tabs

Bottom-tab contributions declare a stable ID, user-facing title, priority, synchronous view factory,
bind callback, click callback, and visibility predicate. The immutable bind context contains the
selected built-in tab name, current user ID, and whether navigation is enabled.

Built-ins occupy priorities `500`, `400`, `300`, `200`, and `100` for Home through Settings. A plugin
priority places its view relative to those built-ins; equal priorities use stable `owner:id` order.
The Bottom Tabs customization screen persists visibility and user order for built-ins and plugin
elements together. Newly installed contributions append in priority order without discarding the
saved order of existing entries. Home and Settings remain visible so navigation and the customization
screen cannot become unreachable; both remain reorderable.

The host gives every visible item equal width and the full navigation-bar height. Factories run once
per host view lifecycle; ordinary state changes only rebind cached views.

### Profile Popouts

Profile contributions declare a stable ID, title, priority, synchronous view factory, bind callback,
and visibility predicate. They share one ordered element list with native About Me, Member Since,
Friends Since, Voice Settings, Activity, Guild/Roles, Connections, and Note sections. Priority supplies
the first default position; the Profile Popouts customization screen then persists visibility and user order.

The immutable profile context contains user/guild identity, current-user and bot flags, plus resolved
surface, border, primary text, secondary text, and interactive colors. Plugins must apply those
palette values to every dynamic child so custom guild/user profile themes remain coherent. Views are
cached for the sheet lifecycle and rebound on profile/palette updates.

Profile-tab contributions use `registerProfileTab(...)` and the same palette-aware profile context.
Main, the optional Board, and visible plugin tabs share the Profile Tabs customization order; Main is
always visible. Plugin views are created once per sheet lifecycle, rebound with profile changes, and
hosted by the existing profile tab/page controller; native Main/Board swipe behavior remains unchanged.

### Message Components

`registerMessage(...)` contributes a cached view at one of three typed placements:

- `HEADER`: on the native author/timestamp line; grouped continuation rows omit it because they have no header.
- `CONTENT`: immediately after the rendered message body and before native accessories.
- `STANDALONE`: after attachments, embeds, stickers, components, and other native accessories.

The immutable bind context exposes message, channel, guild, and author IDs, raw content, and the bot
flag. It does not expose the row holder or its binding. Factories run once per recycled holder and
views are rebound as that holder displays different messages. Priority orders plugin views within a
placement; equal priorities use stable `owner:id` order. Visibility and bind failures are contained.

```kotlin
scope.ui.registerMessage(
    pluginMessageContribution(
        "example_card",
        "Example Card",
        PluginMessagePlacement.STANDALONE,
        { context -> createCard(context) },
    ) {
        bind = { view, message -> bindCard(view, message.messageId) }
    },
)
```

### Message Interceptors

`registerMessageInterceptor(...)` synchronously transforms Microcord's real
`com.discord.models.message.Message` at shared `SEND`, `EDIT`, and `RECEIVE` boundaries.
Interceptors are ordered by priority, then stable `owner:id` order. Return the original instance for
a no-op or use Kotlin `copy(...)` to alter content, attachments, stickers, mentions, flags,
references, embeds, or components in Microcord's local model. No wrapper is allocated; disabled or
non-matching interceptors leave the original message on the normal path.

Normal Discord user send/edit REST routes still determine what reaches the server: send supports
content, attachments, stickers, mentions, flags, and references; edit supports content and allowed
mentions. Embeds and components are server-owned for normal user messages, so changing them affects
Microcord's local optimistic/received rendering, not Discord's accepted payload.

```kotlin
scope.ui.registerMessageInterceptor(
    pluginMessageInterceptor(
        "strip_plugin_prefix",
        PluginMessageInterceptionType.SEND,
        transform = { message ->
            if (message.content?.startsWith("plugin:") == true) {
                message.copy(content = message.content.removePrefix("plugin:").trimStart())
            } else {
                message
            }
        },
    ),
)
```

Plugins that need to observe received, sent, or edited messages should subscribe directly to the
existing public Microcord store. No plugin-specific event fan-out is installed, so disabled plugins
add no Gateway callback, main-thread post, or message-allocation overhead.

```kotlin
StoreStream.getMessages()
    .observeMessagesForChannel(channelId)
    .subscribe { messages -> render(messages) }
```

The observable includes optimistic local messages and subsequent Gateway updates. Plugins own their
Rx subscription and should unsubscribe it from their `stop()` method when they create one.

### Message Deletes And Updates

Two more interceptors, registered with the same `registerMessageInterceptor(...)`, run on the store
thread for Gateway events about messages this client has loaded:

- `pluginMessageDeleteInterceptor(id, onDelete)` runs before a deleted message leaves the chat.
  Return null to let it go, or a message to keep in its place. Pins, replies and mentions still drop
  it. Deletes made from this client are not intercepted, and a kept message disappears once the
  channel is reloaded from the server, so persist anything you need.
- `pluginMessageUpdateInterceptor(id, onUpdate)` receives the version before the update and the
  merged result, and returns the message to store. Updates also arrive when embeds resolve, so
  compare `content` or `editedTimestamp` to find edits.

Both run inside the message store: keep them to a map lookup and an allocation or two, and do I/O on
your own background thread. With none registered the host skips the lookups entirely.

### Message Decorators

`registerMessageDecorator(...)` restyles native message rows. `decorate` receives the row's views
(`root`, `content`, and the `author` and `timestamp` text views when present) on every bind, on the
main thread, so it must stay cheap. Rows are recycled; before each bind the host restores `alpha`
and `background` on `root`, and `alpha`, text colors and paint flags on the text views. Undo any other
change yourself. Attachments, embeds and stickers are separate rows and are not decorated.

```kotlin
// Written on the store thread, read on the main thread.
val deleted = Collections.synchronizedSet(HashSet<Long>())
scope.ui.registerMessageInterceptor(
    pluginMessageDeleteInterceptor("keep_deleted", onDelete = { message -> message.also { deleted += it.id } }),
)
scope.ui.registerMessageDecorator(
    pluginMessageDecorator("deleted_red", decorate = { views, message ->
        if (message.messageId in deleted) views.content.setTextColor(0xFFF04747.toInt())
    }),
)
```

The chat list rebinds after a plugin keeps a deleted message, so its decoration shows at once.
Call `scope.ui.refreshMessages()` to rebind visible rows after other plugin state they read changes.

### Slash Commands

`registerSlashCommand(...)` adds a local command to the existing application-command store. Commands
declare a stable ID, lowercase command name, description, priority, visibility predicate, typed
string/integer/boolean options, and a synchronous executor. They receive the current typed channel
context plus parsed arguments and return the message text to submit.

Plugin commands participate in command discovery, query filtering, execution, built-in application
counts, and frecency. They never create a second parser or send path. Higher priority wins duplicate
plugin names; native command names remain reserved. Exceptions are contained and produce no message.

## Plugin View And Resource Context

A plugin receives these view/resource capabilities through its scope and contribution factories:

- The current host themed `Context` when a contribution creates a view.
- An optional plugin resource context.
- A plugin inflater when `usesResources=true`.
- Automatic registration disposal when the plugin stops.

Resource IDs from an external APK are meaningful only with that plugin's resource context and are
never persisted. Independently compiled plugin theme attribute IDs do not equal host attribute IDs,
even when names match. Custom XML may use plugin-owned themes and resources; automatic Microcord
theme integration uses the palette and host component factories rather than direct references to
host `R` values.

## Settings API

Every discovered plugin appears in the Microcord Plugins settings screen. The common page shows
enabled state, app icon, version, source package, abbreviated signer fingerprint, and runtime state.
The dedicated Settings button opens the plugin's schema and optional custom page.

Normal plugin settings are declared as a schema and rendered with existing Microcord components.
Supported entries are:

- Section and description.
- Boolean toggle.
- Text.
- Single choice.
- Action.

Example:

```kotlin
override val settingsSchema = pluginSettings {
    toggle("enabled_feature", "Enable feature", default = true)
    choice(
        key = "composer_position",
        title = "Composer position",
        default = "left",
        options = listOf("left" to "Left", "inside" to "Inside", "right" to "Right"),
    )
    text("format", "Message format", default = "%s")
    action("clear_cache", "Clear cache") { clearCache() }
}
```

Settings are stored by the host under the plugin ID and setting key. Plugins receive typed reads and
an observable change stream rather than direct access to the shared application preferences. Schema
defaults remain plugin-owned and do not damage settings belonging to another plugin.

### Custom Settings Pages

A plugin may additionally provide one lifecycle-managed custom settings page. This is an escape hatch,
not the default settings mechanism.

Resource plugins may inflate their own XML through the plugin inflater. Code-only plugins may build
the page with host component factories. The host owns navigation chrome, Up/Back behavior, lifecycle,
subscription disposal, error containment, and the outer themed surface. Custom pages receive only
their plugin's namespaced settings and storage handles through the public scope.

## Main Thread

`scope.applicationContext` is the stable Microcord application context. UI work should instead use:

```kotlin
scope.runOnMainThread { context ->
    // context is the active themed AppActivity when one exists.
}
```

The callback is skipped after the plugin scope closes. Do not retain the supplied activity context.

## Host Dialogs

Plugins open first-party Discord notice dialogs through the host service rather than constructing a
platform `AlertDialog`:

```kotlin
scope.dialogs.alert("Example Plugin", "Hello from a plugin")
```

The host posts to the main thread, resolves the current `AppActivity`, and renders
`WidgetNoticeDialog` with normal Discord theming and lifecycle behavior.

## Storage

Each plugin receives private namespaced directories under Microcord's storage for files and cache.
The external APK's own application data directory is not used because loaded code executes under
Microcord's UID, not the plugin package's normal process identity.

Ordinary settings use namespaced host preferences. Files and cache use SHA-256-derived directories
under Microcord's private storage, so a plugin ID cannot escape its directory. Secret-setting storage
is not exposed in v1; plugins must not put credentials into ordinary settings.

## Trust And Recovery

Android requires every installed APK to carry a signing certificate. Microcord does not maintain an
approved signer list: self-signed plugin APKs are accepted. The first enable shows the package name,
certificate fingerprint, and a warning that the plugin receives Microcord's process permissions and
can access account data available to in-process code. Updates retain Android's normal same-signer
enforcement.

The manager records which plugin is entering blocking startup and clears the marker after success.
If the process dies with that marker present, the next launch enters plugin safe mode, disables that
plugin and its dependents, and opens the application without them. Users can inspect the failure and
explicitly retry. This handles accidental crashes; it is not a sandbox against malicious code.

## Load Metrics

The runtime measures with `SystemClock.elapsedRealtimeNanos()` and publishes immutable metric
snapshots. The Plugins screen shows aggregate discovery, dependency-resolution, and total startup
durations. Each running or failed plugin card shows external code/resource loading time and lifecycle
start time. Metrics are captured during loading and only read during rendering; no timing or logging
work is added to header/composer hot paths.

## Verification

Current verification covers:

- Global-disable zero-discovery behavior.
- Resource context and XML custom-view inflation for `usesResources=true`.
- Duplicate IDs, API incompatibility, missing requirements, version mismatch, and cycles.
- Deterministic priority ordering and blocking requirement promotion.
- UI registration ordering and owner cleanup.
- Settings-schema duplicate-key validation.
- A real installed resource-plugin APK reaching `Running` and rendering its own XML/drawable in the
  live chat toolbar and composer on Android 13.
- Explicit approval UI and signer fingerprint display.

All builds run through `scripts/build.sh`. Device tests include the app's API 23 minimum and current Android
16 target, because external resource and classloader behavior is platform-sensitive.

## Remaining Verification

1. Run the lifecycle instrumentation test after the repository's unrelated Android-test compile drift
   is repaired.
2. Exercise a code-only external plugin and a failing/blocking dependency chain on device.
3. Measure cold-start median/p95 with zero, one resource, and five lightweight plugins.
4. Add a package-replaced device test proving a signer change returns to approval-required state.
