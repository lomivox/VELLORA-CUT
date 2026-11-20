package com.vellora.cut.autogen.network

import org.json.JSONObject

/** Turns a failed YouTube API reply into one short, exact message for the screen. */
object YouTubeErrors {

    const val QUOTA_MESSAGE = "YouTube daily quota used up — resets about 12–1 PM Pakistan time"

    fun describe(code: Int, body: String): String {
        var reason = ""
        var message = ""
        try {
            val root = JSONObject(body)
            val err = root.optJSONObject("error")
            if (err != null) {
                // Google's own shape
                message = err.optString("message")
                reason = err.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
            } else {
                // our Pages server's shape: {"errors":[{"code":..,"message":..}]}
                message = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
            }
        } catch (e: Exception) {
            // not JSON — fall through to the code-based messages
        }
        // Google sometimes puts HTML links in the message; show plain text only.
        val clean = message.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
        return when {
            reason.equals("quotaExceeded", true) || reason.equals("dailyLimitExceeded", true) ||
                clean.contains("exceeded your quota", true) -> QUOTA_MESSAGE
            reason.equals("rateLimitExceeded", true) || reason.equals("userRateLimitExceeded", true) ->
                "Too many requests — wait a minute and try again"
            reason.equals("accessNotConfigured", true) || clean.contains("has not been used", true) ->
                "YouTube Data API is not enabled for this Google Cloud project"
            reason.equals("keyInvalid", true) || clean.contains("API key not valid", true) ->
                "YouTube API key is invalid"
            code == 401 -> "Login expired — reconnect the channel in Settings"
            reason.equals("insufficientPermissions", true) -> "Permission missing — reconnect the channel in Settings"
            code == 404 -> "Not found on YouTube"
            clean.isNotEmpty() -> "YouTube error $code: ${clean.take(120)}"
            else -> "YouTube error $code"
        }
    }
}
