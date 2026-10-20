package com.indoorar.wayfinder

import android.app.Application

class WayfinderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
