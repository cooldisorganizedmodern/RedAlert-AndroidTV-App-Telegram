package com.example.redalert

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 1234

    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSubmit: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etInput = findViewById(R.id.etInput)
        btnSubmit = findViewById(R.id.btnSubmit)

        checkOverlayPermission()

        btnSubmit.setOnClickListener {
            val input = etInput.text.toString()
            if (input.isNotBlank()) {
                // Pass input to TDLibManager for auth
                TDLibManager.sendAuthInput(input)
                etInput.text.clear()
            }
        }
        
        findViewById<Button>(R.id.btnDemo).setOnClickListener {
            TDLibManager.showDemo()
        }
        
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            TDLibManager.logOut()
        }

        // Observe authorization state
        lifecycleScope.launch {
            TDLibManager.authStateFlow.collectLatest { state ->
                runOnUiThread {
                    if (state is TdApi.AuthorizationStateReady) {
                        findViewById<View>(R.id.llLogin).visibility = View.GONE
                        findViewById<View>(R.id.llDashboard).visibility = View.VISIBLE
                    } else {
                        findViewById<View>(R.id.llLogin).visibility = View.VISIBLE
                        findViewById<View>(R.id.llDashboard).visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.text = "Please grant Overlay Permission in Settings."
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            } else {
                startAlertService()
            }
        } else {
            startAlertService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startAlertService()
                } else {
                    tvStatus.text = "Overlay permission denied. App cannot function."
                }
            }
        }
    }

    private fun startAlertService() {
        tvStatus.text = "Overlay permission granted. Service running."
        val serviceIntent = Intent(this, AlertService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // In Android 12+, starting FGS from background might crash if exemptions aren't fully active yet
            e.printStackTrace()
            // Fallback to binding or standard start if needed, but since we are in the activity, it should succeed.
        }
    }
}
