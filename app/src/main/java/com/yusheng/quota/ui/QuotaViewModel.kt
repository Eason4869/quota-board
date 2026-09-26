package com.yusheng.quota.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yusheng.quota.QuotaApp
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.ImportCodec
import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Settings
import com.yusheng.quota.data.Template
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.QueryEngine
import com.yusheng.quota.net.Parsers
import com.yusheng.quota.update.UpdateChecker
import com.yusheng.quota.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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

class QuotaViewModel internal constructor(
    app: Application,
    private val query: suspend (Template, QueryConfig, Int) -> QueryResult,
    private val fetchLatest: suspend () -> UpdateChecker.ReleaseInfo?,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, QueryEngine(app)::query, UpdateChecker::fetchLatest)

    /** 一次性 UI 事件：查询结果提示、导入导出提示等 */
    sealed interface Event {
        data class QueryFinished(val id: String, val error: String?) : Event
        data class Message(val text: String) : Event
    }

    private val store = (app as QuotaApp).store

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
    private var updateCheckJob: Job? = null
    /** 拖动排序的写盘防抖 */
    private var orderSaveJob: Job? = null
    private val queryJobs = mutableMapOf<String, Job>()
    private val queryTokens = mutableMapOf<String, Any>()

    private fun invalidateQuery(id: String) {
        queryTokens.remove(id)
        queryJobs.remove(id)?.cancel()
        _state.value = _state.value.copy(querying = _state.value.querying - id)
    }

    private fun invalidateAllQueries() {
        queryTokens.keys.toList().forEach(::invalidateQuery)
    }

    init {
        if (_state.value.settings.autoQueryOnStart && _state.value.accounts.isNotEmpty()) {
            refreshAll()
        }
        restartAutoRefresh()
        // 更新检测不跟启动：改为后台定时（见 restartUpdateCheck）
        restartUpdateCheck()
    }

    // ── 应用内更新：检测 → 下载 → 直接调起安装器 ──────────────

    fun checkUpdate(manual: Boolean) {
        val current = _state.value.update
        if (current.checking || current.downloading) return
        _state.value = _state.value.copy(update = current.copy(checking = true, error = null))
        viewModelScope.launch {
            val info = fetchLatest()
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

    /** 镜像链全没走通时的兜底出口：把用户送去浏览器里的 Release 页 */
    fun openReleasePage() = UpdateChecker.openReleasePage(getApplication())

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
        persist { it + account }
        return account
    }

    /**
     * 保存编辑。用户能改的只有名字 / 厂商 / 查询配置，其余字段（result、lastError、
     * updatedAt）保持磁盘上的最新值——编辑页停留期间刷新可能刚写回一份新结果，
     * 别被编辑页手里那份旧对象抹掉。
     */
    fun updateAccount(account: Account) {
        invalidateQuery(account.id)
        persist { list ->
            list.map {
                if (it.id != account.id) {
                    it
                } else {
                    it.copy(name = account.name, templateId = account.templateId, query = account.query)
                }
            }
        }
    }

    fun deleteAccount(id: String) {
        invalidateQuery(id)
        persist { list -> list.filterNot { it.id == id } }
        if (_state.value.activeId == id) setActive(null)
    }

    fun clearAccounts() {
        invalidateAllQueries()
        persist { emptyList() }
        setActive(null)
    }

    /** 首页拖动排序：把 from 位置的账户移到 to 位置并落盘 */
    /**
     * 首页拖动排序。拖动过程中每帧都会调用这里，所以只改内存状态，
     * 写盘用 400ms 防抖延后一次 —— 否则每帧 JSON 全量序列化会明显卡顿。
     */
    fun moveAccount(from: Int, to: Int) {
        val list = _state.value.accounts.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        _state.value = _state.value.copy(accounts = list)
        orderSaveJob?.cancel()
        orderSaveJob = viewModelScope.launch {
            delay(400)
            // mutateAccounts 是线程安全的写盘入口：把内存里的新顺序直接写回
            store.mutateAccounts { _state.value.accounts }
        }
    }

    /** 导出 JSON（含凭证，提示用户妥善保管） */
    fun exportPayload(): String =
        ImportCodec.encode(_state.value.accounts, _state.value.settings)

    fun updateSettings(settings: Settings) {
        store.saveSettings(settings)
        _state.value = _state.value.copy(settings = settings)
        restartAutoRefresh()
        restartUpdateCheck()
    }

    fun importJson(accounts: List<Account>, settings: Settings) {
        ImportCodec.validateAccounts(accounts)
        invalidateAllQueries()
        store.saveSettings(settings)
        _state.value = _state.value.copy(settings = settings)
        // 导入本来就是整表替换：忽略磁盘上的旧列表，直接落这份
        persist { accounts }
        restartAutoRefresh()
        restartUpdateCheck()
    }

    /**
     * 查询单个账户。
     * 请求前后隔着一次网络往返，期间用户可能改了配置、删了账户，或者自动刷新
     * 刚把同一账户的结果写回。所以这里只把**结果**带出来，落库时按 id 合并到
     * 磁盘上的最新版本，而不是拿请求前那份快照整条盖回去。
     */
    fun refresh(id: String) {
        val account = account(id) ?: return
        invalidateQuery(id)
        val token = Any()
        queryTokens[id] = token
        _state.value = _state.value.copy(querying = _state.value.querying + id)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                var error: String? = null
                val result = try {
                    query(
                        Templates.byId(account.templateId),
                        account.query,
                        _state.value.settings.timeoutSec,
                    )
                } catch (e: CancellationException) {
                    // 协程被取消（页面销毁等）不是查询失败，别把它写成一次错误结果
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    null
                }
                if (queryTokens[id] !== token) return@launch
                val now = System.currentTimeMillis()
                val merged = store.patchAccount(id) { fresh ->
                    if (fresh.query != account.query || fresh.templateId != account.templateId) {
                        fresh
                    } else if (result != null) {
                        fresh.copy(result = result, lastError = null, updatedAt = now)
                    } else {
                        // 失败时保留上一次的结果，只记错误与时间
                        fresh.copy(lastError = error, updatedAt = now)
                    }
                }
                _state.value = _state.value.copy(
                    // merged 为 null 说明这期间账户被删了：跟着从内存里去掉，别复活它
                    accounts = merged ?: _state.value.accounts.filterNot { it.id == id },
                )
                _events.tryEmit(Event.QueryFinished(id, error))
            } finally {
                if (queryTokens[id] === token) {
                    queryTokens.remove(id)
                    queryJobs.remove(id)
                    _state.value = _state.value.copy(querying = _state.value.querying - id)
                }
            }
        }
        queryJobs[id] = job
        job.start()
    }

    fun refreshAll() {
        _state.value.accounts.forEach { refresh(it.id) }
    }

    /**
     * 用在应用内 WebView 里取回的原始 JSON 直接落库。
     * 登录页已成功获取的响应不必再走网络重查一次。
     */
    fun applyJson(id: String, json: String) {
        val account = account(id) ?: return
        invalidateQuery(id)
        val token = Any()
        queryTokens[id] = token
        viewModelScope.launch {
            if (queryTokens[id] !== token) return@launch
            val parsed = runCatching {
                Parsers.parse(
                    templateId = account.templateId,
                    json = org.json.JSONObject(json),
                    mapBalance = account.query.mapBalance,
                    mapPlan = account.query.mapPlan,
                    context = getApplication(),
                )
            }
            val now = System.currentTimeMillis()
            // 同样按 id 合并：解析失败的记录错误、保留旧结果，其余字段以磁盘为准
            val merged = store.patchAccount(id) { fresh ->
                if (fresh.query != account.query || fresh.templateId != account.templateId) fresh else parsed.fold(
                    onSuccess = { fresh.copy(result = it, lastError = null, updatedAt = now) },
                    onFailure = { fresh.copy(lastError = it.message, updatedAt = now) },
                )
            }
            if (merged != null) _state.value = _state.value.copy(accounts = merged)
            queryTokens.remove(id)
            _events.tryEmit(Event.QueryFinished(id, parsed.exceptionOrNull()?.message))
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

    /**
     * 更新检测：**不跟启动**，改为后台定时轮询（默认每 1 小时一次）。
     * 检测结果只更新「关于」页那一行的状态，不弹任何浮层。
     */
    private fun restartUpdateCheck() {
        updateCheckJob?.cancel()
        if (!_state.value.settings.autoCheckUpdate) return
        updateCheckJob = viewModelScope.launch {
            while (isActive) {
                delay(UPDATE_CHECK_INTERVAL_MS)
                checkUpdate(manual = false)
            }
        }
    }

    private companion object {
        /** 定时检测间隔：1 小时 */
        const val UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }

    /**
     * 账户列表的事务性改动：交给 [Store.mutateAccounts] 基于**磁盘上的最新列表**
     * 现算后写回，再把结果同步进内存。不要用 `_state.value.accounts` 当输入——
     * 内存里那份可能落后于磁盘，拿它整表覆盖会把别人的写入抹掉。
     */
    private fun persist(transform: (List<Account>) -> List<Account>) {
        _state.value = _state.value.copy(accounts = store.mutateAccounts(transform))
    }
}
