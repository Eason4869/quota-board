package com.yusheng.quota

import android.app.Application
import com.yusheng.quota.data.Store

class QuotaApp : Application() {

    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        instance = this
    }

    companion object {
        lateinit var instance: QuotaApp
            private set
    }
}
