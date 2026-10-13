package app.gamenative.api

import com.winlator.container.Container

/**
 * The editable container-config boundary: which keys and values a config change may set. Used by the Agent
 * API's `containers.set`.
 */
object SupportSuggestion {
    data class Change(
        val key: String,
        val name: String?,
        val op: String?,
        val to: String?,
    ) {
        val parent: String get() = key.substringBefore('.')
        val subKey: String? get() = key.substringAfter('.', "").ifEmpty { null }
        val isEnv: Boolean get() = key == ENV_KEY
        val isUnset: Boolean get() = isEnv && op == OP_UNSET
    }

    const val ENV_KEY = "envVars"
    const val OP_SET = "set"
    const val OP_UNSET = "unset"

    val KV_PARENTS = setOf("dxwrapperConfig", "wincomponents", "graphicsDriverConfig")

    private val EXCLUDED_KEYS = setOf(
        "containerVariant", "fexcoreTSOMode", "fexcoreX87Mode", "fexcoreMultiBlock",
        ENV_KEY, "dxwrapperConfig", "wincomponents", "graphicsDriverConfig",
    )

    val TOP_LEVEL_KEYS: Set<String> = SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.keys - EXCLUDED_KEYS

    val WIN_COMPONENTS = setOf(
        "direct3d", "directsound", "directinput8", "directinput", "directmusic", "directplay",
        "directshow", "directx", "vcrun2010", "wmdecoder", "opengl",
    )

    private val BLOCKED_ENV = setOf("WINEDEBUG")

    private val ENUM_VALUES: Map<String, Set<String>> = mapOf(
        "dxwrapper" to setOf("wined3d", "dxvk", "vkd3d", "cnc-ddraw"),
        "audioDriver" to setOf("alsa", "pulseaudio", "disabled"),
        "emulator" to setOf("FEXCore", "Box64"),
        "steamType" to setOf(
            Container.STEAM_TYPE_NORMAL, Container.STEAM_TYPE_LIGHT,
            Container.STEAM_TYPE_ULTRALIGHT, Container.STEAM_TYPE_HEADLESS,
        ),
        "suspendPolicy" to setOf(
            Container.SUSPEND_POLICY_AUTO, Container.SUSPEND_POLICY_NEVER, Container.SUSPEND_POLICY_MANUAL,
        ),
        "startupSelection" to setOf("0", "1", "2"),
    )

    private val SUB_KEY = Regex("^[A-Za-z0-9_]{1,40}$")
    private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    private const val MAX_VALUE = 512
    private const val MAX_KV_VALUE = 128

    private fun plain(value: String, max: Int): Boolean =
        value.length <= max && value.none { it == '\n' || it == '\r' || it.code < 0x20 }

    fun isValid(change: Change): Boolean {
        val to = change.to
        if (change.isEnv) {
            val name = change.name ?: return false
            if (!ENV_NAME.matches(name) || name.uppercase() in BLOCKED_ENV) return false
            return when (change.op) {
                OP_UNSET -> true
                OP_SET -> to != null && plain(to, MAX_VALUE)
                else -> false
            }
        }
        if (to == null) return false
        val subKey = change.subKey
        if (subKey != null) {
            if (change.parent !in KV_PARENTS || !SUB_KEY.matches(subKey)) return false
            if (!plain(to, MAX_KV_VALUE) || to.contains(',') || to.contains('=')) return false
            if (change.parent == "wincomponents") return subKey in WIN_COMPONENTS && (to == "0" || to == "1")
            return true
        }
        if (change.key !in TOP_LEVEL_KEYS) return false
        ENUM_VALUES[change.key]?.let { return to in it }
        return when (SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS[change.key]) {
            SuggestionConfigKeys.ConfigValueType.BOOLEAN -> to.lowercase() in setOf("true", "false", "1", "0")
            SuggestionConfigKeys.ConfigValueType.INT -> to.toIntOrNull() != null
            SuggestionConfigKeys.ConfigValueType.STRING -> plain(to, MAX_VALUE)
            null -> false
        }
    }
}
