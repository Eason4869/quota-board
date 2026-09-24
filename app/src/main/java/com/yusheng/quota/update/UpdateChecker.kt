package com.yusheng.quota.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 GitHub Releases 检测新版本。
 * 版本号比对 versionName；APK 下载后走系统安装器。
 */
object UpdateChecker {
    private const val REPO = "https://api.github.com/repos/Eason4869/quota-board/releases/latest"
    private const val ASSET_NAME = "app-release.apk"

    data class ReleaseInfo(
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    fun fetchLatest(): ReleaseInfo? = runCatching {
        val conn = URL(REPO).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "quota-board")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val tag = json.optString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ASSET_NAME) {
                apkUrl = a.optString("browser_download_url")
                break
            }
        }
        if (tag.isBlank() || apkUrl.isBlank()) null
        else ReleaseInfo(tag, apkUrl, json.optString("body").take(400))
    }.getOrNull()

    fun isNewer(current: String, remote: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(remote)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    /** 用浏览器打开下载页（兼容各厂商应用商店限制，不做静默安装） */
    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Eason4869/quota-board/releases/latest"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
