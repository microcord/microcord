package com.discord.plugins

import android.content.Context
import android.os.SystemClock
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginLoadMode
import dev.mantikafasi.manticord.plugins.api.PluginManifest
import dev.mantikafasi.manticord.plugins.api.PluginSettings
import rx.Observable
import rx.subjects.BehaviorSubject
import java8.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class PluginState {
    DISCOVERED,
    STARTING,
    RUNNING,
    FAILED,
    BLOCKED,
    DISABLED,
}

internal data class PluginSnapshot(
    val manifest: PluginManifest,
    val sourcePackage: String?,
    val state: PluginState,
    val blockReason: PluginBlockReason? = null,
    val error: String? = null,
    val codeLoadDurationMs: Long = 0L,
    val startDurationMs: Long? = null,
)

internal data class PluginMetrics(
    val discoveryDurationMs: Long = 0L,
    val resolutionDurationMs: Long = 0L,
    val totalStartupDurationMs: Long? = null,
)

internal class PluginManager(
    private val source: PluginSource,
    private val loadingEnabled: () -> Boolean = PluginLoadingPolicy::isEnabled,
) {
    private data class RuntimePlugin(
        val discovered: DiscoveredPlugin,
        val settings: PluginSettingsImpl,
        val executor: ExecutorService,
        var state: PluginState = PluginState.DISCOVERED,
        var blockReason: PluginBlockReason? = null,
        var error: String? = null,
        var scope: PluginScopeImpl? = null,
        var startDurationMs: Long? = null,
    )

    private val coordinator = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ManticordPluginCoordinator").apply { isDaemon = true }
    }
    private val snapshotsSubject = BehaviorSubject.create<List<PluginSnapshot>>(emptyList()).toSerialized()
    private val metricsSubject = BehaviorSubject.create(PluginMetrics()).toSerialized()
    private val runtimes = linkedMapOf<String, RuntimePlugin>()
    private val startFutures = mutableMapOf<String, CompletableFuture<Void>>()
    private var loadOrder = emptyList<String>()
    private var initialized = false
    private var generation = 0
    private var startupStartedAt = 0L
    private var discoveryDurationMs = 0L
    private var resolutionDurationMs = 0L

    fun initialize(context: Context?): PluginLoadPlan {
        if (!loadingEnabled()) return PluginLoadPlan(emptyList(), emptyMap())
        val appContext = requireNotNull(context).applicationContext
        val runGeneration = synchronized(this) {
            if (initialized) return currentPlan()
            initialized = true
            startupStartedAt = SystemClock.elapsedRealtimeNanos()
            ++generation
        }
        PluginLoadingPolicy.recoverInterruptedBlockingStart()

        val discoveryStartedAt = SystemClock.elapsedRealtimeNanos()
        val handles = source.discover(appContext)
        discoveryDurationMs = elapsedMs(discoveryStartedAt)
        val preflightStartedAt = SystemClock.elapsedRealtimeNanos()
        val preflight = PluginDependencyResolver.resolve(
            handles.map { PluginCandidate(it.manifest, PluginLoadingPolicy.isPluginEnabled(it.manifest.id)) },
        )
        resolutionDurationMs = elapsedMs(preflightStartedAt)
        val handlesById = handles.groupBy { it.manifest.id }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        val blocking = preflight.plugins.filter { it.loadMode == PluginLoadMode.BLOCKING }
            .mapNotNull { handlesById[it.manifest.id]?.load?.invoke() }
        val blockingPlan = integrate(appContext, blocking, runGeneration, waitForBlocking = true)

        val asyncHandles = preflight.plugins.filter { it.loadMode == PluginLoadMode.ASYNC }
            .mapNotNull { handlesById[it.manifest.id] }
        coordinator.execute {
            if (!isGenerationCurrent(runGeneration)) return@execute
            val async = asyncHandles.mapNotNull { it.load() }
            val asyncPlan = integrate(appContext, async, runGeneration, waitForBlocking = false)
            val relevant = synchronized(this) {
                asyncPlan.plugins.mapNotNull { startFutures[it.manifest.id] }
            }
            CompletableFuture.allOf(*relevant.toTypedArray()).whenComplete { _, _ ->
                if (isGenerationCurrent(runGeneration)) publishCompletedMetrics()
            }
        }
        if (asyncHandles.isEmpty()) publishCompletedMetrics()
        return blockingPlan
    }

    @Synchronized
    fun shutdown() {
        generation++
        startFutures.values.forEach { it.cancel(true) }
        startFutures.clear()
        val stopping = loadOrder.asReversed().mapNotNull(runtimes::get)
        stopping.forEach { runtime ->
            runtime.scope?.close()
            runtime.scope = null
            if (runtime.state == PluginState.RUNNING || runtime.state == PluginState.STARTING) {
                val stop = runtime.executor.submit {
                    runtime.discovered.plugin.stop()
                        .timeout(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .await()
                }
                runCatching { stop.get(STOP_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS) }
                stop.cancel(true)
            }
            runtime.state = PluginState.DISABLED
            runtime.executor.shutdownNow()
        }
        initialized = false
        loadOrder = emptyList()
        runtimes.clear()
        publish()
        metricsSubject.onNext(PluginMetrics())
    }

    fun observeSnapshots(): Observable<List<PluginSnapshot>> = snapshotsSubject.asObservable()
    fun observeMetrics(): Observable<PluginMetrics> = metricsSubject.asObservable()

    @Synchronized
    fun snapshots(): List<PluginSnapshot> = snapshotList()

    @Synchronized
    fun plugin(id: String): ManticordPlugin? = runtimes[id]?.discovered?.plugin

    @Synchronized
    fun settings(id: String): PluginSettings? = runtimes[id]?.settings

    @Synchronized
    fun manifest(id: String): PluginManifest? = runtimes[id]?.discovered?.manifest

    private fun integrate(
        context: Context,
        discovered: List<DiscoveredPlugin>,
        runGeneration: Int,
        waitForBlocking: Boolean,
    ): PluginLoadPlan {
        if (!isGenerationCurrent(runGeneration)) return PluginLoadPlan(emptyList(), emptyMap())
        val newIds = discovered.mapTo(mutableSetOf()) { it.manifest.id }
        synchronized(this) {
            discovered.groupBy { it.manifest.id }.filterValues { it.size == 1 }.forEach { (id, matches) ->
                if (id !in runtimes) {
                    runtimes[id] = RuntimePlugin(
                        discovered = matches.single(),
                        settings = PluginSettingsImpl(context, id),
                        executor = newPluginExecutor(id),
                    )
                }
            }
        }

        val resolutionStartedAt = SystemClock.elapsedRealtimeNanos()
        val plan = synchronized(this) {
            PluginDependencyResolver.resolve(
                runtimes.values.map {
                    PluginCandidate(
                        it.discovered.manifest,
                        PluginLoadingPolicy.isPluginEnabled(it.discovered.manifest.id),
                    )
                },
            )
        }
        resolutionDurationMs += elapsedMs(resolutionStartedAt)
        metricsSubject.onNext(PluginMetrics(discoveryDurationMs, resolutionDurationMs))

        synchronized(this) {
            plan.blocked.forEach { (id, reason) ->
                runtimes[id]?.takeUnless { it.state == PluginState.RUNNING }?.apply {
                    state = PluginState.BLOCKED
                    blockReason = reason
                }
            }
            loadOrder = plan.plugins.map { it.manifest.id }
            publish()
        }

        plan.plugins.forEach { resolved ->
            if (resolved.manifest.id !in newIds) return@forEach
            val runtime = synchronized(this) { runtimes.getValue(resolved.manifest.id) }
            val requirements = resolved.manifest.requirements.mapNotNull { requirement ->
                synchronized(this) {
                    when (runtimes[requirement.pluginId]?.state) {
                        PluginState.RUNNING -> CompletableFuture.completedFuture(null)
                        else -> startFutures[requirement.pluginId]
                    }
                }
            }
            val future = CompletableFuture.allOf(*requirements.toTypedArray()).thenRunAsync({
                start(runtime, context, resolved.loadMode, runGeneration)
            }, runtime.executor).whenComplete { _, failure ->
                if (failure != null && runtime.state != PluginState.FAILED && isGenerationCurrent(runGeneration)) {
                    update(runtime) {
                        state = PluginState.BLOCKED
                        error = failure.cause?.message ?: failure.message
                    }
                }
            }
            synchronized(this) { startFutures[resolved.manifest.id] = future }
            if (waitForBlocking && resolved.loadMode == PluginLoadMode.BLOCKING) {
                runCatching { future.get(START_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS) }
            }
        }
        return plan
    }

    private fun start(
        runtime: RuntimePlugin,
        context: Context,
        loadMode: PluginLoadMode,
        runGeneration: Int,
    ) {
        if (!isGenerationCurrent(runGeneration)) return
        update(runtime) { state = PluginState.STARTING }
        val plugin = runtime.discovered.plugin
        val scope = PluginScopeImpl(
            context,
            runtime.discovered.manifest,
            runtime.discovered.resources,
            runtime.settings,
        )
        runtime.scope = scope
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            if (loadMode == PluginLoadMode.BLOCKING) {
                PluginLoadingPolicy.markBlockingStart(runtime.discovered.manifest.id, runtime.discovered.sourcePackage)
            }
            plugin.start(scope).timeout(START_TIMEOUT_SECONDS, TimeUnit.SECONDS).await()
            runtime.startDurationMs = elapsedMs(startedAt)
            if (loadMode == PluginLoadMode.BLOCKING) PluginLoadingPolicy.clearBlockingStart()
            if (!isGenerationCurrent(runGeneration)) {
                scope.close()
                return
            }
            update(runtime) { state = PluginState.RUNNING }
        } catch (failure: Throwable) {
            runtime.startDurationMs = elapsedMs(startedAt)
            if (loadMode == PluginLoadMode.BLOCKING) PluginLoadingPolicy.clearBlockingStart()
            scope.close()
            runtime.scope = null
            update(runtime) {
                state = PluginState.FAILED
                error = failure.message ?: failure::class.java.simpleName
            }
            throw failure
        }
    }

    private inline fun update(runtime: RuntimePlugin, change: RuntimePlugin.() -> Unit) {
        synchronized(this) {
            runtime.change()
            publish()
        }
    }

    @Synchronized
    private fun currentPlan() = PluginLoadPlan(
        plugins = loadOrder.mapNotNull { id ->
            runtimes[id]?.discovered?.manifest?.let { manifest ->
                ResolvedPlugin(manifest, manifest.loadMode)
            }
        },
        blocked = runtimes.mapNotNull { (id, runtime) -> runtime.blockReason?.let { id to it } }.toMap(),
    )

    @Synchronized
    private fun publish() {
        snapshotsSubject.onNext(snapshotList())
    }

    private fun snapshotList(): List<PluginSnapshot> = runtimes.values.map { runtime ->
        PluginSnapshot(
            manifest = runtime.discovered.manifest,
            sourcePackage = runtime.discovered.sourcePackage,
            state = runtime.state,
            blockReason = runtime.blockReason,
            error = runtime.error,
            codeLoadDurationMs = runtime.discovered.codeLoadDurationMs,
            startDurationMs = runtime.startDurationMs,
        )
    }

    private fun publishCompletedMetrics() {
        metricsSubject.onNext(
            PluginMetrics(
                discoveryDurationMs,
                resolutionDurationMs,
                elapsedMs(startupStartedAt),
            ),
        )
    }

    @Synchronized
    private fun isGenerationCurrent(expected: Int): Boolean = initialized && generation == expected

    private fun newPluginExecutor(id: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ManticordPlugin-$id").apply { isDaemon = true }
        }

    private fun elapsedMs(startedAt: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L

    private companion object {
        const val START_TIMEOUT_SECONDS = 10L
        const val STOP_TIMEOUT_SECONDS = 5L
    }
}

