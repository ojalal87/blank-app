package com.bright.onepluscare.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Daily, read-only maintenance scan. Constraints keep it battery- and thermal-friendly:
 * charging, battery not low, storage not low, no network needed. OxygenOS and other OEMs may still defer
 * it; the Battery & Thermal screen explains how to allow background activity if the user wants that.
 */
class MaintenanceScheduler(private val context: Context) {

    fun apply(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<MaintenanceScanWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        /** Same name as v0.3, so an existing schedule is updated rather than duplicated. */
        const val UNIQUE_WORK = "oneplus_care_daily_scan"
    }
}
