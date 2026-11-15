package com.yusheng.quota

import com.yusheng.quota.data.*
import com.yusheng.quota.net.CodexCredentialManager
import com.yusheng.quota.net.CodexHttpException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = QuotaApp::class)
class CodexCredentialTest {
    private val initial = CodexCredentials("session-1", "expired", "refresh-1", "account-1", 0)

    @Test fun concurrentQueriesRotateOnceAndPersistBeforeUsageFailure() = runBlocking {
        var stored = initial
        var rotations = 0
        val manager = CodexCredentialManager(
            latest = { stored }, persist = { _, next -> stored = next },
            refresh = { old, proxy ->
                assertEquals("http://localhost:7890", proxy)
                delay(10)
                rotations++
                old.copy(accessToken = "valid", refreshToken = "rotated", expiresAtMs = Long.MAX_VALUE)
            }, clock = { 1000 },
        )
        val cfg = QueryConfig(codexOAuth = initial, proxyUrl = "http://localhost:7890")
        val results = (1..2).map { async {
            runCatching { manager.query(cfg) { error("usage offline") } }
        } }.awaitAll()
        assertTrue(results.all { it.isFailure })
        assertEquals(1, rotations)
        assertEquals("rotated", stored.refreshToken)
    }

    @Test fun unauthorizedRenewsOnceButForbiddenDoesNot() = runBlocking {
        var stored = initial.copy(expiresAtMs = Long.MAX_VALUE)
        var rotations = 0
        var attempts = 0
        val manager = CodexCredentialManager({ stored }, { _, next -> stored = next }, { old, _ ->
            rotations++
            old.copy(accessToken = "new-token", refreshToken = "new-refresh")
        }, { 1000 })
        val cfg = QueryConfig(codexOAuth = stored)
        assertEquals("new-token", manager.query(cfg) { token ->
            if (attempts++ == 0) throw CodexHttpException(401)
            token.accessToken
        })
        assertEquals(1, rotations)
        assertEquals(2, attempts)
        assertTrue(runCatching { manager.query(cfg) { throw CodexHttpException(403) } }.isFailure)
        assertEquals(1, rotations)
        attempts = 0
        assertTrue(runCatching { manager.query(cfg) { attempts++; throw CodexHttpException(401) } }.isFailure)
        assertEquals(2, attempts)
        assertEquals(2, rotations)
    }

    @Test fun rotationSurvivesBackupAndDoesNotOverwriteReauthorizationOrDeletedAccounts() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.replaceAccounts(listOf(Account("a", "openai", "Codex", QueryConfig(
            mode = QueryMode.LOGIN, codexOAuth = initial))))
        val rotated = initial.copy(accessToken = "new", refreshToken = "rotated")
        store.rotateCodexCredentials(initial, rotated)
        val updated = store.loadAccounts().single()
        assertEquals(rotated, updated.query.codexOAuth)
        assertEquals(rotated, ImportCodec.decode(ImportCodec.encode(listOf(updated), Settings())).first.single().query.codexOAuth)
        assertTrue(updated.query.sameRequestAs(updated.query.copy(codexOAuth = initial)))
        assertFalse(updated.query.sameRequestAs(updated.query.copy(url = "changed")))
        val reauthorized = initial.copy(sessionId = "session-2")
        store.patchAccount("a") { it.copy(query = it.query.copy(codexOAuth = reauthorized)) }
        store.rotateCodexCredentials(rotated, initial)
        assertEquals(reauthorized, store.loadAccounts().single().query.codexOAuth)
        store.replaceAccounts(emptyList())
        store.rotateCodexCredentials(reauthorized, rotated)
        assertTrue(store.loadAccounts().isEmpty())
    }

    @Test fun restoringIdenticalCredentialsInvalidatesAnInFlightRotation() {
        val store = Store(RuntimeEnvironment.getApplication())
        val accounts = listOf(Account("a", "openai", "Codex", QueryConfig(mode = QueryMode.LOGIN, codexOAuth = initial)))
        store.replaceAccounts(accounts)
        val epoch = store.codexCredentialEpoch()
        store.replaceAccounts(accounts)
        assertFalse(store.rotateCodexCredentials(initial, initial.copy(refreshToken = "stale-write"), epoch))
        assertEquals(initial, store.loadAccounts().single().query.codexOAuth)
    }

    @Test fun snapshotTakenBeforeRestoreCannotStartRenewingAfterRestore() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as QuotaApp
        val store = Store(context)
        val accounts = listOf(Account("a", "openai", "Codex", QueryConfig(mode = QueryMode.LOGIN,
            codexOAuth = initial, proxyUrl = "http://127.0.0.1:1")))
        store.replaceAccounts(accounts)
        val (snapshot, _) = store.beginAccountQuerySnapshot("a")!!
        store.replaceAccounts(accounts)
        val failure = runCatching { com.yusheng.quota.net.QueryEngine(context)
            .query(Templates.byId("openai"), snapshot.query) }.exceptionOrNull()
        assertTrue("Old snapshot must cancel before any refresh, got $failure", failure is CancellationException)
        assertEquals(initial, store.loadAccounts().single().query.codexOAuth)
    }

    @Test fun editingAnOldDraftPreservesRotatedCredentialsButAllowsReauthorization() {
        val draft = QueryConfig(mode = QueryMode.LOGIN, codexOAuth = initial)
        val current = draft.copy(codexOAuth = initial.copy(refreshToken = "new-refresh"))
        assertEquals("new-refresh", draft.copy(proxyUrl = "http://localhost:7890")
            .preservingRenewedCredentials(current).codexOAuth!!.refreshToken)
        val reauthorized = draft.copy(codexOAuth = initial.copy(sessionId = "new-session"))
        assertEquals(reauthorized, reauthorized.preservingRenewedCredentials(current))
    }

    @Test fun cancellationCannotLoseAnAlreadyRotatingToken() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored = initial
        val manager = CodexCredentialManager({ stored }, { _, next -> stored = next }, { old, _ ->
            started.complete(Unit)
            release.await()
            old.copy(refreshToken = "durable-replacement", expiresAtMs = Long.MAX_VALUE)
        })
        val job = launch { manager.query(QueryConfig(codexOAuth = initial)) { } }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals("durable-replacement", stored.refreshToken)
    }
}
