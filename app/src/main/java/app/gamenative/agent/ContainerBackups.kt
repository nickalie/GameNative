package app.gamenative.agent

import app.gamenative.api.SuggestionConfigKeys
import com.winlator.container.ContainerData
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Config snapshots taken before every agent write, stored under the container's root dir so they are
 * deleted together with the container. The first snapshot is pinned as the `baseline` (the config
 * before the agent touched the game) and never rotated; the others keep the newest [KEEP].
 */
class ContainerBackups(rootDir: File, private val clock: () -> Long) {

    data class Backup(
        val id: String,
        val createdAt: Long,
        val baseline: Boolean,
        val values: Map<String, String>,
    )

    private val dir = File(rootDir, DIR)

    /** Snapshots [data]; the very first snapshot of a container becomes the baseline. */
    fun create(data: ContainerData): Backup {
        dir.mkdirs()
        val existing = list()
        val now = clock()
        // Ids only grow, even when the wall clock moves back: rotation treats the lowest ids as oldest.
        var stamp = maxOf(now, (existing.maxOfOrNull { it.id.toLong() } ?: -1L) + 1)
        while (fileFor(stamp.toString()).exists()) stamp++
        val backup = Backup(
            id = stamp.toString(),
            createdAt = now,
            baseline = existing.none { it.baseline },
            values = snapshot(data),
        )
        write(backup)
        rotate(existing + backup)
        return backup
    }

    /** All readable backups, oldest first. */
    fun list(): List<Backup> =
        dir.listFiles { file -> file.isFile && FILE_NAME.matches(file.name) }
            .orEmpty()
            .mapNotNull { read(it) }
            .sortedBy { it.id.toLong() }

    fun find(id: String): Backup? = if (ID.matches(id)) list().firstOrNull { it.id == id } else null

    fun baseline(): Backup? = list().firstOrNull { it.baseline }

    private fun rotate(all: List<Backup>) {
        all.filterNot { it.baseline }
            .sortedBy { it.id.toLong() }
            .dropLast(KEEP)
            .forEach { fileFor(it.id).delete() }
    }

    private fun write(backup: Backup) {
        val json = buildJsonObject {
            put("backup_id", backup.id)
            put("created_at", backup.createdAt)
            put("baseline", backup.baseline)
            put("values", JsonObject(backup.values.mapValues { JsonPrimitive(it.value) }))
        }
        val target = fileFor(backup.id)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("could not write backup ${backup.id}")
        }
    }

    private fun read(file: File): Backup? = try {
        val json = Json.parseToJsonElement(file.readText()) as JsonObject
        val values = (json["values"] as JsonObject).mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
        Backup(
            id = file.name.removeSuffix(SUFFIX),
            createdAt = (json["created_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            baseline = (json["baseline"] as? JsonPrimitive)?.booleanOrNull ?: false,
            values = values,
        )
    } catch (e: Exception) {
        Timber.w(e, "Agent: unreadable container backup ${file.name}")
        null
    }

    private fun fileFor(id: String) = File(dir, "$id$SUFFIX")

    companion object {
        const val DIR = ".gamenative/agent_backups"
        const val KEEP = 10

        private const val SUFFIX = ".json"
        private val ID = Regex("^[0-9]{1,18}$")
        private val FILE_NAME = Regex("^[0-9]{1,18}\\.json$")

        /** `containerVariant` is changed only from the UI, so it is neither captured nor restored. */
        val SNAPSHOT_KEYS: Set<String> = SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.keys - "containerVariant"

        fun isValidId(id: String): Boolean = ID.matches(id)

        fun snapshot(data: ContainerData): Map<String, String> =
            SNAPSHOT_KEYS.mapNotNull { key -> SuggestionConfigKeys.configValueOf(data, key)?.let { key to it } }.toMap()

        /**
         * Typed updates that bring [current] back to [backup], only for keys whose value differs. Full
         * values (`envVars`, `dxwrapperConfig`, ...) are written as-is: they were produced by the app.
         */
        fun restoreUpdates(current: ContainerData, backup: Backup): Map<String, Any?> = buildMap {
            backup.values.forEach { (key, value) ->
                if (key !in SNAPSHOT_KEYS || SuggestionConfigKeys.configValueOf(current, key) == value) return@forEach
                val type = SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.getValue(key)
                SuggestionConfigKeys.coerceConfigValue(type, value)?.let { put(key, it) }
            }
        }
    }
}
