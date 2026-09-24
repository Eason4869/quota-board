package com.yusheng.quota.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** 本地存储：账户列表 + 设置。全部留在设备上，不上云。 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("quota_board", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * 账户列表的「读 → 改 → 写」必须互斥。
     * Store 实例会有多个（ViewModel 一个、别处再 new 一个），但 SharedPreferences
     * 只有一份，所以锁挂在伴生对象上按进程共享，而不是每个实例一把。
     */
    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK) { block() }

    fun loadAccounts(): List<Account> = locked { loadAccountsLocked() }

    /**
     * 整表事务：读回**磁盘上的最新列表**交给 [transform]，再写回。
     * 新增 / 删除 / 导入这类本来就要动整表的操作走这里——关键在「读的是磁盘」，
     * 而不是调用方手里那份可能已经过期的内存快照，否则会把并发期间写进去的结果盖回旧的。
     *
     * 账户列表刻意**不提供**「拿一份 list 直接覆盖」的入口（之前那个 saveAccounts 就是），
     * 那种写入只要前后隔着一次网络往返，就一定会把并发写入抹掉。
     */
    fun mutateAccounts(transform: (List<Account>) -> List<Account>): List<Account> = locked {
        val next = transform(loadAccountsLocked())
        saveAccountsLocked(next)
        next
    }

    /**
     * 单账户事务：只改命中 [id] 的那一条，其余原样保留。
     * [patch] 收到的是**磁盘上的最新版本**，不是发起查询前的快照——查询期间用户改了
     * 名字/配置、或另一路刷新刚写回结果，都不会被这份旧对象覆盖。
     * 返回合并后的整表；id 已被删除时返回 null：不写入，也不会把删掉的账户复活。
     */
    fun patchAccount(id: String, patch: (Account) -> Account): List<Account>? = locked {
        val current = loadAccountsLocked()
        if (current.none { it.id == id }) return@locked null
        val next = current.map { if (it.id == id) patch(it) else it }
        saveAccountsLocked(next)
        next
    }

    fun loadSettings(): Settings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return Settings()
        return runCatching { json.decodeFromString<Settings>(raw) }.getOrDefault(Settings())
    }

    fun saveSettings(settings: Settings) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    /**
     * 逐条解码，而不是整串一次性解。
     * 整串解码时只要有一条坏数据（旧版本字段变过、手改过的备份、写盘写到一半……）
     * 就会抛异常，再被 runCatching 吞成空列表——用户看到的「账户全没了」就是这么来的，
     * 而且紧接着任何一次保存都会把好的那些永久覆盖掉。逐条解能把好的那部分留住，
     * 原文另存一份以便事后找回。
     */
    private fun loadAccountsLocked(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        if (array == null) {
            stashCorruptLocked(raw)
            return emptyList()
        }
        val good = ArrayList<Account>(array.size)
        var dropped = 0
        for (element in array) {
            val account = runCatching {
                json.decodeFromJsonElement(Account.serializer(), element)
            }.getOrNull()
            if (account == null) dropped++ else good += account
        }
        if (dropped > 0) stashCorruptLocked(raw)
        return good
    }

    /**
     * 留下现场：坏数据原文挪到 [KEY_ACCOUNTS_CORRUPT]（只留最近一次）。
     * accounts 下一次写盘就会被覆盖，原文留在这里，用户导出的备份还能对得上。
     */
    private fun stashCorruptLocked(raw: String) {
        prefs.edit().putString(KEY_ACCOUNTS_CORRUPT, raw).apply()
    }

    private fun saveAccountsLocked(accounts: List<Account>) {
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(accounts)).apply()
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_SETTINGS = "settings"
        const val KEY_ACCOUNTS_CORRUPT = "accounts_corrupt"
        val LOCK = Any()
    }
}
