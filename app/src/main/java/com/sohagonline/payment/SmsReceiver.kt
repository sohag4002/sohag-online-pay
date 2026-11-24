package com.sohagonline.payment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

        // Approved payment gateway & bank addresses matching server whitelist in ApiController.php
        val PAYMENT_SENDERS = setOf(
            "bkash", "nagad", "16167", "16216", "upay", "16495",
            "islami.bank", "ibbl", "tap", "09638900800", "01401195496", "mcash", "3737",
            "citybank", "city touch", "citytouch", "dbbl", "bracbank", "ebl",
            "bankasia", "basic", "abbank", "agrani", "sonali", "jamunabank", "ific",
            "ucb", "sebl", "primebank", "dhakabank"
        )
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = AppPrefs(context)
        if (!prefs.isConfigured()) {
            Log.w(TAG, "SMS received but app is not configured yet.")
            return
        }

        // Ensure background foreground service is alive
        PaymentForegroundService.startService(context)

        val bundle = intent.extras ?: return
        val format = bundle.getString("format")
        val pdus = bundle.get("pdus") as? Array<*> ?: return

        val messagesBySender = mutableMapOf<String, StringBuilder>()

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

        val db = DatabaseHelper.getInstance(context)
        val apiClient = ApiClient.getInstance(context)

        // Process each incoming SMS
        for ((sender, bodyBuilder) in messagesBySender) {
            val fullBody = bodyBuilder.toString().trim()
            val cleanSender = sender.trim()

            Log.d(TAG, "Incoming SMS from [$cleanSender]: $fullBody")

            // Check if sender is a recognized payment gateway sender
            val isPaymentSender = isRecognizedSender(cleanSender)

            // Save to local database with PENDING status
            val record = SmsRecord(
                address = cleanSender,
                message = fullBody,
                status = SmsRecord.STATUS_PENDING
            )
            val insertedId = db.insertSms(record)

            // Auto-clean database: purge records older than 48 hours
            db.purgeOldRecords(48)

            // Immediately send to server in background thread
            executor.execute {
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
        }
    }

    private fun isRecognizedSender(sender: String): Boolean {
        val lower = sender.lowercase()
        return PAYMENT_SENDERS.any { lower.contains(it) } ||
                lower.startsWith("01") ||
                lower.startsWith("+8801") ||
                lower.contains("pay") ||
                lower.contains("bank")
    }
}
