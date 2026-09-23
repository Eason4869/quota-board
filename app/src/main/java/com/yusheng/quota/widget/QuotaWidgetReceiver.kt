package com.yusheng.quota.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yusheng.quota.data.Store
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.QueryEngine
import java.util.concurrent.TimeUnit

class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 跟随用户设置，而不是写死 30 分钟
        QuotaWidgetWorker.schedule(context, Store(context).loadSettings().autoRefreshMinutes)
    }
}

/** 后台定时刷新：拉取所有账户后更新小组件 */
class QuotaWidgetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val accounts = store.loadAccounts()
        val engine = QueryEngine(applicationContext)
        val timeoutSec = store.loadSettings().timeoutSec

        val updated = accounts.map { account ->
            runCatching {
                val result = engine.query(Templates.byId(account.templateId), account.query, timeoutSec)
                account.copy(result = result, lastError = null, updatedAt = System.currentTimeMillis())
            }.getOrElse {
                account.copy(lastError = it.message ?: it.toString(), updatedAt = System.currentTimeMillis())
            }
        }

        store.saveAccounts(updated)
        QuotaWidget.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "quota_widget_refresh"

        /** minutes <= 0 表示关闭后台刷新 */
        fun schedule(context: Context, minutes: Int) {
            if (minutes <= 0) {
                cancel(context)
                return
            }
            // WorkManager 周期任务下限 15 分钟
            val mins = minutes.coerceIn(15, 1440).toLong()
            val request = PeriodicWorkRequestBuilder<QuotaWidgetWorker>(mins, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
