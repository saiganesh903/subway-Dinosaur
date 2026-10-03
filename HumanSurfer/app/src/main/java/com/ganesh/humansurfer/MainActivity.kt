package com.ganesh.humansurfer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "human_surfer"
        private const val KEY_YT = "youtube_endpoint"
        private const val KEY_FB = "facebook_endpoint"
        private const val KEY_YT_ON = "youtube_on"
        private const val KEY_FB_ON = "facebook_on"
        private const val KEY_DURATION = "duration_minutes"
        private const val KEY_BOT_ENABLED = "bot_enabled"
        private const val GAME_PACKAGE = "com.kiloo.subwaysurf"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var spDuration: Spinner
    private lateinit var cbYouTube: CheckBox
    private lateinit var cbFacebook: CheckBox
    private lateinit var etYouTube: EditText
    private lateinit var etFacebook: EditText

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            setStatus("Screen capture permission was cancelled")
            return@registerForActivityResult
        }

        saveSettings()
        prefs.edit().putBoolean(KEY_BOT_ENABLED, true).apply()

        // IMPORTANT:
        // Explicitly start the bot controller before opening Subway Surfers.
        BotAccessibilityService.start(selectedDuration())

        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(StreamService.EXTRA_RESULT_DATA, result.data)
            putExtra(
                StreamService.EXTRA_YOUTUBE,
                if (cbYouTube.isChecked) etYouTube.text.toString().trim() else ""
            )
            putExtra(
                StreamService.EXTRA_FACEBOOK,
                if (cbFacebook.isChecked) etFacebook.text.toString().trim() else ""
            )
            putExtra(StreamService.EXTRA_DURATION, selectedDuration())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        launchGame()
        setStatus("Bot started • Streaming enabled")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        bindViews()
        loadSettings()

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startHumanSurfer()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopHumanSurfer()
        }

findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            UpdateManager.checkForUpdate(this, showNoUpdateMessage = true)
        }
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        // Check for a newer published Human Surfer build.
        UpdateManager.checkForUpdate(this)
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun bindViews() {

        tvStatus = findViewById(R.id.tvStatus)
        spDuration = findViewById(R.id.spDuration)
        cbYouTube = findViewById(R.id.cbYouTube)
        cbFacebook = findViewById(R.id.cbFacebook)
        etYouTube = findViewById(R.id.etYouTube)
        etFacebook = findViewById(R.id.etFacebook)

        val durations = arrayOf(
            "1 minute",
            "5 minutes",
            "10 minutes",
            "30 minutes",
            "60 minutes",
            "Unlimited"
        )

        spDuration.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            durations
        )
    }

    private fun loadSettings() {

        etYouTube.setText(
            prefs.getString(KEY_YT, "")
        )

        etFacebook.setText(
            prefs.getString(KEY_FB, "")
        )

        cbYouTube.isChecked =
            prefs.getBoolean(KEY_YT_ON, false)

        cbFacebook.isChecked =
            prefs.getBoolean(KEY_FB_ON, false)

        val saved =
            prefs.getInt(KEY_DURATION, 0)

        spDuration.setSelection(
            saved.coerceIn(0, 5)
        )
    }

    private fun saveSettings() {

        prefs.edit()
            .putString(
                KEY_YT,
                etYouTube.text.toString().trim()
            )
            .putString(
                KEY_FB,
                etFacebook.text.toString().trim()
            )
            .putBoolean(
                KEY_YT_ON,
                cbYouTube.isChecked
            )
            .putBoolean(
                KEY_FB_ON,
                cbFacebook.isChecked
            )
            .putInt(
                KEY_DURATION,
                spDuration.selectedItemPosition
            )
            .apply()
    }

    private fun selectedDuration(): Int {

        return when (spDuration.selectedItemPosition) {
            0 -> 1
            1 -> 5
            2 -> 10
            3 -> 30
            4 -> 60
            else -> 0
        }
    }

    private fun startHumanSurfer() {

        saveSettings()

        if (!isAccessibilityEnabled()) {

            Toast.makeText(
                this,
                "Enable the Human Surfer accessibility service first",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )

            return
        }

        val hasYouTube =
            cbYouTube.isChecked &&
                etYouTube.text.toString()
                    .trim()
                    .isNotEmpty()

        val hasFacebook =
            cbFacebook.isChecked &&
                etFacebook.text.toString()
                    .trim()
                    .isNotEmpty()

        if (!hasYouTube && !hasFacebook) {
            startBotOnly()
            return
        }

        val projectionManager =
            getSystemService(
                MediaProjectionManager::class.java
            )

        projectionLauncher.launch(
            projectionManager.createScreenCaptureIntent()
        )
    }

    private fun startBotOnly() {

        prefs.edit()
            .putBoolean(KEY_BOT_ENABLED, true)
            .apply()

        // This was the missing link in the old MainActivity.
        // Setting the preference alone did not command the already-running
        // AccessibilityService to start a new bot session.
        BotAccessibilityService.start(
            selectedDuration()
        )

        launchGame()

        setStatus(
            "Bot started • Streaming is off"
        )
    }

    private fun stopHumanSurfer() {

        prefs.edit()
            .putBoolean(KEY_BOT_ENABLED, false)
            .apply()

        // Stop the in-process Accessibility bot explicitly.
        BotAccessibilityService.stop()

        startService(
            Intent(
                this,
                StreamService::class.java
            ).setAction(
                StreamService.ACTION_STOP
            )
        )

        setStatus("Stopped")
    }

    private fun launchGame() {

        val game =
            packageManager.getLaunchIntentForPackage(
                GAME_PACKAGE
            )

        if (game == null) {

            BotAccessibilityService.stop()

            Toast.makeText(
                this,
                "Subway Surfers is not installed",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        game.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        startActivity(game)
    }

    private fun isAccessibilityEnabled(): Boolean {

        val enabled =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

        val expected =
            "$packageName/${BotAccessibilityService::class.java.name}"

        return enabled
            .split(':')
            .any {
                it.equals(
                    expected,
                    ignoreCase = true
                )
            }
    }

    private fun updateAccessibilityStatus() {

        if (
            ::tvStatus.isInitialized &&
            isAccessibilityEnabled()
        ) {
            tvStatus.text =
                "Ready • Accessibility enabled"
        }
    }

    private fun setStatus(value: String) {

        if (::tvStatus.isInitialized) {
            tvStatus.text = value
        }
    }
}
