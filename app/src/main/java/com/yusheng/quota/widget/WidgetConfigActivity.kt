package com.yusheng.quota.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yusheng.quota.MainActivity
import com.yusheng.quota.QuotaApp
import com.yusheng.quota.R
import com.yusheng.quota.ui.theme.QuotaBoardTheme

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id !in QuotaWidgets.ids(this)) { finish(); return }
        val store = (application as QuotaApp).store
        setContent {
            var accounts by remember { mutableStateOf(store.loadAccounts()) }
            var selected by rememberSaveable { mutableStateOf(WidgetStore(this).accountId(id)) }
            DisposableEffect(store) {
                val subscription = store.observe(onAccountsChanged = { accounts = store.loadAccounts() })
                onDispose { subscription.close() }
            }
            QuotaBoardTheme(darkMode = store.loadSettings().darkMode) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                        Text(stringResource(R.string.widget_choose_source), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.widget_config_help), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        if (accounts.isEmpty()) {
                            Text(stringResource(R.string.widget_no_accounts))
                            TextButton(onClick = { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)) }) {
                                Text(stringResource(R.string.widget_open_app))
                            }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(accounts, key = { it.id }) { account ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { selected = account.id }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected == account.id, onClick = { selected = account.id })
                                    Text(account.name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
                            Button(
                                enabled = accounts.any { it.id == selected },
                                onClick = {
                                    val chosen = selected ?: return@Button
                                    if (store.loadAccounts().none { it.id == chosen }) return@Button
                                    WidgetStore(this@WidgetConfigActivity).select(id, chosen)
                                    QuotaWidgets.render(this@WidgetConfigActivity, id)
                                    QuotaWidgets.schedule(this@WidgetConfigActivity)
                                    QuotaWidgets.refresh(this@WidgetConfigActivity, chosen)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                },
                            ) { Text(stringResource(android.R.string.ok)) }
                        }
                    }
                }
            }
        }
    }
}
