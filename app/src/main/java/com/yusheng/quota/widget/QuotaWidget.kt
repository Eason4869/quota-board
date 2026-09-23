package com.yusheng.quota.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.yusheng.quota.MainActivity
import com.yusheng.quota.R
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.Store
import com.yusheng.quota.ui.fmtNum
import kotlin.math.roundToInt

/**
 * 桌面小组件：显示前几个账户的余额 / 订阅剩余 / 周期窗口，整体可点开应用。
 * 长按桌面 → 小组件 → 额度台 添加。
 */
class QuotaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accounts = Store(context).loadAccounts()
        provideContent { WidgetContent(accounts) }
    }

    companion object {
        suspend fun refreshAll(context: Context) {
            QuotaWidget().updateAll(context)
        }
    }
}

private const val MAX_ROWS = 3

@Composable
private fun WidgetContent(accounts: List<Account>) {
    val ctx = LocalContext.current
    val accent = ColorProvider(Color(0xFFFF6900))
    val muted = ColorProvider(Color(0xFF7E8B9C))
    val ink = ColorProvider(Color(0xFFF3F7FC))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF141A22))
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(ctx, MainActivity::class.java))),
    ) {
        if (accounts.isEmpty()) {
            Text(
                ctx.getString(R.string.widget_empty),
                style = TextStyle(color = muted, fontSize = 12.sp),
            )
            return@Column
        }

        accounts.take(MAX_ROWS).forEachIndexed { index, account ->
            if (index > 0) Spacer(GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        account.name,
                        style = TextStyle(color = muted, fontSize = 11.sp),
                    )
                    Text(
                        mainValue(account),
                        style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    )
                }
                val pct = account.result?.periods?.firstOrNull()?.remainPercent
                if (pct != null) {
                    Text(
                        ctx.getString(R.string.label_remain_pct, pct.roundToInt()),
                        style = TextStyle(
                            color = if (pct <= 25.0) accent else ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                } else {
                    account.result?.subscription?.tier?.let {
                        Text(it, style = TextStyle(color = accent, fontSize = 11.sp))
                    }
                }
            }
        }

        if (accounts.size > MAX_ROWS) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                ctx.getString(R.string.widget_more, accounts.size - MAX_ROWS),
                style = TextStyle(color = muted, fontSize = 10.sp),
            )
        }

        // 还一次都没查过：提示可以点开应用
        if (accounts.none { it.result != null }) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                ctx.getString(R.string.widget_tap_open),
                style = TextStyle(color = muted, fontSize = 10.sp),
            )
        }
    }
}

private fun mainValue(account: Account): String {
    val result = account.result ?: return account.lastError?.let { "!" } ?: "—"
    result.balance?.let { return "${it.currency} ${fmtNum(it.amount)}" }
    result.subscription?.remaining?.let { return fmtNum(it) }
    return "—"
}
