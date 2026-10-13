package app.gamenative.agent

import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SupportSuggestion
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `containers.set` request codec. Every requested change goes through the editable-key boundary
 * (`SupportSuggestion.isValid`), is folded into full values by `SupportSuggestionApplier.updatesFor`
 * and applied with `ContainerUtils.applyBestConfigMapToContainerData`.
 */
object ContainerEdits {

    const val WARNING_WINE_VERSION_CHANGED = "wine_version_changed"
    const val WARNING_NEEDS_UNPACKING = "needs_unpacking"

    const val REASON_READ_ONLY = "read_only"
    const val REASON_NOT_EDITABLE = "not_editable"
    const val REASON_UNKNOWN_KEY = "unknown_key"
    const val REASON_BLOCKED_ENV = "blocked_env"
    const val REASON_INVALID_NAME = "invalid_name"
    const val REASON_INVALID_VALUE = "invalid_value"
    const val REASON_DUPLICATE = "duplicate"

    /** Upper bound on changes per request; the request size cap keeps each one small. */
    const val MAX_CHANGES = 64

    private const val ENV_SET = "set"
    private const val ENV_UNSET = "unset"

    // WINEDEBUG is owned by the launch extras (`agent_debug` / `wine_debug_channels`).
    private val BLOCKED_ENV = setOf("WINEDEBUG")
    private val READ_ONLY_KEYS = setOf("containerVariant")

    // Same shape SupportSuggestion.isValid accepts; only used to explain a rejection.
    private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    /** The changes requested by `{values?: {key: value}, env?: {set?: {NAME: value}, unset?: [NAME]}}`. */
    fun parse(args: JsonObject): List<SupportSuggestion.Change> {
        val changes = mutableListOf<SupportSuggestion.Change>()
        val rejected = mutableListOf<Rejection>()

        objectArg(args, "values")?.forEach { (key, value) ->
            val to = primitiveText(value)
            when {
                // The whole env string is not editable; single variables go through `env`.
                key == SupportSuggestion.ENV_KEY -> rejected += Rejection(key, null, REASON_NOT_EDITABLE)
                to == null -> rejected += Rejection(key, null, REASON_INVALID_VALUE)
                else -> changes += SupportSuggestion.Change(key = key, name = null, op = null, to = to)
            }
        }

        objectArg(args, "env")?.let { env ->
            val unknown = env.keys - setOf(ENV_SET, ENV_UNSET)
            if (unknown.isNotEmpty()) throw badArgs("env only supports '$ENV_SET' and '$ENV_UNSET'")
            objectArg(env, ENV_SET, "env.$ENV_SET")?.forEach { (name, value) ->
                val to = primitiveText(value)
                if (to == null) {
                    rejected += Rejection(SupportSuggestion.ENV_KEY, name, REASON_INVALID_VALUE)
                } else {
                    changes += envChange(name, SupportSuggestion.OP_SET, to)
                }
            }
            unsetNames(env).forEach { changes += envChange(it, SupportSuggestion.OP_UNSET, null) }
        }

        if (changes.size + rejected.size > MAX_CHANGES) throw badArgs("at most $MAX_CHANGES changes per request")
        if (changes.isEmpty() && rejected.isEmpty()) throw badArgs("nothing to change; pass values and/or env")

        val envNames = changes.filter { it.isEnv }.groupBy { it.name.orEmpty().uppercase(Locale.ROOT) }
        changes.forEach { change ->
            val duplicate = change.isEnv && envNames[change.name.orEmpty().uppercase(Locale.ROOT)].orEmpty().size > 1
            when {
                duplicate -> rejected += Rejection(change.key, change.name, REASON_DUPLICATE)
                !SupportSuggestion.isValid(change) -> rejected += Rejection(change.key, change.name, reasonFor(change))
            }
        }
        if (rejected.isNotEmpty()) {
            throw AgentException(
                AgentErrorCode.BAD_ARGS,
                "${rejected.size} change(s) rejected; see details.rejected",
                buildJsonObject { put("rejected", rejectedJson(rejected)) },
            )
        }
        return changes
    }

    /** [live] with every change applied. */
    fun apply(live: ContainerData, changes: List<SupportSuggestion.Change>): ContainerData {
        val updates = SupportSuggestionApplier.updatesFor(live, changes.map { SupportSuggestionApplier.editFor(it) })
        return ContainerUtils.applyBestConfigMapToContainerData(live, updates)
    }

