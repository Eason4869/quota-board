package com.yusheng.quota

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.Settings
import com.yusheng.quota.widget.QuotaWidgets
import com.yusheng.quota.widget.SmallQuotaWidget
import com.yusheng.quota.widget.WidgetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = QuotaApp::class)
class WidgetSchedulingTest {
    @Test fun manualRefreshCanStartWithoutWaitingForNetworkConstraints() {
        val app = RuntimeEnvironment.getApplication() as QuotaApp
        app.store.saveSettings(Settings(autoRefreshMinutes = 0, autoQueryOnStart = false, autoCheckUpdate = false))
        app.store.mutateAccounts { listOf(Account("offline-source", "openai", "Offline")) }
        val started = CountDownLatch(1)
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker =
                object : Worker(context, params) {
                    override fun doWork(): Result { started.countDown(); return Result.success() }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(factory).build())
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(app, SmallQuotaWidget::class.java)
            initialLayout = R.layout.widget_quota_small
        }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        host.addInstalledProvider(info)
        host.addBoundWidget(101, info)
        WidgetStore(app).select(101, "offline-source")
        QuotaWidgets.refresh(app, "offline-source")
        assertTrue("Manual query must run without signaling network constraints", started.await(2, TimeUnit.SECONDS))
    }
}
