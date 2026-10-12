package app.gamenative.agent

/**
 * Strips credentials from log lines before they enter the agent log buffer. The patterns are
 * deliberately broad: a false positive costs a few characters of log, a false negative leaks a token.
 */
object LogRedactor {
    const val REDACTED = "[REDACTED]"

    // Name parts are bounded so a long run of key-like characters cannot make a match attempt
    // backtrack over the whole run.
    private const val NAME_PART = "[A-Za-z0-9_.-]{0,64}"
    private const val SECRET_NAME = "$NAME_PART(?:password|passwd|token|secret|api_?key|epicovt)$NAME_PART"

    // Quoted values with backslash escapes, written as unrolled loops so the regex engine does not
    // recurse once per character.
    private const val DOUBLE_QUOTED = "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\""
    private const val SINGLE_QUOTED = "'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+'"

    private val rules: List<Pair<Regex, String>> = listOf(
        // Command-line style: -AUTH_PASSWORD=..., --access-token=..., token=..., quoted or not. A match
        // starts only at the beginning of a run of key characters, which keeps the scan linear. The
        // unquoted value runs to the next whitespace or `&` (query-string separator), so it also takes
        // punctuation that belongs to the secret.
        Regex("(?i)((?<![A-Za-z0-9_.-])-{0,2}$SECRET_NAME=)($DOUBLE_QUOTED|$SINGLE_QUOTED|[^\\s&]+)") to "$1$REDACTED",
        // JSON style: "refresh_token": "...".
        Regex("(?i)(\"$SECRET_NAME\"\\s*:\\s*)$DOUBLE_QUOTED") to "$1\"$REDACTED\"",
        // HTTP headers: Authorization: Bearer xxx / Basic xxx.
        Regex("(?i)(authorization\\s*[:=]\\s*(?:(?:bearer|basic)\\s+)?)\\S+") to "$1$REDACTED",
        // Bare bearer tokens.
        Regex("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}") to "$1$REDACTED",
    )

    fun redact(line: String): String = rules.fold(line) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
