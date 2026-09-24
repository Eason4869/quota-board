package com.yusheng.quota.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.yusheng.quota.R
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import com.yusheng.quota.ui.theme.Glass
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.QueryMode
import com.yusheng.quota.data.Template
import com.yusheng.quota.data.Templates
import kotlin.math.roundToInt

private enum class Screen { HOME, DETAIL, CATALOG, CONFIG, SETTINGS, ABOUT }

/** 登录抓取请求：在应用内 WebView 登录，成功后回填 Cookie / 直接带回额度 JSON */
private data class LoginRequest(
    val loginUrl: String,
    val fetchUrl: String,
    val onResult: (cookie: String?, json: String?) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaAppRoot(vm: QuotaViewModel) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var draftTemplate by remember { mutableStateOf<Template?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftCfg by remember { mutableStateOf(QueryConfig()) }
    var loginRequest by remember { mutableStateOf<LoginRequest?>(null) }

    val ctx = LocalContext.current
    val queryOk = stringResource(R.string.toast_query_ok)
    val queryFailed = stringResource(R.string.toast_query_failed)

    // 查询结束的统一反馈（VM 不持有文案，文案留在 UI 层，便于多语言）
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is QuotaViewModel.Event.QueryFinished ->
                    event.error?.let { snackbar.showSnackbar("$queryFailed：$it") }
                is QuotaViewModel.Event.Message ->
                    snackbar.showSnackbar(event.text)
            }
        }
    }

    // 系统返回 / 侧边滑动返回：按页面层级回退，不再直接退出应用
    //（登录页自己会先接管返回键，见 LoginCaptureScreen）
    BackHandler(enabled = loginRequest == null && screen != Screen.HOME) {
        screen = when (screen) {
            Screen.DETAIL, Screen.CATALOG, Screen.SETTINGS -> Screen.HOME
            Screen.CONFIG -> if (editing != null) Screen.DETAIL else Screen.CATALOG
            Screen.ABOUT -> Screen.SETTINGS
            Screen.HOME -> Screen.HOME
        }
    }

    Box(Modifier.fillMaxSize().background(Glass.background())) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (screen == Screen.HOME || screen == Screen.CATALOG || screen == Screen.SETTINGS) {
                    GlassBottomBar(
                        current = when (screen) {
                            Screen.CATALOG -> 1
                            Screen.SETTINGS -> 2
                            else -> 0
                        },
                        onSelect = { idx ->
                            screen = when (idx) {
                                1 -> Screen.CATALOG
                                2 -> Screen.SETTINGS
                                else -> Screen.HOME
                            }
                        },
                    )
                }
            },
            topBar = {
                var title: String = stringResource(R.string.app_name)
                var back: (() -> Unit)? = null
                when (screen) {
                    Screen.HOME -> Unit
                    Screen.DETAIL -> {
                        title = stringResource(R.string.title_result)
                        back = { screen = Screen.HOME }
                    }
                    Screen.CATALOG -> {
                        title = stringResource(R.string.title_add)
                        back = { screen = Screen.HOME }
                    }
                    Screen.CONFIG -> {
                        val tpl = draftTemplate
                        title = if (tpl != null) stringResource(tpl.nameRes) else stringResource(R.string.title_add)
                        back = { screen = if (editing != null) Screen.DETAIL else Screen.CATALOG }
                    }
                    Screen.SETTINGS -> {
                        title = stringResource(R.string.title_settings)
                        back = { screen = Screen.HOME }
                    }
                    Screen.ABOUT -> {
                        title = stringResource(R.string.title_about)
                        back = { screen = Screen.SETTINGS }
                    }
                }
                TopAppBar(
                    title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        back?.let { onBack ->
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.HOME || screen == Screen.DETAIL) {
                            IconButton(onClick = {
                                if (screen == Screen.DETAIL) activeId?.let { vm.refresh(it) } else vm.refreshAll()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // 页面切换动效：淡入 + 微位移，方向跟随前进 / 后退
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        (
                            slideInHorizontally(tween(240)) { w -> if (forward) w / 10 else -w / 10 } +
                                fadeIn(tween(220))
                            ) togetherWith (
                            slideOutHorizontally(tween(220)) { w -> if (forward) -w / 14 else w / 14 } +
                                fadeOut(tween(160))
                            )
                    },
                    label = "screen",
                ) { target ->
                when (target) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        onOpen = { activeId = it; vm.setActive(it); screen = Screen.DETAIL },
                        onDelete = { vm.deleteAccount(it) },
                        onAdd = { screen = Screen.CATALOG },
                    )

                    Screen.DETAIL -> {
                        val account = state.accounts.firstOrNull { it.id == activeId }
                        if (account == null) {
                            screen = Screen.HOME
                        } else {
                            DetailScreen(
                                account = account,
                                querying = state.querying.contains(account.id),
                                onQuery = { vm.refresh(account.id) },
                                onConfig = {
                                    editing = account
                                    draftTemplate = Templates.byId(account.templateId)
                                    draftName = account.name
                                    draftCfg = account.query
                                    screen = Screen.CONFIG
                                },
                                onLoginCapture = { loginUrl ->
                                    loginRequest = LoginRequest(loginUrl, account.query.url) { cookie, json ->
                                        val q = account.query
                                        if (!cookie.isNullOrBlank()) {
                                            vm.updateAccount(account.copy(query = q.copy(cookie = cookie)))
                                        }
                                        if (json != null) vm.applyJson(account.id, json) else vm.refresh(account.id)
                                    }
                                },
                                onDelete = { vm.deleteAccount(account.id); screen = Screen.HOME },
                            )
                        }
                    }

                    Screen.CATALOG -> CatalogScreen(
                        onPick = { t ->
                            editing = null
                            draftTemplate = t
                            draftName = t.id
                            draftCfg = t.defaults
                            screen = Screen.CONFIG
                        },
                    )

                    Screen.CONFIG -> {
                        val tpl = draftTemplate
                        if (tpl == null) {
                            screen = Screen.CATALOG
                        } else {
                            ConfigScreen(
                                template = tpl,
                                name = draftName,
                                cfg = draftCfg,
                                onNameChange = { draftName = it },
                                onCfgChange = { draftCfg = it },
                                onLoginCapture = { loginUrl, fetchUrl ->
                                    loginRequest = LoginRequest(loginUrl, fetchUrl) { cookie, _ ->
                                        if (!cookie.isNullOrBlank()) draftCfg = draftCfg.copy(cookie = cookie)
                                    }
                                },
                                onSave = {
                                    val cfg = draftCfg.copy(
                                        mode = draftCfg.mode,
                                        timeoutSec = draftCfg.timeoutSec,
                                    )
                                    val name = draftName.ifBlank { tpl.id }
                                    val acc = if (editing != null) {
                                        editing!!.copy(name = name, query = cfg)
                                    } else {
                                        vm.addAccount(tpl, name, cfg)
                                    }
                                    if (editing != null) vm.updateAccount(acc)
                                    activeId = acc.id
                                    vm.setActive(acc.id)
                                    vm.refresh(acc.id)
                                    screen = Screen.DETAIL
                                },
                            )
                        }
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        settings = state.settings,
                        accounts = state.accounts,
                        onSave = { vm.updateSettings(it) },
                        onImport = { accounts, settings -> vm.importJson(accounts, settings) },
                        onClear = { vm.clearAccounts() },
                        onAbout = { screen = Screen.ABOUT },
                        exportJson = { vm.exportPayload() },
                        message = { msg -> vm.emit(msg) },
                    )

                    Screen.ABOUT -> AboutScreen()
                }
                }
            }
        }

        // 登录抓取：盖在配置页之上，保证配置页状态不丢
        AnimatedVisibility(
            visible = loginRequest != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
        ) {
            loginRequest?.let { req ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LoginCaptureScreen(
                        startUrl = req.loginUrl,
                        fetchUrl = req.fetchUrl,
                        onCancel = { loginRequest = null },
                        onCaptured = { cookie, json ->
                            req.onResult(cookie, json)
                            loginRequest = null
                            vm.emit(ctx.getString(R.string.toast_cookie_captured))
                        },
                    )
                }
            }
        }
    }
}

