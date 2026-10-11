package com.yusheng.quota

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.yusheng.quota.data.*
import com.yusheng.quota.widget.*
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
class WidgetPresentationTest {
    private val app get() = RuntimeEnvironment.getApplication() as QuotaApp

    @Before fun reset() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
        app.store.replaceAccounts(emptyList())
        app.store.saveSettings(Settings(autoRefreshMinutes = 0, autoQueryOnStart = false, autoCheckUpdate = false))
    }

    private fun render(account: Account, wide: Boolean = false, height: Int = 170, width: Int = if (wide) 340 else 160): View {
        app.store.replaceAccounts(listOf(account))
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(app, if (wide) WideQuotaWidget::class.java else SmallQuotaWidget::class.java)
            initialLayout = if (wide) R.layout.widget_quota_wide else R.layout.widget_quota_small
        }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        host.addInstalledProvider(info)
        host.addBoundWidget(301, info)
        AppWidgetManager.getInstance(app).updateAppWidgetOptions(301, android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        })
        WidgetStore(app).select(301, account.id)
        QuotaWidgets.render(app, 301)
        return host.getViewFor(301)
    }

    private fun visibleViews(root: View): List<View> {
        if (root.visibility != View.VISIBLE) return emptyList()
        return listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { visibleViews(root.getChildAt(it)) } else emptyList()
    }

    @Test fun balanceOnlySourceDoesNotShowQuotaBarsOrUnrelatedExtras() {
        val root = render(Account("a", "deepseek", "DeepSeek", result = QueryResult(
            balance = Balance(123.45), extras = listOf(Extra("granted", "10")), fetchedAt = 1000,
        )))
        val views = visibleViews(root)
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "¥123.45" })
        assertTrue(views.filterIsInstance<ProgressBar>().isEmpty())
        assertFalse(views.filterIsInstance<TextView>().any { it.text.contains("granted") })
    }

    @Test fun codexShowsEachRemainingWindowWithProgressAndResetTime() {
        for (wide in listOf(false, true)) {
            val root = render(Account("a", "openai", "Codex", result = QueryResult(periods = listOf(
                Period("weekly", usedPct = 25.0, resetAt = "2030-04-03 10:30"),
                Period("monthly", usedPct = 60.0, resetAt = "2030-05-01 08:00"),
            ))), wide)
            val views = visibleViews(root)
            assertEquals(listOf(75, 40), views.filterIsInstance<ProgressBar>().map { it.progress })
            val text = views.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            assertTrue(text.contains("04/03 10:30"))
            assertTrue(text.contains("05/01 08:00"))
        }
    }

    @Test fun volcanicPlansKeepTwoWindowsInSmallAndThreeInWideWidgets() {
        val agent = org.json.JSONObject("""{"Result":{
            "AFPFiveHour":{"Quota":100,"Used":25,"ResetTime":"2030-04-03 10:30"},
            "AFPWeekly":{"Quota":1000,"Used":400,"ResetTime":"2030-04-10 08:00"},
            "AFPMonthly":{"Quota":2000,"Used":1000,"ResetTime":"2030-05-01 00:00"}
        }}""")
        val coding = org.json.JSONObject("""{"Result":{"QuotaUsage":[
            {"Level":"session","Percent":25,"ResetTime":"2030-04-03 10:30"},
            {"Level":"weekly","Percent":40,"ResetTime":"2030-04-10 08:00"},
            {"Level":"monthly","Percent":50,"ResetTime":"2030-05-01 00:00"}
        ]}}""")
        for ((source, payload) in listOf("volc_agent" to agent, "volc_coding" to coding)) {
            val result = com.yusheng.quota.net.Parsers.parse(source, payload, "", "", app)
            assertEquals(listOf("5h", "weekly", "monthly"), result.periods.map { it.label })
            for (wide in listOf(false, true)) {
                val visible = visibleViews(render(Account("a", source, "Volcengine", result = result), wide))
                assertEquals("Windows must render for $source wide=$wide", if (wide) listOf(75, 60, 50) else listOf(75, 60), visible.filterIsInstance<ProgressBar>().map { it.progress })
                val text = visible.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                assertTrue(text.contains("04/03 10:30"))
                assertTrue(text.contains("04/10 08:00"))
                assertEquals(wide, text.contains("05/01 00:00"))
            }
        }
    }

    @Test fun aSourceWithBothBalanceAndQuotaStillShowsItsWindows() {
        val result = QueryResult(balance = Balance(88.0), periods = listOf(
            Period("5h", usedPct = 25.0), Period("weekly", usedPct = 40.0), Period("monthly", usedPct = 50.0),
        ))
        val views = visibleViews(render(Account("a", "volc_agent", "Volcengine", result = result), true))
        assertEquals(listOf(75, 60, 50), views.filterIsInstance<ProgressBar>().map { it.progress })
    }

    @Test fun bothWidgetSizesFollowTheSameAccountsLatestWindows() {
        val weekly = Account("volc", "volc_agent", "Volcengine", result = QueryResult(
            periods = listOf(Period("weekly", usedPct = 40.0)),
        ))
        app.store.replaceAccounts(listOf(weekly))
        val manager = AppWidgetManager.getInstance(app)
        val host = shadowOf(manager)
        for ((id, provider) in listOf(401 to SmallQuotaWidget::class.java, 402 to WideQuotaWidget::class.java)) {
            val info = AppWidgetProviderInfo().apply {
                this.provider = ComponentName(app, provider)
                initialLayout = if (id == 401) R.layout.widget_quota_small else R.layout.widget_quota_wide
            }
            host.addInstalledProvider(info)
            host.addBoundWidget(id, info)
            WidgetStore(app).select(id, weekly.id)
            QuotaWidgets.render(app, id)
            assertEquals(listOf(60), visibleViews(host.getViewFor(id)).filterIsInstance<ProgressBar>().map { it.progress })
        }
        app.store.replaceAccounts(listOf(weekly.copy(result = QueryResult(periods = listOf(
            Period("5h", usedPct = 25.0), Period("weekly", usedPct = 40.0), Period("monthly", usedPct = 50.0),
        )))))
        assertEquals(listOf(75, 60), visibleViews(host.getViewFor(401)).filterIsInstance<ProgressBar>().map { it.progress })
        assertEquals(listOf(75, 60, 50), visibleViews(host.getViewFor(402)).filterIsInstance<ProgressBar>().map { it.progress })
    }

    @Test fun cachedCodexQuotaRemainsUnchangedAfterNetworkFailure() {
        val account = Account("a", "openai", "Codex", result = QueryResult(
            periods = listOf(Period("weekly", usedPct = 25.0, resetAt = "2030-04-03 10:30")), fetchedAt = 1000,
        ))
        val before = WidgetContent.from(app, account)
        val after = WidgetContent.from(app, account.copy(lastError = "TLS timeout"))
        assertEquals("75%", after.value)
        assertEquals(before.periods, after.periods)
        assertNotEquals(before.status, after.status)
    }

    @Test fun failureWithoutCachedResultRemainsAnEmptyState() {
        val account = Account("a", "openai", "Codex")
        assertEquals("—", WidgetContent.from(app, account.copy(lastError = "offline")).value)
    }

    @Test fun resetCardCountIsShownOnlyWhenActuallyProvided() {
        val account = Account("a", "openai", "Codex", result = QueryResult(
            periods = listOf(Period("weekly", usedPct = 25.0)), extras = listOf(Extra("reset_cards", "3")),
        ))
        val text = visibleViews(render(account, true)).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(text.contains("Reset cards"))
        assertTrue(text.contains("3"))
        val emptyText = visibleViews(render(account.copy(result = account.result!!.copy(extras = emptyList())), true))
            .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertFalse(emptyText.contains("Reset cards"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun deepSeekLogoKeepsItsOriginalWideAspectRatio() {
        val bitmap = android.graphics.Bitmap.createBitmap(270, 270, android.graphics.Bitmap.Config.ARGB_8888)
        val logo = app.getDrawable(R.drawable.logo_deepseek)!!
        logo.setBounds(0, 0, 270, 270)
        logo.draw(android.graphics.Canvas(bitmap))
        var minX = 270; var minY = 270; var maxX = 0; var maxY = 0
        for (y in 0 until 270) for (x in 0 until 270) {
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 128) {
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        bitmap.recycle()
        val ratio = (maxY - minY + 1).toFloat() / (maxX - minX + 1)
        assertTrue("Whale silhouette must not be stretched vertically: ratio=$ratio", ratio in 0.70f..0.80f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun quotaValuesAndResetTimesFitAtSmallSizesAndLargerFonts() {
        val balance = Account("a", "deepseek", "DeepSeek", result = QueryResult(balance = Balance(25.96)))
        val quota = Account("a", "openai", "Codex", lastError = "offline", result = QueryResult(periods = listOf(
            Period("weekly", usedPct = 25.0, resetAt = "2030-04-03 10:30"),
            Period("monthly", usedPct = 60.0, resetAt = "2030-05-01 08:00"),
        )))
        val three = quota.copy(result = quota.result!!.copy(periods = listOf(Period("5h", usedPct = 10.0, resetAt = "2030-04-01 14:00")) + quota.result.periods))
        val volcanic = three.copy(templateId = "volc_agent", name = "火山引擎")
        val precise = volcanic.copy(id = "precise", result = volcanic.result!!.copy(periods = listOf(
            Period("5h", usedPct = 0.0, resetAt = "2030-04-01 14:00"),
            Period("weekly", usedPct = 0.01, resetAt = "2030-04-03 10:30"),
            Period("monthly", usedPct = 100.0, resetAt = "2030-05-01 08:00"),
        )))
        val mixedReset = volcanic.copy(id = "mixed-reset", templateId = "volc_coding", name = "coding plan", lastError = null,
            result = QueryResult(periods = listOf(
                Period("5h", usedPct = 0.0),
                Period("weekly", usedPct = 0.0, resetAt = "2030-10-12 00:00"),
                Period("monthly", usedPct = 0.0, resetAt = "2030-10-28 23:59"),
            )))
        val fixtures = listOf(
            Triple(balance, false, 160 to 170), Triple(quota, false, 160 to 170),
            Triple(quota, true, 340 to 170), Triple(three, true, 340 to 170),
            Triple(quota, false, 110 to 110), Triple(three, true, 250 to 110),
            Triple(volcanic, false, 160 to 170), Triple(volcanic, false, 110 to 110), Triple(volcanic, true, 340 to 170),
            Triple(precise, false, 110 to 110), Triple(precise, true, 250 to 110),
            Triple(volcanic, true, 250 to 170), Triple(precise, true, 250 to 170),
            Triple(mixedReset, true, 340 to 170), Triple(mixedReset, true, 250 to 170),
            Triple(mixedReset, false, 110 to 110), Triple(mixedReset, true, 250 to 110),
        )
        for (fontScale in listOf(1f, 1.3f)) for (dark in listOf(false, true)) for ((account, wide, size) in fixtures) {
            val config = android.content.res.Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
            app.store.saveSettings(app.store.loadSettings().copy(darkMode = if (dark) "dark" else "light"))
            val root = render(account, wide, size.second, size.first)
            val density = app.resources.displayMetrics.density
            val width = (size.first * density).toInt()
            val height = (size.second * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val background = root.findViewById<View>(android.R.id.background)
            assertEquals(app.resources.getDimensionPixelSize(R.dimen.widget_top_inset), background.top)
            assertEquals(height, background.bottom)
            val visible = visibleViews(root)
            assertEquals("Available windows must remain visible", minOf(account.result!!.periods.size, if (wide) 3 else 2), visible.filterIsInstance<ProgressBar>().size)
            if (wide || size.second < 140) {
                fun topInRoot(view: View): Int {
                    var top = view.top
                    var parent = view.parent
                    while (parent is View) { top += parent.top; parent = parent.parent }
                    return top
                }
                for (id in listOf(R.id.widget_period_value, R.id.widget_period_progress, R.id.widget_period_reset)) {
                    val elements = visible.filter { it.id == id }
                    if (elements.isNotEmpty()) assertEquals("Quota columns must align: ${account.id} $size id=$id", 1, elements.map { topInRoot(it) }.distinct().size)
                }
            }
            val values = visible.filterIsInstance<TextView>().filter { it.id == R.id.widget_period_value || it.id == R.id.widget_value }
            for (value in values) {
                val required = value.paint.fontMetrics.descent - value.paint.fontMetrics.ascent
                assertTrue("Value clipped: ${account.templateId} $wide $size text=${value.text} height=${value.height} required=$required", value.height >= required)
                assertEquals("The entire value must fit on one visible line: $wide $size text=${value.text}", 1, value.layout.lineCount)
                assertEquals("Value characters must not wrap into a hidden line: $wide $size text=${value.text}", value.text.length, value.layout.getLineEnd(0))
                for (line in 0 until value.layout.lineCount) {
                    assertEquals("Window value must fit: $wide $size text=${value.text}", 0, value.layout.getEllipsisCount(line))
                    assertTrue("Value text overflows horizontally: $wide $size text=${value.text}", value.layout.getLineWidth(line) <= value.width - value.compoundPaddingLeft - value.compoundPaddingRight + 1)
                }
            }
            for (reset in visible.filterIsInstance<TextView>().filter { it.id == R.id.widget_period_reset }) {
                for (line in 0 until reset.layout.lineCount) {
                    assertEquals("Reset time must fit: $wide $size text=${reset.text}", 0, reset.layout.getEllipsisCount(line))
                }
            }
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val output = java.io.File("build/outputs/widget-previews-v4").apply { mkdirs() }
            val suffix = (if (fontScale > 1f) "-large-font" else "") + (if (account.id != "a") "-${account.id}" else "")
            java.io.File(output, "${account.templateId}-${account.result!!.periods.size}-${size.first}x${size.second}-${if (dark) "dark" else "light"}$suffix.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
