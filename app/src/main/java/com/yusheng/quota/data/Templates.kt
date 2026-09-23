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

object Templates {

    val ALL: List<Template> = listOf(
        // ── 国内订阅 / Token Plan ──
        Template(
            id = "xiaomi",
            nameRes = R.string.vendor_xiaomi,
            descRes = R.string.vendor_xiaomi_desc,
            color = 0xFFFF6900,
            defaultMode = QueryMode.LOGIN,
            modes = listOf(QueryMode.LOGIN, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.LOGIN,
                url = "https://platform.xiaomimimo.com/console/plan-manage",
                loginUrl = "https://platform.xiaomimimo.com/console/plan-manage",
            ),
        ),
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
                loginUrl = "https://console.volcengine.com/ark/region:cn-beijing/subscription/agent-plan",
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
                loginUrl = "https://console.volcengine.com/ark/region:cn-beijing/subscription/coding-plan",
                region = "cn-beijing",
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
            id = "minimax",
            nameRes = R.string.vendor_minimax,
            descRes = R.string.vendor_minimax_desc,
            color = 0xFFFF5C5C,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains",
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

        // ── 国内按量余额 ──
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
            id = "siliconflow",
            nameRes = R.string.vendor_siliconflow,
            descRes = R.string.vendor_siliconflow_desc,
            color = 0xFF14B8A6,
            defaultMode = QueryMode.API,
            modes = listOf(QueryMode.API, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.API,
                url = "https://api.siliconflow.cn/v1/user/info",
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

        // ── 海外订阅 ──
        Template(
            id = "claude",
            nameRes = R.string.vendor_claude,
            descRes = R.string.vendor_claude_desc,
            color = 0xFFD4A27F,
            defaultMode = QueryMode.LOGIN,
            modes = listOf(QueryMode.LOGIN, QueryMode.WEBHOOK, QueryMode.API),
            defaults = QueryConfig(
                mode = QueryMode.LOGIN,
                url = "https://claude.ai/api/oauth/usage",
                loginUrl = "https://claude.ai/",
            ),
        ),
        Template(
            id = "gemini",
            nameRes = R.string.vendor_gemini,
            descRes = R.string.vendor_gemini_desc,
            color = 0xFF4285F4,
            defaultMode = QueryMode.LOGIN,
            modes = listOf(QueryMode.LOGIN, QueryMode.WEBHOOK),
            defaults = QueryConfig(
                mode = QueryMode.LOGIN,
                url = "https://gemini.google.com/",
                loginUrl = "https://gemini.google.com/",
            ),
        ),
        Template(
            id = "openai",
            nameRes = R.string.vendor_openai,
            descRes = R.string.vendor_openai_desc,
            color = 0xFF10A37F,
            defaultMode = QueryMode.LOGIN,
            modes = listOf(QueryMode.LOGIN, QueryMode.WEBHOOK, QueryMode.API),
            defaults = QueryConfig(
                mode = QueryMode.LOGIN,
                url = "https://chatgpt.com/backend-api/wham/usage",
                loginUrl = "https://chatgpt.com/",
            ),
        ),

        // ── 海外按量 / 聚合 ──
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

        // ── 通用 ──
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
