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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yusheng.quota.R
import com.yusheng.quota.net.QianwenLoginSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QianwenLoginScreen(onCancel: () -> Unit, onCaptured: (String?, String?) -> Unit) {
    val context = LocalContext.current
    val captured by rememberUpdatedState(onCaptured)
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(attempt) { QianwenLoginSession() }
    var browserUrl by remember(session) { mutableStateOf<String?>(null) }
    var busy by remember(session) { mutableStateOf(true) }
    var querying by remember(session) { mutableStateOf(false) }
    var failed by remember(session) { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(session) {
        try {
            val ticket = withContext(Dispatchers.IO) { session.begin() }
            browserUrl = ticket.browserUrl
            val credential = session.awaitApproval(ticket)
            querying = true
            browserUrl = null
            val json = withContext(Dispatchers.IO) { session.quota(credential).toString() }
            captured(credential, json)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            browserUrl = null
        } finally {
            busy = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.qianwen_connect)) }, navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.mimo_back))
            }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.qianwen_intro))
            browserUrl?.let { url ->
                Button(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (_: Exception) { failed = true }
                }) { Text(stringResource(R.string.mimo_browser)) }
                Text(stringResource(R.string.qianwen_browser_help))
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(if (querying) R.string.mimo_loading else R.string.qianwen_waiting))
            }
            if (failed) Text(stringResource(R.string.qianwen_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.mimo_retry)) }
        }
    }
}
