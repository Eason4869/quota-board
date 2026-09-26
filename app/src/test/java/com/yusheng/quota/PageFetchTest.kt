package com.yusheng.quota

import com.yusheng.quota.net.PageFetch
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageFetchTest {
    private val response = """[{"url":"https://example.test","status":200,"body":"{}"}]"""
    @Test fun waitsUntilAsynchronousFetchProducesAResult() = runTest {
        var polls = 0
        var cleaned = false
        val result = PageFetch.run(listOf("https://example.test"), 1000) { script, callback ->
            when {
                script.startsWith("window[") -> callback(if (++polls < 3) "null" else org.json.JSONObject.quote(response))
                script.contains("delete window[") -> { cleaned = true; callback("null") }
                else -> callback("true")
            }
        }
        assertEquals(200L, testScheduler.currentTime)
        assertEquals(200, result!!.single().status)
        assertTrue(cleaned)
    }
    @Test fun timeoutCleansUpEvenIfWebViewNeverCallsBack() = runTest {
        var cleaned = false
        val result = PageFetch.run(listOf("https://example.test"), 250) { script, _ ->
            if (script.contains("delete window[")) cleaned = true
        }
        assertNull(result)
        assertEquals(250L, testScheduler.currentTime)
        assertTrue(cleaned)
    }
    @Test fun cancellationCleansUpAndIgnoresLateCallback() = runTest {
        var late: ((String?) -> Unit)? = null
        var cleaned = false
        val task = launch {
            PageFetch.run(listOf("https://example.test"), 1000) { script, callback ->
                if (script.contains("delete window[")) cleaned = true else late = callback
            }
            fail("cancelled request returned normally")
        }
        runCurrent()
        task.cancelAndJoin()
        late!!.invoke(response)
        assertTrue(cleaned)
    }
    @Test fun destroyedWebViewIsReportedAsFailedCapture() = runTest {
        assertNull(PageFetch.run(listOf("https://example.test"), 1000) { _, _ ->
            throw IllegalStateException("WebView destroyed")
        })
    }
}
