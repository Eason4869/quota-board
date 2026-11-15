package com.yusheng.quota

import com.yusheng.quota.net.CodexOAuth
import com.yusheng.quota.data.CodexCredentials
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.MessageDigest

class CodexOAuthTest {
    private fun jwt(account: String = "account-1") = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"exp":2000000000,"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}""".toByteArray()) + ".sig"

    @Test fun deviceFlowPollsPendingAndExchangesPkceCode() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        var polls = 0
        val verifier = "test-verifier"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        CodexOAuth(request = { url, body, type ->
            calls += Triple(url, body, type)
            when {
                url.endsWith("/usercode") -> CodexOAuth.Response(200,
                    """{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":"1"}""")
                url.endsWith("/deviceauth/token") -> if (polls++ == 0) CodexOAuth.Response(403, "{}")
                    else CodexOAuth.Response(200,
                        """{"authorization_code":"code&value","code_verifier":"$verifier","code_challenge":"$challenge"}""")
                else -> CodexOAuth.Response(200, JSONObject().put("access_token", jwt())
                    .put("id_token", jwt()).put("refresh_token", "refresh-1").put("expires_in", 3600).toString())
            }
        }, wait = {}, clock = { 1000L }).use { oauth ->
            val ticket = oauth.begin()
            assertEquals("https://auth.openai.com/codex/device", ticket.browserUrl)
            assertEquals("ABCD-EFGH", ticket.userCode)
            val tokens = oauth.awaitApproval(ticket)
            assertEquals("account-1", tokens.accountId)
            assertEquals("refresh-1", tokens.refreshToken)
            assertEquals(3_601_000L, tokens.expiresAtMs)
            assertEquals(2, polls)
            val exchange = calls.last()
            assertTrue(exchange.second.contains("code=code%26value"))
            assertTrue(exchange.second.contains("code_verifier=test-verifier"))
            assertTrue(exchange.second.contains("redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback"))
            assertEquals("application/x-www-form-urlencoded", exchange.third)
        }
    }

    @Test fun expiresWithoutAnotherPollAndCloseCancels() = runBlocking {
        var time = 0L
        var polls = 0
        val oauth = CodexOAuth(request = { _, _, _ ->
            polls++
            CodexOAuth.Response(200, """{"device_auth_id":"d","usercode":"CODE","interval":1}""")
        }, clock = { time }, wait = { time = 901_000 })
        val ticket = oauth.begin()
        assertTrue(runCatching { oauth.awaitApproval(ticket) }.isFailure)
        assertEquals(1, polls)
        oauth.close()
        assertTrue(runCatching { oauth.begin() }.isFailure)
        assertEquals(1, polls)
    }

    @Test fun failuresNeverExposeResponseSecrets() {
        CodexOAuth(request = { _, _, _ -> CodexOAuth.Response(401, "secret-access secret-refresh") }).use {
            val error = runCatching { it.begin() }.exceptionOrNull()!!
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun refreshUsesOfficialJsonContractAndPreservesAccountAndSession() {
        val original = CodexCredentials("same-session", "old", "old-refresh", "account-1", 0)
        CodexOAuth(request = { url, body, type ->
            assertEquals("https://auth.openai.com/oauth/token", url)
            assertEquals("application/json", type)
            val form = JSONObject(body)
            assertEquals("refresh_token", form.getString("grant_type"))
            assertEquals(CodexOAuth.CLIENT_ID, form.getString("client_id"))
            assertEquals("old-refresh", form.getString("refresh_token"))
            CodexOAuth.Response(200, JSONObject().put("access_token", jwt())
                .put("refresh_token", "rotated").put("expires_in", 120).toString())
        }, clock = { 1000 }).use {
            val next = it.refresh(original)
            assertEquals("same-session", next.sessionId)
            assertEquals("account-1", next.accountId)
            assertEquals("rotated", next.refreshToken)
            assertEquals(121_000L, next.expiresAtMs)
        }
    }

    @Test fun refreshCanOmitReplacementTokenButNeverChangeAccounts() {
        val original = CodexCredentials("session", "old", "keep-refresh", "account-1", 0)
        CodexOAuth(request = { _, _, _ -> CodexOAuth.Response(200,
            JSONObject().put("access_token", jwt()).toString()) }).use {
            assertEquals("keep-refresh", it.refresh(original).refreshToken)
        }
        CodexOAuth(request = { _, _, _ -> CodexOAuth.Response(200,
            JSONObject().put("access_token", jwt("other-account")).toString()) }).use {
            assertTrue(runCatching { it.refresh(original) }.isFailure)
        }
    }
}
