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
import com.yusheng.quota.update.UpdateChecker
import com.yusheng.quota.widget.QuotaWidget
import com.yusheng.quota.widget.QuotaWidgetWorker
import com.yusheng.quota.BuildConfig
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

/** 应用内更新的界面状态 */
data class UpdateUi(
    val checking: Boolean = false,
    val info: UpdateChecker.ReleaseInfo? = null,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val error: String? = null,
    /** 已下载完成、等待授权或安装 */
    val pendingInstall: java.io.File? = null,
    val needsPermission: Boolean = false,
    val dismissed: Boolean = false,
)

data class UiState(
    val accounts: List<Account> = emptyList(),
    val settings: Settings = Settings(),
    val activeId: String? = null,
    val querying: Set<String> = emptySet(),
    val update: UpdateUi = UpdateUi(),
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
        if (_state.value.settings.autoCheckUpdate) checkUpdate(manual = false)
    }

    // ── 应用内更新：检测 → 下载 → 直接调起安装器 ──────────────

    fun checkUpdate(manual: Boolean) {
        val current = _state.value.update
        if (current.checking || current.downloading) return
        _state.value = _state.value.copy(update = current.copy(checking = true, error = null))
        viewModelScope.launch {
            val info = UpdateChecker.fetchLatest()
            val newer = info?.takeIf { UpdateChecker.isNewer(BuildConfig.VERSION_NAME, it.versionName) }
            _state.value = _state.value.copy(
                update = _state.value.update.copy(
                    checking = false,
                    info = newer,
                    dismissed = false,
                    error = null,
                )
            )
            if (manual) {
                when {
                    info == null -> emit("check_failed")
                    newer == null -> emit("up_to_date")
                }
            }
        }
    }

    /** 从「安装未知应用」授权页返回后自动继续安装 */
    fun onResumed() {
        val u = _state.value.update
        if (u.needsPermission && UpdateChecker.canInstall(getApplication())) {
            _state.value = _state.value.copy(update = u.copy(needsPermission = false))
            installPending()
        }
    }

    /** 下载并安装：首次会引导去开启「安装未知应用」 */
    fun downloadAndInstall() {
        val info = _state.value.update.info ?: return
        val app = getApplication<Application>()
        if (!UpdateChecker.canInstall(app)) {
            _state.value = _state.value.copy(update = _state.value.update.copy(needsPermission = true))
            UpdateChecker.openInstallPermission(app)
            return
        }
        _state.value = _state.value.copy(
            update = _state.value.update.copy(downloading = true, progress = 0, error = null)
        )
        viewModelScope.launch {
            val file = UpdateChecker.download(app, info) { pct ->
                _state.value = _state.value.copy(update = _state.value.update.copy(progress = pct))
            }
            if (file == null) {
                _state.value = _state.value.copy(
                    update = _state.value.update.copy(downloading = false, error = "download_failed")
                )
                return@launch
            }
            _state.value = _state.value.copy(
                update = _state.value.update.copy(downloading = false, pendingInstall = file, progress = 100)
            )
            installPending()
        }
    }

    /** 从「安装未知应用」授权页回来后重试安装 */
    fun installPending() {
        val app = getApplication<Application>()
        val file = _state.value.update.pendingInstall ?: return
        if (!UpdateChecker.canInstall(app)) {
            _state.value = _state.value.copy(update = _state.value.update.copy(needsPermission = true))
            return
        }
        val opened = UpdateChecker.install(app, file)
        if (!opened) {
            _state.value = _state.value.copy(
                update = _state.value.update.copy(error = "install_failed")
            )
        } else {
            _state.value = _state.value.copy(update = _state.value.update.copy(dismissed = true))
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(
            update = _state.value.update.copy(dismissed = true, error = null, needsPermission = false)
        )
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
