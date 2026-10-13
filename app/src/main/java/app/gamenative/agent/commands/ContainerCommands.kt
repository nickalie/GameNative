package app.gamenative.agent.commands

import android.content.Context
import app.gamenative.agent.AgentDispatcher
import app.gamenative.agent.AgentErrorCode
import app.gamenative.agent.AgentException
import app.gamenative.agent.AgentSessionTracker
import app.gamenative.agent.ContainerBackups
import app.gamenative.agent.ContainerEdits
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SuggestionConfigKeys.ConfigValueType
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.SecretEnv
import app.gamenative.utils.SessionReport
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A component the requested config needs but the device does not have. */
data class MissingComponent(val name: String, val downloadable: Boolean, val type: String? = null)

/**
 * Outcome of validating a config's components. [rejected] means the validator refused the config
 * outright (e.g. an empty `wineVersion`), so nothing was checked. [graphicsDriverConfig] is the
 * config with the driver alias rewritten to the id the runtime resolves, `null` when unknown.
 */
data class ComponentCheck(
    val missing: List<MissingComponent> = emptyList(),
    val rejected: Boolean = false,
    val graphicsDriverConfig: String? = null,
)

/**
 * App state the write commands need, injected so tests run against a temp container without
 * PluviaApp. [ContainerWriteDeps.forApp] is the production wiring.
 */
class ContainerWriteDeps(
    val openContainer: (appId: String) -> Container?,
    val readContainer: (Container) -> ContainerData,
    val writeContainer: (container: Container, data: ContainerData, source: String) -> Unit,
    val gpuCardIds: () -> Set<Int>,
    val checkComponents: (ContainerData) -> ComponentCheck,
    val isSessionActive: (appId: String) -> Boolean,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val MATCH_TYPE = "exact_gpu_match"

        fun forApp(context: Context, tracker: AgentSessionTracker) = ContainerWriteDeps(
            openContainer = { appId ->
                if (ContainerUtils.hasContainer(context, appId)) ContainerUtils.getContainer(context, appId) else null
            },
            readContainer = ContainerUtils::toContainerData,
            writeContainer = { container, data, source -> write(context, container, data, source) },
            gpuCardIds = { ContainerUtils.getGPUCards(context).keys },
            checkComponents = { checkComponents(context, it) },
            isSessionActive = tracker::isActive,
        )

        /**
         * Persists [data] the way the config dialog does, but fails instead of
         * reporting success when the `.container` file could not be written.
         */
        fun write(context: Context, container: Container, data: ContainerData, source: String) {
            ContainerUtils.applyToContainer(context, container, data, saveToDisk = true)
            if (!container.saveDataChecked()) {
                throw AgentException(
                    AgentErrorCode.INTERNAL,
                    "could not save the container config; it may be partly applied, roll back with ${ContainerCommands.RESTORE}",
                )
            }
            SessionReport.markConfigApplied(container, source)
        }

        /**
         * Downloadable-but-not-installed manifest entries plus versions nobody provides. Never downloads
         * anything; may refresh a stale manifest cache over the network (documented).
         */
        fun checkComponents(context: Context, data: ContainerData): ComponentCheck = runBlocking {
            val configJson = Json.parseToJsonElement(SupportSuggestionApplier.componentJson(data).toString()).jsonObject
            val downloadable = BestConfigService.resolveMissingManifestInstallRequests(
                context = context,
                configJson = configJson,
                matchType = MATCH_TYPE,
                preserveConfigValues = true,
            ).map { request ->
                val type = if (request.isDriver) "driver" else request.contentType?.name?.lowercase(Locale.ROOT)
                MissingComponent(request.entry.id, downloadable = true, type = type)
            }
            val parsed = BestConfigService.parseConfigResult(
                context = context,
                configJson = configJson,
                matchType = MATCH_TYPE,
                applyKnownConfig = true,
                preserveConfigValues = true,
            )
            val unknown = parsed.missingComponents.map { MissingComponent(it, downloadable = false) }
            ComponentCheck(
                missing = (downloadable + unknown).distinctBy { it.name },
                // An empty config without missing components is the parser refusing the config before
                // it validated anything (empty wineVersion/dxwrapper, glibc on modern, parse error).
                rejected = parsed.config.isEmpty() && parsed.missingComponents.isEmpty(),
                graphicsDriverConfig = parsed.config["graphicsDriverConfig"] as? String,
            )
        }
    }
}

/**
 * `containers.*` commands. The readable and editable keys are the editable config boundary
 * (`SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS`); `containerVariant` is reported but read-only.
 * Writes are persistent and always preceded by a backup that `containers.restore` can roll back to.
 */
