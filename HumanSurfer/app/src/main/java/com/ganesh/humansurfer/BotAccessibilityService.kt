package com.ganesh.humansurfer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Color
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_STOP = "com.ganesh.humansurfer.STOP_BOT"
        private const val GAME_PACKAGE = "com.kiloo.subwaysurf"
        private var instance: BotAccessibilityService? = null
        private var requestedDurationMinutes = 0
        private var requestedStartTime = 0L
        private var enabled = false

        fun start(durationMinutes: Int) {
            requestedDurationMinutes = durationMinutes
            requestedStartTime = SystemClock.elapsedRealtime()
            enabled = true
            instance?.beginLoop()
        }

        fun stop() {
            enabled = false
            instance?.stopLoop()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var loopActive = false
    private var lastScreenshotAt = 0L
    private var lastActionAt = 0L
    private var lastFrame: Bitmap? = null
    private var currentLane = 1
    private var missedFrames = 0
    private var gameForeground = false
    private val random = Random(System.currentTimeMillis())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val prefs = getSharedPreferences("human_surfer", MODE_PRIVATE)
        if (prefs.getBoolean("bot_enabled", false)) {
            requestedDurationMinutes = when (prefs.getInt("duration_minutes", 5)) {
                0 -> 1
                1 -> 5
                2 -> 10
                3 -> 30
                4 -> 60
                else -> 0
            }
            requestedStartTime = SystemClock.elapsedRealtime()
            enabled = true
            beginLoop()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        gameForeground = event.packageName?.toString() == GAME_PACKAGE
        if (gameForeground && enabled) {
            beginLoop()
        }
    }

    override fun onInterrupt() {
        stopLoop()
    }

    override fun onDestroy() {
        stopLoop()
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun beginLoop() {
        if (loopActive || !enabled) return
        loopActive = true
        captureNextFrame(120)
    }

    private fun stopLoop() {
        loopActive = false
        lastFrame?.recycle()
        lastFrame = null
        missedFrames = 0
    }

    private fun durationExpired(): Boolean {
        if (!enabled) return true
        if (requestedDurationMinutes <= 0) return false
        val elapsed = SystemClock.elapsedRealtime() - requestedStartTime
        return elapsed >= requestedDurationMinutes * 60_000L
    }

    private fun captureNextFrame(delayMs: Long) {
        if (!loopActive || durationExpired()) {
            enabled = false
            loopActive = false
            return
        }

        val now = SystemClock.elapsedRealtime()
        val wait = max(delayMs, 110L - (now - lastScreenshotAt))
        handler.postDelayed({ takeGameScreenshot() }, wait)
    }

    private fun takeGameScreenshot() {
        if (!loopActive || durationExpired()) {
            stopLoop()
            return
        }

        val now = SystemClock.elapsedRealtime()
        lastScreenshotAt = now

        if (!gameForeground) {
            captureNextFrame(500L)
            return
        }

        if (Build.VERSION.SDK_INT < 30) {
            stopLoop()
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer: HardwareBuffer = screenshot.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val bitmap = hardware?.copy(Config.ARGB_8888, false)
                    hardware?.recycle()
                    buffer.close()

                    if (bitmap == null) {
                        missedFrames++
                        captureNextFrame(180)
                        return
                    }

                    val old = lastFrame
                    lastFrame = bitmap
                    old?.recycle()

                    val decision = GameBot.decide(bitmap, old, currentLane, random)
                    if (decision != null && SystemClock.elapsedRealtime() - lastActionAt >= decision.minimumCooldownMs) {
                        performDecision(decision, bitmap.width, bitmap.height)
                    }

                    missedFrames = 0
                    captureNextFrame(decision?.nextCaptureDelayMs ?: 140L)
                }

                override fun onFailure(errorCode: Int) {
                    missedFrames++
                    captureNextFrame(if (missedFrames > 3) 300L else 180L)
                }
            }
        )
    }

    private fun performDecision(decision: GameBot.Decision, width: Int, height: Int) {
        val centerY = (height * 0.72f).toInt()
        val centerX = when (currentLane) {
            0 -> (width * 0.27f).toInt()
            2 -> (width * 0.73f).toInt()
            else -> (width * 0.50f).toInt()
        }

        val targetLaneX = when (decision.action) {
            GameBot.Action.LEFT -> (width * 0.27f).toInt()
            GameBot.Action.RIGHT -> (width * 0.73f).toInt()
            else -> centerX
        }

        when (decision.action) {
            GameBot.Action.LEFT, GameBot.Action.RIGHT -> {
                val path = Path().apply {
                    moveTo(centerX.toFloat(), centerY.toFloat())
                    quadTo(
                        ((centerX + targetLaneX) / 2f),
                        (centerY - height * 0.025f),
                        targetLaneX.toFloat(),
                        centerY.toFloat()
                    )
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, decision.gestureDurationMs))
                        .build(),
                    null,
                    null
                )
                currentLane = if (decision.action == GameBot.Action.LEFT) max(0, currentLane - 1) else min(2, currentLane + 1)
            }
            GameBot.Action.JUMP -> dispatchSwipe(centerX, centerY, centerX, (height * 0.42f).toInt(), decision.gestureDurationMs)
            GameBot.Action.ROLL -> dispatchSwipe(centerX, centerY, centerX, (height * 0.91f).toInt(), decision.gestureDurationMs)
            GameBot.Action.NONE -> return
        }
        lastActionAt = SystemClock.elapsedRealtime()
    }

    private fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(),
            null,
            null
        )
    }
}

