package com.yusheng.quota.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 本地存储：账户列表 + 设置。全部留在设备上，不上云。 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("quota_board", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun loadAccounts(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Account>>(raw) }.getOrDefault(emptyList())
    }

    fun saveAccounts(accounts: List<Account>) {
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(accounts)).apply()
    }

    fun loadSettings(): Settings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return Settings()
        return runCatching { json.decodeFromString<Settings>(raw) }.getOrDefault(Settings())
    }

    fun saveSettings(settings: Settings) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    /** 导出整包（含凭证，需提示用户妥善保管） */
    fun exportJson(accounts: List<Account>, settings: Settings): String =
        ImportCodec.encode(accounts, settings)

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_SETTINGS = "settings"
    }
}
