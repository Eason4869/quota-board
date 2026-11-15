package com.yusheng.quota.net

import com.yusheng.quota.data.CodexCredentials
import com.yusheng.quota.data.QueryConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground and widgets share one renewal lock, including separate QueryEngine instances. */
internal class CodexCredentialManager(
    private val latest: (CodexCredentials) -> CodexCredentials,
    private val persist: (CodexCredentials, CodexCredentials) -> Unit,
    private val refresh: suspend (CodexCredentials, String) -> CodexCredentials = { previous, proxy ->
        CodexOAuth(proxy).use { it.refresh(previous) }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun <T> query(cfg: QueryConfig, request: suspend (CodexCredentials) -> T): T {
        val original = cfg.codexOAuth ?: throw CodexAuthException(reconnect = true)
        suspend fun credential(rejected: CodexCredentials? = null): CodexCredentials = RENEWAL.withLock {
            val current = latest(original)
            if ((rejected != null && current.accessToken == rejected.accessToken) ||
                current.expiresAtMs <= clock() + 60_000) {
                // A refresh can consume the previous token. Finish saving its replacement even
                // if a foreground refresh is cancelled while the bounded HTTP call is in flight.
                withContext(NonCancellable + Dispatchers.IO) {
                    refresh(current, cfg.proxyUrl).also { persist(current, it) }
                }
            } else current
        }
        val first = credential()
        return try { request(first) } catch (e: CodexHttpException) {
            if (e.status != 401) throw e
            request(credential(first)) // At most one retry; a second 401 goes to reconnect UI.
        }
    }

    companion object { private val RENEWAL = Mutex() }
}
