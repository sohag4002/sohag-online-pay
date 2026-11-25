package com.sohagonline.payment

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
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

    private var smsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val retryRunnable = object : Runnable {
        override fun run() {
            // 1. Scan SMS inbox in case any broadcast was missed or delayed
            scanRecentInbox()

            // 2. Process any pending queue
            processPendingQueue()

            // 3. Hourly 48-hour database purge
            if (System.currentTimeMillis() - prefs.lastCleanTime > 3600 * 1000L) {
                db.purgeOldRecords(48)
                prefs.lastCleanTime = System.currentTimeMillis()
            }

            // 4. Update status notification
            updateNotification()

            handler.postDelayed(this, 15_000L) // Scan and retry every 15 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PaymentForegroundService onCreate")
        db = DatabaseHelper.getInstance(this)
        prefs = AppPrefs(this)
        apiClient = ApiClient.getInstance(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SohagOnline::PaymentServiceWakeLock"
        )

        createNotificationChannel()
        startInForeground()

        // Register ContentObserver to capture all SMS directly from the device SMS provider
        registerSmsObserver()

        // Register Network Callback to immediately sync when connectivity is restored
        registerNetworkCallback()

        // Start background retry & cleanup loop
        handler.post(retryRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PaymentForegroundService onStartCommand")
        startInForeground()
        // If observer is not registered (e.g. permission was granted later), try registering
        if (smsObserver == null) {
            registerSmsObserver()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification("⚡ সোহাগ অনলাইন পেমেন্ট গেটওয়ে সক্রিয় আছে", "এসএমএস পর্যবেক্ষণ ও অটো সার্ভার সিঙ্ক চালু")

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
        val statusText = "সফল: ${counts.second} | পেন্ডিং: ${counts.third} • অটো সিঙ্ক ও রিডার সক্রিয়"

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
            val notification = buildNotification("⚡ সোহাগ অনলাইন পেমেন্ট গেটওয়ে সক্রিয় আছে", "")
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }

    /**
     * Engine 2: Real-time SMS ContentObserver.
     * Triggers whenever ANY SMS is written to the device SMS database (by RCS, Google Messages, OEM apps).
     */
    private fun registerSmsObserver() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_SMS permission not granted; cannot register SmsObserver.")
            return
        }

        try {
            smsObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d(TAG, "SmsObserver detected SMS database change: $uri")
                    scanRecentInbox()
                }
            }
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                smsObserver!!
            )
            Log.i(TAG, "SmsObserver successfully registered on Telephony.Sms.CONTENT_URI")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SmsObserver: ${e.message}")
        }
    }

    /**
     * Engine 3: Inbox Polling & Scanning.
     * Scans the latest incoming SMS in the inbox (last 15 minutes) to guarantee zero missed messages.
     */
    fun scanRecentInbox() {
        if (!prefs.isConfigured()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        executor.execute {
            var foundNew = false
            acquireWakeLock(15_000L)

            try {
                val fifteenMinutesAgo = System.currentTimeMillis() - (15 * 60 * 1000L)
                val projection = arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                )

                val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ?"
                val selectionArgs = arrayOf(fifteenMinutesAgo.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())

                contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${Telephony.Sms.DATE} DESC LIMIT 15"
                )?.use { cursor ->
                    val idxAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    val idxBody = cursor.getColumnIndex(Telephony.Sms.BODY)
                    val idxDate = cursor.getColumnIndex(Telephony.Sms.DATE)

                    while (cursor.moveToNext()) {
                        val sender = if (idxAddress != -1) cursor.getString(idxAddress) else null
                        val body = if (idxBody != -1) cursor.getString(idxBody) else null
                        val date = if (idxDate != -1) cursor.getLong(idxDate) else System.currentTimeMillis()

                        if (sender.isNullOrBlank() || body.isNullOrBlank()) continue

                        val cleanSender = sender.trim()
                        val cleanBody = body.trim()

                        // Check if it's a payment SMS
                        if (!SmsReceiver.isPaymentSms(cleanSender, cleanBody)) continue

                        // Check if already stored in our database
                        if (db.isSmsDuplicate(cleanSender, cleanBody)) continue

                        Log.i(TAG, "Inbox Scanner detected new SMS from [$cleanSender]: $cleanBody")

                        val record = SmsRecord(
                            address = cleanSender,
                            message = cleanBody,
                            timestamp = date,
                            status = SmsRecord.STATUS_PENDING
                        )
                        val insertedId = db.insertSms(record)
                        foundNew = true

                        // Immediately upload to server
                        try {
                            val response = apiClient.sendSms(cleanSender, cleanBody)
                            if (response.isSuccess) {
                                db.updateStatus(insertedId, SmsRecord.STATUS_SUCCESS, response.message)
                            } else {
                                db.updateStatus(insertedId, SmsRecord.STATUS_PENDING, response.message)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending inbox SMS #${insertedId}: ${e.message}")
                            db.updateStatus(insertedId, SmsRecord.STATUS_PENDING, e.message ?: "Failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning SMS inbox: ${e.message}", e)
            } finally {
                releaseWakeLock()
            }

            if (foundNew) {
                sendBroadcast(Intent(SmsReceiver.ACTION_REFRESH_UI))
            }
        }
    }

    private fun processPendingQueue() {
        if (!prefs.isConfigured()) return

        executor.execute {
            val pendingList = db.getPendingSms(limit = 10)
            if (pendingList.isNotEmpty()) {
                Log.d(TAG, "Processing ${pendingList.size} pending SMS...")
                var changed = false
                acquireWakeLock(20_000L)

                try {
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
                } finally {
                    releaseWakeLock()
                }

                if (changed) {
                    sendBroadcast(Intent(SmsReceiver.ACTION_REFRESH_UI))
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.d(TAG, "Internet connectivity restored! Triggering immediate sync & scan...")
                        processPendingQueue()
                        scanRecentInbox()
                    }
                }
                cm?.registerDefaultNetworkCallback(networkCallback!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
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

        try {
            if (smsObserver != null) {
                contentResolver.unregisterContentObserver(smsObserver!!)
            }
        } catch (_: Exception) {}

        try {
            if (networkCallback != null) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (_: Exception) {}

        releaseWakeLock()

        // Resurrect service
        startService(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
