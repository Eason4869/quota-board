package com.yusheng.quota

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.Balance
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Settings
import com.yusheng.quota.widget.QuotaWidgets
import com.yusheng.quota.widget.SmallQuotaWidget
import com.yusheng.quota.widget.WideQuotaWidget
import com.yusheng.quota.widget.WidgetStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = QuotaApp::class)
class WidgetRestoreTest {
    private val app get() = RuntimeEnvironment.getApplication() as QuotaApp

    @Before fun reset() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker =
                        object : Worker(context, params) {
                            override fun doWork(): Result = Result.success()
                        }
                }).build())
        app.store.replaceAccounts(listOf(
            Account("a", "deepseek", "DeepSeek", result = QueryResult(balance = Balance(12.0))),
            Account("b", "openai", "Codex", result = QueryResult(balance = Balance(20.0))),
        ))
        app.store.saveSettings(Settings(autoRefreshMinutes = 0, autoQueryOnStart = false, autoCheckUpdate = false))
    }

    @Test fun restoredBroadcastRebindsBothProvidersBeforeFrameworkUpdatesThem() {
        val selection = WidgetStore(app)
        val host = shadowOf(AppWidgetManager.getInstance(app))
        selection.select(11, "a")
        selection.select(12, "b")
        for ((provider, oldId, newId, accountId, title) in listOf(
            RestoreCase(SmallQuotaWidget(), 11, 101, "a", "DeepSeek"),
            RestoreCase(WideQuotaWidget(), 12, 102, "b", "Codex"),
        )) {
            val info = AppWidgetProviderInfo().apply {
                this.provider = ComponentName(app, provider.javaClass)
                initialLayout = if (provider is WideQuotaWidget) R.layout.widget_quota_wide else R.layout.widget_quota_small
            }
            host.addInstalledProvider(info)
            host.addBoundWidget(newId, info)
            provider.onReceive(app, Intent(AppWidgetManager.ACTION_APPWIDGET_RESTORED)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, intArrayOf(oldId))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(newId)))
            assertEquals(accountId, WidgetStore(app).accountId(newId))
            assertNull(selection.accountId(oldId))
            assertEquals(title, host.getViewFor(newId).findViewById<android.widget.TextView>(R.id.widget_title).text)
        }
        assertEquals(setOf("a", "b"), QuotaWidgets.selectedAccounts(app))
    }

    @Test fun overlappingRestoredIdsPreserveBothBindings() {
        val selection = WidgetStore(app)
        selection.select(10, "a")
        selection.select(20, "b")
        bind(SmallQuotaWidget(), 10, 20)
        SmallQuotaWidget().onRestored(app, intArrayOf(10, 20), intArrayOf(20, 10))
        assertEquals("b", WidgetStore(app).accountId(10))
        assertEquals("a", WidgetStore(app).accountId(20))
    }

    @Test fun missingRestoredSelectionDoesNotReuseAStaleDestinationBinding() {
        val selection = WidgetStore(app)
        selection.select(10, "a")
        selection.select(30, "b")
        bind(SmallQuotaWidget(), 40, 30)
        SmallQuotaWidget().onRestored(app, intArrayOf(10, 20), intArrayOf(40, 30))
        assertEquals("a", selection.accountId(40))
        assertNull(selection.accountId(10))
        assertNull(selection.accountId(30))
    }

    @Test fun separateProviderRestoresDoNotOverwriteEachOthersBindings() {
        for (legacy in listOf(true, false)) for (smallFirst in listOf(true, false)) {
            val selection = WidgetStore(app)
            app.getSharedPreferences("quota_widgets", Context.MODE_PRIVATE).edit().clear().apply()
            if (legacy) {
                // Legacy bindings can also be present after upgrading a local test build.
                app.getSharedPreferences("quota_widgets", Context.MODE_PRIVATE).edit()
                    .putString("account_1", "a").putString("account_2", "b").apply()
            } else {
                bind(SmallQuotaWidget(), 1)
                bind(WideQuotaWidget(), 2)
                selection.select(1, "a")
                selection.select(2, "b")
            }
            bind(SmallQuotaWidget(), 2)
            bind(WideQuotaWidget(), 3)
            val small = { SmallQuotaWidget().onRestored(app, intArrayOf(1), intArrayOf(2)) }
            val wide = { WideQuotaWidget().onRestored(app, intArrayOf(2), intArrayOf(3)) }
            if (smallFirst) { small(); wide() } else { wide(); small() }
            assertEquals("legacy=$legacy smallFirst=$smallFirst", "a", selection.accountId(2))
            assertEquals("legacy=$legacy smallFirst=$smallFirst", "b", selection.accountId(3))
        }
    }

    @Test fun deviceTransferIncludesAccountsAndTheirWidgetBindings() {
        val includes = mutableSetOf<String>()
        app.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            var inDeviceTransfer = false
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if (xml.name == "device-transfer") inDeviceTransfer = true
                    if (inDeviceTransfer && xml.name == "include" && xml.getAttributeValue(null, "domain") == "sharedpref") {
                        includes += xml.getAttributeValue(null, "path")
                    }
                } else if (xml.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && xml.name == "device-transfer") {
                    inDeviceTransfer = false
                }
            }
        }
        assertTrue(includes.contains("quota_board.xml"))
        assertTrue("Widget bindings must migrate with accounts", includes.contains("quota_widgets.xml"))
    }

    private fun bind(provider: com.yusheng.quota.widget.QuotaWidgetProvider, vararg ids: Int) {
        val info = AppWidgetProviderInfo().apply {
            this.provider = ComponentName(app, provider.javaClass)
            initialLayout = if (provider is WideQuotaWidget) R.layout.widget_quota_wide else R.layout.widget_quota_small
        }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        host.addInstalledProvider(info)
        ids.forEach { host.addBoundWidget(it, info) }
    }

    private data class RestoreCase(
        val provider: com.yusheng.quota.widget.QuotaWidgetProvider,
        val oldId: Int,
        val newId: Int,
        val accountId: String,
        val title: String,
    )
}
