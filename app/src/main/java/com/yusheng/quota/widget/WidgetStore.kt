package com.yusheng.quota.widget

import android.content.Context
import android.appwidget.AppWidgetManager

/** Contains account IDs only; credentials remain in the existing Store. */
class WidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("quota_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(context)
    private val packageName = context.packageName
    private fun providerKey(provider: String, id: Int) = "account_${provider}_$id"
    private fun selectionKey(id: Int): String = manager.getAppWidgetInfo(id)?.provider
        ?.takeIf { it.packageName == packageName }?.className?.let { providerKey(it, id) }
        ?: "account_$id"

    fun accountId(widgetId: Int): String? {
        val key = selectionKey(widgetId)
        return (if (prefs.contains(key)) prefs.getString(key, null)
            else prefs.getString("account_$widgetId", null))?.takeIf { it.isNotBlank() }
    }
    fun select(widgetId: Int, accountId: String) {
        val key = selectionKey(widgetId)
        prefs.edit().apply {
            if (key != "account_$widgetId") remove("account_$widgetId")
            putString(key, accountId)
        }.apply()
    }
    fun remove(widgetId: Int) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("account_") && it.endsWith("_$widgetId") }.forEach { remove(it) }
        }.apply()
    }

    /** Providers restore separately; namespaced destinations must never replace another provider's old ID. */
    fun restore(provider: String, oldIds: IntArray, newIds: IntArray) {
        if (oldIds.size != newIds.size) return
        val selections = oldIds.map { id ->
            val key = providerKey(provider, id)
            if (prefs.contains(key)) prefs.getString(key, null) else prefs.getString("account_$id", null)
        }
        prefs.edit().apply {
            oldIds.forEach { id -> remove(providerKey(provider, id)); remove("account_$id") }
            newIds.forEachIndexed { index, id ->
                // An explicit empty selection also blocks legacy keys belonging to a different old widget.
                putString(providerKey(provider, id), selections[index].orEmpty())
            }
        }.apply()
    }
    fun hasScheduledWork(): Boolean = prefs.getBoolean("scheduled", false)
    fun markScheduled(scheduled: Boolean) {
        prefs.edit().putBoolean("scheduled", scheduled).apply()
    }
}
