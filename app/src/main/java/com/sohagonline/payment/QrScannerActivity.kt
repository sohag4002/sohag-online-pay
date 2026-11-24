package com.sohagonline.payment

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class QrScannerActivity : AppCompatActivity() {

    private lateinit var captureManager: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private var isFlashOn = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Allow screenshots and screen recording
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_qr_scanner)

        barcodeScannerView = findViewById(R.id.barcodeScannerView)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnFlash = findViewById<ImageView>(R.id.btnFlash)

        btnBack.setOnClickListener { finish() }

        btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn
            if (isFlashOn) {
                barcodeScannerView.setTorchOn()
            } else {
                barcodeScannerView.setTorchOff()
            }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else {
            startScanner(savedInstanceState)
        }
    }

    private fun startScanner(savedInstanceState: Bundle?) {
        captureManager = CaptureManager(this, barcodeScannerView)
        captureManager.initializeFromIntent(intent, savedInstanceState)
        captureManager.decode()

        barcodeScannerView.decodeSingle { result ->
            val data = result.text ?: ""
            val resultIntent = Intent().apply {
                putExtra("qr_data", data)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanner(null)
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::captureManager.isInitialized) {
            captureManager.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::captureManager.isInitialized) {
            captureManager.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::captureManager.isInitialized) {
            captureManager.onDestroy()
        }
    }
}
