package com.yusheng.quota.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yusheng.quota.R
import com.yusheng.quota.net.MimoEndpoints
import com.yusheng.quota.net.MimoLoginSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MiMo authorization has no embedded WebView. The browser approves this isolated QR session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MimoLoginScreen(onCancel: () -> Unit, onCaptured: (String?, String?) -> Unit) {
    val context = LocalContext.current
    val captured by rememberUpdatedState(onCaptured)
    var attempt by remember { mutableIntStateOf(0) }
    var importMode by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var submittedCookie by remember { mutableStateOf<String?>(null) }
    val session = remember(attempt, importMode) { MimoLoginSession() }
    var browserUrl by remember(session) { mutableStateOf<String?>(null) }
    var qr by remember(session) { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var readingQuota by remember(session) { mutableStateOf(false) }
    var failed by remember(session) { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(session) {
        browserUrl = null
        qr = null
        failed = false
        busy = false
        if (importMode && submittedCookie == null) return@LaunchedEffect
        busy = true
        try {
            val cookie = if (importMode) submittedCookie!! else {
                val ticket = withContext(Dispatchers.IO) { session.begin() }
                browserUrl = ticket.browserUrl
                // A failed image must not prevent approval on this phone via the browser.
                qr = withContext(Dispatchers.IO) {
                    try {
                        val bytes = session.image(ticket)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
                withContext(Dispatchers.IO) { session.awaitApproval(ticket) }
            }
            readingQuota = true
            browserUrl = null
            qr = null
            val json = withContext(Dispatchers.IO) { session.quota(cookie).toString() }
            captured(cookie, json)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Do not display raw transport errors: they can contain a login ticket or signed URL.
            failed = true
            browserUrl = null
            qr = null
        } finally {
            busy = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mimo_connect)) }, navigationIcon = {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.mimo_back)) }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.mimo_intro))
            if (importMode) {
                Text(stringResource(R.string.mimo_cookie_help))
                SecretField(stringResource(R.string.field_cookie), cookieInput) { cookieInput = it }
                Button(onClick = { submittedCookie = cookieInput.trim(); attempt++ },
                    enabled = !busy && MimoEndpoints.hasSession(cookieInput)) {
                    Text(stringResource(R.string.mimo_verify))
                }
            } else {
                qr?.let { bitmap ->
                    Image(bitmap, stringResource(R.string.mimo_qr), Modifier.size(240.dp).background(Color.White).padding(8.dp))
                }
                browserUrl?.let { url ->
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) { failed = true }
                    }) { Text(stringResource(R.string.mimo_browser)) }
                    Text(stringResource(R.string.mimo_qr_help))
                }
                OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.mimo_retry)) }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(if (importMode || readingQuota) R.string.mimo_loading else R.string.mimo_waiting))
            }
            if (failed) Text(stringResource(R.string.mimo_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                submittedCookie = null
                importMode = !importMode
            }) { Text(stringResource(if (importMode) R.string.mimo_connect else R.string.mimo_import)) }
        }
    }
}
