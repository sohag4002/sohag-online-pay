package com.sohagonline.payment

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sohagonline.payment.worker.CleanupWorker
import java.util.concurrent.TimeUnit

class SohagApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("SohagApplication", "Application initialized.")

        // Start 24/7 Foreground Service
        PaymentForegroundService.startService(this)

        // Schedule periodic 48-hour data cleanup & service watchdog (runs every 6 hours)
        schedulePeriodicCleanup()
    }

    private fun schedulePeriodicCleanup() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                CleanupWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            Log.e("SohagApplication", "Failed to schedule periodic cleanup: ${e.message}")
        }
    }
}
