package com.yusheng.quota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yusheng.quota.BuildConfig
import com.yusheng.quota.R
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.ImportCodec
import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.QueryMode
import com.yusheng.quota.data.Settings
import com.yusheng.quota.data.Template

/**
 * 查询配置：按厂商能力裁剪字段。
 *
 * 状态由调用方持有（hoisted），这样在应用内登录抓 Cookie 时返回，草稿不会丢。
 */
@Composable
fun ConfigScreen(
    template: Template,
    name: String,
    cfg: QueryConfig,
    onNameChange: (String) -> Unit,
    onCfgChange: (QueryConfig) -> Unit,
    onLoginCapture: (loginUrl: String, fetchUrl: String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.field_mode), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    template.modes.forEach { m ->
                        val selected = cfg.mode == m
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable { onCfgChange(cfg.copy(mode = m)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                modeText(m),
                                fontSize = 12.sp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        SectionCard {
            Column {
                when (cfg.mode) {
                    QueryMode.API -> {
                        Field(stringResource(R.string.field_url), cfg.url) { onCfgChange(cfg.copy(url = it)) }
                        Field(stringResource(R.string.field_api_key), cfg.apiKey, secret = true) {
                            onCfgChange(cfg.copy(apiKey = it))
                        }
                        if (template.id == "zhipu") {
                            Hint(stringResource(R.string.hint_zhipu))
                            Field(stringResource(R.string.field_org_id), cfg.orgId) {
                                onCfgChange(cfg.copy(orgId = it))
                            }
                            Field(stringResource(R.string.field_project_id), cfg.projectId) {
                                onCfgChange(cfg.copy(projectId = it))
                            }
                            Hint(stringResource(R.string.hint_zhipu_team))
                        }
                    }

                    QueryMode.AKSK -> {
                        Field(stringResource(R.string.field_ak), cfg.accessKeyId) { onCfgChange(cfg.copy(accessKeyId = it)) }
                        Field(stringResource(R.string.field_sk), cfg.secretAccessKey, secret = true) {
                            onCfgChange(cfg.copy(secretAccessKey = it))
                        }
                        Field(stringResource(R.string.field_region), cfg.region) { onCfgChange(cfg.copy(region = it)) }
                        Field(stringResource(R.string.field_wh_fallback), cfg.webhookUrl) {
                            onCfgChange(cfg.copy(webhookUrl = it))
                        }
                        Hint(stringResource(R.string.hint_volc_aksk))
                    }

                    QueryMode.LOGIN -> {
                        Field(stringResource(R.string.field_url), cfg.url) { onCfgChange(cfg.copy(url = it)) }
                        Field(stringResource(R.string.field_login_url), cfg.loginUrl) { onCfgChange(cfg.copy(loginUrl = it)) }
                        Field(stringResource(R.string.field_cookie), cfg.cookie, secret = true) {
                            onCfgChange(cfg.copy(cookie = it))
                        }
                        Button(
                            onClick = { onLoginCapture(cfg.loginUrl.ifBlank { cfg.url }, cfg.url) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            enabled = cfg.loginUrl.isNotBlank() || cfg.url.isNotBlank(),
                        ) { Text(stringResource(R.string.action_login_fetch)) }
                        Hint(stringResource(R.string.hint_login))
                    }

                    QueryMode.WEBHOOK -> {
                        Field(stringResource(R.string.field_webhook), cfg.webhookUrl) { onCfgChange(cfg.copy(webhookUrl = it)) }
                        Text(
                            stringResource(R.string.field_wh_auth),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("none", "bearer", "header", "query").forEach { t ->
                                val selected = cfg.whAuthType == t
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { onCfgChange(cfg.copy(whAuthType = t)) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        whAuthText(t),
                                        fontSize = 11.sp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (cfg.whAuthType != "none") {
                            Field(stringResource(R.string.field_wh_key), cfg.whAuthKey) {
                                onCfgChange(cfg.copy(whAuthKey = it))
                            }
                            Field(stringResource(R.string.field_wh_prefix), cfg.whAuthPrefix) {
                                onCfgChange(cfg.copy(whAuthPrefix = it))
                            }
                            Field(stringResource(R.string.field_wh_value), cfg.whAuthValue, secret = true) {
                                onCfgChange(cfg.copy(whAuthValue = it))
                            }
                        }
                        Hint(stringResource(R.string.hint_webhook))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Field(stringResource(R.string.field_map_balance), cfg.mapBalance) {
                    onCfgChange(cfg.copy(mapBalance = it))
                }
                Field(stringResource(R.string.field_map_plan), cfg.mapPlan) {
                    onCfgChange(cfg.copy(mapPlan = it))
                }
                Field(stringResource(R.string.field_timeout), cfg.timeoutSec.toString()) { v ->
                    onCfgChange(cfg.copy(timeoutSec = v.toIntOrNull()?.coerceIn(0, 60) ?: 0))
                }
                if (cfg.script.isNotBlank()) {
                    Hint(stringResource(R.string.hint_script_present))
                }
            }
        }

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save_query))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 模式名走字符串资源，跟随系统语言 */
@Composable
fun modeText(m: QueryMode): String = modeLabel(m)

@Composable
private fun whAuthText(t: String): String = when (t) {
    "none" -> stringResource(R.string.wh_auth_none)
    "bearer" -> stringResource(R.string.wh_auth_bearer)
    "header" -> stringResource(R.string.wh_auth_header)
    else -> stringResource(R.string.wh_auth_query)
}

// ── 设置 ──────────────────────────────────────────────────
@Composable
fun SettingsScreen(
    settings: Settings,
    accounts: List<Account>,
    onSave: (Settings) -> Unit,
    onImport: (List<Account>, Settings) -> Unit,
    onClear: () -> Unit,
    onAbout: () -> Unit,
    exportJson: () -> String,
    message: (String) -> Unit,
) {
    // 设置项改动即生效，不需要手动保存；文本输入框保留本地文本，避免边输入边被格式化
    var refreshText by remember(settings.autoRefreshMinutes) {
        mutableStateOf(settings.autoRefreshMinutes.toString())
    }
    var timeoutText by remember(settings.timeoutSec) {
        mutableStateOf(settings.timeoutSec.toString())
    }
    var io by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val exportedMsg = stringResource(R.string.toast_exported)
    val importedMsg = stringResource(R.string.toast_imported)
    val badJsonMsg = stringResource(R.string.toast_import_failed)
    val clearedMsg = stringResource(R.string.toast_cleared)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard {
            Column {
                Text(stringResource(R.string.title_settings), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.field_auto_refresh), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = refreshText,
                    onValueChange = { raw ->
                        refreshText = raw.filter { it.isDigit() }.take(4)
                        refreshText.toIntOrNull()?.let {
                            onSave(settings.copy(autoRefreshMinutes = it.coerceIn(0, 1440)))
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.field_timeout_default), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { raw ->
                        timeoutText = raw.filter { it.isDigit() }.take(2)
                        timeoutText.toIntOrNull()?.let {
                            onSave(settings.copy(timeoutSec = it.coerceIn(2, 60)))
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.field_auto_query_start), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = settings.autoQueryOnStart,
                        onCheckedChange = { onSave(settings.copy(autoQueryOnStart = it)) },
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.field_dark_mode), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to R.string.mode_system, "dark" to R.string.mode_dark, "light" to R.string.mode_light)
                        .forEach { (v, res) ->
                            val selected = settings.darkMode == v
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { onSave(settings.copy(darkMode = v)) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    stringResource(res),
                                    fontSize = 12.sp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }

            }
        }

        SectionCard {
            Column {
                Text(
                    stringResource(R.string.title_data) + " · " + stringResource(R.string.dash_accounts, accounts.size),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = io,
                    onValueChange = { io = it },
                    label = { Text(stringResource(R.string.field_io), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val payload = exportJson()
                            io = payload
                            clipboard.setText(AnnotatedString(payload))
                            message(exportedMsg)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_export)) }
                    OutlinedButton(
                        onClick = {
                            val parsed = runCatching { ImportCodec.decode(io) }.getOrNull()
                            if (parsed == null) {
                                message(badJsonMsg)
                            } else {
                                onImport(parsed.first, parsed.second)
                                message(importedMsg)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_import)) }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_clear), color = Color(0xFFFF5A6E))
                }
            }
        }

        SectionCard {
            Row(
                Modifier.fillMaxWidth().clickable { onAbout() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.title_about), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.confirm_clear_title)) },
            text = { Text(stringResource(R.string.confirm_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                    message(clearedMsg)
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// ── 关于 ──────────────────────────────────────────────────
@Composable
fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                VendorBadge(short = "QB", color = 0xFF5A6472, size = 64)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.app_subtitle), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        SectionCard {
            Column {
                InfoRow(stringResource(R.string.about_author), stringResource(R.string.about_author_value))
                InfoRow(stringResource(R.string.about_license), "MIT")
                RepoLinkRow(stringResource(R.string.about_repo), stringResource(R.string.about_repo_value))
                UpdateRow()
            }
        }

        SectionCard {
            Column {
                Text(stringResource(R.string.about_privacy), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.about_privacy_body), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UpdateRow() {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    val upToDate = stringResource(R.string.update_none)
    val updateAvailable = stringResource(R.string.update_available)
    val check = stringResource(R.string.action_check_update)
    val current = BuildConfig.VERSION_NAME

    Row(
        Modifier.fillMaxWidth().clickable {
            status = "…"
            Thread {
                val rel = com.yusheng.quota.update.UpdateChecker.fetchLatest()
                val msg = when {
                    rel == null -> "!"
                    com.yusheng.quota.update.UpdateChecker.isNewer(current, rel.versionName) -> {
                        com.yusheng.quota.update.UpdateChecker.openInBrowser(ctx, "https://github.com/Eason4869/quota-board/releases/tag/v${rel.versionName}")
                        "$updateAvailable ${rel.versionName}"
                    }
                    else -> upToDate
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { status = msg }
            }.start()
        }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(check, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status.ifBlank { "v$current" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RepoLinkRow(k: String, v: String) {
    val ctx = LocalContext.current
    val url = "https://github.com/Eason4869/quota-board"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            v,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}
