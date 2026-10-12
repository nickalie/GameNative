package app.gamenative.agent.commands

import android.content.Context
import app.gamenative.agent.AgentDispatcher
import app.gamenative.agent.AgentErrorCode
import app.gamenative.agent.AgentException
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SuggestionConfigKeys.ConfigValueType
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `containers.*` commands. The readable and editable keys are the AI-suggestion boundary
 * (`SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS`); `containerVariant` is reported but read-only.
 */
class ContainerCommands(private val loadContainer: (appId: String) -> ContainerData?) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(GET) { get(it) }
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

    companion object {
        const val GET = "containers.get"

        const val ENV_KEY = "envVars"
        const val MASK = "***"
        val READ_ONLY_KEYS = listOf("containerVariant")

        // Whole name segments only, so DXVK_..._BYPASS or ..._PASSTHROUGH stay readable.
        private val SECRET_ENV = Regex("(^|_)(TOKEN|KEY|SECRET|PASSWORD|PASS|AUTH)(_|$)")

        fun isSecretEnv(name: String): Boolean = SECRET_ENV.containsMatchIn(name.uppercase(Locale.ROOT))

        fun maskedEnv(envVars: String): JsonObject {
            val env = EnvVars(envVars)
            return buildJsonObject {
                env.forEach { name -> put(name, if (isSecretEnv(name)) MASK else env.get(name)) }
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
