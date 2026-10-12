package app.gamenative.agent

/**
 * Strips credentials from log lines before they enter the agent log buffer. The patterns are
 * deliberately broad: a false positive costs a few characters of log, a false negative leaks a token.
 */
object LogRedactor {
    const val REDACTED = "[REDACTED]"

    private const val SECRET_NAME = "[A-Za-z0-9_.-]*(?:password|passwd|token|secret|api_?key|epicovt)[A-Za-z0-9_.-]*"

    private val rules: List<Pair<Regex, String>> = listOf(
        // Command-line style: -AUTH_PASSWORD=..., --access-token=..., token=..., quoted or not.
        Regex("(?i)(-{0,2}$SECRET_NAME=)(\"[^\"]*\"|'[^']*'|[^\\s&\"',;]+)") to "$1$REDACTED",
        // JSON style: "refresh_token": "...".
        Regex("(?i)(\"$SECRET_NAME\"\\s*:\\s*)\"[^\"]*\"") to "$1\"$REDACTED\"",
        // HTTP headers: Authorization: Bearer xxx / Basic xxx.
        Regex("(?i)(authorization\\s*[:=]\\s*(?:(?:bearer|basic)\\s+)?)\\S+") to "$1$REDACTED",
        // Bare bearer tokens.
        Regex("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}") to "$1$REDACTED",
    )

    fun redact(line: String): String = rules.fold(line) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