    /**
     * Applicable keys whose value differs between [before] and [after]. Key/value configs and env vars
     * are reported per entry (`dxwrapperConfig.version`, `envVars.DXVK_HUD`).
     */
    fun changedFields(before: ContainerData, after: ContainerData): List<String> =
        SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.keys.flatMap { key ->
            val old = SuggestionConfigKeys.configValueOf(before, key)
            val new = SuggestionConfigKeys.configValueOf(after, key)
            if (old == new) return@flatMap emptyList()
            val entries = when (key) {
                SupportSuggestion.ENV_KEY -> changedEntries(envMap(old), envMap(new))
                in SupportSuggestion.KV_PARENTS -> changedEntries(kvMap(old), kvMap(new))
                else -> emptyList()
            }
            // A reordered key/value string differs without any entry changing; report the whole key then.
            if (entries.isEmpty()) listOf(key) else entries.map { "$key.$it" }
        }

    /** Side effects of the change that `containers.restore` cannot undo or that cost time on the next launch. */
    fun warnings(before: ContainerData, after: ContainerData): List<String> = buildList {
        if (before.wineVersion != after.wineVersion) add(WARNING_WINE_VERSION_CHANGED)
        // Mirrors the conditions under which ContainerUtils.applyToContainer flags the container for unpacking.
        val needsUnpacking = (before.executablePath != after.executablePath && before.executablePath.isNotEmpty()) ||
            before.launchBionicSteam != after.launchBionicSteam ||
            before.launchRealSteam != after.launchRealSteam ||
            (!before.unpackFiles && after.unpackFiles)
        if (needsUnpacking) add(WARNING_NEEDS_UNPACKING)
    }

    private data class Rejection(val key: String, val name: String?, val reason: String)

    private fun rejectedJson(rejected: List<Rejection>): JsonArray = buildJsonArray {
        rejected.forEach { rejection ->
            addJsonObject {
                put("key", rejection.key)
                rejection.name?.let { put("name", it) }
                put("reason", rejection.reason)
            }
        }
    }

    private fun reasonFor(change: SupportSuggestion.Change): String = when {
        change.isEnv -> when {
            change.name.orEmpty().uppercase(Locale.ROOT) in BLOCKED_ENV -> REASON_BLOCKED_ENV
            change.name == null || !ENV_NAME.matches(change.name) -> REASON_INVALID_NAME
            else -> REASON_INVALID_VALUE
        }
        change.key in READ_ONLY_KEYS -> REASON_READ_ONLY
        change.subKey != null -> if (change.parent in SupportSuggestion.KV_PARENTS) REASON_INVALID_VALUE else REASON_UNKNOWN_KEY
        change.key !in SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS -> REASON_UNKNOWN_KEY
        change.key !in SupportSuggestion.TOP_LEVEL_KEYS -> REASON_NOT_EDITABLE
        else -> REASON_INVALID_VALUE
    }

    private fun envChange(name: String, op: String, to: String?) =
        SupportSuggestion.Change(key = SupportSuggestion.ENV_KEY, name = name, op = op, to = to)

    private fun unsetNames(env: JsonObject): List<String> {
        val raw = env[ENV_UNSET]
        if (raw == null || raw is JsonNull) return emptyList()
        val array = raw as? JsonArray ?: throw badArgs("env.$ENV_UNSET must be an array of names")
        return array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw badArgs("env.$ENV_UNSET must be an array of names")
        }
    }

    private fun objectArg(args: JsonObject, name: String, label: String = name): JsonObject? {
        val raw = args[name]
        if (raw == null || raw is JsonNull) return null
        return raw as? JsonObject ?: throw badArgs("$label must be an object")
    }

    /** Strings, numbers and booleans are accepted as text; `null`, arrays and objects are not values. */
    private fun primitiveText(value: JsonElement): String? =
        (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun badArgs(message: String) = AgentException(AgentErrorCode.BAD_ARGS, message)

    private fun envMap(envVars: String?): Map<String, String> {
        val env = EnvVars(envVars.orEmpty())
        return buildMap { env.forEach { name -> put(name, env.get(name)) } }
    }

    private fun kvMap(value: String?): Map<String, String> = buildMap {
        for (pair in KeyValueSet(value.orEmpty())) put(pair[0], pair[1])
    }

    private fun changedEntries(old: Map<String, String>, new: Map<String, String>): List<String> =
        (old.keys + new.keys).filter { old[it] != new[it] }
}
