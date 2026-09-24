package com.yusheng.quota.data

import kotlinx.serialization.Serializable

/** 单个额度窗口（5 小时 / 周 / 月等） */
@Serializable
data class Period(
    val label: String,
    val used: Double? = null,
    val total: Double? = null,
    /** 已用百分比（部分厂商只回百分比） */
    val usedPct: Double? = null,
    val resetAt: String? = null,
) {
    val usedPercent: Double?
        get() = usedPct ?: if (total != null && total > 0 && used != null) used / total * 100.0 else null

    val remainPercent: Double?
        get() = usedPercent?.let { (100.0 - it).coerceIn(0.0, 100.0) }

    val remain: Double?
        get() = if (total != null && used != null) (total - used).coerceAtLeast(0.0) else null
}

/** 按量余额 */
@Serializable
data class Balance(
    val amount: Double,
    val currency: String = "¥",
)

/** 订阅信息 */
@Serializable
data class Subscription(
    val tier: String? = null,
    val remaining: Double? = null,
    val total: Double? = null,
    val unit: String = "Credits",
    val resetAt: String? = null,
)

/** 额外信息（厂商特有字段） */
@Serializable
data class Extra(val label: String, val value: String)

/** 一次查询的归一化结果：有什么展示什么 */
@Serializable
data class QueryResult(
    val balance: Balance? = null,
    val subscription: Subscription? = null,
    val periods: List<Period> = emptyList(),
    val extras: List<Extra> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis(),
)

/** 查询方式 */
enum class QueryMode { API, WEBHOOK, LOGIN, AKSK }

/** 一个账户的查询配置 */
@Serializable
data class QueryConfig(
    val mode: QueryMode = QueryMode.API,
    val url: String = "",
    val method: String = "GET",
    /** 官方 API 的 Key（部分厂商用裸 Key，无 Bearer） */
    val apiKey: String = "",
    /** 登录取数用的登录页 */
    val loginUrl: String = "",
    val cookie: String = "",
    /** 云函数代理地址 */
    val webhookUrl: String = "",
    /** 云函数可选鉴权 */
    val whAuthType: String = "none",
    val whAuthKey: String = "",
    val whAuthPrefix: String = "",
    val whAuthValue: String = "",
    /** 火山 AK/SK */
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val region: String = "cn-beijing",
    /** 智谱团队版：组织 ID / 项目 ID（两项都填才走 team 接口 ?type=2） */
    val orgId: String = "",
    val projectId: String = "",
    /** 自定义提取器（可空，留空走内置解析） */
    val script: String = "",
    /** 可选字段路径映射 */
    val mapBalance: String = "",
    val mapPlan: String = "",
    val timeoutSec: Int = 10,
)

/** 用户添加的一个账户实例（同一厂商可重复添加） */
@Serializable
data class Account(
    val id: String,
    val templateId: String,
    val name: String,
    val query: QueryConfig = QueryConfig(),
    val result: QueryResult? = null,
    val lastError: String? = null,
    val updatedAt: Long = 0L,
)

/** 全局设置 */
@Serializable
data class Settings(
    val autoRefreshMinutes: Int = 30,
    val autoQueryOnStart: Boolean = true,
    val darkMode: String = "system",
    /** 全局默认超时；账户级 timeoutSec > 0 时优先用账户级 */
    val timeoutSec: Int = 10,
    /** 启动时自动检查新版本 */
    val autoCheckUpdate: Boolean = true,
)
