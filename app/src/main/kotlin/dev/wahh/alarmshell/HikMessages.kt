package dev.wahh.alarmshell

import dev.wahh.alarmshell.core.HikException
import dev.wahh.alarmshell.core.HikFailure

/**
 * Fixed, non-sensitive explanations. The cloud response body and exception messages are never
 * shown: both can carry account or device identifiers. A structural [HikException.detail]
 * (result code, payload shape) is appended so phase 1 stays diagnosable on the phone.
 */
internal fun describeHikFailure(error: Throwable): String {
    val failure = (error as? HikException)?.failure
    val text =
        when (failure) {
            HikFailure.INVALID_CREDENTIALS -> "Cloud rejected the account or password."
            HikFailure.CAPTCHA_REQUIRED -> "Cloud requires a captcha. Log in once in the official Hik-Connect app, then retry, or solve the image below."
            HikFailure.SESSION_EXPIRED -> "Session expired. Log in again."
            HikFailure.MALFORMED_RESPONSE -> "Unexpected cloud response. The API may have changed."
            HikFailure.HTTP_ERROR -> "Cloud refused the request."
            HikFailure.NETWORK_ERROR -> "Cloud unreachable. Check the connection and retry."
            null -> "Unexpected error (${error.javaClass.simpleName})."
        }
    val detail = (error as? HikException)?.detail
    return if (detail.isNullOrEmpty()) text else "$text [$detail]"
}
