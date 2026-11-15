package com.yusheng.quota.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yusheng.quota.R
import com.yusheng.quota.data.CodexCredentials
import com.yusheng.quota.net.CodexOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexLoginScreen(proxyUrl: String, onCancel: () -> Unit, onAuthorized: (CodexCredentials) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val authorized by rememberUpdatedState(onAuthorized)
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(attempt, proxyUrl) { CodexOAuth(proxyUrl) }
    var ticket by remember(session) { mutableStateOf<CodexOAuth.Ticket?>(null) }
    var busy by remember(session) { mutableStateOf(true) }
    var failed by remember(session) { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(session) {
        try {
            val created = withContext(Dispatchers.IO) { session.begin() }
            ticket = created
            authorized(session.awaitApproval(created))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; ticket = null }
        finally { busy = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.codex_connect)) }, navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.mimo_back))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.codex_intro))
            ticket?.let { code ->
                Text(code.userCode, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code.userCode)) }) {
                    Text(stringResource(R.string.codex_copy_code))
                }
                Button(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.browserUrl))) }
                    catch (_: Exception) { failed = true }
                }) { Text(stringResource(R.string.mimo_browser)) }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.codex_waiting))
            }
            if (failed) Text(stringResource(R.string.codex_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { attempt++ }, enabled = !busy || failed) {
                Text(stringResource(R.string.mimo_retry))
            }
        }
    }
}
