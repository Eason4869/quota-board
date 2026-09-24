package com.yusheng.quota.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：检测 GitHub Releases → 应用内下载 APK → 直接调起系统安装器。
 * 全程不跳浏览器。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/Eason4869/quota-board/releases/latest"
    private const val RELEASE_PAGE = "https://github.com/Eason4869/quota-board/releases/latest"
    private const val ASSET_NAME = "app-release.apk"

    data class ReleaseInfo(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    /** 拉取最新 Release（GitHub 匿名接口，未登录也可用） */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "quota-board-android")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            buildRelease(JSONObject(body))
        }.getOrNull()
    }

    private fun buildRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name").removePrefix("v").trim()
        if (tag.isBlank()) return null
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var url = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == ASSET_NAME) {
                url = a.optString("browser_download_url")
                size = a.optLong("size")
                break
            }
        }
        if (url.isBlank()) return null
        return ReleaseInfo(
            versionName = tag,
            notes = json.optString("body").trim(),
            apkUrl = url,
            apkSize = size,
        )
    }

    /** 语义化版本比较：远程比当前新才返回 true */
    fun isNewer(current: String, remote: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(remote)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    /** 下载 APK 到应用私有目录（不需要存储权限） */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "update")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, "quota-board-${info.versionName}.apk")
            if (target.exists()) target.delete()

            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "quota-board-android")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            conn.disconnect()
            if (target.length() < 100_000) {
                target.delete()
                null
            } else {
                onProgress(100)
                target
            }
        }.getOrNull()
    }

    /** 是否已允许「安装未知应用」 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到本应用的「安装未知应用」授权页 */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openReleasePage(context) }
    }

    /** 调起系统安装器安装已下载的 APK */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun openReleasePage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
