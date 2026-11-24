package com.sohagonline.payment

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sohagonline.payment.data.AppPrefs
import com.sohagonline.payment.data.DatabaseHelper
import com.sohagonline.payment.data.SmsRecord
import com.sohagonline.payment.databinding.ActivityMainBinding
import com.sohagonline.payment.network.ApiClient
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var db: DatabaseHelper
    private lateinit var apiClient: ApiClient
    private lateinit var adapter: SmsAdapter
    private val executor = Executors.newSingleThreadExecutor()

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadDashboardData()
        }
    }

    // QR Scanner Launcher
    private val qrScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val qrData = result.data?.getStringExtra("qr_data") ?: ""
            handleScannedQrData(qrData)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Allow screenshots, screen recording, and remote screen share (AnyDesk/TeamViewer/Google Meet)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        db = DatabaseHelper.getInstance(this)
        apiClient = ApiClient.getInstance(this)

        setupRecyclerView()
        setupClickListeners()
        checkPermissions()
        handleIncomingDeepLink(intent)

        // Ensure 24/7 foreground service is running
        PaymentForegroundService.startService(this)

        loadDashboardData()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryCardVisibility()
        loadDashboardData()
        val filter = IntentFilter(SmsReceiver.ACTION_REFRESH_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
    }

    private fun setupRecyclerView() {
        adapter = SmsAdapter(emptyList())
        binding.recyclerViewSms.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSms.adapter = adapter
    }

    private fun setupClickListeners() {
        // QR Code Scanner (Zero-hassle 1-click connect)
        binding.btnScanQr.setOnClickListener {
            val intent = Intent(this, QrScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

        // Manual Settings Dialog
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnManualSetup.setOnClickListener { showSettingsDialog() }

        // Battery Optimization
        binding.btnIgnoreBattery.setOnClickListener {
            AutoStartHelper.requestIgnoreBatteryOptimization(this)
        }

        // Auto-Start Settings
        binding.btnAutoStartSettings.setOnClickListener {
            val opened = AutoStartHelper.openAutoStartSettings(this)
            if (!opened) {
                Toast.makeText(this, "সেটিংস অপশন পাওয়া যায়নি, অ্যাপ ইনফো সেটিংস ওপেন হচ্ছে", Toast.LENGTH_SHORT).show()
            }
        }

        // Test SMS Button
        binding.btnTestSms.setOnClickListener { triggerTestSms() }

        // Manual Clear Data Button
        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ডাটা ক্লিয়ার")
                .setMessage("আপনি কি সব লোকাল এসএমএস হিস্ট্রি মুছে ফেলতে চান? এতে অ্যাপের স্টোরেজ সম্পূর্ণ খালি হবে।")
                .setPositiveButton("হ্যাঁ, মুছুন") { _, _ ->
                    db.clearAll()
                    loadDashboardData()
                    Toast.makeText(this, "সব ডাটা সফলভাবে মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("বাতিল", null)
                .show()
        }
    }

    private fun loadDashboardData() {
        val (total, success, pending) = db.getCounts()
        binding.tvCountTotal.text = total.toString()
        binding.tvCountSuccess.text = success.toString()
        binding.tvCountPending.text = pending.toString()

        val recentList = db.getRecentSms(50)
        if (recentList.isEmpty()) {
            binding.recyclerViewSms.visibility = View.GONE
            binding.layoutEmpty.visibility = View.VISIBLE
        } else {
            binding.recyclerViewSms.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
            adapter.updateData(recentList)
        }

        updateConnectionStatusUi()
    }

    private fun updateConnectionStatusUi() {
        if (prefs.isConfigured() && prefs.isConnected) {
            binding.dotStatus.setBackgroundResource(R.drawable.status_dot_green)
            binding.tvStatusText.text = getString(R.string.status_connected)
            binding.cardQuickSetup.visibility = View.GONE
        } else if (prefs.isConfigured()) {
            binding.dotStatus.setBackgroundResource(R.drawable.status_dot_green)
            binding.tvStatusText.text = "কনফিগার করা আছে • সার্ভার সিঙ্ক চালু"
            binding.cardQuickSetup.visibility = View.GONE
        } else {
            binding.dotStatus.setBackgroundResource(R.drawable.status_dot_red)
            binding.tvStatusText.text = getString(R.string.status_disconnected)
            binding.cardQuickSetup.visibility = View.VISIBLE
        }
    }

    private fun updateBatteryCardVisibility() {
        val isIgnored = AutoStartHelper.isBatteryOptimizationIgnored(this)
        binding.cardBatteryWarning.visibility = if (isIgnored) View.GONE else View.VISIBLE
    }

    private fun handleScannedQrData(qrData: String) {
        if (qrData.isBlank()) return

        try {
            // Check if JSON: {"url":"...","email":"...","key":"..."}
            val json = JsonParser.parseString(qrData).asJsonObject
            val url = json.get("url")?.asString ?: prefs.serverUrl
            val email = json.get("email")?.asString ?: ""
            val key = json.get("key")?.asString ?: ""

            if (email.isNotBlank() && key.isNotBlank()) {
                connectAndSave(url, email, key)
            } else {
                Toast.makeText(this, "QR কোডের ডাটা অসম্পূর্ণ!", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            // Check pipe-separated: url|email|key or email|key
            val parts = qrData.split("|")
            if (parts.size >= 3) {
                connectAndSave(parts[0], parts[1], parts[2])
            } else if (parts.size == 2) {
                connectAndSave(prefs.serverUrl, parts[0], parts[1])
            } else {
                Toast.makeText(this, "অকার্যকর QR কোড ফরম্যাট", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "sohagonline" && data.host == "setup") {
            val url = data.getQueryParameter("url") ?: prefs.serverUrl
            val email = data.getQueryParameter("email") ?: ""
            val key = data.getQueryParameter("key") ?: ""

            if (email.isNotBlank() && key.isNotBlank()) {
                connectAndSave(url, email, key)
            }
        }
    }

    private fun connectAndSave(url: String, email: String, key: String) {
        val cleanUrl = url.trim().trimEnd('/')
        Toast.makeText(this, "সার্ভারের সাথে সংযোগ স্থাপন করা হচ্ছে...", Toast.LENGTH_SHORT).show()

        executor.execute {
            val response = apiClient.connectDevice(cleanUrl, email, key)
            runOnUiThread {
                if (response.isSuccess) {
                    prefs.serverUrl = cleanUrl
                    prefs.userEmail = email
                    prefs.deviceKey = key
                    prefs.isConnected = true

                    updateConnectionStatusUi()
                    PaymentForegroundService.startService(this)

                    AlertDialog.Builder(this)
                        .setTitle("✅ সফলভাবে সংযুক্ত!")
                        .setMessage("আপনার সোয়েল/সোহাগ পেমেন্ট গেটওয়ে সার্ভারের সাথে ডিভাইসটি সম্পূর্ণ সফলভাবে সংযুক্ত হয়েছে। এখন যেকোনো এসএমএস আসামাত্র স্বয়ংক্রিয়ভাবে সার্ভারে চলে যাবে।")
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                } else {
                    prefs.isConnected = false
                    updateConnectionStatusUi()
                    AlertDialog.Builder(this)
                        .setTitle("❌ সংযোগ ব্যর্থ হয়েছে")
                        .setMessage("সার্ভার থেকে উত্তর: ${response.message}\nদয়া করে ইমেইল ও ডিভাইস কী সঠিক কি না পরীক্ষা করুন।")
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etServerUrl = dialogView.findViewById<TextInputEditText>(R.id.etServerUrl)
        val etUserEmail = dialogView.findViewById<TextInputEditText>(R.id.etUserEmail)
        val etDeviceKey = dialogView.findViewById<TextInputEditText>(R.id.etDeviceKey)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<View>(R.id.btnSave)

        etServerUrl.setText(prefs.serverUrl)
        etUserEmail.setText(prefs.userEmail)
        etDeviceKey.setText(prefs.deviceKey)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            val email = etUserEmail.text.toString().trim()
            val key = etDeviceKey.text.toString().trim()

            if (url.isBlank() || email.isBlank() || key.isBlank()) {
                Toast.makeText(this, "সবগুলো ফিল্ড পূরণ করুন", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            connectAndSave(url, email, key)
        }

        dialog.show()
    }

    private fun triggerTestSms() {
        if (!prefs.isConfigured()) {
            Toast.makeText(this, "প্রথমে QR কোড বা সেটিংস দিয়ে সার্ভার সংযুক্ত করুন", Toast.LENGTH_LONG).show()
            return
        }

        val testTrx = "TEST" + (100000..999999).random()
        val testMessage = "You have received Tk 500.00 from 01700000000. Fee Tk 0.00. Balance Tk 1500.00. TrxID $testTrx at 24/11/2026 12:00"
        val testSender = "bKash"

        val record = SmsRecord(
            address = testSender,
            message = testMessage,
            status = SmsRecord.STATUS_PENDING
        )
        val insertedId = db.insertSms(record)
        loadDashboardData()
        Toast.makeText(this, "টেস্ট এসএমএস সার্ভারে পাঠানো হচ্ছে...", Toast.LENGTH_SHORT).show()

        executor.execute {
            val response = apiClient.sendSms(testSender, testMessage)
            runOnUiThread {
                if (response.isSuccess) {
                    db.updateStatus(insertedId, SmsRecord.STATUS_SUCCESS, response.message)
                    Toast.makeText(this, "✅ টেস্ট সফল! সার্ভার রেসপন্স: ${response.message}", Toast.LENGTH_LONG).show()
                } else {
                    db.updateStatus(insertedId, SmsRecord.STATUS_FAILED, response.message)
                    Toast.makeText(this, "❌ ব্যর্থ: ${response.message}", Toast.LENGTH_LONG).show()
                }
                loadDashboardData()
            }
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            var allGranted = true
            for (res in grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                Toast.makeText(this, "এসএমএস পর্যবেক্ষণ পারমিশন অনুমোদিত হয়েছে", Toast.LENGTH_SHORT).show()
                PaymentForegroundService.startService(this)
            }
        }
    }
}
