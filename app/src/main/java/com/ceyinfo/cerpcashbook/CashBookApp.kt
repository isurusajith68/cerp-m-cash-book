package com.ceyinfo.cerpcashbook

import android.app.Application

class CashBookApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CashBookApp
            private set
    }
}