// ── 首页 ──────────────────────────────────────────────────
@Composable
private fun HomeScreen(
    state: UiState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    if (state.accounts.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.empty_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.empty_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAdd) { Text(stringResource(R.string.action_add)) }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DashboardCard(state) }
        items(state.accounts, key = { it.id }) { account ->
            AccountRow(
                account = account,
                querying = state.querying.contains(account.id),
                onOpen = { onOpen(account.id) },
                onDelete = { onDelete(account.id) },
            )
        }
    }
}

/** 仪表盘：账户数 / 成功数 / 待排查数 / 最近同步时间 */
@Composable
private fun DashboardCard(state: UiState) {
    val total = state.accounts.size
    val ok = state.accounts.count { it.result != null }
    val failed = state.accounts.count { it.lastError != null }
    val latest = state.accounts.maxOfOrNull { it.updatedAt } ?: 0L

    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    stringResource(R.string.dash_accounts, total),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$ok / $total",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (failed > 0) stringResource(R.string.dash_failed, failed)
                    else if (latest > 0L) stringResource(R.string.dash_synced, relTime(latest))
                    else stringResource(R.string.dash_tap_add),
                    fontSize = 12.sp,
                    color = if (failed > 0) Color(0xFFFF5A6E) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    querying: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val tpl = Templates.byId(account.templateId)
    val result = account.result
    val vendorName = stringResource(tpl.nameRes)
    val modeText = modeLabel(account.query.mode)
    val failedText = stringResource(R.string.state_failed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VendorBadge(short = tpl.id.take(2).uppercase(), color = tpl.color, templateId = tpl.id)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(account.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(
                text = "$vendorName · $modeText" + if (account.lastError != null) " · $failedText" else "",
                fontSize = 12.sp,
                color = if (account.lastError != null) Color(0xFFFF5A6E) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (querying) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = result?.balance?.let { fmtMoney(it.amount, it.currency) }
                        ?: result?.subscription?.remaining?.let { fmtNum(it) }
                        ?: "—",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = result?.periods?.firstOrNull()?.remainPercent
                        ?.let { stringResource(R.string.label_remain_pct, it.roundToInt()) }
                        ?: result?.subscription?.tier.orEmpty(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5A6E), modifier = Modifier.size(18.dp))
        }
    }
}

