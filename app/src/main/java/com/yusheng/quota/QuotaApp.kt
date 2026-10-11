package com.yusheng.quota

import android.app.Application
import android.content.res.Configuration
import com.yusheng.quota.data.Store
import com.yusheng.quota.widget.QuotaWidgets
import java.io.Closeable

class QuotaApp : Application() {

    lateinit var store: Store
        private set
    private var widgetObserver: Closeable? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        instance = this
        widgetObserver = store.observe(
            onAccountsChanged = {
                QuotaWidgets.renderAll(this)
                QuotaWidgets.schedule(this)
            },
            onSettingsChanged = {
                QuotaWidgets.renderAll(this)
                QuotaWidgets.schedule(this)
            },
        )
        QuotaWidgets.renderAll(this)
        QuotaWidgets.schedule(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        QuotaWidgets.renderAll(this)
    }

    companion object {
        lateinit var instance: QuotaApp
            private set
    }
}
