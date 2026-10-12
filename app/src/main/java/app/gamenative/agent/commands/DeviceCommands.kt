package app.gamenative.agent.commands

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.annotation.VisibleForTesting
import app.gamenative.agent.AgentDispatcher
import app.gamenative.utils.DeviceInfo
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/** `device.info`: hardware, OS, GPU and free storage, from the same properties the session report uses. */
class DeviceCommands(private val collect: () -> Map<String, Any?>) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(INFO) { info() }
    }

    fun info(): JsonElement = JsonObject(collect().toSortedMap().mapValues { (_, value) -> toJson(value) })

    companion object {
        const val INFO = "device.info"
        private const val GPU_TIMEOUT_MS = 5_000L

        private fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

        /**
         * Gathers the device properties. Each group is collected separately so a failing probe (GPU
         * detection, storage) only drops its own fields instead of failing the whole command.
         */
        fun collect(context: Context, gpuTimeoutMs: Long = GPU_TIMEOUT_MS): Map<String, Any?> = buildMap {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("android_release", Build.VERSION.RELEASE)
            section("device") { putAll(DeviceInfo.deviceProperties(context)) }
            section("gpu") { putAll(gpuProperties(context, gpuTimeoutMs)) }
            section("storage") { put("storage_free_bytes", StatFs(context.dataDir.path).availableBytes) }
            section("external storage") {
                context.getExternalFilesDir(null)?.let { put("external_storage_free_bytes", StatFs(it.path).availableBytes) }
            }
        }

        private val gpuProbeLock = Any()
        private var gpuProbe: FutureTask<Map<String, Any>>? = null

        /**
         * GPU detection may spin up an EGL context and block on it the first time; run it on its own
         * daemon thread so a stuck probe cannot hold the binder thread. Concurrent and retried requests
         * share the one in-flight probe, so a probe that never returns leaves a single thread behind
         * rather than one per request; it is dropped once done so the next request probes again.
         */
        private fun gpuProperties(context: Context, timeoutMs: Long): Map<String, Any> {
            val task = synchronized(gpuProbeLock) {
                gpuProbe ?: FutureTask { DeviceInfo.gpuProperties(context) }.also { probe ->
                    gpuProbe = probe
                    Thread(probe, "agent-gpu-info").apply { isDaemon = true }.start()
                }
            }
            try {
                return task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                if (task.isDone) synchronized(gpuProbeLock) { if (gpuProbe === task) gpuProbe = null }
            }
        }

        /** Forgets the shared GPU probe so a test does not reuse one another test left in flight. */
        @VisibleForTesting
        internal fun resetGpuProbe() {
            synchronized(gpuProbeLock) { gpuProbe = null }
        }

        private inline fun section(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                Timber.w(e, "device.info: %s properties failed", name)
            }
        }
    }
}