internal object BundledPlugins {
    val all: List<BundledPlugin> = emptyList()
}

internal object PluginRuntime {
    private val externalSource = ExternalPluginSource()
    private val manager = PluginManager(
        CompositePluginSource(BundledPluginSource(BundledPlugins.all), externalSource),
    )
    private val controls = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ManticordPluginControls").apply { isDaemon = true }
    }

    fun initialize(context: Context) = manager.initialize(context)
    fun observeSnapshots(): Observable<List<PluginSnapshot>> = manager.observeSnapshots()
    fun observeMetrics(): Observable<PluginMetrics> = manager.observeMetrics()
    fun snapshots(): List<PluginSnapshot> = manager.snapshots()
    fun plugin(id: String): ManticordPlugin? = manager.plugin(id)
    fun settings(id: String): PluginSettings? = manager.settings(id)
    fun manifest(id: String): PluginManifest? = manager.manifest(id)
    fun externalPlugins(context: Context): List<ExternalPluginDescriptor> = externalSource.catalog(context)
    fun externalFailure(packageName: String): String? = externalSource.failure(packageName)

    fun setLoadingEnabled(context: Context, enabled: Boolean) {
        PluginLoadingPolicy.setEnabled(enabled)
        reload(context, enabled)
    }

    fun setPackageEnabled(
        context: Context,
        packageName: String,
        enabled: Boolean,
        signerSha256: String? = null,
    ) {
        PluginLoadingPolicy.setPackageEnabled(packageName, enabled, signerSha256)
        reload(context, PluginLoadingPolicy.isEnabled())
    }

    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        PluginLoadingPolicy.setPluginEnabled(pluginId, enabled)
        reload(context, PluginLoadingPolicy.isEnabled())
    }

    private fun reload(context: Context, initialize: Boolean) {
        val appContext = context.applicationContext
        controls.execute {
            manager.shutdown()
            if (initialize) manager.initialize(appContext)
        }
    }
}
