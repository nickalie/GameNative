package app.gamenative.ui.screen.support

import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SupportSuggestion
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import org.json.JSONObject

/** Turns validated [SupportSuggestion.Change]s into container-config updates (used by `containers.set`). */
object SupportSuggestionApplier {

    internal val COMPONENT_KEYS = setOf(
        "wineVersion", "dxwrapper", "dxwrapperConfig", "box64Version", "box64Preset", "emulator",
        "fexcoreVersion", "fexcorePreset", "graphicsDriver", "graphicsDriverVersion", "graphicsDriverConfig",
    )

    data class Edit(val key: String, val name: String?, val value: String?)

    private fun kvRemove(data: String, key: String): String {
        val parts = mutableListOf<String>()
        for (pair in KeyValueSet(data)) {
            if (pair[0] != key) parts += "${pair[0]}=${pair[1]}"
        }
        return parts.joinToString(",")
    }

    fun editFor(change: SupportSuggestion.Change): Edit =
        Edit(change.key, change.name, if (change.isUnset) null else change.to)

    internal fun updatesFor(live: ContainerData, edits: List<Edit>): Map<String, Any?> {
        val updates = linkedMapOf<String, Any?>()
        var env: EnvVars? = null
        val kv = linkedMapOf<String, String>()
        for (edit in edits) {
            when {
                edit.key == SupportSuggestion.ENV_KEY -> {
                    val name = edit.name ?: continue
                    val vars = env ?: EnvVars(live.envVars).also { env = it }
                    if (edit.value == null) vars.remove(name) else vars.put(name, edit.value)
                }
                edit.key.contains('.') -> {
                    val parent = edit.key.substringBefore('.')
                    val subKey = edit.key.substringAfter('.')
                    val current = kv[parent] ?: SuggestionConfigKeys.configValueOf(live, parent).orEmpty()
                    kv[parent] = if (edit.value == null) {
                        kvRemove(current, subKey)
                    } else {
                        KeyValueSet(current).put(subKey, edit.value).toString()
                    }
                }
                else -> {
                    val type = SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS[edit.key] ?: continue
                    SuggestionConfigKeys.coerceConfigValue(type, edit.value)?.let { updates[edit.key] = it }
                }
            }
        }
        env?.let { updates[SupportSuggestion.ENV_KEY] = it.toString() }
        updates.putAll(kv)
        return updates
    }

    internal fun componentJson(data: ContainerData): JSONObject = JSONObject().apply {
        put("containerVariant", data.containerVariant)
        put("wineVersion", data.wineVersion)
        put("dxwrapper", data.dxwrapper)
        put("dxwrapperConfig", data.dxwrapperConfig)
        put("box64Version", data.box64Version)
        put("box64Preset", data.box64Preset)
        put("emulator", data.emulator)
        put("fexcoreVersion", data.fexcoreVersion)
        put("fexcorePreset", data.fexcorePreset)
        put("graphicsDriver", data.graphicsDriver)
        put("graphicsDriverVersion", data.graphicsDriverVersion)
        put("graphicsDriverConfig", data.graphicsDriverConfig)
    }
}
