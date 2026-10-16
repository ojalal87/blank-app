package com.bright.onepluscare

import android.app.Application

class CareApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.startup()
    }
}
