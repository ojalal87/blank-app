package com.rootguard.app

import android.app.Application
import com.rootguard.app.data.FirewallController
import com.rootguard.app.data.InventoryRepository
import com.rootguard.app.data.db.ControlDatabase
import com.rootguard.app.root.LibsuGateway
import com.topjohnwu.superuser.Shell

class RootGuardApp : Application() {
    lateinit var controller: FirewallController
        private set

    override fun onCreate() {
        super.onCreate()
        // libsu: no verbose logging (logs could reveal app lists); default non-mount-master shell.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(20))
        val db = ControlDatabase.build(this)
        controller = FirewallController(db, LibsuGateway(this), InventoryRepository(this, db))
    }
}
