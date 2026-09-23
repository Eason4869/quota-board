package com.yusheng.quota.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yusheng.quota.QuotaApp
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.ImportCodec
import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.Settings
import com.yusheng.quota.data.Template
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.QueryEngine
import com.yusheng.quota.net.Parsers
import com.yusheng.quota.widget.QuotaWidget
import com.yusheng.quota.widget.QuotaWidgetWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class UiState(
    val accounts: List<Account> = emptyList(),
    val settings: Settings = Settings(),
    val activeId: String? = null,
    val querying: Set<String> = emptySet(),
)

class QuotaViewModel(app: Application) : AndroidViewModel(app) {

    /** 一次性 UI 事件：查询结果提示、导入导出提示等 */
    sealed interface Event {
        data class QueryFinished(val id: String, val error: String?) : Event
        data class Message(val text: String) : Event
    }

    private val store = (app as QuotaApp).store
    private val engine = QueryEngine(app)

    private val _state = MutableStateFlow(
        UiState(
            accounts = store.loadAccounts(),
            settings = store.loadSettings(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var autoRefreshJob: Job? = null

    init {
        // 后台小组件刷新间隔跟随设置
        QuotaWidgetWorker.schedule(app, _state.value.settings.autoRefreshMinutes)
        if (_state.value.settings.autoQueryOnStart && _state.value.accounts.isNotEmpty()) {
            refreshAll()
        }
        restartAutoRefresh()
    }

    fun account(id: String?): Account? = _state.value.accounts.firstOrNull { it.id == id }

    fun setActive(id: String?) {
        _state.value = _state.value.copy(activeId = id)
    }

    fun emit(message: String) {
        _events.tryEmit(Event.Message(message))
    }

    /** 新增账户（同一厂商可重复添加） */
    fun addAccount(template: Template, name: String, cfg: QueryConfig): Account {
        val account = Account(
            id = UUID.randomUUID().toString(),
            templateId = template.id,
            name = name.ifBlank { template.id },
            query = cfg,
            // 还没查过就不能算「已同步」，留给 refresh() 写时间戳
            updatedAt = 0L,
        )
        persist(_state.value.accounts + account)
        return account
    }

    fun updateAccount(account: Account) {
        persist(_state.value.accounts.map { if (it.id == account.id) account else it })
    }

    fun deleteAccount(id: String) {
        persist(_state.value.accounts.filterNot { it.id == id })
        if (_state.value.activeId == id) setActive(null)
    }

    fun clearAccounts() = persist(emptyList())

    /** 导出 JSON（含凭证，提示用户妥善保管） */
    fun exportPayload(): String =
        ImportCodec.encode(_state.value.accounts, _state.value.settings)

    fun updateSettings(settings: Settings) {
        store.saveSettings(settings)
        _state.value = _state.value.copy(settings = settings)
        QuotaWidgetWorker.schedule(getApplication(), settings.autoRefreshMinutes)
        restartAutoRefresh()
    }

    fun importJson(accounts: List<Account>, settings: Settings) {
        store.saveSettings(settings)
        _state.value = _state.value.copy(settings = settings)
        persist(accounts)
        QuotaWidgetWorker.schedule(getApplication(), settings.autoRefreshMinutes)
        restartAutoRefresh()
    }

    /** 查询单个账户 */
    fun refresh(id: String) {
        val account = account(id) ?: return
        _state.value = _state.value.copy(querying = _state.value.querying + id)
        viewModelScope.launch {
            var error: String? = null
            val updated = try {
                val result = engine.query(
                    template = Templates.byId(account.templateId),
                    cfg = account.query,
                    defaultTimeoutSec = _state.value.settings.timeoutSec,
                )
                account.copy(result = result, lastError = null, updatedAt = System.currentTimeMillis())
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                account.copy(lastError = error, updatedAt = System.currentTimeMillis())
            }
            val list = _state.value.accounts.map { if (it.id == id) updated else it }
            store.saveAccounts(list)
            _state.value = _state.value.copy(accounts = list, querying = _state.value.querying - id)
            // 查询完成后立即刷新桌面小组件
            runCatching { QuotaWidget.refreshAll(getApplication()) }
            _events.tryEmit(Event.QueryFinished(id, error))
        }
    }

    fun refreshAll() {
        _state.value.accounts.forEach { refresh(it.id) }
    }

    /**
     * 用在应用内 WebView 里取回的原始 JSON 直接落库。
     * 有些控制台接口的登录态是 HttpOnly，拿不到 Cookie 字符串，
     * 只能靠 WebView 同源 fetch —— 这条路走通了就不必再走网络重查一次。
     */
    fun applyJson(id: String, json: String) {
        val account = account(id) ?: return
        viewModelScope.launch {
            val error = runCatching {
                val parsed = Parsers.parse(
                    templateId = account.templateId,
                    json = org.json.JSONObject(json),
                    mapBalance = account.query.mapBalance,
                    mapPlan = account.query.mapPlan,
                    context = getApplication(),
                )
                val updated = account.copy(
                    result = parsed,
                    lastError = null,
                    updatedAt = System.currentTimeMillis(),
                )
                val list = _state.value.accounts.map { if (it.id == id) updated else it }
                store.saveAccounts(list)
                _state.value = _state.value.copy(accounts = list)
                runCatching { QuotaWidget.refreshAll(getApplication()) }
            }.exceptionOrNull()
            _events.tryEmit(Event.QueryFinished(id, error?.message))
        }
    }

    /** 前台自动刷新循环；间隔 0 表示关闭 */
    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        val minutes = _state.value.settings.autoRefreshMinutes
        if (minutes <= 0) return
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(minutes.coerceAtLeast(1) * 60_000L)
                if (_state.value.accounts.isNotEmpty()) refreshAll()
            }
        }
    }

    private fun persist(accounts: List<Account>) {
        store.saveAccounts(accounts)
        _state.value = _state.value.copy(accounts = accounts)
    }
}
