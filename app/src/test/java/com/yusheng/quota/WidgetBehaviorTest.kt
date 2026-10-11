package com.yusheng.quota

import android.os.Looper
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.res.Configuration
import android.widget.RemoteViews
import android.widget.TextView
import androidx.lifecycle.ViewModelStore
import com.yusheng.quota.data.*
import com.yusheng.quota.ui.QuotaViewModel
import com.yusheng.quota.widget.WidgetContent
import com.yusheng.quota.widget.WidgetStore
import com.yusheng.quota.widget.QuotaWidgets
import com.yusheng.quota.widget.SmallQuotaWidget
import com.yusheng.quota.widget.refreshWidgetAccount
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = QuotaApp::class)
class WidgetBehaviorTest {
    private val app get() = RuntimeEnvironment.getApplication() as QuotaApp
    private val initial = Account("a", "openai", "Codex", result = QueryResult(balance = Balance(7.0), fetchedAt = 1000))

    @Before fun reset() {
        app.store.mutateAccounts { listOf(initial) }
        app.store.saveSettings(Settings(autoRefreshMinutes = 0, autoQueryOnStart = false, autoCheckUpdate = false))
    }

    @Test fun instancesKeepIndependentSelectionsAndDeletingOneDoesNotClearAnother() {
        val selection = WidgetStore(app)
        selection.select(1, "a")
        selection.select(2, "b")
        selection.remove(1)
        assertNull(selection.accountId(1))
        assertEquals("b", selection.accountId(2))
    }

    @Test fun queryFailureRetainsBalanceAndLastSuccessTime() = runBlocking {
        refreshWidgetAccount(app.store, "a") { _, _, _ -> throw IllegalStateException("offline") }
        val saved = app.store.loadAccounts().single()
        assertEquals(initial.result, saved.result)
        assertEquals("offline", saved.lastError)
        assertEquals(1000, saved.result!!.fetchedAt)
        val stale = WidgetContent.from(app, saved)
        assertEquals(WidgetContent.from(app, initial).value, stale.value)
        assertNotEquals(WidgetContent.from(app, initial).status, stale.status)
    }

    @Test fun deletingSourceDuringRequestDoesNotReviveIt() = runBlocking {
        refreshWidgetAccount(app.store, "a") { _, _, _ ->
            app.store.mutateAccounts { emptyList() }
            QueryResult(balance = Balance(99.0))
        }
        assertTrue(app.store.loadAccounts().isEmpty())
        assertEquals("—", WidgetContent.from(app, null).value)
    }

    @Test fun editingCredentialsDuringRequestDiscardsOldResponse() = runBlocking {
        refreshWidgetAccount(app.store, "a") { _, _, _ ->
            app.store.patchAccount("a") { it.copy(query = it.query.copy(apiKey = "new")) }
            QueryResult(balance = Balance(99.0))
        }
        val saved = app.store.loadAccounts().single()
        assertEquals("new", saved.query.apiKey)
        assertEquals(initial.result, saved.result)
    }

