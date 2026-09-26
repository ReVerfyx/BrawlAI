package com.reverfyx.brawlai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.reverfyx.brawlai.capture.CaptureService
import com.reverfyx.brawlai.input.PylaAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var nnapiCheck: CheckBox
    private lateinit var cropCheck: CheckBox

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            statusText.text = "Захват экрана не разрешён"
            return@registerForActivityResult
        }

        val service = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            putExtra(CaptureService.EXTRA_USE_NNAPI, nnapiCheck.isChecked)
            putExtra(CaptureService.EXTRA_CROP_16_9, cropCheck.isChecked)
        }
        startForegroundService(service)
        statusText.text = "BrawlAI запущен. Открой Brawl Stars."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        nnapiCheck = findViewById(R.id.nnapiCheck)
        cropCheck = findViewById(R.id.cropCheck)

        val prefs = getSharedPreferences("pyla", MODE_PRIVATE)
        nnapiCheck.isChecked = prefs.getBoolean("nnapi", true)
        cropCheck.isChecked = prefs.getBoolean("crop169", true)

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            prefs.edit()
                .putBoolean("nnapi", nnapiCheck.isChecked)
                .putBoolean("crop169", cropCheck.isChecked)
                .apply()

            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (PylaAccessibilityService.instance == null) {
                Toast.makeText(
                    this,
                    "Сервис управления ещё не включён. Детектор запустится, но бот не сможет нажимать кнопки.",
                    Toast.LENGTH_LONG
                ).show()
            }
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.openGameButton).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage("com.supercell.brawlstars")
            if (launch == null) {
                Toast.makeText(this, "Brawl Stars не найдена", Toast.LENGTH_SHORT).show()
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            PylaAccessibilityService.instance?.cancelBotGesture()
            statusText.text = "Остановлено"
        }
    }

    override fun onResume() {
        super.onResume()
        if (CaptureService.running) {
            statusText.text = "BrawlAI работает в фоне"
        }
    }
}
