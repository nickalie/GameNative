package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
import java.util.Locale

/** Detects and masks secret-looking container env vars wherever a container's env leaves the device. */
object SecretEnv {
    const val MASK = "***"

    // Whole name segments only, so DXVK_..._BYPASS or ..._PASSTHROUGH stay readable.
    private val SECRET_NAME = Regex("(^|_)(TOKEN|KEY|SECRET|PASSWORD|PASS|AUTH)(_|$)")

    fun isSecret(name: String): Boolean = SECRET_NAME.containsMatchIn(name.uppercase(Locale.ROOT))

    /** The `envVars` string with every secret value replaced by [MASK], in the same serialized form. */
    fun mask(envVars: String): String {
        val env = EnvVars(envVars)
        env.toList().filter(::isSecret).forEach { env.put(it, MASK) }
        return env.toString()
    }
}