    @Test fun importingSameIdDuringWidgetQueryDiscardsPreImportResponse() = runBlocking {
        val owner = ViewModelStore()
        try {
            val vm = QuotaViewModel(app, { _, _, _ -> QueryResult() }, { null })
            owner.put("test", vm)
            val renamed = initial.copy(name = "Imported name")
            refreshWidgetAccount(app.store, "a") { _, _, _ ->
                vm.importJson(listOf(renamed), app.store.loadSettings())
                QueryResult(balance = Balance(99.0))
            }
            assertEquals(renamed, app.store.loadAccounts().single())
        } finally { owner.clear() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun minimumWidgetSizeKeepsBalanceVisibleAtLargerFontScale() {
        app.resources.configuration.fontScale = 1.3f
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(app.resources.configuration, app.resources.displayMetrics)
        for (locale in listOf(java.util.Locale.US, java.util.Locale.SIMPLIFIED_CHINESE)) {
            val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
            for ((layout, width) in listOf(R.layout.widget_quota_small to 110, R.layout.widget_quota_wide to 250)) {
                val content = WidgetContent.from(app, initial.copy(lastError = "offline", result = initial.result!!.copy(fetchedAt = System.currentTimeMillis())))
                val views = RemoteViews(app.packageName, layout)
                views.setTextViewText(R.id.widget_value, "¥123.45")
                views.setTextViewText(R.id.widget_detail, "剩余：5 小时 75% · 每周 50%")
                views.setTextViewText(R.id.widget_status, if (layout == R.layout.widget_quota_small) content.compactStatus else content.status)
                val root = views.apply(app, null)
                val density = app.resources.displayMetrics.density
                root.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec((width * density).toInt(), android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec((110 * density).toInt(), android.view.View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val value = root.findViewById<TextView>(R.id.widget_value)
                val rows = root.findViewById<android.widget.LinearLayout>(android.R.id.background)
                val heights = (0 until rows.childCount).map { rows.getChildAt(it).measuredHeight }
                assertTrue("Balance must remain visible: layout=$layout value=${value.height} density=$density rows=$heights", value.height >= 20 * density)
                val status = root.findViewById<TextView>(R.id.widget_status)
                assertEquals("Last success time must remain visible: locale=$locale layout=$layout text=${status.text}", 0, status.layout.getEllipsisCount(0))
                val output = java.io.File("build/outputs/widget-previews").apply { mkdirs() }
                for (height in listOf(110, 170)) {
                    val previewWidth = if (height == 110) width else if (layout == R.layout.widget_quota_small) 170 else 350
                    root.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec((previewWidth * density).toInt(), android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec((height * density).toInt(), android.view.View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(output, "${locale.language}-${width}x$height.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun latestForegroundOrWidgetRequestWinsAcrossStoreInstances() {
        val token1 = app.store.beginAccountQuery("a")
        val secondStore = Store(app)
        val token2 = secondStore.beginAccountQuery("a")
        secondStore.patchQueryAccount("a", token2) { it.copy(result = QueryResult(balance = Balance(20.0))) }
        app.store.patchQueryAccount("a", token1) { it.copy(result = QueryResult(balance = Balance(10.0))) }
        assertEquals(20.0, app.store.loadAccounts().single().result!!.balance!!.amount, 0.0)
    }

    @Test fun foregroundObservesResultsWrittenByBackgroundStore() {
        val owner = ViewModelStore()
        try {
            val vm = QuotaViewModel(app, { _, _, _ -> QueryResult() }, { null })
            owner.put("test", vm)
            Store(app).patchAccount("a") { it.copy(result = QueryResult(balance = Balance(22.0))) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(22.0, vm.state.value.accounts.single().result!!.balance!!.amount, 0.0)
        } finally { owner.clear() }
    }

    @Test fun cancellationDoesNotBecomeAnAccountError() = runBlocking {
        try {
            refreshWidgetAccount(app.store, "a") { _, _, _ -> throw CancellationException("stopped") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(initial, app.store.loadAccounts().single())
    }

    @Test fun codexPercentAndCurrencyBalancesRenderAsDifferentValues() {
        val quota = initial.copy(result = QueryResult(periods = listOf(Period("5h", usedPct = 25.0))))
        assertEquals("75%", WidgetContent.from(app, quota).value)
        assertTrue(WidgetContent.from(app, initial).value.startsWith("¥"))
    }

    @Test fun bothRemoteViewsLayoutsInflateAndAcceptLiveValues() {
        for (layout in listOf(R.layout.widget_quota_small, R.layout.widget_quota_wide)) {
            val views = RemoteViews(app.packageName, layout)
            views.setTextViewText(R.id.widget_value, "¥123.45")
            views.setTextViewText(R.id.widget_title, "My source")
            val root = views.apply(app, null)
            assertEquals("¥123.45", root.findViewById<TextView>(R.id.widget_value).text)
            assertEquals("My source", root.findViewById<TextView>(R.id.widget_title).text)
        }
    }

    @Test fun systemThemeChangesRepaintExistingWidgetWithoutQuerying() {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(app, SmallQuotaWidget::class.java)
            initialLayout = R.layout.widget_quota_small
        }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        host.addInstalledProvider(info)
        host.addBoundWidget(201, info)
        WidgetStore(app).select(201, "a")
        val config = Configuration(app.resources.configuration)
        config.uiMode = Configuration.UI_MODE_NIGHT_NO
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        QuotaWidgets.render(app, 201)
        val before = host.getViewFor(201).findViewById<TextView>(R.id.widget_value).currentTextColor
        config.uiMode = Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        app.onConfigurationChanged(config)
        val after = host.getViewFor(201).findViewById<TextView>(R.id.widget_value).currentTextColor
        assertNotEquals(before, after)
        assertEquals(initial.result, app.store.loadAccounts().single().result)
    }

    @Test fun changingSourceClearsPreviousCodexLogoTintOnHostReuse() {
        app.store.mutateAccounts { it + initial.copy(id = "b", templateId = "siliconflow", name = "SiliconFlow") }
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(app, SmallQuotaWidget::class.java)
            initialLayout = R.layout.widget_quota_small
        }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        host.addInstalledProvider(info)
        host.addBoundWidget(202, info)
        val selection = WidgetStore(app)
        selection.select(202, "a")
        QuotaWidgets.render(app, 202)
        val before = host.getViewFor(202).findViewById<android.widget.ImageView>(R.id.widget_logo)
        assertNotEquals(android.graphics.Color.TRANSPARENT, shadowOf(before.colorFilter as android.graphics.PorterDuffColorFilter).color)
        selection.select(202, "b")
        QuotaWidgets.render(app, 202)
        val after = host.getViewFor(202).findViewById<android.widget.ImageView>(R.id.widget_logo)
        assertEquals(android.graphics.Color.TRANSPARENT, shadowOf(after.colorFilter as android.graphics.PorterDuffColorFilter).color)
    }
}
