package com.yusheng.quota.data

import androidx.annotation.StringRes
import com.yusheng.quota.R

/**
 * 厂商模板。名称/描述走字符串资源，跟随系统语言。
 * 查询方式按各厂商真实能力裁剪，不套统一模板。
 */
data class Template(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    val color: Long,
    val defaultMode: QueryMode,
    val modes: List<QueryMode>,
    val defaults: QueryConfig,
)

/**
 * 火山引擎账号登录页（不是控制台里的深链）。
 *
 * 登录页那份 bundle 是压到 ES5 级别的（实测 `?.` 0 处、`??=` 0 处），老内核也能跑；
 * 而控制台（方舟）的入口 bundle 有 154 处 `?.`、12 处 `??=`，要 **Chrome 85+**，
 * 内核低了整段脚本连解析都过不去 —— 页面停在空壳上，什么都连不上。
 * 登录只是为了拿 Cookie，不需要那个页面本身画得出来。
 */
const val VOLC_LOGIN_PAGE = "https://console.volcengine.com/auth/login/"

/**
 * 登录页归一化：把「控制台深链」这类伪登录页换成真正的登录页。
 *
 * 老账户里存的可能还是深链（模板改之前添加的），所以这一步放在打开登录页时做，
 * 而不是只改模板 —— 否则已经建好的账户仍旧白屏。
 */
fun normalizeLoginUrl(raw: String): String {
    val url = raw.trim()
    if (url.isEmpty()) return url
    val lower = url.lowercase()
    if (!lower.contains("console.volcengine.com")) return url
    if (lower.contains("/auth/login")) return url
    return VOLC_LOGIN_PAGE
}

object Templates {

