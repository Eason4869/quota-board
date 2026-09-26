package com.yusheng.quota.net

import org.json.JSONObject
import java.net.URI
import kotlinx.coroutines.CancellationException

/**
 * 小米 MiMo（Token Plan）额度接口。
 *
 * 关键事实（与 CodexBar / opencode-quota / dsh-cost-meter 等实现一致，我也实测过）：
 *  - 配额只能通过小米账号 **Web SSO 会话**访问，必需 Cookie `api-platform_serviceToken` + `userId`
 *  - `tp-*`（套餐 Key）与 `sk-*`（按量 Key）**都没有额度接口**，只能走登录态
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
        val usage = request(USAGE, cookie)
        check(usage.isOk && !isNotLoggedIn(usage.body)) { "MiMo: 登录已失效，请重新登录" }
        val results = mutableListOf(usage)
        for (url in listOf(DETAIL, BALANCE)) {
            try {
                val result = request(url, cookie)
                if (!isNotLoggedIn(result.body)) results += result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 详情或余额接口暂不可用时仍保留成功获取的用量。
            }
        }
        return checkNotNull(merge(results)) { "MiMo: 无法解析用量响应" }
    }

    /** 用户填的地址属于 MiMo 时，返回「用量 / 详情 / 余额」三件套；否则 null */
    fun endpointsFor(url: String): List<String>? =
        if (isMimo(url)) ALL else null

    /**
     * 把三个接口的原始回包合并成一份给解析器用的 JSON：
     * `{"mimo":{"usage":<原始回包>,"detail":<原始回包>,"balance":<原始回包>}}`
     */
    fun merge(results: List<PageFetch.Fetched>): JSONObject? {
        val usage = results.firstOrNull { it.url.contains("tokenPlan/usage") } ?: results.firstOrNull()
        if (usage == null || !usage.isOk) return null
        val mimo = JSONObject()
        mimo.put("usage", runCatching { JSONObject(usage.body) }.getOrNull() ?: return null)
        results.firstOrNull { it.url.contains("tokenPlan/detail") && it.isOk }
            ?.let { r -> runCatching { JSONObject(r.body) }.getOrNull()?.let { mimo.put("detail", it) } }
        results.firstOrNull { it.url.contains("/balance") && it.isOk }
            ?.let { r -> runCatching { JSONObject(r.body) }.getOrNull()?.let { mimo.put("balance", it) } }
        return JSONObject().put("mimo", mimo)
    }

    /** 平台域 Cookie 里是否已有登录会话（`api-platform_serviceToken` 出现 = 登录完成） */
    fun hasSession(cookie: String): Boolean = cookie.contains("api-platform_serviceToken")

    /** 回包里是不是「未登录」 */
    fun isNotLoggedIn(body: String): Boolean =
        runCatching { JSONObject(body).optInt("code") == 401 }.getOrDefault(false) || loginUrlIn(body) != null

    fun loginUrlIn(body: String): String? = PageFetch.loginUrlIn(body)
}
