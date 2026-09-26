package com.yusheng.quota.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 导入/导出：与设置页 JSON 互转 */
object ImportCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(accounts: List<Account>, settings: Settings): String =
        json.encodeToString(ExportPayload(accounts, settings))

    /** 返回 (accounts, settings) */
    fun decode(raw: String): Pair<List<Account>, Settings> {
        val root = json.parseToJsonElement(raw).jsonObject
        val accountData = root["accounts"]
        require(accountData is JsonArray) { "备份必须包含 accounts 数组" }
        val accounts = accountData.let {
            json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(Account.serializer()),
                it,
            )
        }
        validateAccounts(accounts)
        val settings = (root["settings"] as? JsonObject)?.let {
            json.decodeFromJsonElement(Settings.serializer(), it)
        } ?: Settings()
        return accounts to settings
    }

    fun validateAccounts(accounts: List<Account>) {
        require(accounts.all { it.id.isNotBlank() }) { "账户 ID 不能为空" }
        require(accounts.map { it.id }.toSet().size == accounts.size) { "账户 ID 不能重复" }
    }

    @kotlinx.serialization.Serializable
    private data class ExportPayload(
        val accounts: List<Account>,
        val settings: Settings,
    )
}