    val ALL: List<Template> = listOf(
        // ── 国际订阅 ──
        Template(
            id = "openai",
            nameRes = R.string.vendor_openai,
            descRes = R.string.vendor_openai_desc,
            color = 0xFF10A37F,
            // 默认走 API：粘贴 Codex CLI 的 access token + 账号 ID，不必in-app 打开 ChatGPT 登录页
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.LOGIN, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://chatgpt.com/backend-api/wham/usage",
                loginUrl = "https://chatgpt.com/",
            ),
        ),
        Template(
            id = "claude",
            nameRes = R.string.vendor_claude,
            descRes = R.string.vendor_claude_desc,
            color = 0xFFD4A27F,
            // 默认走 API：粘贴 Claude Code 的 OAuth access token（官方 oauth/usage 接口）
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.LOGIN, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.anthropic.com/api/oauth/usage",
                loginUrl = "https://claude.ai/",
            ),
        ),
        Template(
            id = "gemini",
            nameRes = R.string.vendor_gemini,
            descRes = R.string.vendor_gemini_desc,
            color = 0xFF4285F4,
            // 默认走 API：粘贴 Gemini/Antigravity CLI 的 OAuth token + 项目 ID
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.LOGIN, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
                loginUrl = "https://gemini.google.com/",
            ),
        ),

        // ── 国内订阅 / Token Plan ──
        Template(
            id = "deepseek",
            nameRes = R.string.vendor_deepseek,
            descRes = R.string.vendor_deepseek_desc,
            color = 0xFF4D6BFE,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.deepseek.com/user/balance",
            ),
        ),
        Template(
            id = "zhipu",
            nameRes = R.string.vendor_zhipu,
            descRes = R.string.vendor_zhipu_desc,
            color = 0xFF3CC8FF,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://open.bigmodel.cn/api/monitor/usage/quota/limit",
            ),
        ),
        Template(
            id = "kimi",
            nameRes = R.string.vendor_kimi,
            descRes = R.string.vendor_kimi_desc,
            color = 0xFF5B9DFF,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.kimi.com/coding/v1/usages",
            ),
        ),
        Template(
            id = "minimax",
            nameRes = R.string.vendor_minimax,
            descRes = R.string.vendor_minimax_desc,
            color = 0xFFFF5C5C,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                // 官方现行路由；旧路由由引擎自动兜底
                url = "https://api.minimaxi.com/v1/token_plan/remains",
            ),
        ),
        Template(
            id = "xiaomi",
            nameRes = R.string.vendor_xiaomi,
            descRes = R.string.vendor_xiaomi_desc,
            color = 0xFFFF6900,
            defaultMode = QueryMode.LOGIN,
            modes = listOf(QueryMode.LOGIN, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.LOGIN,
                // 直接填额度接口：登录页地址由接口自己返回的 loginUrl 决定（见 LoginCaptureScreen），
                // 比猜控制台深链可靠 —— 深链要先跑通 SPA 才谈得上登录，老内核上就是白屏。
                url = "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage",
                loginUrl = "https://platform.xiaomimimo.com/console/plan-manage",
            ),
        ),
        Template(
            id = "opencode_go",
            nameRes = R.string.vendor_opencode,
            descRes = R.string.vendor_opencode_desc,
            color = 0xFF2FD48B,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK, QueryMode.LOGIN),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://opencode.ai/zen/go/v1/usage",
                loginUrl = "https://opencode.ai/",
            ),
        ),

        // ── 国内按量 / 平台 ──
        Template(
            id = "volc_agent",
            nameRes = R.string.vendor_volc_agent,
            descRes = R.string.vendor_volc_agent_desc,
            color = 0xFF8B7CFF,
            defaultMode = QueryMode.AKSK,
            modes = listOf(QueryMode.AKSK, QueryMode.WEBHOOK, QueryMode.LOGIN),
            defaults = QueryConfig(
                mode = QueryMode.AKSK,
                url = "https://open.volcengineapi.com/",
                loginUrl = VOLC_LOGIN_PAGE,
                region = "cn-beijing",
            ),
        ),
        Template(
            id = "volc_coding",
            nameRes = R.string.vendor_volc_coding,
            descRes = R.string.vendor_volc_coding_desc,
            color = 0xFFC084FC,
            defaultMode = QueryMode.AKSK,
            modes = listOf(QueryMode.AKSK, QueryMode.WEBHOOK, QueryMode.LOGIN),
            defaults = QueryConfig(
                mode = QueryMode.AKSK,
                url = "https://open.volcengineapi.com/",
                loginUrl = VOLC_LOGIN_PAGE,
                region = "cn-beijing",
            ),
        ),
        Template(
            id = "siliconflow",
            nameRes = R.string.vendor_siliconflow,
            descRes = R.string.vendor_siliconflow_desc,
            color = 0xFF14B8A6,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK, QueryMode.LOGIN),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.siliconflow.cn/v1/user/info",
                loginUrl = "https://account.siliconflow.cn/",
            ),
        ),
        Template(
            id = "novita",
            nameRes = R.string.vendor_novita,
            descRes = R.string.vendor_novita_desc,
            color = 0xFF6C5CE7,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.novita.ai/v3/user/balance",
            ),
        ),
        Template(
            id = "openrouter",
            nameRes = R.string.vendor_openrouter,
            descRes = R.string.vendor_openrouter_desc,
            color = 0xFFC084FC,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://openrouter.ai/api/v1/credits",
            ),
        ),
        Template(
            id = "stepfun",
            nameRes = R.string.vendor_stepfun,
            descRes = R.string.vendor_stepfun_desc,
            color = 0xFF2E7CF6,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.stepfun.com/v1/accounts",
            ),
        ),

        // ── 通用（永远最后）──
        Template(
            id = "generic",
            nameRes = R.string.vendor_generic,
            descRes = R.string.vendor_generic_desc,
            color = 0xFF94A3B8,
            defaultMode = QueryMode.WEBHOOK,
            modes = listOf(QueryMode.WEBHOOK, QueryMode.API, QueryMode.LOGIN),
            defaults = QueryConfig(mode = QueryMode.WEBHOOK),
        ),
    )

    fun byId(id: String): Template = ALL.firstOrNull { it.id == id } ?: ALL.last()
}
