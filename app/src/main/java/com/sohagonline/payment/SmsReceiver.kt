package com.sohagonline.payment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.sohagonline.payment.data.AppPrefs
import com.sohagonline.payment.data.DatabaseHelper
import com.sohagonline.payment.data.SmsRecord
import com.sohagonline.payment.network.ApiClient
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val ACTION_REFRESH_UI = "com.sohagonline.payment.REFRESH_UI"

        // Approved payment gateway & bank addresses
        val PAYMENT_SENDERS = setOf(
            "bkash", "nagad", "16167", "16216", "rocket", "upay", "16495",
            "islami.bank", "ibbl", "tap", "09638900800", "01401195496", "mcash", "3737",
            "citybank", "city touch", "citytouch", "dbbl", "bracbank", "ebl",
            "bankasia", "basic", "abbank", "agrani", "sonali", "jamunabank", "ific",
            "ucb", "sebl", "primebank", "dhakabank", "cellfin", "16259", "surecash", "okwallet"
        )

        // Payment-related keywords
        private val PAYMENT_KEYWORDS = listOf(
            "trxid", "trx id", "transaction id", "received tk", "cash in",
            "payment received", "cashin", "credited", "acct", "balance tk",
            "fee tk", "successful", "bdt", "tk."
        )

        fun isPaymentSms(sender: String, message: String): Boolean {
            val lowerSender = sender.lowercase().trim()
            val lowerMsg = message.lowercase()

            // 1. Matches recognized sender
            if (PAYMENT_SENDERS.any { lowerSender.contains(it) }) return true

            // 2. Starts with standard shortcode or BD number
            if (lowerSender.startsWith("16") || lowerSender.startsWith("01") || lowerSender.startsWith("+8801")) {
                if (PAYMENT_KEYWORDS.any { lowerMsg.contains(it) }) return true
            }

            // 3. Any sender whose message clearly contains transaction indicators
            if (lowerMsg.contains("trxid") || lowerMsg.contains("trx id") ||
                (lowerMsg.contains("received") && (lowerMsg.contains("tk") || lowerMsg.contains("bdt")))) {
                return true
            }

            return false
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != "android.provider.Telephony.SMS_DELIVER") return

        val prefs = AppPrefs(context)
        if (!prefs.isConfigured()) {
            Log.w(TAG, "SMS received but app is not configured yet.")
            return
        }

        // Ensure background foreground service is running
        PaymentForegroundService.startService(context)

        // Hold WakeLock and goAsync so Android won't freeze the process during server sync
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SohagOnline::SmsReceiverWakeLock"
        )
        wakeLock?.acquire(30_000L) // 30-sec safety timeout

        executor.execute {
            try {
                // Official, crash-free Android SMS extraction across all devices and formats
                val messages: Array<SmsMessage>? = try {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "getMessagesFromIntent failed, falling back to manual pdus: ${e.message}")
                    null
                }

                val messagesBySender = mutableMapOf<String, StringBuilder>()

                if (messages != null && messages.isNotEmpty()) {
                    for (sms in messages) {
                        val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: continue
                        val body = sms.displayMessageBody ?: sms.messageBody ?: continue

                        if (!messagesBySender.containsKey(sender)) {
                            messagesBySender[sender] = StringBuilder()
                        }
                        messagesBySender[sender]?.append(body)
                    }
                } else {
                    // Fallback to manual PDU extraction if getMessagesFromIntent returned null
                    val bundle = intent.extras
                    if (bundle != null) {
                        val format = bundle.getString("format")
                        val pdus = bundle.get("pdus") as? Array<*>
                        if (pdus != null) {
                            for (pdu in pdus) {
                                val pduBytes = pdu as? ByteArray ?: continue
                                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    SmsMessage.createFromPdu(pduBytes, format)
                                } else {
                                    @Suppress("DEPRECATION")
                                    SmsMessage.createFromPdu(pduBytes)
                                } ?: continue

                                val sender = sms.originatingAddress ?: continue
                                val body = sms.messageBody ?: continue
                                if (!messagesBySender.containsKey(sender)) {
                                    messagesBySender[sender] = StringBuilder()
                                }
                                messagesBySender[sender]?.append(body)
                            }
                        }
                    }
                }

                val db = DatabaseHelper.getInstance(context)
                val apiClient = ApiClient.getInstance(context)

                // Process each incoming SMS
                for ((sender, bodyBuilder) in messagesBySender) {
                    val fullBody = bodyBuilder.toString().trim()
                    val cleanSender = sender.trim()

                    Log.d(TAG, "Incoming SMS from [$cleanSender]: $fullBody")

                    // Check if sender/body is a payment SMS
                    if (!isPaymentSms(cleanSender, fullBody)) {
                        Log.d(TAG, "Ignored non-payment SMS from $cleanSender")
                        continue
                    }

                    // Check for duplicate
                    if (db.isSmsDuplicate(cleanSender, fullBody)) {
                        Log.d(TAG, "Duplicate SMS already processed for $cleanSender, skipping.")
                        continue
                    }

                    // Save to local database with PENDING status
                    val record = SmsRecord(
                        address = cleanSender,
                        message = fullBody,
                        status = SmsRecord.STATUS_PENDING
                    )
                    val insertedId = db.insertSms(record)

                    // Auto-clean database: purge records older than 48 hours
                    db.purgeOldRecords(48)

                    // Immediately send to server
                    try {
                        val response = apiClient.sendSms(cleanSender, fullBody)
                        Log.d(TAG, "Server sync result for #$insertedId: ${response.message}")

                        if (response.isSuccess) {
                            db.updateStatus(insertedId, SmsRecord.STATUS_SUCCESS, response.message)
                        } else {
                            db.updateStatus(insertedId, SmsRecord.STATUS_PENDING, response.message)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error posting SMS to server: ${e.message}", e)
                        db.updateStatus(insertedId, SmsRecord.STATUS_PENDING, e.message ?: "Failed")
                    }

                    // Notify UI to update
                    context.sendBroadcast(Intent(ACTION_REFRESH_UI))
                }
            } catch (e: Exception) {
                Log.e(TAG, "General error in SmsReceiver onReceive: ${e.message}", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }
}
