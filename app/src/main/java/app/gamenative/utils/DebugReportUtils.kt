package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.agent.LogRedactor
import app.gamenative.filedetect.GameFileDetection
import com.winlator.core.GPUInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugReportUtils {

    private const val HEADER_FILE = "header.json"
    private const val LOG_FILE = "log.gz"
    private const val PERF_FILE = "perf.json"
    private const val LOGCAT_FILE = "logcat.gz"
    private const val ENV_VARS_KEY = "envVars"
    private const val EXEC_ARGS_KEY = "execArgs"

    @Volatile
    private var logcatProcess: Process? = null
    private const val LOG_HEAD_BYTES = 1L * 1024 * 1024
    private const val LOG_TAIL_BYTES = 7L * 1024 * 1024
    private const val LOG_MAX_BYTES = LOG_HEAD_BYTES + LOG_TAIL_BYTES

    fun reportsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "debug_reports")

    fun wineLogFile(context: Context, appId: String): File =
        File(context.getExternalFilesDir(null), "wine_logs/debug_run_$appId.log")

    fun headerFile(reportDir: File): File = File(reportDir, HEADER_FILE)

    fun logFile(reportDir: File): File = File(reportDir, LOG_FILE)

    fun perfFile(reportDir: File): File = File(reportDir, PERF_FILE)

    fun logcatFile(reportDir: File): File = File(reportDir, LOGCAT_FILE)

    /**
     * The report's files a user can share or save: the header (game, device, container config), the Wine
     * log, perf samples and logcat — whichever exist.
     */
    fun exportFiles(reportDir: File): List<File> =
        listOf(headerFile(reportDir), logFile(reportDir), perfFile(reportDir), logcatFile(reportDir)).filter { it.exists() }

    fun writeZip(reportDir: File, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            exportFiles(reportDir).forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun rawLogcatFile(context: Context, appId: String): File =
        File(context.getExternalFilesDir(null), "wine_logs/debug_run_$appId.logcat")

    fun startLogcatCapture(context: Context, appId: String) {
        stopLogcatCapture()
        try {
            val out = rawLogcatFile(context, appId)
            out.parentFile?.mkdirs()
            if (out.exists()) out.delete()
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
            logcatProcess = ProcessBuilder("logcat", "-v", "threadtime")
                .redirectOutput(out)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Timber.e(e, "DebugReportUtils: Failed to start logcat capture")
            logcatProcess = null
        }
    }

    fun stopLogcatCapture() {
        logcatProcess?.let {
            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }
        logcatProcess = null
    }

    fun readHeader(reportDir: File): JSONObject? = try {
        val file = headerFile(reportDir)
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Timber.e(e, "DebugReportUtils: Failed to read header from $reportDir")
        null
    }

    /** The `<appId>_<timestamp>` report directories of [appId]. */
    private fun reportDirs(context: Context, appId: String): List<File> {
        val prefix = "${appId}_"
        return reportsDir(context).listFiles().orEmpty().filter { dir ->
            dir.isDirectory && dir.name.startsWith(prefix) && dir.name.removePrefix(prefix).toLongOrNull() != null
        }
    }

    fun newestReport(context: Context, appId: String): File? =
        reportDirs(context, appId)
            .filter { logFile(it).exists() }
            .maxByOrNull { it.name.substringAfterLast('_').toLong() }

    /**
     * Deletes [appId]'s reports other than [keep]. Runs when a new report is written rather than when the
     * dialog closes, because an app the report was shared to may still be reading the files then.
     */
    private fun deleteOlderReports(context: Context, appId: String, keep: File) {
        reportDirs(context, appId).filter { it != keep }.forEach { dir ->
            if (!dir.deleteRecursively()) Timber.w("DebugReportUtils: Failed to delete old report $dir")
        }
    }

    suspend fun createPendingReport(context: Context, appId: String): File? = withContext(Dispatchers.IO) {
        var reportDir: File? = null
        val perf = PerfSampler.stop()
        stopLogcatCapture()
        try {
            val wineLog = wineLogFile(context, appId)
            if (!wineLog.exists() || wineLog.length() == 0L) {
                Timber.w("DebugReportUtils: No debug run log found for $appId")
                return@withContext null
            }

            awaitStableSize(wineLog)

            val dir = File(reportsDir(context), "${appId}_${System.currentTimeMillis()}")
            reportDir = dir
            dir.mkdirs()

            val claimedLog = File(dir, "raw.log")
            if (!wineLog.renameTo(claimedLog)) {
                val sizeBefore = wineLog.length()
                wineLog.copyTo(claimedLog, overwrite = true)
                if (wineLog.length() == sizeBefore) {
                    wineLog.delete()
                }
            }

            compressLog(claimedLog, logFile(dir))
            val header = buildHeader(context, appId)
            if (perf != null) {
                perfFile(dir).writeText(perf.perf.toString())
                header.put("perf", perf.verdict)
            }
            val rawLogcat = rawLogcatFile(context, appId)
            if (rawLogcat.exists() && rawLogcat.length() > 0L) {
                compressLog(rawLogcat, logcatFile(dir))
            }
            rawLogcat.delete()
            headerFile(dir).writeText(header.toString())
            claimedLog.delete()
            deleteOlderReports(context, appId, keep = dir)

            dir
        } catch (e: Exception) {
            Timber.e(e, "DebugReportUtils: Failed to create pending report for $appId")
            reportDir?.deleteRecursively()
            null
        }
    }

    private suspend fun awaitStableSize(file: File) {
        val start = System.currentTimeMillis()
        var lastSize = file.length()
        var lastChange = start
        while (System.currentTimeMillis() - start < 3_000) {
            delay(100)
            val size = file.length()
            if (size != lastSize) {
                lastSize = size
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChange >= 500) {
                return
            }
        }
    }

    private fun buildHeader(context: Context, appId: String): JSONObject {
        val container = ContainerUtils.getContainer(context, appId)

        val gpu = try {
            GPUInformation.getRenderer(context)
        } catch (e: Exception) {
            Timber.e(e, "DebugReportUtils: Failed to get GPU info")
            "Unknown GPU"
        }

        val avgFps = container.getSessionMetadata("avg_fps", "").toFloatOrNull()
        val sessionLengthSec = container.getSessionMetadata("session_length_sec", "").toIntOrNull()
        val totalFrames = container.getSessionMetadata("total_frames", "").toLongOrNull()

        return JSONObject().apply {
            put("gameName", ContainerUtils.resolveGameName(appId))
            put("appId", appId)
            put("deviceName", HardwareUtils.getMachineName())
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("socName", HardwareUtils.getSOCName() ?: JSONObject.NULL)
            put("gpuName", gpu)
            put("androidVersion", Build.VERSION.RELEASE)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("configs", redactedConfigs(container.containerJson))
            if (avgFps != null) put("avgFps", avgFps.toDouble()) else put("avgFps", JSONObject.NULL)
            if (sessionLengthSec != null) put("sessionLengthSec", sessionLengthSec) else put("sessionLengthSec", JSONObject.NULL)
            if (totalFrames != null) put("totalFrames", totalFrames) else put("totalFrames", JSONObject.NULL)
            runCatching { GameFileDetection.properties(container) }.getOrNull()?.forEach { (key, value) ->
                put(key, if (value is List<*>) JSONArray(value) else value)
            }
        }
    }

    /**
     * The container config for the header, which leaves the device on Share or Save: secret env values
     * ([SecretEnv]) and secret-looking launch arguments ([LogRedactor]) are masked.
     */
    internal fun redactedConfigs(containerJson: String): JSONObject = JSONObject(containerJson).apply {
        optString(ENV_VARS_KEY).takeIf { it.isNotEmpty() }?.let { put(ENV_VARS_KEY, SecretEnv.mask(it)) }
        optString(EXEC_ARGS_KEY).takeIf { it.isNotEmpty() }?.let { put(EXEC_ARGS_KEY, LogRedactor.redact(it)) }
    }

    private fun compressLog(source: File, destination: File) {
        GZIPOutputStream(destination.outputStream().buffered()).use { out ->
            val length = source.length()
            if (length <= LOG_MAX_BYTES) {
                source.inputStream().buffered().use { it.copyTo(out) }
            } else {
                RandomAccessFile(source, "r").use { raf ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = LOG_HEAD_BYTES
                    while (remaining > 0) {
                        val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.write("\n[... ${length - LOG_MAX_BYTES} bytes truncated ...]\n".toByteArray())
                    raf.seek(length - LOG_TAIL_BYTES)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}
