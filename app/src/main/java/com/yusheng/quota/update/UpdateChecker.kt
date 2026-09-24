package com.yusheng.quota.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
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
 *
 * 检测与下载都走同一条**镜像兜底链**：直连失败了依次换公共加速镜像，
 * 全挂也不硬刚——上层会把用户引到浏览器的 Release 页（[openReleasePage]）。
 */
object UpdateChecker {

    private const val REPO = "Eason4869/quota-board"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    private const val RELEASE_PAGE = LATEST_PAGE
    private const val ASSET_NAME = "app-release.apk"
    private const val UA = "quota-board-android"

    /**
     * 镜像前缀，空串表示直连。
     *
     * 这些是社区维护的免费加速站，随时可能改域名或失效，所以做成**有序链**而不是
     * 唯一来源：任何一条超时、非 200、或回了不合法内容都会被跳过，不影响后面的。
     * 另外 ghfast.top / ghproxy.net 这类只加速 github.com 的路径，代理不了
     * api.github.com（实测回 403），所以下面「读 API」那一步实际上只有前两条生效。
     */
    private val MIRRORS = listOf(
        "",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
    )

    data class ReleaseInfo(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    /** 拉取最新 Release（GitHub 匿名接口，未登录也可用） */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        // 优先走 API：能一并拿到 Release 说明、资产的真实地址与体积
        for (prefix in MIRRORS) {
            val info = runCatching { fetchViaApi(prefix) }.getOrNull()
            if (info != null) return@withContext info
        }
        // API 全挂（被墙 / 匿名配额 60 次每小时用尽返回 403）：退回解析 releases/latest
        // 的跳转。代价是没有 Release 说明和体积，但至少还能更新。
        for (prefix in MIRRORS) {
            val info = runCatching { fetchViaRedirect(prefix) }.getOrNull()
            if (info != null) return@withContext info
        }
        null
    }

    private fun fetchViaApi(prefix: String): ReleaseInfo? {
        val conn = open(prefix + API, accept = "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return buildRelease(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `/releases/latest` 会 301 到 `/releases/tag/vX.Y.Z`。
     * **不跟随这个跳转**，只读 Location 头：不少镜像会把它指回 github.com，
     * 跟过去就是白等一次超时。
     */
    private fun fetchViaRedirect(prefix: String): ReleaseInfo? {
        val conn = open(prefix + LATEST_PAGE, accept = "*/*", followRedirects = false)
        try {
            if (conn.responseCode !in 300..399) return null
            val tag = conn.getHeaderField("Location").orEmpty()
                .substringAfterLast("/releases/tag/", "")
                .substringBefore('?')
                .trim()
            if (tag.isBlank()) return null
            // 资产名是 CI 固定的（app-release.apk），可以直接拼出来
            return ReleaseInfo(
                versionName = tag.removePrefix("v"),
                notes = "",
                apkUrl = "https://github.com/$REPO/releases/download/$tag/$ASSET_NAME",
                apkSize = 0L,
            )
        } finally {
            conn.disconnect()
        }
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

    /**
     * 下载 APK 到应用私有目录（不需要存储权限）。
     * 按镜像链依次尝试，每个来源的产物都校验一遍：
     *   - 已知体积就核对字节数（镜像回错误页 / 传到一半断流是最常见的失败形态）
     *   - 再看文件头是不是 ZIP，挡住体积够大的 HTML 错误页
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        for (prefix in MIRRORS) {
            val url = mirrorUrl(prefix, info.apkUrl) ?: continue
            // 换源后进度重新计，不要拿着上一个源的百分比往回跳
            onProgress(0)
            val file = try {
                fetchApk(context, url, info, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (file != null) return@withContext file
        }
        null
    }

    /** 非 github.com 的地址没法套加速前缀 */
    private fun mirrorUrl(prefix: String, url: String): String? = when {
        prefix.isEmpty() -> url
        url.startsWith("https://github.com/") -> prefix + url
        else -> null
    }

    private fun fetchApk(
        context: Context,
        url: String,
        info: ReleaseInfo,
        onProgress: (Int) -> Unit,
    ): File? {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "update")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, "quota-board-${info.versionName}.apk")
        if (target.exists()) target.delete()

        // readTimeout 是「连续多久没收到字节」而不是总耗时：正常慢速下载照样能走完，
        // 死掉的连接 30 秒就断，好让镜像链快点轮到下一个源
        val conn = open(url, accept = "application/octet-stream", readTimeoutMs = 30_000)
        try {
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val size = target.length()
        val sizeOk = size >= 100_000 && (info.apkSize <= 0 || size == info.apkSize)
        if (sizeOk && looksLikeZip(target)) {
            onProgress(100)
            return target
        }
        target.delete()
        return null
    }

    /** APK 本质是 ZIP：头 4 字节必须是 PK\x03\x04 */
    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    private fun open(
        url: String,
        accept: String,
        followRedirects: Boolean = true,
        readTimeoutMs: Int = 15_000,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = readTimeoutMs
        instanceFollowRedirects = followRedirects
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", UA)
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

    /** 兜底出口：所有镜像都没走通时，去浏览器里下载 */
    fun openReleasePage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
