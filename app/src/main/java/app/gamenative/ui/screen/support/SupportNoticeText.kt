package app.gamenative.ui.screen.support

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.api.SupportApi
import java.text.DateFormat
import java.util.Date

internal fun FocusRequester.requestFocusSafely(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

internal suspend fun FocusRequester.requestFocusAfterLayout() {
    if (requestFocusSafely()) return
    withFrameNanos { }
    requestFocusSafely()
}

@Composable
internal fun fairUseReasonText(hours: Int?, fallback: String? = null): String =
    when {
        hours != null -> pluralStringResource(R.plurals.support_fair_use_resets_in_hours, hours, hours)
        fallback != null -> fallback
        else -> stringResource(R.string.support_upgrade_reason_reply_cap)
    }

/** Text for a server refusal: fair-use and fix-quota limits keep their reset details, anything else is "unavailable". */
@Composable
internal fun refusalReasonText(reason: String?, resetsAt: Long? = null, message: String? = null): String {
    if (SupportApi.isFairUse(reason)) {
        return fairUseReasonText(SupportApi.FairUse(resetsAt, null).hoursLeft())
    }
    if (reason == SupportApi.REASON_FIX_QUOTA) return fixQuotaText(resetsAt, message)
    return stringResource(R.string.support_unavailable_reason)
}

/** Non-composable [refusalReasonText] for snackbars. */
internal fun refusalReasonText(context: Context, reason: String?): String =
    if (SupportApi.isFairUse(reason)) {
        SupportReportSubmitter.fairUseText(context, SupportApi.lastFairUse?.hoursLeft())
    } else {
        context.getString(R.string.support_unavailable_reason)
    }

@Composable
internal fun fixQuotaText(resetsAt: Long?, message: String? = null): String =
    message ?: if (resetsAt != null) {
        stringResource(R.string.support_fix_quota_resets, fixResetDate(resetsAt))
    } else {
        stringResource(R.string.support_fix_quota)
    }

internal fun fixResetDate(resetsAt: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(resetsAt))