// ── 详情 ──────────────────────────────────────────────────
@Composable
private fun DetailScreen(
    account: Account,
    querying: Boolean,
    onQuery: () -> Unit,
    onConfig: () -> Unit,
    onLoginCapture: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val tpl = Templates.byId(account.templateId)
    val result = account.result
    val vendorName = stringResource(tpl.nameRes)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VendorBadge(short = tpl.id.take(2).uppercase(), color = tpl.color, templateId = tpl.id)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(vendorName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(account.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    ModeBadge(account.query.mode)
                }

                if (account.updatedAt > 0L) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.label_updated, relTime(account.updatedAt)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                result?.balance?.let { b ->
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.label_balance), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        fmtMoney(b.amount, b.currency),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                result?.subscription?.let { s ->
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.label_subscription), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            s.tier ?: "—",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (s.remaining != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${stringResource(R.string.label_remaining)} ${fmtNum(s.remaining)} ${s.unit}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    s.resetAt?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${stringResource(R.string.label_reset)} $it",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val periods = result?.periods.orEmpty()
        if (periods.isNotEmpty()) {
            SectionCard {
                Column {
                    Text(stringResource(R.string.label_periods), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    periods.forEachIndexed { i, p -> PeriodRow(p, i) }
                }
            }
        }

        // 厂商特有字段：有什么显示什么
        val extras = result?.extras.orEmpty()
        if (extras.isNotEmpty()) {
            SectionCard {
                Column {
                    Text(stringResource(R.string.label_extras), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    extras.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(e.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(e.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // 登录拉取类账户：还没有登录态时，直接给进入登录的入口
        if (account.query.mode == QueryMode.LOGIN && account.query.cookie.isBlank()) {
            SectionCard {
                Column {
                    Text(stringResource(R.string.login_need_cookie), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val url = account.query.loginUrl.ifBlank { account.query.url }
                            if (url.isNotBlank()) onLoginCapture(url)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = account.query.loginUrl.isNotBlank() || account.query.url.isNotBlank(),
                    ) { Text(stringResource(R.string.action_login_fetch)) }
                }
            }
        }

        account.lastError?.let { err ->
            SectionCard {
                Column {
                    Text(stringResource(R.string.label_error), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF5A6E))
                    Spacer(Modifier.height(6.dp))
                    Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (result == null && account.lastError == null) {
            SectionCard {
                Text(stringResource(R.string.empty_result), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onQuery, modifier = Modifier.weight(1f), enabled = !querying) {
                if (querying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.action_query))
            }
            OutlinedButton(onClick = onConfig, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_config))
            }
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_delete_account), color = Color(0xFFFF5A6E))
        }
    }
}

@Composable
private fun ModeBadge(mode: QueryMode) {
    val label = modeLabel(mode)
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun modeLabel(mode: QueryMode): String = when (mode) {
    QueryMode.API -> stringResource(R.string.mode_api)
    QueryMode.WEBHOOK -> stringResource(R.string.mode_webhook)
    QueryMode.LOGIN -> stringResource(R.string.mode_login)
    QueryMode.AKSK -> stringResource(R.string.mode_aksk)
}

// ── 厂商目录 ──────────────────────────────────────────────
@Composable
private fun CatalogScreen(onPick: (Template) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.catalog_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(Templates.ALL, key = { it.id }) { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .clickable { onPick(t) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VendorBadge(short = t.id.take(2).uppercase(), color = t.color, templateId = t.id)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(t.nameRes), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(t.descRes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.action_add), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
