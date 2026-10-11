package com.yusheng.quota.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

open class QuotaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { QuotaWidgets.render(context, it) }
        QuotaWidgets.schedule(context)
        ids.map { WidgetStore(context).accountId(it) }.filterNotNull().distinct().forEach {
            QuotaWidgets.refresh(context, it)
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        QuotaWidgets.render(context, id)
    }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        WidgetStore(context).restore(javaClass.name, oldIds, newIds)
        // AppWidgetProvider.onReceive invokes onUpdate with the new IDs immediately afterwards.
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore(context).remove(it) }
        QuotaWidgets.schedule(context)
    }
    override fun onDisabled(context: Context) {
        QuotaWidgets.schedule(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == QuotaWidgets.ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id in QuotaWidgets.ids(context)) {
                WidgetStore(context).accountId(id)?.let { QuotaWidgets.refresh(context, it) }
            }
        }
    }
}

class SmallQuotaWidget : QuotaWidgetProvider()
class WideQuotaWidget : QuotaWidgetProvider()
