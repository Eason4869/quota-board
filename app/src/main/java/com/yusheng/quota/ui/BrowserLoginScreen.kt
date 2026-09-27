package com.yusheng.quota.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yusheng.quota.R
import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.QueryMode
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.QueryEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

/** Android browsers keep their own cookies; importing an authorized Cookie is explicit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserLoginScreen(
    templateId: String,
    config: QueryConfig,
    startUrl: String,
    onCancel: () -> Unit,
    onCaptured: (String?, String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cookie by remember { mutableStateOf(config.cookie) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.browser_login_title)) }, navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.browser_login_help))
            Button(onClick = {
                try {
                    val target = URI(startUrl)
                    require(target.scheme in listOf("https", "http") && target.host != null && target.rawUserInfo == null)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(startUrl)))
                    failed = false
                } catch (_: Exception) { failed = true }
            }, enabled = startUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.browser_login_open))
            }
            SecretField(stringResource(R.string.field_cookie), cookie) { cookie = it }
            Button(onClick = {
                busy = true
                failed = false
                scope.launch {
                    try {
                        val candidate = cookie.trim()
                        val result = QueryEngine(context).query(
                            Templates.byId(templateId), config.copy(mode = QueryMode.LOGIN, cookie = candidate),
                        )
                        check(result.balance != null || result.subscription != null ||
                            result.periods.isNotEmpty() || result.extras.isNotEmpty())
                        onCaptured(candidate, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    } finally { busy = false }
                }
            }, enabled = !busy && cookie.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.browser_login_verify))
            }
            if (busy) CircularProgressIndicator()
            if (failed) Text(stringResource(R.string.browser_login_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
