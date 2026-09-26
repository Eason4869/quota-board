package com.yusheng.quota

import androidx.lifecycle.ViewModelStore
import com.yusheng.quota.data.*
import com.yusheng.quota.ui.QuotaViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = QuotaApp::class)
class ViewModelRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: QuotaApp
    private val owner = ViewModelStore()
    private val settings = Settings(autoRefreshMinutes = 0, autoQueryOnStart = false, autoCheckUpdate = false)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication() as QuotaApp
        app.store.mutateAccounts { emptyList() }
        app.store.saveSettings(settings)
    }
    @After fun cleanup() { owner.clear(); Dispatchers.resetMain() }
    private fun vm(query: suspend (Template, QueryConfig, Int) -> QueryResult = { _, _, _ -> QueryResult() },
                   latest: suspend () -> com.yusheng.quota.update.UpdateChecker.ReleaseInfo? = { null }): QuotaViewModel =
        QuotaViewModel(app, query, latest).also { owner.put("vm", it) }

    @Test fun lateOldRequestCannotOverwriteNewResultOrClearItsSpinner() = runTest(dispatcher) {
        var calls = 0
        val model = vm(query = { _, _, _ ->
            val n = ++calls
            // Emulate an HTTP operation that does not cooperate with cancellation.
            withContext(NonCancellable) { delay(if (n == 1) 100 else 200) }
            QueryResult(balance = Balance(n.toDouble()))
        })
        val a = model.addAccount(Templates.byId("novita"), "test", QueryConfig())
        model.refresh(a.id); runCurrent()
        model.refresh(a.id); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertTrue(model.state.value.querying.contains(a.id))
        assertNull(app.store.loadAccounts().single().result)
        advanceTimeBy(100); runCurrent()
        assertEquals(2.0, app.store.loadAccounts().single().result!!.balance!!.amount, 0.0)
        assertTrue(model.state.value.querying.isEmpty())
    }
    @Test fun editingConfigurationInvalidatesInFlightResult() = runTest(dispatcher) {
        val model = vm(query = { _, _, _ ->
            withContext(NonCancellable) { delay(100) }
            QueryResult(balance = Balance(99.0))
        })
        val a = model.addAccount(Templates.byId("novita"), "test", QueryConfig())
        model.refresh(a.id); runCurrent()
        model.updateAccount(a.copy(query = a.query.copy(apiKey = "replacement")))
        advanceTimeBy(101); runCurrent()
        assertNull(app.store.loadAccounts().single().result)
        assertTrue(model.state.value.querying.isEmpty())
    }
    @Test fun oldRequestFinishingLastCannotReplaceNewResult() = runTest(dispatcher) {
        var calls = 0
        val model = vm(query = { _, _, _ ->
            val n = ++calls
            withContext(NonCancellable) { delay(if (n == 1) 200 else 100) }
            QueryResult(balance = Balance(n.toDouble()))
        })
        val a = model.addAccount(Templates.byId("novita"), "test", QueryConfig())
        model.refresh(a.id); runCurrent()
        model.refresh(a.id); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(2.0, app.store.loadAccounts().single().result!!.balance!!.amount, 0.0)
        advanceTimeBy(100); runCurrent()
        assertEquals(2.0, app.store.loadAccounts().single().result!!.balance!!.amount, 0.0)
    }
    @Test fun capturedJsonSupersedesPendingNetworkRequest() = runTest(dispatcher) {
        val model = vm(query = { _, _, _ ->
            withContext(NonCancellable) { delay(100) }
            QueryResult(balance = Balance(99.0))
        })
        val a = model.addAccount(Templates.byId("novita"), "test", QueryConfig())
        model.refresh(a.id); runCurrent()
        model.applyJson(a.id, """{"availableBalance":50000}"""); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(5.0, app.store.loadAccounts().single().result!!.balance!!.amount, 0.0)
        assertTrue(model.state.value.querying.isEmpty())
    }
    @Test fun invalidImportCannotChangeAccountsOrSettings() {
        val model = vm()
        val a = model.addAccount(Templates.byId("novita"), "test", QueryConfig())
        assertThrows(IllegalArgumentException::class.java) {
            model.importJson(listOf(a, a), settings.copy(darkMode = "dark"))
        }
        assertEquals(listOf(a), app.store.loadAccounts())
        assertEquals(settings, app.store.loadSettings())
    }
    @Test fun replacingSameIdByImportInvalidatesOldRequest() = runTest(dispatcher) {
        val model = vm(query = { _, _, _ ->
            withContext(NonCancellable) { delay(100) }
            QueryResult(balance = Balance(99.0))
        })
        val a = model.addAccount(Templates.byId("novita"), "test", QueryConfig())
        model.refresh(a.id); runCurrent()
        model.importJson(listOf(a.copy(name = "imported")), settings)
        advanceTimeBy(101); runCurrent()
        assertNull(app.store.loadAccounts().single().result)
    }
    @Test fun updateToggleWorksWithAutoRefreshDisabled() = runTest(dispatcher) {
        var checks = 0
        val model = vm(latest = { checks++; null })
        try {
            model.updateSettings(settings.copy(autoCheckUpdate = true)); runCurrent()
            advanceTimeBy(3_600_001); runCurrent()
            assertEquals(1, checks)
            model.updateSettings(settings); runCurrent()
            advanceTimeBy(3_600_001); runCurrent()
            assertEquals(1, checks)
            model.importJson(emptyList(), settings.copy(autoCheckUpdate = true)); runCurrent()
            advanceTimeBy(3_600_001); runCurrent()
            assertEquals(2, checks)
        } finally {
            // Stop the ViewModel's periodic job before runTest drains virtual time.
            owner.clear()
        }
    }
}
