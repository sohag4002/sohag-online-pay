package com.sohagonline.payment.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sohagonline.payment.PaymentForegroundService
import com.sohagonline.payment.data.DatabaseHelper

class CleanupWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "sohag_cleanup_and_watchdog_work"
    }

    override suspend fun doWork(): Result {
        Log.d("CleanupWorker", "Executing periodic 48-hour database purge & watchdog check...")

        // 1. Purge records older than 48 hours to maintain feather-light storage
        try {
            val db = DatabaseHelper.getInstance(context)
            val deletedCount = db.purgeOldRecords(48)
            Log.d("CleanupWorker", "Cleanup completed: $deletedCount records purged.")
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Error purging records: ${e.message}")
        }

        // 2. Watchdog: Ensure PaymentForegroundService is running 24/7
        try {
            PaymentForegroundService.startService(context)
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Error starting service in watchdog: ${e.message}")
        }

        return Result.success()
    }
}
