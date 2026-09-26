package com.yusheng.quota

import com.yusheng.quota.net.geminiRefreshTokenOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gemini 凭证识别：access token 约 1 小时就过期（这正是 401 的来源），
 * 支持「完整 oauth_creds.json / 1// 开头的 refresh_token」两种可持续粘贴形式。
 * OAuth 客户端常量与刷新端点已对照 gemini-cli 源码与真实端点验证。
 */
class GeminiTokenTest {

    @Test fun fullOauthCredsJsonYieldsRefreshToken() {
        val creds = """{"access_token":"ya29.a0-expired","refresh_token":"1//0g-realtoken","token_type":"Bearer","expiry_date":1759000000000}"""
        assertEquals("1//0g-realtoken", geminiRefreshTokenOf(creds))
    }

    @Test fun bareRefreshTokenIsKeptAsIs() {
        assertEquals("1//0gabc", geminiRefreshTokenOf("  1//0gabc  "))
    }

    @Test fun bareAccessTokenIsNotRefreshable() {
        assertNull(geminiRefreshTokenOf("ya29.a0AfH6SMB...longtoken"))
    }

    @Test fun jsonWithoutRefreshTokenIsNotRefreshable() {
        assertNull(geminiRefreshTokenOf("""{"access_token":"ya29.x","token_type":"Bearer"}"""))
    }

    @Test fun credentialsPastedAsMultilineJsonStillParse() {
        // 从编辑器整段复制时可能带换行与缩进
        val creds = """
            {
              "access_token": "ya29.a0",
              "refresh_token": "1//0multiline"
            }
        """.trimIndent()
        assertEquals("1//0multiline", geminiRefreshTokenOf(creds))
    }

    @Test fun garbageIsNotRefreshable() {
        assertNull(geminiRefreshTokenOf(""))
        assertNull(geminiRefreshTokenOf("not a credential"))
        assertNull(geminiRefreshTokenOf("{broken json"))
    }
}
