package com.yusheng.quota.net

import org.json.JSONObject

/** Response returned by a provider quota endpoint. */
object PageFetch {
    data class Fetched(val url: String, val status: Int, val body: String) {
        val isOk: Boolean get() = status in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    fun loginUrlIn(body: String): String? =
        runCatching { JSONObject(body).optString("loginUrl").takeIf { it.isNotBlank() } }.getOrNull()
}
