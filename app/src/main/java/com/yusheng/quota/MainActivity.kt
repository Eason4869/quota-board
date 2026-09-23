package com.yusheng.quota

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yusheng.quota.ui.QuotaAppRoot
import com.yusheng.quota.ui.QuotaViewModel
import com.yusheng.quota.ui.theme.QuotaBoardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: QuotaViewModel = viewModel()
            val state by vm.state.collectAsState()

            val dark = when (state.settings.darkMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            QuotaBoardTheme(darkMode = state.settings.darkMode) {
                // 状态栏 / 导航栏图标对比度跟随主题，保证浅色主题下仍然可见
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
                QuotaAppRoot(vm)
            }
        }
    }
}
