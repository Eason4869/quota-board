package com.yusheng.quota.net

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 火山引擎签名 V4 —— 与 CC Switch / volc-openapi-demos 完全一致。
 *
 * 与标准 AWS SigV4 的两处关键差异：
 *  1. SignedHeaders 固定顺序 `host;x-date;x-content-sha256;content-type`（不按字母序）
 *  2. algorithm 为 `HMAC-SHA256`（无 AWS4 前缀），credential scope 以 `request` 结尾，
 *     签名密钥 kDate = HMAC(SK, date)，SK 不加 `AWS4` 前缀
 */
object VolcSigner {

    const val HOST = "open.volcengineapi.com"
    private const val SERVICE = "ark"
    private const val VERSION = "2024-01-01"
    private const val CONTENT_TYPE = "application/json; charset=utf-8"
    private const val SIGNED_HEADERS = "host;x-date;x-content-sha256;content-type"

    data class Signed(val url: String, val headers: Map<String, String>)

    fun sign(
        action: String,
        accessKeyId: String,
        secretAccessKey: String,
        region: String = "cn-beijing",
    ): Signed {
        val ak = accessKeyId.filterPrintable()
        val sk = secretAccessKey.filterPrintable()
        require(ak.isNotEmpty() && sk.isNotEmpty()) { "AccessKey ID / SecretAccessKey 为空" }
        val rg = region.filterPrintable().ifEmpty { "cn-beijing" }

        val xDate = utcNow()
        val shortDate = xDate.substring(0, 8)
        val body = ""
        val payloadHash = sha256Hex(body)

        val query = listOf("Action" to action, "Region" to rg, "Version" to VERSION)
            .sortedBy { it.first }
            .joinToString("&") { (k, v) -> "${uriEncode(k)}=${uriEncode(v)}" }

        val canonicalHeaders =
            "host:$HOST\n" +
            "x-date:$xDate\n" +
            "x-content-sha256:$payloadHash\n" +
            "content-type:$CONTENT_TYPE\n"
        val canonicalRequest =
            "POST\n/\n$query\n$canonicalHeaders\n$SIGNED_HEADERS\n$payloadHash"

        val scope = "$shortDate/$rg/$SERVICE/request"
        val stringToSign = "HMAC-SHA256\n$xDate\n$scope\n${sha256Hex(canonicalRequest)}"

        var key = hmac(sk.toByteArray(), shortDate)
        key = hmac(key, rg)
        key = hmac(key, SERVICE)
        key = hmac(key, "request")
        val signature = hmac(key, stringToSign).toHex()

        return Signed(
            url = "https://$HOST/?$query",
            headers = mapOf(
                "X-Date" to xDate,
                "X-Content-Sha256" to payloadHash,
                "Content-Type" to CONTENT_TYPE,
                "Authorization" to
                    "HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$SIGNED_HEADERS, Signature=$signature",
            ),
        )
    }

    // ── 工具 ──────────────────────────────────────────────

    private fun utcNow(): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /** RFC3986：unreserved 之外全部 %XX */
    private fun uriEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 密钥只允许可打印 ASCII，去掉控制字符 */
    private fun String.filterPrintable(): String =
        filter { it.code in 0x20..0x7E }.trim()
}
