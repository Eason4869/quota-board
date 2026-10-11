package com.yusheng.quota.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.yusheng.quota.MainActivity
import com.yusheng.quota.R
import com.yusheng.quota.data.Store
import com.yusheng.quota.ui.logoResFor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

object QuotaWidgets {
    const val ACTION_REFRESH = "com.yusheng.quota.widget.REFRESH"
    private const val PERIODIC_WORK = "quota-widget-periodic"
    private val providers = listOf(SmallQuotaWidget::class.java, WideQuotaWidget::class.java)

    fun ids(context: Context): IntArray = providers.flatMap {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).toList()
    }.toIntArray()

    fun selectedAccounts(context: Context): Set<String> {
        val selection = WidgetStore(context)
        val existing = Store(context).loadAccounts().map { it.id }.toSet()
        return ids(context).map { selection.accountId(it) }.filterNotNull().filter { it in existing }.toSet()
    }

    fun renderAll(context: Context) { ids(context).forEach { render(context, it) } }

    fun render(context: Context, widgetId: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val provider = manager.getAppWidgetInfo(widgetId)?.provider ?: return
        if (provider.packageName != context.packageName) return
        val wide = provider.className == WideQuotaWidget::class.java.name
        val store = Store(context)
        val account = store.loadAccounts().firstOrNull { it.id == WidgetStore(context).accountId(widgetId) }
        val content = WidgetContent.from(context, account)
        val options = manager.getAppWidgetOptions(widgetId)
        val height = options.getInt(
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 170),
        )
        val compact = height in 1 until 140
        val width = options.getInt(
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            if (wide) 340 else 160,
        )
        val splitReset = compact || (wide && content.periods.size >= 3 && width in 1 until 300)
        fun spHeight(size: Float) = ceil(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)).toInt()
        val periodLabelHeight = spHeight(if (compact) 10f else 14f)
        val periodResetHeight = spHeight(if (compact) 9f else 13f) * if (splitReset) 2 else 1
        val dark = when (store.loadSettings().darkMode) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val views = RemoteViews(context.packageName, if (wide || compact) R.layout.widget_quota_wide else R.layout.widget_quota_small).apply {
            setInt(android.R.id.background, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background)
            val density = context.resources.displayMetrics.density
            val horizontalPadding = (12 * density).toInt()
            val verticalPadding = ((if (compact) 2 else 6) * density).toInt()
            setViewPadding(android.R.id.background, horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setTextViewText(R.id.widget_title, content.title)
            setTextViewText(R.id.widget_value, content.value)
            setTextViewText(R.id.widget_detail, content.detail)
            setTextViewText(R.id.widget_status, if (wide && account?.lastError != null) content.status else content.compactStatus)
            setViewVisibility(R.id.widget_balance_group, if (content.periods.isEmpty()) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_periods, if (content.periods.isEmpty()) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_cards, if (wide && !compact && content.resetCards != null) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_cards, content.resetCards.orEmpty())
            removeAllViews(R.id.widget_periods)
            val periodPadding = if (wide && !compact) (3 * density).toInt() else 0
            setViewPadding(R.id.widget_periods, 0, periodPadding, 0, periodPadding)
            setImageViewResource(R.id.widget_logo, logoResFor(account?.templateId.orEmpty()))
            setInt(R.id.widget_logo, "setColorFilter", when {
                account?.templateId != "openai" -> Color.TRANSPARENT
                dark -> Color.WHITE
                else -> Color.rgb(25, 25, 25)
            })
            val textColor = Color.parseColor(if (dark) "#F3F7FC" else "#152033")
            val secondaryColor = Color.parseColor(if (dark) "#A7B2C2" else "#6B7A90")
            setTextColor(R.id.widget_title, textColor)
            setTextColor(R.id.widget_value, if (account?.templateId == "deepseek") Color.rgb(77, 107, 254) else textColor)
            setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, if (wide) 40f else 34f)
            setTextColor(R.id.widget_detail, secondaryColor)
            setTextColor(R.id.widget_status, if (account?.lastError != null) Color.rgb(217, 110, 20) else secondaryColor)
            content.periods.take(if (wide) 3 else 2).forEach { period ->
                val card = RemoteViews(context.packageName, when {
                    compact -> R.layout.widget_period_compact
                    wide -> R.layout.widget_period_card
                    else -> R.layout.widget_period_row
                }).apply {
                    setTextViewText(R.id.widget_period_label, if (compact) period.label else context.getString(R.string.widget_window_remaining, period.label))
                    setTextColor(R.id.widget_period_label, secondaryColor)
                    if (wide || compact) {
                        setInt(R.id.widget_period_label, "setHeight", periodLabelHeight)
                        setInt(R.id.widget_period_reset, "setHeight", periodResetHeight)
                    }
                    setTextViewText(R.id.widget_period_value, period.value)
                    setTextColor(R.id.widget_period_value, textColor)
                    setTextColor(R.id.widget_period_reset, secondaryColor)
                    setProgressBar(R.id.widget_period_progress, 100, period.remaining, false)
                    setInt(R.id.widget_period_reset, "setMaxLines", if (splitReset) 2 else 1)
                    setTextViewText(R.id.widget_period_reset, period.reset?.let {
                        val time = if (splitReset) it.replaceFirst(" ", "\n") else it
                        if (compact) time else context.getString(R.string.widget_reset_short, time)
                    } ?: if (compact) "—" else context.getString(R.string.widget_reset_unknown))
                    setViewVisibility(R.id.widget_period_reset, View.VISIBLE)
                    setContentDescription(R.id.widget_period_progress, context.getString(R.string.widget_percent_remaining, period.value))
                    setContentDescription(R.id.widget_period_reset, period.reset?.let { context.getString(R.string.widget_reset_time, it) }
                        ?: context.getString(R.string.widget_reset_unavailable))
                }
                addView(R.id.widget_periods, card)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val edit = Intent(context, WidgetConfigActivity::class.java)
                .setData(Uri.parse("quota-board://widget/$widgetId/edit"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            setOnClickPendingIntent(R.id.widget_edit, PendingIntent.getActivity(context, widgetId, edit, flags))
            val refresh = Intent(ACTION_REFRESH).setComponent(provider)
                .setData(Uri.parse("quota-board://widget/$widgetId/refresh"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, widgetId, refresh, flags))
            val open = if (account == null) edit else Intent(context, MainActivity::class.java)
                .setData(Uri.parse("quota-board://widget/$widgetId/open"))
            setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, widgetId, open, flags))
        }
        manager.updateAppWidget(widgetId, views)
    }

    fun refresh(context: Context, accountId: String) {
        if (accountId !in selectedAccounts(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "quota-widget-refresh-$accountId", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInputData(workDataOf(WidgetRefreshWorker.ACCOUNT_ID to accountId))
                .build(),
        )
    }

    fun schedule(context: Context) {
        val selection = WidgetStore(context)
        if (ids(context).isEmpty()) {
            if (selection.hasScheduledWork()) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
                selection.markScheduled(false)
            }
            return
        }
        val minutes = Store(context).loadSettings().autoRefreshMinutes
        val manager = WorkManager.getInstance(context)
        if (minutes <= 0 || selectedAccounts(context).isEmpty()) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            selection.markScheduled(false)
        } else {
            manager.enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
            selection.markScheduled(true)
        }
    }
}
