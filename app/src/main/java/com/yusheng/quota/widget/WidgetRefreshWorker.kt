package com.yusheng.quota.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yusheng.quota.data.*
import com.yusheng.quota.net.QueryEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val selected = QuotaWidgets.selectedAccounts(applicationContext)
        val requested = inputData.getString(ACCOUNT_ID)
        if (requested == null) {
            // Manual and periodic requests share the same per-account work, avoiding duplicate queries.
            selected.forEach { QuotaWidgets.refresh(applicationContext, it) }
        } else if (requested in selected) {
            refreshWidgetAccount(Store(applicationContext), requested, QueryEngine(applicationContext)::query)
        }
        QuotaWidgets.renderAll(applicationContext)
        return Result.success()
    }
    companion object { const val ACCOUNT_ID = "account_id" }
}

/** Never revive deleted accounts or overwrite an edit/newer result during a network roundtrip. */
internal suspend fun refreshWidgetAccount(
    store: Store,
    id: String,
    query: suspend (Template, QueryConfig, Int) -> QueryResult,
) = withContext(Dispatchers.IO) {
    val (snapshot, token) = store.beginAccountQuerySnapshot(id) ?: return@withContext
    var error: String? = null
    val result = try {
        query(Templates.byId(snapshot.templateId), snapshot.query, store.loadSettings().timeoutSec)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.message ?: e.javaClass.simpleName
        null
    }
    store.patchQueryAccount(id, token) { fresh ->
        if (fresh.query != snapshot.query || fresh.templateId != snapshot.templateId ||
            fresh.updatedAt != snapshot.updatedAt || fresh.result != snapshot.result) fresh
        else fresh.copy(result = result ?: fresh.result, lastError = error, updatedAt = System.currentTimeMillis())
    }
}
