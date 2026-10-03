package com.ganesh.humansurfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.multiple.MultiStream
import com.pedro.library.multiple.MultiType

class StreamService : Service() {
    companion object {
        const val ACTION_START = "com.ganesh.humansurfer.START_STREAM"
        const val ACTION_STOP = "com.ganesh.humansurfer.STOP_STREAM"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_YOUTUBE = "youtube"
        const val EXTRA_FACEBOOK = "facebook"
        const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "human_surfer_stream"
        private const val NOTIFICATION_ID = 4107
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var multiStream: MultiStream? = null
    private var durationStopRunnable: Runnable? = null
    private var rtmpStreamCount = 0

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopEverything()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode < 0 || resultData == null) {
            stopSelf()
            return
        }

        val youtube = intent.getStringExtra(EXTRA_YOUTUBE).orEmpty().trim()
        val facebook = intent.getStringExtra(EXTRA_FACEBOOK).orEmpty().trim()
        val duration = intent.getIntExtra(EXTRA_DURATION, 0)

        startAsMediaProjectionForeground()

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection unavailable")

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopEverything()
                }
            }, handler)

            val checkers = ArrayList<ConnectChecker>()
            val endpoints = ArrayList<String>()
            if (youtube.isNotBlank()) {
                endpoints += youtube
                checkers += LoggingChecker("YouTube")
            }
            if (facebook.isNotBlank()) {
                endpoints += facebook
                checkers += LoggingChecker("Facebook")
            }

            if (endpoints.isEmpty()) {
                stopEverything()
                return
            }

            val screenSource = ScreenSource(applicationContext, projection!!)
            val audioSource = InternalAudioSource(projection!!)

            val stream = MultiStream(
                applicationContext,
                checkers.toTypedArray(),
                null,
                null,
                null,
                screenSource,
                audioSource
            )
            multiStream = stream

            val preparedVideo = stream.prepareVideo(
                720,
                1280,
                3_000_000,
                30,
                2,
                0
            )
            val preparedAudio = stream.prepareAudio(
                44_100,
                true,
                128_000
            )

            if (!preparedVideo || !preparedAudio) {
                throw IllegalStateException("Encoder preparation failed")
            }

            rtmpStreamCount = endpoints.size
            endpoints.forEachIndexed { index, endpoint ->
                stream.startStream(MultiType.RTMP, index, endpoint)
            }

            if (duration > 0) {
                durationStopRunnable = Runnable { stopEverything() }
                handler.postDelayed(durationStopRunnable!!, duration * 60_000L)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            stopEverything()
        }
    }

    private fun startAsMediaProjectionForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Human Surfer streaming")
            .setContentText("Game screen is being streamed")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Human Surfer streaming",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun stopEverything() {
        durationStopRunnable?.let(handler::removeCallbacks)
        durationStopRunnable = null

        try {
            val stream = multiStream
            if (stream != null) {
                for (index in 0 until rtmpStreamCount) {
                    try { stream.stopStream(MultiType.RTMP, index) } catch (_: Throwable) { }
                }
            }
        } catch (_: Throwable) {
        }
        rtmpStreamCount = 0
        try {
            multiStream?.release()
        } catch (_: Throwable) {
        }
        multiStream = null

        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Throwable) {
        }
        stopEverything()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class LoggingChecker(private val platform: String) : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            println("HumanSurfer $platform connecting")
        }

        override fun onConnectionSuccess() {
            println("HumanSurfer $platform connected")
        }

        override fun onNewBitrate(bitrate: Long) {}

        override fun onConnectionFailed(reason: String) {
            println("HumanSurfer $platform failed: $reason")
        }

        override fun onDisconnect() {
            println("HumanSurfer $platform disconnected")
        }

        override fun onAuthError() {
            println("HumanSurfer $platform auth error")
        }

        override fun onAuthSuccess() {
            println("HumanSurfer $platform auth success")
        }
    }
}
