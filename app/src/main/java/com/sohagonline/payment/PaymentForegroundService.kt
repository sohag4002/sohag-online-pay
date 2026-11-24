package com.sohagonline.payment

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sohagonline.payment.data.AppPrefs
import com.sohagonline.payment.data.DatabaseHelper
import com.sohagonline.payment.data.SmsRecord
import com.sohagonline.payment.network.ApiClient
import java.util.concurrent.Executors

class PaymentForegroundService : Service() {

    companion object {
        private const val TAG = "PaymentService"
        const val CHANNEL_ID = "sohag_payment_service_channel"
        const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, PaymentForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PaymentForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: DatabaseHelper
    private lateinit var prefs: AppPrefs
    private lateinit var apiClient: ApiClient

    private val retryRunnable = object : Runnable {
        override fun run() {
            processPendingQueue()
            // Run 48-hour data cleanup every hour
            if (System.currentTimeMillis() - prefs.lastCleanTime > 3600 * 1000L) {
                db.purgeOldRecords(48)
                prefs.lastCleanTime = System.currentTimeMillis()
            }
            updateNotification()
            handler.postDelayed(this, 20_000L) // check every 20 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PaymentForegroundService onCreate")
        db = DatabaseHelper.getInstance(this)
        prefs = AppPrefs(this)
        apiClient = ApiClient.getInstance(this)

        createNotificationChannel()
        startInForeground()

        // Start background retry & cleanup loop
        handler.post(retryRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PaymentForegroundService onStartCommand")
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification("⚡ সোয়েল/সোহাগ পেমেন্ট গেটওয়ে সক্রিয় আছে", "এসএমএস পর্যবেক্ষণ ও অটো সার্ভার সিঙ্ক চালু")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires dataSync type
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val counts = if (::db.isInitialized) db.getCounts() else Triple(0, 0, 0)
        val statusText = "সফল: ${counts.second} | পেন্ডিং: ${counts.third} • ৪৮ ঘণ্টার অটো ক্লিনার চালু"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notification = buildNotification("⚡ সোয়েল/সোহাগ পেমেন্ট গেটওয়ে সক্রিয় আছে", "")
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }

    private fun processPendingQueue() {
        if (!prefs.isConfigured()) return

        executor.execute {
            val pendingList = db.getPendingSms(limit = 10)
            if (pendingList.isNotEmpty()) {
                Log.d(TAG, "Processing ${pendingList.size} pending SMS...")
                var changed = false

                for (sms in pendingList) {
                    try {
                        val response = apiClient.sendSms(sms.address, sms.message)
                        if (response.isSuccess) {
                            db.updateStatus(sms.id, SmsRecord.STATUS_SUCCESS, response.message)
                            changed = true
                        } else {
                            db.updateStatus(sms.id, SmsRecord.STATUS_PENDING, response.message)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to retry SMS #${sms.id}: ${e.message}")
                    }
                }

                if (changed) {
                    sendBroadcast(Intent(SmsReceiver.ACTION_REFRESH_UI))
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "App task removed from recent apps! Rescheduling service restart...")

        val restartIntent = Intent(applicationContext, PaymentForegroundService::class.java)
        val restartPendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            restartPendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "PaymentForegroundService onDestroy. Restarting...")
        handler.removeCallbacks(retryRunnable)

        // Resurrect service
        startService(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
