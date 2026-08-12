package com.discord.utilities.persister

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.discord.utilities.time.Clock
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.objenesis.strategy.StdInstantiatorStrategy
import rx.Observable
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject
import rx.subjects.Subject

class Persister<T : Any>(val key: String, private val defaultValue: T, preload: Boolean = true) {
    private var value: T = defaultValue
    private var valueDirty = false
    private var valueUnset = true
    private var valueSubject: Subject<T, T>? = null

    init {
        check(initialized) { "Persister.init() must be called before creating preferences" }
        require(key.isNotBlank() && key == File(key).name && key != "." && key != "..") {
            "Preference key must be a private file name"
        }
        preferences += WeakReference(this)
        if (preload) preloader.preload(this)
    }

    @Synchronized
    fun get(): T {
        if (valueUnset) {
            valueUnset = false
            value = readValue()
        }
        return value
    }

    @JvmOverloads
    @Synchronized
    fun set(value: T, persist: Boolean = false): T {
        this.value = value
        valueUnset = false
        valueDirty = true
        valueSubject?.onNext(value)
        if (persist) persist()
        return value
    }

    @JvmOverloads
    @Synchronized
    fun getAndSet(persist: Boolean = false, setter: (T) -> T): T = set(setter(get()), persist)

    @JvmOverloads
    fun clear(persist: Boolean = false): T = set(defaultValue, persist)

    @Synchronized
    fun getObservable(): Observable<T> {
        valueSubject?.let { return it }
        return SerializedSubject(BehaviorSubject.create(get())).also { valueSubject = it }
    }

    @Synchronized
    private fun persist(): Boolean {
        if (!valueDirty) return true

        val file = File(filesDir, key)
        val temporary = File(filesDir, "$key.new")
        val backup = File(filesDir, "$key.bak")
        try {
            restoreBackup(file, backup)
            val stream = temporary.outputStream()
            Output(stream).use { output ->
                output.writeInt(CACHE_MAGIC)
                requireNotNull(kryo.get()).writeClassAndObject(output, value)
                output.flush()
                stream.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) error("Unable to back up preference")
            if (!temporary.renameTo(file)) error("Unable to replace preference")
            backup.delete()
            valueDirty = false
            return true
        } catch (exception: Exception) {
            temporary.delete()
            if (!file.exists()) backup.renameTo(file)
            logger(5, "Unable to serialize preference $key.", Exception(key, exception))
            return false
        }
    }

    private fun readValue(): T {
        val file = File(filesDir, key)
        val backup = File(filesDir, "$key.bak")
        restoreBackup(file, backup)
        if (!file.exists() || file.length() >= filesDir.usableSpace) return value

        return try {
            val decoded = Input(file.inputStream()).use { input ->
                if (input.readInt() != CACHE_MAGIC) null
                else requireNotNull(kryo.get()).readClassAndObject(input)
            }
            if (decoded != null) {
                @Suppress("UNCHECKED_CAST")
                decoded as T
            } else {
                file.delete()
                value
            }
        } catch (exception: Exception) {
            file.delete()
            logger(5, "Unable to deserialize preference $key.", Exception(key, exception))
            value
        }
    }

    private fun restoreBackup(file: File, backup: File) {
        if (!backup.exists()) return
        file.delete()
        backup.renameTo(file)
    }

    private class Preloader {
        private val active = java.util.concurrent.atomic.AtomicInteger()
        private val state = BehaviorSubject.create(true)
        // ponytail: two readers overlap Kryo CPU work without turning startup into an I/O stampede.
        private val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "PersisterPreload").apply { isDaemon = true }
        }
        private val scheduler = Schedulers.from(executor)

        fun preload(persister: Persister<*>) {
            active.incrementAndGet()
            state.onNext(false)
            Observable.fromCallable { persister.get() }
                .subscribeOn(scheduler)
                .subscribe(
                    { finish() },
                    { finish() },
                )
        }

        fun isPreloaded(): Observable<Boolean> = state.distinctUntilChanged()

        fun close() = executor.shutdownNow()

        private fun finish() {
            if (active.decrementAndGet() == 0) state.onNext(true)
        }
    }

    companion object {
        private const val CACHE_MAGIC = 0x4D435034
        private val preferences = CopyOnWriteArrayList<WeakReference<Persister<*>>>()
        private var filesDir = File("")
        private var initialized = false
        private var clock: Clock? = null
        private var persistenceSubscription: Subscription? = null
        private var preloader = Preloader()
        private var kryoConfig: (Kryo) -> Unit = {}
        private var logger: (Int, String, Exception?) -> Unit = { _, _, _ -> }
        private val kryo = object : ThreadLocal<Kryo>() {
            override fun initialValue() = Kryo().apply {
                instantiatorStrategy = Kryo.DefaultInstantiatorStrategy(StdInstantiatorStrategy())
                fieldSerializerConfig.isOptimizedGenerics = true
                kryoConfig(this)
            }
        }

        fun setKryoConfig(config: (Kryo) -> Unit) {
            kryoConfig = config
            kryo.remove()
        }

        fun setLogger(logger: (Int, String, Exception?) -> Unit) {
            this.logger = logger
        }

        fun init(context: Context, clock: Clock, persistenceStrategy: Observable<Boolean>) {
            init(context.filesDir, persistenceStrategy, clock)
        }

        @VisibleForTesting
        internal fun init(
            directory: File,
            persistenceStrategy: Observable<Boolean> = Observable.never(),
            clock: Clock? = null,
        ) {
            filesDir = directory
            this.clock = clock
            initialized = true
            preloader.close()
            preloader = Preloader()
            persistenceSubscription?.unsubscribe()
            persistenceSubscription = persistenceStrategy
                .observeOn(Schedulers.io())
                .subscribe({ persistAll() }, { })
        }

        @JvmStatic
        fun isPreloaded(): Observable<Boolean> = preloader.isPreloaded()

        fun persistAll(): Boolean = persistMatching(null)

        fun persist(keys: Set<String>): Boolean = persistMatching(keys)

        private fun persistMatching(keys: Set<String>?): Boolean {
            var success = true
            preferences.removeAll { reference ->
                reference.get()?.let {
                    if (keys != null && it.key !in keys) return@let false
                    val startedAt = clock?.currentTimeMillis()
                    success = it.persist() && success
                    startedAt?.let { start ->
                        val elapsed = requireNotNull(clock).currentTimeMillis() - start
                        if (elapsed > 100) logger(4, "Cached ${it.key} in ${elapsed / 1_000f} seconds.", null)
                    }
                    false
                } ?: true
            }
            return success
        }

        @JvmStatic
        fun reset() {
            preferences.removeAll { reference ->
                reference.get()?.let {
                    it.clear(true)
                    false
                } ?: true
            }
        }
    }
}
