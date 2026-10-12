package app.gamenative.agent.commands

import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.ManifestInstallResult
import com.winlator.contents.ContentProfile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A manifest entry `components.install` downloads, and how `ManifestInstaller` installs it. */
data class InstallPlan(
    val target: String,
    val variant: String?,
    val entry: ManifestEntry,
    val isDriver: Boolean,
    val contentType: ContentProfile.ContentType?,
)

/**
 * Runs `components.install` downloads off the binder thread, one at a time (the content installer is not
 * meant to run concurrently), and keeps the last job per component so asking again reports its progress.
 * Lives for the app process; a restart forgets the jobs, not what they installed.
 */
class ComponentInstallQueue(private val scope: CoroutineScope) {

    enum class State { QUEUED, DOWNLOADING, INSTALLED, FAILED }

    class Job(val plan: InstallPlan) {
        @Volatile
        var state: State = State.QUEUED
            internal set

        @Volatile
        var progress: Float = 0f
            internal set

        @Volatile
        var message: String? = null
            internal set

        val isActive: Boolean get() = state == State.QUEUED || state == State.DOWNLOADING

        private var failureTaken = false

        /** True exactly once for a failed job, so its failure is answered once before the next call retries it. */
        @Synchronized
        fun takeFailure(): Boolean {
            if (state != State.FAILED || failureTaken) return false
            failureTaken = true
            return true
        }
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val running = Mutex()

    /**
     * The job for [value] under [target] and [variant], or null. There is one job per target, id and variant;
     * with a null [variant] any of that id's jobs may be returned.
     */
    fun find(target: String, value: String, variant: String?): Job? = jobs.values.firstOrNull {
        it.plan.target == target && it.plan.entry.id.equals(value, ignoreCase = true) &&
            (variant == null || it.plan.variant == variant)
    }

    /**
     * Starts [plan] unless a job for the same component is still queued or downloading, which is returned
     * instead. A finished job (installed or failed) is replaced, so a failed download can be retried.
     */
    @Synchronized
    fun enqueue(plan: InstallPlan, run: suspend (InstallPlan, onProgress: (Float) -> Unit) -> ManifestInstallResult): Job {
        val key = key(plan)
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        val job = Job(plan)
        jobs[key] = job
        scope.launch {
            running.withLock {
                job.state = State.DOWNLOADING
                val result = try {
                    run(plan) { job.progress = it.coerceIn(0f, 1f) }
                } catch (e: Exception) {
                    ManifestInstallResult(success = false, message = "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                }
                job.message = result.message
                if (result.success) job.progress = 1f
                job.state = if (result.success) State.INSTALLED else State.FAILED
            }
        }
        return job
    }

    private fun key(plan: InstallPlan) = "${plan.target}|${plan.entry.id.lowercase(Locale.ROOT)}|${plan.variant}"
}