class ContainerCommands(
    private val writes: ContainerWriteDeps? = null,
    private val loadContainer: (appId: String) -> ContainerData?,
) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(GET) { get(it) }
        if (writes != null) {
            dispatcher.register(SET) { set(it) }
            dispatcher.register(RESTORE) { restore(it) }
            dispatcher.register(BACKUPS) { backups(it) }
        }
    }

    /**
     * `containers.get {app_id}` → `{app_id, values, env, read_only}`. `values` holds every applicable
     * key except `envVars`, which is returned parsed as `env` with secret-looking values masked.
     */
    fun get(args: JsonObject): JsonElement {
        val appId = CommandArgs.requireAppId(args)
        val data = loadContainer(appId) ?: throw noContainer(appId)
        return buildJsonObject {
            put("app_id", appId)
            put(
                "values",
                buildJsonObject {
                    SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.forEach { (key, type) ->
                        if (key == ENV_KEY) return@forEach
                        put(key, typed(type, SuggestionConfigKeys.configValueOf(data, key)))
                    }
                },
            )
            put("env", maskedEnv(data.envVars))
            put("read_only", buildJsonArray { READ_ONLY_KEYS.forEach { add(it) } })
        }
    }

    /**
     * `containers.set {app_id, values?, env?: {set?, unset?}}` → `{app_id, backup_id, changed_fields, warnings}`.
     * Nothing is written (and no backup taken) when the request changes nothing.
     */
    fun set(args: JsonObject): JsonElement {
        val appId = CommandArgs.requireAppId(args)
        val deps = requireWrites()
        synchronized(WRITE_LOCK) {
            requireNoSession(deps, appId)
            val changes = ContainerEdits.parse(args)
            val container = deps.openContainer(appId) ?: throw noContainer(appId)
            val live = deps.readContainer(container)
            requireKnownGpu(deps, live)
            val edited = ContainerEdits.apply(live, changes)
            val editedFields = ContainerEdits.changedFields(live, edited)
            if (editedFields.isEmpty()) return writeResult(appId, null, editedFields, emptyList())
            val updated = if (editedFields.any { it.substringBefore('.') in SupportSuggestionApplier.COMPONENT_KEYS }) {
                checkedComponents(deps, edited)
            } else {
                edited
            }
            // The driver alias may canonicalize back to the live value, so recompute what changes.
            val changed = ContainerEdits.changedFields(live, updated)
            if (changed.isEmpty()) return writeResult(appId, null, changed, emptyList())
            val backup = ContainerBackups(container.rootDir, deps.clock).create(live)
            deps.writeContainer(container, updated, SOURCE_SET)
            return writeResult(appId, backup.id, changed, ContainerEdits.warnings(live, updated))
        }
    }

    /**
     * `containers.restore {app_id, backup_id? | baseline: true}` → `{app_id, restored_from, backup_id,
     * changed_fields, warnings}`. Only keys that differ from the backup are written, after a backup of
     * the current config.
     */
    fun restore(args: JsonObject): JsonElement {
        val appId = CommandArgs.requireAppId(args)
        val deps = requireWrites()
        val backupId = CommandArgs.optString(args, "backup_id")?.trim()
        val baseline = CommandArgs.optBoolean(args, "baseline") ?: false
        if ((backupId != null) == baseline) throw CommandArgs.badArgs("pass either backup_id or baseline=true")
        if (backupId != null && !ContainerBackups.isValidId(backupId)) {
            throw CommandArgs.badArgs("backup_id must be an id listed by $BACKUPS")
        }
        synchronized(WRITE_LOCK) {
            requireNoSession(deps, appId)
            val container = deps.openContainer(appId) ?: throw noContainer(appId)
            val backups = ContainerBackups(container.rootDir, deps.clock)
            val source = (if (baseline) backups.baseline() else backups.find(backupId!!))
                ?: throw AgentException(
                    AgentErrorCode.NO_BACKUP,
                    if (baseline) "$appId has no baseline backup" else "$appId has no backup $backupId",
                )
            val live = deps.readContainer(container)
            requireKnownGpu(deps, live)
            val updates = ContainerBackups.restoreUpdates(live, source)
            if (updates.isEmpty()) return restoreResult(writeResult(appId, null, emptyList(), emptyList()), source)
            val updated = ContainerUtils.applyBestConfigMapToContainerData(live, updates)
            val created = backups.create(live)
            deps.writeContainer(container, updated, SOURCE_RESTORE)
            val changed = ContainerEdits.changedFields(live, updated)
            return restoreResult(writeResult(appId, created.id, changed, ContainerEdits.warnings(live, updated)), source)
        }
    }

    /** `containers.backups {app_id}` → `[{backup_id, created_at, baseline}]`, oldest first. */
    fun backups(args: JsonObject): JsonElement {
        val appId = CommandArgs.requireAppId(args)
        val deps = requireWrites()
        val container = deps.openContainer(appId) ?: throw noContainer(appId)
        return buildJsonArray {
            ContainerBackups(container.rootDir, deps.clock).list().forEach { backup ->
                addJsonObject {
                    put("backup_id", backup.id)
                    put("created_at", backup.createdAt)
                    put("baseline", backup.baseline)
                }
            }
        }
    }

    private fun requireWrites(): ContainerWriteDeps =
        writes ?: throw AgentException(AgentErrorCode.UNKNOWN_COMMAND, "container writes are not available")

    private fun requireNoSession(deps: ContainerWriteDeps, appId: String) {
        if (deps.isSessionActive(appId)) {
            throw AgentException(AgentErrorCode.SESSION_ACTIVE, "$appId is running; stop the session before changing its config")
        }
    }

    // ContainerUtils.applyToContainer looks the current GPU id up with `!!`; refuse instead of crashing.
    private fun requireKnownGpu(deps: ContainerWriteDeps, live: ContainerData) {
        if (live.videoPciDeviceID !in deps.gpuCardIds()) {
            throw AgentException(
                AgentErrorCode.CONTAINER_INVALID,
                "the container's videoPciDeviceID ${live.videoPciDeviceID} is unknown; fix it in the app's container settings",
            )
        }
    }

    /**
     * Fails unless every component of [data] is available; returns [data] with the driver version
     * rewritten to the id the runtime resolves (a manifest name like `Turnip_Gen8_V34` is otherwise
     * persisted as-is and the next launch falls back to the System driver).
     */
    private fun checkedComponents(deps: ContainerWriteDeps, data: ContainerData): ContainerData {
        val check = deps.checkComponents(data)
        requireComponents(check.missing)
        if (check.rejected) {
            throw CommandArgs.badArgs(
                "the component validator rejected this config; containerVariant, wineVersion, dxwrapper " +
                    "and dxwrapperConfig must be set to supported values",
            )
        }
        val driver = check.graphicsDriverConfig
        return if (driver != null && driver != data.graphicsDriverConfig) data.copy(graphicsDriverConfig = driver) else data
    }

    private fun requireComponents(missing: List<MissingComponent>) {
        if (missing.isEmpty()) return
        throw AgentException(
            AgentErrorCode.MISSING_COMPONENTS,
            "missing components: ${missing.joinToString { it.name }}; install them from the app first",
            buildJsonArray {
                missing.forEach { component ->
                    addJsonObject {
                        put("name", component.name)
                        component.type?.let { put("type", it) }
                        put("downloadable", component.downloadable)
                    }
                }
            },
        )
    }

    private fun writeResult(appId: String, backupId: String?, changed: List<String>, warnings: List<String>) = buildJsonObject {
        put("app_id", appId)
        put("backup_id", backupId)
        put("changed_fields", buildJsonArray { changed.forEach { add(it) } })
        put("warnings", buildJsonArray { warnings.forEach { add(it) } })
    }

    private fun restoreResult(result: JsonObject, source: ContainerBackups.Backup) =
        JsonObject(result + ("restored_from" to JsonPrimitive(source.id)))

    companion object {
        const val GET = "containers.get"
        const val SET = "containers.set"
        const val RESTORE = "containers.restore"
        const val BACKUPS = "containers.backups"

        const val SOURCE_SET = "agent_api"
        const val SOURCE_RESTORE = "agent_api_restored"

        const val ENV_KEY = "envVars"
        const val MASK = SecretEnv.MASK
        val READ_ONLY_KEYS = listOf("containerVariant")

        // One writer at a time: backups and the container file must not interleave between binder threads.
        private val WRITE_LOCK = Any()

        fun maskedEnv(envVars: String): JsonObject {
            val env = EnvVars(envVars)
            return buildJsonObject {
                env.forEach { name -> put(name, if (SecretEnv.isSecret(name)) MASK else env.get(name)) }
            }
        }

        fun noContainer(appId: String) = AgentException(
            AgentErrorCode.NO_CONTAINER,
            "$appId has no container yet; launch it once from the app first",
        )

        private fun typed(type: ConfigValueType, value: String?): JsonElement {
            if (value == null) return JsonNull
            return when (type) {
                ConfigValueType.BOOLEAN -> value.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                ConfigValueType.INT -> value.toIntOrNull()?.let { JsonPrimitive(it) }
                ConfigValueType.STRING -> null
            } ?: JsonPrimitive(value)
        }

        /** The container's current config, or `null` when the game has never been launched. */
        fun load(context: Context, appId: String): ContainerData? {
            if (!ContainerUtils.hasContainer(context, appId)) return null
            return ContainerUtils.toContainerData(ContainerUtils.getContainer(context, appId))
        }
    }
}