private object GameBot {
    enum class Action { NONE, LEFT, RIGHT, JUMP, ROLL }

    data class Decision(
        val action: Action,
        val minimumCooldownMs: Long,
        val nextCaptureDelayMs: Long,
        val gestureDurationMs: Long
    )

    fun decide(bitmap: Bitmap, previous: Bitmap?, lane: Int, random: Random): Decision? {
        if (bitmap.width < 100 || bitmap.height < 100) return null

        val laneScores = FloatArray(3)
        val yTop = (bitmap.height * 0.38f).toInt()
        val yBottom = (bitmap.height * 0.82f).toInt()

        for (l in 0..2) {
            val x0 = (bitmap.width * (l / 3.0)).toInt()
            val x1 = (bitmap.width * ((l + 1) / 3.0)).toInt()
            laneScores[l] = obstacleLikelihood(bitmap, previous, x0, x1, yTop, yBottom)
        }

        val currentRisk = laneScores[lane]
        val leftRisk = if (lane > 0) laneScores[lane - 1] else 9f
        val rightRisk = if (lane < 2) laneScores[lane + 1] else 9f

        // Low risk: mostly continue. A small probability of a useful lane change makes
        // the run less mechanically repetitive, but the choice is still based on screen data.
        if (currentRisk < 0.46f) {
            if (random.nextFloat() < 0.055f) {
                val best = minOf(leftRisk, rightRisk)
                if (best + 0.10f < currentRisk || currentRisk > 0.32f) {
                    return if (leftRisk <= rightRisk) decision(Action.LEFT, random) else decision(Action.RIGHT, random)
                }
            }
            return Decision(Action.NONE, 180, 145 + random.nextLong(0, 70), 120)
        }

        val safeThreshold = 0.52f
        val chooseImperfectly = random.nextFloat() < 0.08f && currentRisk < 0.70f

        if (leftRisk < safeThreshold && rightRisk < safeThreshold) {
            if (chooseImperfectly) {
                return if (random.nextBoolean()) decision(Action.LEFT, random) else decision(Action.RIGHT, random)
            }
            return if (leftRisk <= rightRisk) decision(Action.LEFT, random) else decision(Action.RIGHT, random)
        }

        if (leftRisk < safeThreshold) return decision(Action.LEFT, random)
        if (rightRisk < safeThreshold) return decision(Action.RIGHT, random)

        // If every lane looks dangerous, a vertical action is preferable to blindly
        // changing lanes. The choice is intentionally state-driven, not a fixed sequence.
        val action = if (random.nextFloat() < 0.72f) Action.JUMP else Action.ROLL
        return decision(action, random)
    }

    private fun decision(action: Action, random: Random): Decision {
        val reaction = random.nextLong(145, 360)
        val cooldown = random.nextLong(230, 420)
        val gesture = random.nextLong(95, 165)
        return Decision(action, cooldown, reaction, gesture)
    }

    private fun obstacleLikelihood(
        bitmap: Bitmap,
        previous: Bitmap?,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int
    ): Float {
        val width = bitmap.width
        val height = bitmap.height
        val sx0 = max(0, x0)
        val sx1 = min(width - 1, x1)
        val sy0 = max(0, y0)
        val sy1 = min(height - 1, y1)
        if (sx1 <= sx0 || sy1 <= sy0) return 0f

        var edges = 0
        var strongEdges = 0
        var samples = 0
        var movement = 0
        var darkness = 0
        var previousSamples = 0

        val stepX = max(5, (sx1 - sx0) / 30)
        val stepY = max(6, (sy1 - sy0) / 34)

        var y = sy0 + stepY
        while (y < sy1 - stepY) {
            var x = sx0 + stepX
            while (x < sx1 - stepX) {
                val c = bitmap.getPixel(x, y)
                val right = bitmap.getPixel(x + stepX, y)
                val down = bitmap.getPixel(x, y + stepY)

                val lum = luminance(c)
                val dx = abs(lum - luminance(right))
                val dy = abs(lum - luminance(down))
                val gradient = dx + dy

                if (gradient > 42) edges++
                if (gradient > 78) strongEdges++
                if (lum < 58) darkness++
                samples++

                if (previous != null && x < previous.width && y < previous.height) {
                    val p = previous.getPixel(x, y)
                    if (abs(lum - luminance(p)) > 34) movement++
                    previousSamples++
                }
                x += stepX
            }
            y += stepY
        }

        if (samples == 0) return 0f
        val edgeScore = (edges.toFloat() / samples) * 1.55f
        val strongScore = (strongEdges.toFloat() / samples) * 0.90f
        val darkScore = (darkness.toFloat() / samples) * 0.28f
        val motionScore = if (previousSamples > 0) (movement.toFloat() / previousSamples) * 0.75f else 0f

        // Keep the result bounded and avoid treating a completely busy frame as certain danger.
        return (edgeScore + strongScore + darkScore + motionScore).coerceIn(0f, 1f)
    }

    private fun luminance(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    }
}
