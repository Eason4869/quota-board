package com.yusheng.quota

import com.yusheng.quota.net.MimoEndpoints
import com.yusheng.quota.net.PageFetch
import com.yusheng.quota.ui.upgradeCleartextForFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 登录拉取流程里可纯测的判定逻辑。
 *
 * 背景（2026-09 实测）：小米额度接口未登录时回 `401 + loginUrl`，其 callback 的
 * followup 参数双重编码后是 **http:// 明文** 地址；SSO 登录成功后 sts 会 302 到它。
 * 应用禁止明文加载，不处理就是 ERR_CLEARTEXT_NOT_PERMITTED —— 登录成功却停在
 * 错误页上，「抓取额度」跑在错误页上下文里必然失败。相关修复见
 * [upgradeCleartextForFetch] 与 LoginCaptureScreen 的自动抓取。
 */
class LoginCaptureLogicTest {

    private val fetch = "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage"

    // ── 明文跳转升级 ──────────────────────────────────────

    @Test fun upgradesCleartextFollowupOnFetchHost() {
        assertEquals(
            "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage",
            upgradeCleartextForFetch("http://platform.xiaomimimo.com/api/v1/tokenPlan/usage", fetch),
        )
    }

    @Test fun upgradesCleartextFollowupWithQuery() {
        assertEquals(
            "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage?from=sts",
            upgradeCleartextForFetch("http://platform.xiaomimimo.com/api/v1/tokenPlan/usage?from=sts", fetch),
        )
    }

    @Test fun keepsCleartextOnUnrelatedHost() {
        assertNull(upgradeCleartextForFetch("http://example.com/balance", fetch))
    }

    @Test fun keepsHttpsNavigationUntouched() {
        assertNull(upgradeCleartextForFetch("https://platform.xiaomimimo.com/api/v1/balance", fetch))
    }

    @Test fun keepsCleartextWhenFetchItselfIsHttp() {
        // 查询接口本身是 http（本机云函数等）时不升级，免得把局域网地址改坏
        assertNull(upgradeCleartextForFetch("http://192.168.1.5/api/quota", "http://192.168.1.5/api/quota"))
    }

    @Test fun malformedUrlIsIgnored() {
        assertNull(upgradeCleartextForFetch("http://", fetch))
        assertNull(upgradeCleartextForFetch("not a url", fetch))
    }

    // ── 登录完成判定 ──────────────────────────────────────

    @Test fun sessionIsDetectedFromServiceTokenCookie() {
        assertTrue(MimoEndpoints.hasSession("userId=123; api-platform_serviceToken=abc; other=1"))
        assertFalse(MimoEndpoints.hasSession("userId=123"))
        assertFalse(MimoEndpoints.hasSession(""))
    }

    // ── 真实回包的链路推演 ────────────────────────────────

    /**
     * 用接口真实的 401 回包（脱敏 sign）推演整条链：
     * loginUrl 提取 → followup 双重解码得到 http 明文 → 升级为 https 后可放行。
     */
    @Test fun realUnauthorizedBodyYieldsCleartextFollowupThatUpgrades() {
        val body = """{"code":401,"loginUrl":"https://account.xiaomi.com/pass/serviceLogin?callback=https%3A%2F%2Fplatform.xiaomimimo.com%2Fsts%3Fsign%3DX%26followup%3Dhttp%253A%252F%252Fplatform.xiaomimimo.com%252Fapi%252Fv1%252FtokenPlan%252Fusage&sid=api-platform&_group=DEFAULT"}"""
        val loginUrl = PageFetch.loginUrlIn(body)
        assertNotNull(loginUrl)

        val callbackEnc = loginUrl!!.substringAfter("callback=").substringBefore("&sid")
        val callback = URLDecoder.decode(URLDecoder.decode(callbackEnc, "UTF-8"), "UTF-8")
        val followup = callback.substringAfter("followup=")
        // 登录成功后 sts 302 的目标就是它：明文 http
        assertEquals("http://platform.xiaomimimo.com/api/v1/tokenPlan/usage", followup)
        // 拦截层把它升级成 https，登录不会再死在 ERR_CLEARTEXT_NOT_PERMITTED 上
        assertEquals(
            "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage",
            upgradeCleartextForFetch(followup, fetch),
        )
    }
}
