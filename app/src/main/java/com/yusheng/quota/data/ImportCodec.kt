package com.yusheng.quota.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val accounts = root["accounts"]?.let {
            json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(Account.serializer()),
                it,
            )
        } ?: emptyList()
        val settings = (root["settings"] as? JsonObject)?.let {
            json.decodeFromJsonElement(Settings.serializer(), it)
        } ?: Settings()
        return accounts to settings
    }

    @kotlinx.serialization.Serializable
    private data class ExportPayload(
        val accounts: List<Account>,
        val settings: Settings,
    )
}
