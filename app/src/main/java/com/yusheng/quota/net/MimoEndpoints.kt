package com.yusheng.quota.net

import org.json.JSONObject
import java.net.URI
import kotlinx.coroutines.CancellationException

/**
 * 小米 MiMo Token Plan 与按量余额接口。
 *
 *  - 这些控制台接口使用小米账号 SSO 会话，必需 Cookie `api-platform_serviceToken` + `userId`
 *  - 模型调用的 `tp-*` / `sk-*` Key 不能作为这些接口的会话凭证
 *  - 未登录时三个接口都会返回 `{"code":401,"loginUrl":"https://account.xiaomi.com/pass/serviceLogin?..."}`
 *    —— 这个 loginUrl 就是**权威的登录页**，比猜控制台深链可靠得多
 *  - 统一信封 `{"code":0,"data":{...}}`
 */
object MimoEndpoints {

    const val HOST = "https://platform.xiaomimimo.com"
    const val USAGE = "$HOST/api/v1/tokenPlan/usage"
    const val DETAIL = "$HOST/api/v1/tokenPlan/detail"
    const val BALANCE = "$HOST/api/v1/balance"

    val ALL = listOf(USAGE, DETAIL, BALANCE)

    /** 报错里提示用户要粘哪些 Cookie */
    const val REQUIRED_COOKIES = "api-platform_serviceToken、userId"

    fun isMimo(url: String): Boolean =
        runCatching { URI(url).host.equals("platform.xiaomimimo.com", ignoreCase = true) }.getOrDefault(false)

    /** 每个账户显式提供自己的 Cookie；不读取 WebView 的全局 CookieJar。 */
    internal fun fetch(cookie: String, request: (String, String) -> PageFetch.Fetched): JSONObject {
        require(cookie.isNotBlank()) { "MiMo: 请登录并保存 Cookie ($REQUIRED_COOKIES)" }
        val results = mutableListOf<PageFetch.Fetched>()
        for (url in ALL) {
            try {
                results += request(url, cookie)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 套餐与按量余额独立；其中一个不可用不应遮住另一个。
            }
        }
        return checkNotNull(merge(results)) {
            if (results.any { it.status == 401 || isNotLoggedIn(it.body) })
                "MiMo: 登录已失效，请重新登录"
            else "MiMo: 未取得有效的套餐用量或余额，请稍后重试"
        }
    }

    /** 用户填的地址属于 MiMo 时，返回「用量 / 详情 / 余额」三件套；否则 null */
    fun endpointsFor(url: String): List<String>? =
        if (isMimo(url)) ALL else null

    /**
     * 把三个接口的原始回包合并成一份给解析器用的 JSON：
     * `{"mimo":{"usage":<原始回包>,"detail":<原始回包>,"balance":<原始回包>}}`
     */
    fun merge(results: List<PageFetch.Fetched>): JSONObject? {
        val mimo = JSONObject()
        val unavailable = org.json.JSONArray()
        for ((url, key) in listOf(USAGE to "usage", DETAIL to "detail", BALANCE to "balance")) {
            val result = results.firstOrNull { it.url == url && it.isOk }
            val body = result?.let { runCatching { JSONObject(it.body) }.getOrNull() }
            if (body != null && body.optInt("code", -1) == 0 && (body.optJSONObject("data")?.length() ?: 0) > 0 &&
                !isNotLoggedIn(result.body)) mimo.put(key, body)
            else unavailable.put(key)
        }
        if (!mimo.has("usage") && !mimo.has("balance")) return null
        if (unavailable.length() > 0) mimo.put("unavailable", unavailable)
        return JSONObject().put("mimo", mimo)
    }

    /** Cookie 名必须精确匹配；字符串中提到名称并不代表已取得凭证。 */
    fun hasSession(cookie: String): Boolean {
        val names = cookie.split(';').mapNotNull {
            val pair = it.trim().split('=', limit = 2)
            pair.first().takeIf { pair.size == 2 && pair[1].trim().trim('"').isNotBlank() }
        }.toSet()
        return names.containsAll(listOf("api-platform_serviceToken", "userId"))
    }

    /** 回包里是不是「未登录」 */
    fun isNotLoggedIn(body: String): Boolean =
        runCatching { JSONObject(body).optInt("code") == 401 }.getOrDefault(false) || loginUrlIn(body) != null

    fun loginUrlIn(body: String): String? = PageFetch.loginUrlIn(body)
}
