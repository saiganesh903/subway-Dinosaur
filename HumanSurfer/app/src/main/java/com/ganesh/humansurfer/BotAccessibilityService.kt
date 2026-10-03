package com.ganesh.humansurfer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * HumanSurfer controller (v6).
 *
 * Control loop (one pass per screenshot):
 *
 *   OBSERVE   screenshot -> small working frame
 *   PERCEIVE  track the runner, ray-cast the three lanes for obstacles
 *   VERIFY    did the last lane / jump / roll gesture visibly happen?
 *   PREDICT   time-to-collision per lane
 *   DECIDE    GameBrain scores STAY / LEFT / RIGHT / JUMP
 *   ACT       dispatchGesture() (never trusted as proof of movement)
 *
 * Invariants:
 *  - currentLane is only ever written from a visual player estimate.
 *    Sending a swipe only sets pendingLaneTarget.
 *  - No gameplay gesture is sent unless the game is the foreground window,
 *    the screen looks like gameplay, the runner is located with confidence
 *    and no ad / popup is active.
 *  - No randomness chooses a direction or an action.
 *
 * It is still a heuristic vision controller (no trained model). All
 * geometry constants live in [Geo] so they can be tuned from real frames.
 */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_STOP = "com.ganesh.humansurfer.STOP_BOT"

        private const val TAG = "HumanSurfer"
        private const val GAME_PACKAGE = "com.kiloo.subwaysurf"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        private var instance: BotAccessibilityService? = null
        private var requestedDurationMinutes = 0
        private var requestedStartTime = 0L
        private var enabled = false

        fun start(durationMinutes: Int) {
            requestedDurationMinutes = durationMinutes
            requestedStartTime = SystemClock.elapsedRealtime()
            enabled = true
            instance?.resetSession()
            instance?.beginController()
        }

        fun stop() {
            enabled = false
            instance?.stopController()
        }
    }

    private enum class BotState {
        WAITING_FOR_GAME,
        STARTING,
        PLAYING,
        RECOVERING,
        STOPPED
    }

    private enum class ScreenMode {
        UNKNOWN,
        GAMEPLAY,
        PLAY_OR_RESULT,
        CONTINUE_DIALOG,
        ADVERTISEMENT
    }

    private enum class Action {
        NONE,
        LEFT,
        RIGHT,
        JUMP,
        ROLL,
        TAP
    }

    private data class Decision(
        val action: Action,
        val reason: String
    )

    private data class VisualRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val cells: Int
    ) {
        fun centerX(): Int = (left + right) / 2
        fun centerY(): Int = (top + bottom) / 2
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    /** Runner estimate. All coordinates are fractions of the screen (0..1). */
    private data class PlayerEstimate(
        val lane: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val confidence: Float
    )

    /** Down-scaled working copy of a screenshot. */
    private class SmallFrame(
        val w: Int,
        val h: Int,
        val px: IntArray
    )

    /** What the ray-cast found in one lane. */
    private class LaneScan {
        var obstacleZ: Float = Geo.NO_OBSTACLE   // inverse-depth of nearest obstacle base
        var kind: Int = Geo.KIND_NONE
        var beside: Boolean = false              // obstacle already at the runner's row
        var coins: Int = 0
        var evaluated: Boolean = false
    }

    /**
     * Image-space geometry. Depth is handled through z = 1 / (y - VANISH_Y):
     * a static obstacle approaches at constant dz/dt, so time-to-collision is
     * (z_obstacle - Z_NEAR) / speed regardless of how far away it is.
     */
    private object Geo {
        const val SMALL_W = 240
        const val STEPS = 40

        const val VANISH_Y = 0.30f      // horizon row (fraction of height)
        const val PLAYER_Y = 0.80f      // runner's feet row
        const val FAR_OFFSET = 0.14f    // farthest scanned row = VANISH_Y + this

        val Z_NEAR: Float = 1f / (PLAYER_Y - VANISH_Y)
        val Z_FAR: Float = 1f / FAR_OFFSET

        const val RAY_HALF_WIDTH = 0.05f   // fraction of width at the runner's row
        const val RAY_HALF_HEIGHT = 0.010f

        const val NO_OBSTACLE = 1.0e9f
        const val KIND_NONE = 0
        const val KIND_LOW = 1
        const val KIND_TALL = 2

        const val ANOMALY_DIST = 46f
        const val DEFAULT_SPEED = 1.4f
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- loop / lifecycle state
    private var controllerActive = false
    private var state = BotState.WAITING_FOR_GAME
    private var frameScheduled = false

    private var lastScreenshotAt = 0L
    private var lastFrameAt = 0L
    private var framePeriodMs = 350f
    private var lastActionAt = 0L
    private var lastStartTapAt = 0L
    private var lastPopupActionAt = 0L
    private var lastPopupScanAt = 0L
    private var lastLaneChangeAt = 0L
    private var startGraceUntil = 0L
    private var adActiveUntil = 0L

    private var lastFrame: Bitmap? = null
    private var previousFrame: Bitmap? = null

    private var missedFrames = 0
    private var gameForeground = false
    private var lastForegroundCheckAt = 0L
    private var lastForegroundPkg = ""
    private var foregroundLostAt = 0L
    private var externalBackCount = 0
    private var lastExternalBackAt = 0L
    private var consecutiveGameplayFrames = 0
    private var consecutiveStaticFrames = 0
    private var lowMotionScans = 0
    private var deathRecoveryCount = 0

    // ---- runner tracking
    private var currentLane = 1
    private var playerCenterX = 0f          // fraction of width, 0 = unknown
    private var playerCenterY = 0f          // fraction of height
    private var playerConfidence = 0f
    private var playerLocked = false
    private var playerLostFrames = 0
    private var playerStableFrames = 0
    private val laneXN = floatArrayOf(0.27f, 0.50f, 0.73f)

    private var tplValid = false
    private var tplW = 0.12f
    private var tplH = 0.15f
    private var tplR = 0f
    private var tplG = 0f
    private var tplB = 0f
    private var tplVotes = 0
    private var groundY = 0f

    // ---- gesture verification
    private var pendingLaneTarget: Int? = null
    private var pendingLaneOrigin = 1
    private var pendingLaneRequestedAt = 0L
    private var pendingLaneAttempts = 0
    private var pendingLaneFrames = 0
    private var gestureBusy = false
    private var swipeBoost = 1.0f
    private var laneMoveOk = 0
    private var laneMoveFail = 0

    private var pendingVertical: Action? = null
    private var verticalRequestedAt = 0L
    private var verticalSawChange = false
    private var airborne = false
    private var airborneSince = 0L
    private var vertBoost = 1.0f
    private var verticalFailStreak = 0

    // ---- obstacle perception
    private val refR = FloatArray(3 * Geo.STEPS)
    private val refG = FloatArray(3 * Geo.STEPS)
    private val refB = FloatArray(3 * Geo.STEPS)
    private val refSet = BooleanArray(3 * Geo.STEPS)
    private var framesSinceReset = 0
    private var sceneChangeFrames = 0
    private val prevObstZ = FloatArray(3) { Geo.NO_OBSTACLE }
    private val prevObstAt = LongArray(3)
    private var speedEst = Geo.DEFAULT_SPEED
    private var lastSmall: SmallFrame? = null
    private var screenW = 0
    private var screenH = 0
    private var lastVerticalAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        val prefs = getSharedPreferences(
            "human_surfer",
            MODE_PRIVATE
        )

        if (prefs.getBoolean("bot_enabled", false)) {
            requestedDurationMinutes = when (
                prefs.getInt("duration_minutes", 5)
            ) {
                0 -> 1
                1 -> 5
                2 -> 10
                3 -> 30
                4 -> 60
                else -> 0
            }

            requestedStartTime = SystemClock.elapsedRealtime()
            enabled = true
            resetSession()
            beginController()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        when {
            packageName == GAME_PACKAGE -> {
                if (!gameForeground) {
                    gameForeground = true
                    foregroundLostAt = 0L
                    externalBackCount = 0
                }
                if (enabled) beginController()
            }

            packageName == PLAY_STORE_PACKAGE -> {
                // Purchase UI opened by the game. Never touch its controls:
                // stop gameplay input; the loop presses BACK to leave it.
                gameForeground = false
                lastForegroundPkg = packageName
                suspendGameplay("google play opened")
                if (foregroundLostAt == 0L) {
                    foregroundLostAt = SystemClock.elapsedRealtime()
                }
                lastForegroundCheckAt = 0L
                if (enabled) beginController()
            }

            else -> {
                // Incidental events (system UI, keyboard, toasts) must not
                // kill the controller. Ask the polling check to re-verify.
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    lastForegroundCheckAt = 0L
                }
            }
        }
    }

    override fun onInterrupt() {
        stopController()
    }

    override fun onDestroy() {
        stopController()
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Polls the real foreground window. Events are only hints; this is the
     * authority for "is Subway Surfers on screen?".
     */
    private fun refreshForeground(now: Long) {
        if (now - lastForegroundCheckAt < 400L) return
        lastForegroundCheckAt = now

        val pkg: String? = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }

        // Unknown (null) keeps the previous belief; some game windows expose
        // no root node for a moment.
        if (pkg == null) return

        lastForegroundPkg = pkg

        if (pkg == GAME_PACKAGE) {
            if (!gameForeground) {
                gameForeground = true
            }
            foregroundLostAt = 0L
            externalBackCount = 0
        } else if (gameForeground) {
            gameForeground = false
            suspendGameplay("foreground is $pkg")
            if (foregroundLostAt == 0L) foregroundLostAt = now
        }
    }

    /**
     * Called while the game is not the foreground window. Sends no gameplay
     * input. Leaves purchase / ad click-through screens with BACK only, and
     * gives up completely if the game does not return.
     */
    private fun handleExternalWindow(now: Long) {
        if (foregroundLostAt == 0L) foregroundLostAt = now

        val pkg = lastForegroundPkg
        val isOwnApp = pkg == packageName
        val isSystemUi = pkg == "com.android.systemui"
        val isPlay = pkg == PLAY_STORE_PACKAGE

        if (now - foregroundLostAt > 45_000L) {
            finishBot("Subway Surfers did not return to the foreground")
            return
        }

        if (isOwnApp || isSystemUi || pkg.isEmpty()) return

        // BACK inside the real game opens its pause / exit dialog, so only
        // press it when the game has been gone for a while (or Play opened).
        if (!isPlay && now - foregroundLostAt < 2500L) return

        val limit = if (isPlay) 8 else 4
        if (externalBackCount >= limit) {
            finishBot("could not leave external screen: $pkg")
            return
        }

        if (now - lastExternalBackAt >= 900L) {
            lastExternalBackAt = now
            externalBackCount++
            Log.d(TAG, "BACK from $pkg (#$externalBackCount)")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun suspendGameplay(reason: String) {
        Log.d(TAG, "suspend gameplay: $reason")
        clearPendingMotion()
        gestureBusy = false
        state = BotState.WAITING_FOR_GAME
        consecutiveGameplayFrames = 0
        lowMotionScans = 0
    }

    private fun clearPendingMotion() {
        pendingLaneTarget = null
        pendingLaneAttempts = 0
        pendingLaneFrames = 0
        pendingVertical = null
        verticalSawChange = false
        airborne = false
    }

    /** Forget everything perceived about the scene and the runner. */
    private fun resetPerception() {
        clearPendingMotion()
        gestureBusy = false
        currentLane = 1
        playerCenterX = 0f
        playerCenterY = 0f
        playerConfidence = 0f
        playerLocked = false
        playerLostFrames = 0
        playerStableFrames = 0
        tplValid = false
        tplVotes = 0
        groundY = 0f
        laneXN[0] = 0.27f
        laneXN[1] = 0.50f
        laneXN[2] = 0.73f
        java.util.Arrays.fill(refSet, false)
        framesSinceReset = 0
        sceneChangeFrames = 0
        for (i in 0 until 3) {
            prevObstZ[i] = Geo.NO_OBSTACLE
            prevObstAt[i] = 0L
        }
        speedEst = Geo.DEFAULT_SPEED
        lastSmall = null
    }

    private fun resetSession() {
        state = BotState.WAITING_FOR_GAME
        resetPerception()
        missedFrames = 0
        consecutiveGameplayFrames = 0
        consecutiveStaticFrames = 0
        lowMotionScans = 0
        deathRecoveryCount = 0
        swipeBoost = 1.0f
        vertBoost = 1.0f
        laneMoveOk = 0
        laneMoveFail = 0
        verticalFailStreak = 0
        externalBackCount = 0
        gameForeground = false
        foregroundLostAt = SystemClock.elapsedRealtime()
        lastForegroundCheckAt = 0L

        lastScreenshotAt = 0L
        lastFrameAt = 0L
        lastActionAt = 0L
        lastStartTapAt = 0L
        lastPopupActionAt = 0L
        lastPopupScanAt = 0L
        lastLaneChangeAt = 0L
        startGraceUntil = 0L
        adActiveUntil = 0L

        previousFrame?.recycle()
        previousFrame = null

        lastFrame?.recycle()
        lastFrame = null
    }

    private fun beginController() {
        if (!enabled || controllerActive) return

        controllerActive = true
        captureNextFrame(180L)
    }

    private fun stopController() {
        controllerActive = false
        gameForeground = false
        state = BotState.STOPPED
        gestureBusy = false
        clearPendingMotion()

        previousFrame?.recycle()
        previousFrame = null

        lastFrame?.recycle()
        lastFrame = null
        lastSmall = null

        missedFrames = 0
        handler.removeCallbacksAndMessages(null)
    }

    /** Ends the session for good (duration reached, unrecoverable screen). */
    private fun finishBot(reason: String) {
        Log.d(TAG, "bot finished: $reason")
        enabled = false
        try {
            getSharedPreferences("human_surfer", MODE_PRIVATE)
                .edit()
                .putBoolean("bot_enabled", false)
                .apply()
        } catch (_: Exception) {
        }
        stopController()
    }

    private fun durationExpired(): Boolean {
        if (!enabled) return true

        if (requestedDurationMinutes <= 0) {
            return false
        }

        val elapsed =
            SystemClock.elapsedRealtime() - requestedStartTime

        return elapsed >= requestedDurationMinutes * 60_000L
    }

    private fun captureNextFrame(delayMs: Long) {
        frameScheduled = true

        if (!controllerActive || !enabled) {
            stopController()
            return
        }

        if (durationExpired()) {
            finishBot("duration reached")
            return
        }

        val now = SystemClock.elapsedRealtime()

        // Android rate-limits takeScreenshot(); a too-early request simply
        // fails with ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT and is retried.
        val minimumFrameGap = 60L

        val wait = max(
            delayMs,
            minimumFrameGap - (now - lastScreenshotAt)
        )

        handler.postDelayed(
            { takeGameScreenshot() },
            wait
        )
    }

    private fun takeGameScreenshot() {
        if (!controllerActive || !enabled) {
            stopController()
            return
        }

        if (durationExpired()) {
            finishBot("duration reached")
            return
        }

        val now = SystemClock.elapsedRealtime()
        refreshForeground(now)

        if (!gameForeground) {
            state = BotState.WAITING_FOR_GAME
            handleExternalWindow(now)
            if (controllerActive) captureNextFrame(350L)
            return
        }

        if (Build.VERSION.SDK_INT < 30) {
            stopController()
            return
        }

        lastScreenshotAt = now

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {

                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer: HardwareBuffer =
                        screenshot.hardwareBuffer

                    val hardware =
                        Bitmap.wrapHardwareBuffer(
                            buffer,
                            screenshot.colorSpace
                        )

                    val bitmap =
                        hardware?.copy(
                            Config.ARGB_8888,
                            false
                        )

                    hardware?.recycle()
                    buffer.close()

                    if (bitmap == null) {
                        missedFrames++

                        captureNextFrame(
                            if (missedFrames > 3) 180L else 100L
                        )

                        return
                    }

                    val t = SystemClock.elapsedRealtime()

                    if (lastFrameAt > 0L) {
                        val dt = (t - lastFrameAt).toFloat()
                        if (dt in 40f..1500f) {
                            framePeriodMs = framePeriodMs * 0.8f + dt * 0.2f
                        }
                    }
                    lastFrameAt = t

                    previousFrame?.recycle()
                    previousFrame = lastFrame
                    lastFrame = bitmap
                    missedFrames = 0

                    processFrameSafely(bitmap, t)
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        // Asked too soon. Not a real failure.
                        captureNextFrame(40L)
                        return
                    }

                    missedFrames++

                    captureNextFrame(
                        if (missedFrames > 3) 250L else 120L
                    )
                }
            }
        )
    }

    private fun processFrameSafely(bitmap: Bitmap, now: Long) {
        frameScheduled = false

        try {
            processFrame(bitmap, now)
        } catch (e: Exception) {
            Log.e(TAG, "frame processing failed", e)
        }

        if (!frameScheduled && controllerActive) {
            captureNextFrame(150L)
        }
    }

    private fun processFrame(bitmap: Bitmap, now: Long) {
        if (!gameForeground) {
            state = BotState.WAITING_FOR_GAME
            captureNextFrame(350L)
            return
        }

        screenW = bitmap.width
        screenH = bitmap.height

        val playing = state == BotState.PLAYING

        /*
         * Screen understanding (popups, ads, menus) walks the accessibility
         * tree and samples the whole bitmap. While the runner is alive that
         * is far too slow to do every frame, so during gameplay it runs
         * periodically; outside gameplay it runs every frame.
         */
        val scanDue = !playing || now - lastPopupScanAt >= 600L

        if (scanDue) {
            lastPopupScanAt = now

            val mode = detectScreenMode(bitmap, previousFrame)

            // Ads have priority over gameplay: never swipe through an ad.
            if (handleAdvertisementControls(bitmap)) {
                captureNextFrame(180L)
                return
            }

            // Visual recovery (Continue?, red X, PLAY) before any gameplay.
            if (handleVisualScreen(mode, bitmap)) {
                captureNextFrame(180L)
                return
            }

            if (handleAccessibilityControls()) {
                state = BotState.RECOVERING
                captureNextFrame(180L)
                return
            }

            if (mode == ScreenMode.PLAY_OR_RESULT) {
                captureNextFrame(150L)
                return
            }

            val gameplayScore = detectGameplayScore(bitmap, previousFrame)
            val moving = gameplayScore > 0.16f

            if (playing) {
                // A frozen picture while "playing" means a popup, a pause
                // or a death screen. Hand control back to recovery.
                if (moving) lowMotionScans = 0 else lowMotionScans++

                if (lowMotionScans >= 3) {
                    Log.d(TAG, "picture frozen -> recovery")
                    clearPendingMotion()
                    gestureBusy = false
                    state = BotState.RECOVERING
                    consecutiveGameplayFrames = 0
                    lowMotionScans = 0
                }
            } else {
                if (mode == ScreenMode.GAMEPLAY ||
                    state == BotState.STARTING ||
                    moving
                ) {
                    consecutiveGameplayFrames++
                    consecutiveStaticFrames = 0
                } else {
                    consecutiveStaticFrames++
                    consecutiveGameplayFrames = 0
                }

                /*
                 * RECOVERING must be able to return to PLAYING. Without this
                 * the bot stayed idle forever after the first closed popup,
                 * continued run, or dismissed ad.
                 */
                val needed = if (state == BotState.RECOVERING) 3 else 2
                val calm =
                    now - lastPopupActionAt > 1200L && now >= adActiveUntil

                if (state == BotState.STARTING ||
                    (consecutiveGameplayFrames >= needed && calm)
                ) {
                    state = BotState.PLAYING
                    lowMotionScans = 0
                    Log.d(TAG, "state -> PLAYING")
                }
            }
        }

        if (now < adActiveUntil) {
            // An advertisement is on screen and could not be closed.
            if (state == BotState.PLAYING) {
                clearPendingMotion()
                state = BotState.RECOVERING
            }
            captureNextFrame(200L)
            return
        }

        if (state != BotState.PLAYING) {
            captureNextFrame(120L)
            return
        }

        if (now < startGraceUntil) {
            captureNextFrame(90L)
            return
        }

        // ---- OBSERVE -> PERCEIVE
        val small = toSmall(bitmap)
        val estimate = trackPlayer(small, now)

        // ---- VERIFY what the last gesture actually did
        verifyPendingLaneChange(estimate, now)
        verifyVertical(estimate, now)

        framesSinceReset++

        // Without a confident, locked runner position there is no valid
        // lane state. Do not invent one and do not swipe.
        if (!playerLocked || playerConfidence < 0.40f) {
            lastSmall = small
            captureNextFrame(40L)
            return
        }

        val scans = perceive(small, now)
        lastSmall = small

        if (scans == null) {
            captureNextFrame(40L)
            return
        }

        // ---- PREDICT + DECIDE
        val decision = GameBrain.decide(
            scans = scans,
            lane = currentLane,
            speed = speedEst,
            framePeriodSec = framePeriodMs / 1000f,
            now = now,
            lastLaneChangeAt = lastLaneChangeAt,
            laneBusy = pendingLaneTarget != null || gestureBusy,
            airborne = airborne || pendingVertical != null
        )

        // ---- ACT
        if (decision.action != Action.NONE) {
            performDecision(decision, bitmap.width, bitmap.height, now)
        }

        captureNextFrame(40L)
    }

    /*
     * ------------------------------------------------------------
     * PERCEPTION
     * ------------------------------------------------------------
     */

    private fun toSmall(bitmap: Bitmap): SmallFrame {
        val sw = Geo.SMALL_W
        val sh = max(
            120,
            (bitmap.height.toLong() * sw / max(1, bitmap.width)).toInt()
        )

        val scaled = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val px = IntArray(sw * sh)
        scaled.getPixels(px, 0, sw, 0, 0, sw, sh)

        if (scaled !== bitmap) scaled.recycle()

        return SmallFrame(sw, sh, px)
    }

    private fun nearestLane(x: Float): Int {
        var best = 1
        var bestD = Float.MAX_VALUE
        for (l in 0..2) {
            val d = abs(x - laneXN[l])
            if (d < bestD) {
                bestD = d
                best = l
            }
        }
        return best
    }

    private fun laneSpacing(): Float = (laneXN[2] - laneXN[0]) / 2f

    /**
     * Finds the runner. Candidates are compact saturated blobs in the lower
     * play area. They are scored on size (against a template learned from
     * the runner itself), distance from the last known position, fill, colour
     * similarity with the template and vertical position. Blobs much larger
     * than the runner (trains) are rejected, which a pure "biggest colourful
     * blob" detector gets wrong exactly when a train is next to the runner.
     */
    private fun trackPlayer(f: SmallFrame, now: Long): PlayerEstimate? {
        val w = f.w
        val h = f.h
        val x0 = (w * 0.08f).toInt()
        val x1 = (w * 0.92f).toInt()
        val y0 = (h * 0.48f).toInt()
        val y1 = (h * 0.96f).toInt()
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 20 || ch < 20) return null

        val active = BooleanArray(cw * ch)

        for (yy in 0 until ch) {
            val row = (y0 + yy) * w + x0
            for (xx in 0 until cw) {
                val c = f.px[row + xx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val mx = max(r, max(g, b))
                if (mx < 40) continue
                val mn = min(r, min(g, b))
                val sat = (mx - mn).toFloat() / mx
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                active[yy * cw + xx] = sat > 0.20f && lum in 25..240
            }
        }

        // Close 1-pixel gaps so a runner split by dark clothing stays whole.
        val mask = BooleanArray(active.size)
        for (yy in 0 until ch) {
            for (xx in 0 until cw) {
                val i = yy * cw + xx
                mask[i] = active[i] ||
                    (xx > 0 && active[i - 1]) ||
                    (xx < cw - 1 && active[i + 1]) ||
                    (yy > 0 && active[i - cw]) ||
                    (yy < ch - 1 && active[i + cw])
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)

        var bestScore = -1f
        var bestX = 0f
        var bestY = 0f
        var bestW = 0f
        var bestH = 0f
        var bestR = 0f
        var bestG = 0f
        var bestB = 0f

        val transit = pendingLaneTarget != null

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var minX = cw
            var maxX = -1
            var minY = ch
            var maxY = -1
            var count = 0
            var sr = 0L
            var sg = 0L
            var sb = 0L

            while (head < tail) {
                val cur = queue[head++]
                val cx = cur % cw
                val cy = cur / cw
                count++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                val c = f.px[(y0 + cy) * w + x0 + cx]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF

                if (cx > 0) {
                    val n = cur - 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cx < cw - 1) {
                    val n = cur + 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy > 0) {
                    val n = cur - cw
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy < ch - 1) {
                    val n = cur + cw
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }

            if (count < 30) continue

            val bw = (maxX - minX + 1).toFloat() / w
            val bh = (maxY - minY + 1).toFloat() / h
            if (bw < 0.035f || bw > 0.32f || bh < 0.045f || bh > 0.30f) continue

            // Anything far larger than the runner is a train / wall.
            if (tplValid && (bw > tplW * 2.0f || bh > tplH * 2.0f)) continue

            val cxN = (x0 + (minX + maxX + 1) / 2f) / w
            val cyN = (y0 + (minY + maxY + 1) / 2f) / h
            val fill = count.toFloat() /
                ((maxX - minX + 1) * (maxY - minY + 1)).toFloat()

            val mr = sr.toFloat() / count
            val mg = sg.toFloat() / count
            val mb = sb.toFloat() / count

            val sizeScore: Float = if (tplValid) {
                val dw = abs(bw - tplW) / tplW
                val dh = abs(bh - tplH) / tplH
                1f - min(1f, (dw + dh) / 1.4f)
            } else {
                val dw = abs(bw - 0.12f) / 0.12f
                val dh = abs(bh - 0.15f) / 0.15f
                1f - min(1f, (dw + dh) / 1.8f)
            }

            val prox: Float = if (playerLocked && playerCenterX > 0f) {
                val sx = if (transit) 0.30f else 0.16f
                val dx = (cxN - playerCenterX) / sx
                val dy = (cyN - playerCenterY) / 0.12f
                1f / (1f + dx * dx + dy * dy)
            } else {
                val dx = (cxN - 0.5f) / 0.30f
                val dy = (cyN - 0.78f) / 0.15f
                1f / (1f + dx * dx + dy * dy)
            }

            val colorScore: Float = if (tplValid) {
                val dr = mr - tplR
                val dg = mg - tplG
                val db = mb - tplB
                1f - min(1f, sqrt(dr * dr + dg * dg + db * db) / 110f)
            } else {
                0.5f
            }

            val lowerBias = 1f - min(1f, abs(cyN - 0.78f) / 0.30f)

            val score =
                0.28f * sizeScore +
                    0.30f * prox +
                    0.12f * fill +
                    0.18f * colorScore +
                    0.12f * lowerBias

            if (score > bestScore) {
                bestScore = score
                bestX = cxN
                bestY = cyN
                bestW = bw
                bestH = bh
                bestR = mr
                bestG = mg
                bestB = mb
            }
        }

        val conf = ((bestScore - 0.38f) / 0.50f).coerceIn(0f, 1f)

        if (bestScore < 0f || conf < 0.30f) {
            playerLostFrames++
            playerConfidence *= 0.7f
            if (playerLostFrames >= 6) {
                playerLocked = false
                playerStableFrames = 0
            }
            return null
        }

        val consistent = playerCenterX <= 0f ||
            abs(bestX - playerCenterX) < (if (transit) 0.34f else 0.16f)

        if (consistent) playerStableFrames++ else playerStableFrames = 1

        playerLostFrames = 0
        playerCenterX = bestX
        playerCenterY = bestY
        playerConfidence = conf

        if (!playerLocked && playerStableFrames >= 2 && conf >= 0.40f) {
            playerLocked = true
        }

        val lane = nearestLane(bestX)

        if (pendingLaneTarget == null && playerLocked) {
            currentLane = lane
        }

        val settled =
            pendingLaneTarget == null &&
                pendingVertical == null &&
                !airborne &&
                now - lastVerticalAt > 900L

        if (conf >= 0.55f && settled) {
            if (!tplValid) {
                if (abs(bestX - 0.5f) < 0.12f && bestY in 0.60f..0.92f) {
                    tplVotes++
                    if (tplVotes >= 3) {
                        tplValid = true
                        tplW = bestW
                        tplH = bestH
                        tplR = bestR
                        tplG = bestG
                        tplB = bestB
                    }
                }
            } else {
                tplW = tplW * 0.92f + bestW * 0.08f
                tplH = tplH * 0.92f + bestH * 0.08f
                tplR = tplR * 0.92f + bestR * 0.08f
                tplG = tplG * 0.92f + bestG * 0.08f
                tplB = tplB * 0.92f + bestB * 0.08f
            }

            groundY =
                if (groundY <= 0f) bestY else groundY * 0.9f + bestY * 0.1f

            // Learn where each lane really is at the runner's row.
            if (playerStableFrames >= 3 && conf >= 0.60f) {
                val defaults = floatArrayOf(0.27f, 0.50f, 0.73f)
                val learned = laneXN[lane] * 0.92f + bestX * 0.08f
                laneXN[lane] = learned.coerceIn(
                    defaults[lane] - 0.07f,
                    defaults[lane] + 0.07f
                )
            }
        }

        return PlayerEstimate(lane, bestX, bestY, bestW, bestH, conf)
    }

    /**
     * A swipe is only a request. This checks the following frames and only
     * then accepts, retries once, or gives up. currentLane is never set here
     * from the request, only from what the runner visibly did.
     */
    private fun verifyPendingLaneChange(est: PlayerEstimate?, now: Long) {
        val target = pendingLaneTarget ?: return
        val elapsed = now - pendingLaneRequestedAt
        val spacing = laneSpacing()

        if (est != null && est.confidence >= 0.40f) {
            pendingLaneFrames++

            val offTarget = abs(est.x - laneXN[target])
            val offOrigin = abs(est.x - laneXN[pendingLaneOrigin])

            if (est.lane == target && offTarget < spacing * 0.45f) {
                currentLane = target
                pendingLaneTarget = null
                pendingLaneAttempts = 0
                pendingLaneFrames = 0
                lastLaneChangeAt = now
                laneMoveOk++
                swipeBoost = max(1.0f, swipeBoost - 0.05f)
                Log.d(TAG, "lane change CONFIRMED -> $target in ${elapsed}ms")
                return
            }

            val stillAtOrigin =
                est.lane == pendingLaneOrigin && offOrigin < spacing * 0.30f

            if (stillAtOrigin && elapsed >= 420L && pendingLaneFrames >= 2) {
                handleLaneMoveFailure(est, now, true)
                return
            }
        }

        if (elapsed >= 1000L) {
            handleLaneMoveFailure(est, now, false)
        }
    }

    private fun handleLaneMoveFailure(
        est: PlayerEstimate?,
        now: Long,
        seenAtOrigin: Boolean
    ) {
        val target = pendingLaneTarget ?: return

        laneMoveFail++
        swipeBoost = min(1.35f, swipeBoost + 0.10f)

        // Retry exactly once, and only when the runner was seen in the
        // original lane (an unseen runner might already have moved, and a
        // second swipe could overshoot).
        if (seenAtOrigin &&
            pendingLaneAttempts < 1 &&
            gameForeground &&
            controllerActive &&
            !gestureBusy &&
            screenW > 0
        ) {
            pendingLaneAttempts++
            pendingLaneRequestedAt = now
            pendingLaneFrames = 0
            gestureBusy = true
            val dir = if (target > pendingLaneOrigin) 1 else -1
            Log.w(TAG, "lane change NOT visible, retrying once (dir=$dir)")
            dispatchHorizontalSwipe(dir, screenW, screenH)
            return
        }

        Log.w(TAG, "lane change FAILED, keeping visual lane")
        pendingLaneTarget = null
        pendingLaneAttempts = 0
        pendingLaneFrames = 0
        lastLaneChangeAt = now
        if (est != null && est.confidence >= 0.40f) {
            currentLane = est.lane
        }
    }

    private fun verifyVertical(est: PlayerEstimate?, now: Long) {
        val v = pendingVertical

        if (v == null) {
            if (airborne) {
                val landed =
                    est != null &&
                        groundY > 0f &&
                        est.y >= groundY - 0.015f &&
                        now - airborneSince > 250L

                if (landed || now - airborneSince > 1100L) {
                    airborne = false
                }
            }
            return
        }

        val elapsed = now - verticalRequestedAt

        if (est != null && est.confidence >= 0.40f && groundY > 0f) {
            if (v == Action.JUMP && est.y < groundY - 0.030f) {
                Log.d(TAG, "jump CONFIRMED")
                pendingVertical = null
                airborneSince = now
                verticalFailStreak = 0
                vertBoost = max(1.0f, vertBoost - 0.05f)
                return
            }

            if (v == Action.ROLL &&
                (est.h < tplH * 0.82f || est.y > groundY + 0.015f)
            ) {
                Log.d(TAG, "roll CONFIRMED")
                pendingVertical = null
                verticalFailStreak = 0
                vertBoost = max(1.0f, vertBoost - 0.05f)
                return
            }
        }

        if (elapsed >= 700L) {
            verticalFailStreak++
            vertBoost = min(1.4f, vertBoost + 0.10f)
            Log.w(TAG, "$v not visible after ${elapsed}ms (fails=$verticalFailStreak)")
            pendingVertical = null
            if (v == Action.JUMP) airborne = false
        }
    }

    private fun zAt(i: Int): Float =
        Geo.Z_NEAR + (Geo.Z_FAR - Geo.Z_NEAR) * i / (Geo.STEPS - 1)

    /**
     * Ray-casts the three lanes. For every lane a column of small patches is
     * sampled along the lane centre line from the runner towards the horizon,
     * evenly spaced in inverse depth. Each patch is compared with a learned
     * "empty track" colour for that lane and distance. Patches that differ
     * are obstacle candidates; short gold patches are coin lines.
     *
     * Returns null when the scene cannot be trusted (reference not ready, or
     * the whole picture changed, e.g. tunnel / new world).
     */
    private fun perceive(f: SmallFrame, now: Long): Array<LaneScan>? {
        val n = Geo.STEPS
        val w = f.w
        val h = f.h

        val pr = FloatArray(3 * n)
        val pg = FloatArray(3 * n)
        val pb = FloatArray(3 * n)
        val gold = FloatArray(3 * n)
        val usable = BooleanArray(3 * n)

        // Rows covered by the runner's own body cannot be read in his lane.
        val bodyTop = playerCenterY - tplH * 0.5f - 0.02f
        val pendingTarget = pendingLaneTarget

        for (lane in 0..2) {
            val occluded = lane == currentLane || lane == pendingTarget

            for (i in 0 until n) {
                val z = zAt(i)
                val y = Geo.VANISH_Y + 1f / z
                val s = Geo.Z_NEAR / z

                if (occluded && y >= bodyTop) continue

                val cx = 0.5f + (laneXN[lane] - 0.5f) * s
                val hw = Geo.RAY_HALF_WIDTH * s
                val hh = Geo.RAY_HALF_HEIGHT * s

                val xa = max(0, ((cx - hw) * w).toInt())
                val xb = min(w - 1, max(xa + 1, ((cx + hw) * w).toInt()))
                val ya = max(0, ((y - hh) * h).toInt())
                val yb = min(h - 1, max(ya + 1, ((y + hh) * h).toInt()))

                var sr = 0
                var sg = 0
                var sb = 0
                var gc = 0
                var cnt = 0

                for (yy in ya..yb) {
                    val row = yy * w
                    for (xx in xa..xb) {
                        val c = f.px[row + xx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        sr += r
                        sg += g
                        sb += b
                        if (r > 170 && g > 110 && b < 90 && r - b > 90) gc++
                        cnt++
                    }
                }

                if (cnt == 0) continue

                val k = lane * n + i
                pr[k] = sr.toFloat() / cnt
                pg[k] = sg.toFloat() / cnt
                pb[k] = sb.toFloat() / cnt
                gold[k] = gc.toFloat() / cnt
                usable[k] = true
            }
        }

        // Seed the empty-track reference where at least two lanes agree.
        if (framesSinceReset >= 2) {
            for (i in 0 until n) {
                var bestA = -1
                var bestB = -1
                var bestD = Float.MAX_VALUE

                for (a in 0..1) {
                    for (b in (a + 1)..2) {
                        val ka = a * n + i
                        val kb = b * n + i
                        if (!usable[ka] || !usable[kb]) continue
                        val d = colorDist(
                            pr[ka], pg[ka], pb[ka],
                            pr[kb], pg[kb], pb[kb]
                        )
                        if (d < bestD) {
                            bestD = d
                            bestA = a
                            bestB = b
                        }
                    }
                }

                if (bestA < 0 || bestD >= 28f) continue

                val ka = bestA * n + i
                val kb = bestB * n + i
                val mr = (pr[ka] + pr[kb]) / 2f
                val mg = (pg[ka] + pg[kb]) / 2f
                val mb = (pb[ka] + pb[kb]) / 2f

                for (lane in 0..2) {
                    val k = lane * n + i
                    if (!usable[k] || refSet[k]) continue
                    val inPair = lane == bestA || lane == bestB
                    refR[k] = if (inPair) pr[k] else mr
                    refG[k] = if (inPair) pg[k] else mg
                    refB[k] = if (inPair) pb[k] else mb
                    refSet[k] = true
                }
            }
        }

        val anomalous = BooleanArray(3 * n)
        var evaluatedCells = 0
        var anomalousCells = 0

        for (k in 0 until 3 * n) {
            if (!usable[k] || !refSet[k]) continue
            val d = colorDist(pr[k], pg[k], pb[k], refR[k], refG[k], refB[k])
            evaluatedCells++
            if (d > Geo.ANOMALY_DIST) {
                anomalous[k] = true
                anomalousCells++
            } else if (d < 24f) {
                // Empty track: let the reference follow slow lighting drift.
                refR[k] = refR[k] * 0.94f + pr[k] * 0.06f
                refG[k] = refG[k] * 0.94f + pg[k] * 0.06f
                refB[k] = refB[k] * 0.94f + pb[k] * 0.06f
            }
        }

        if (evaluatedCells < 24) return null

        // Everything changed at once: new scene, not 40 obstacles.
        if (anomalousCells.toFloat() / evaluatedCells > 0.70f) {
            sceneChangeFrames++
            if (sceneChangeFrames >= 3) {
                java.util.Arrays.fill(refSet, false)
                framesSinceReset = 0
                sceneChangeFrames = 0
                Log.d(TAG, "scene changed, reference reset")
            }
            return null
        }
        sceneChangeFrames = 0

        val scans = Array(3) { LaneScan() }

        for (lane in 0..2) {
            val sc = scans[lane]
            var i = 0

            while (i < n) {
                val k = lane * n + i

                if (!usable[k] || !refSet[k]) {
                    i++
                    continue
                }

                sc.evaluated = true

                if (!anomalous[k]) {
                    i++
                    continue
                }

                // Extend the run, tolerating a single clean step inside it.
                var last = i
                var gapUsed = false
                while (last + 1 < n) {
                    val kn = lane * n + last + 1
                    if (usable[kn] && anomalous[kn]) {
                        last++
                    } else if (!gapUsed &&
                        last + 2 < n &&
                        usable[lane * n + last + 2] &&
                        anomalous[lane * n + last + 2]
                    ) {
                        gapUsed = true
                        last += 2
                    } else {
                        break
                    }
                }

                val runLen = last - i + 1
                var gsum = 0f
                for (q in i..last) gsum += gold[lane * n + q]
                val goldAvg = gsum / runLen

                // Short gold patches along the lane are coins, not obstacles.
                if (runLen <= 3 && goldAvg in 0.05f..0.55f) {
                    sc.coins += runLen
                    i = last + 1
                    continue
                }

                // A single isolated far step is noise.
                if (runLen == 1 && i > 1) {
                    i = last + 1
                    continue
                }

                sc.obstacleZ = zAt(i)
                sc.beside = i <= 1
                sc.kind =
                    if (runLen <= 6 && last < n - 1) Geo.KIND_LOW
                    else Geo.KIND_TALL
                break
            }
        }

        // Closing speed from how fast each lane's first obstacle moved.
        for (lane in 0..2) {
            val z = scans[lane].obstacleZ
            val pz = prevObstZ[lane]

            if (z < Geo.NO_OBSTACLE && pz < Geo.NO_OBSTACLE) {
                val dt = (now - prevObstAt[lane]) / 1000f
                if (dt in 0.08f..1.2f) {
                    val rate = (pz - z) / dt
                    if (rate in 0.3f..9f) {
                        speedEst = (speedEst * 0.75f + rate * 0.25f)
                            .coerceIn(0.5f, 6f)
                    }
                }
            }

            prevObstZ[lane] = z
            prevObstAt[lane] = now
        }

        return scans
    }

    private fun colorDist(
        r1: Float, g1: Float, b1: Float,
        r2: Float, g2: Float, b2: Float
    ): Float {
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /*
     * ------------------------------------------------------------
     * SCREEN / UI DETECTION
     * ------------------------------------------------------------
     */

    private fun detectScreenMode(
        bitmap: Bitmap,
        previous: Bitmap?
    ): ScreenMode {

        if (detectContinueDialog(bitmap)) {
            return ScreenMode.CONTINUE_DIALOG
        }

        /*
         * Main menu and result screen both contain a large green PLAY
         * button near the bottom. We deliberately choose the LOWEST
         * large green button so "Watch Video" above it is ignored.
         */
        if (findLargeGreenButton(
                bitmap,
                0.76f,
                0.995f
            ) != null
        ) {
            return ScreenMode.PLAY_OR_RESULT
        }

        if (state == BotState.PLAYING ||
            state == BotState.STARTING
        ) {
            return ScreenMode.GAMEPLAY
        }

        val score =
            detectGameplayScore(bitmap, previous)

        return if (score > 0.18f) {
            ScreenMode.GAMEPLAY
        } else {
            ScreenMode.UNKNOWN
        }
    }

    private fun detectContinueDialog(bitmap: Bitmap): Boolean {
        if (bitmap.width < 300 || bitmap.height < 500) {
            return false
        }

        val topAverage =
            averageLuminance(
                bitmap,
                0.00f,
                0.28f,
                0.00f,
                1.00f
            )

        val bottomAverage =
            averageLuminance(
                bitmap,
                0.72f,
                1.00f,
                0.00f,
                1.00f
            )

        val centerWhiteRatio =
            whiteRatio(
                bitmap,
                0.06f,
                0.74f,
                0.08f,
                0.92f
            )

        val blue =
            findLargeBlueButton(
                bitmap,
                0.45f,
                0.61f
            )

        /*
         * Screenshot pattern:
         * darkened game background + large white Continue panel
         * + blue key button / green ad button.
         */
        return topAverage < 105f &&
            bottomAverage < 105f &&
            centerWhiteRatio > 0.24f &&
            blue != null
    }

    /**
     * Handles interstitial/reward advertisements without interacting with
     * the ad itself. The controller only presses explicit Skip/Close/Dismiss
     * controls. It never presses Install, Buy, Learn More, Open, or the ad
     * creative.
     *
     * This is intentionally conservative because an ad may still belong to
     * the Subway Surfers package. In that situation package-name checks alone
     * cannot tell us that gameplay is covered.
     */
    private fun handleAdvertisementControls(bitmap: Bitmap): Boolean {
        val now = SystemClock.elapsedRealtime()

        if (now - lastPopupActionAt < 500L) {
            return false
        }

        val root = rootInActiveWindow
        var adSignal = false

        if (root != null) {
            adSignal = containsAdvertisementSignal(root)
            if (adSignal) adActiveUntil = now + 2000L

            if (adSignal) {
                val skipOrClose = findNodeByTextOrDescription(
                    root,
                    listOf(
                        "skip ad",
                        "skip advertisement",
                        "close ad",
                        "close advertisement",
                        "dismiss ad",
                        "dismiss advertisement",
                        "skip",
                        "close",
                        "dismiss",
                        "×"
                    )
                )

                if (skipOrClose != null && clickNodeSafely(skipOrClose)) {
                    lastPopupActionAt = now
                    state = BotState.RECOVERING
                    consecutiveGameplayFrames = 0
                    consecutiveStaticFrames = 0
                    return true
                }
            }
        }

        /*
         * Some ad SDKs expose no useful Accessibility text. If the
         * accessibility tree strongly indicates an advertisement, allow the
         * existing conservative red-X detector to close it.
         */
        if (adSignal) {
            val visualClose = findRedCloseButton(bitmap)

            if (visualClose != null) {
                tap(
                    visualClose.centerX(),
                    visualClose.centerY()
                )
                lastPopupActionAt = now
                state = BotState.RECOVERING
                consecutiveGameplayFrames = 0
                consecutiveStaticFrames = 0
                return true
            }
        }

        return false
    }

    private fun containsAdvertisementSignal(
        root: AccessibilityNodeInfo
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)

        val strongSignals = listOf(
            "skip ad",
            "skip advertisement",
            "close ad",
            "close advertisement",
            "dismiss ad",
            "dismiss advertisement",
            "advertisement",
            "ad choices",
            "ad info"
        )

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val description =
                node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            if (strongSignals.any { signal ->
                    text.contains(signal) || description.contains(signal)
                }) {
                return true
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.addLast(it) }
            }
        }

        return false
    }

    private fun handleVisualScreen(
        mode: ScreenMode,
        bitmap: Bitmap
    ): Boolean {

        val now = SystemClock.elapsedRealtime()

        if (now - lastPopupActionAt < 450L) {
            return false
        }

        /*
         * HIGHEST PRIORITY: a modal popup with a visible red/white X.
         *
         * The "Not Enough Keys" screen in the supplied screenshot is a
         * modal overlay. We must close it before looking for Continue/Play.
         */
        val closeButton =
            if (mode != ScreenMode.GAMEPLAY) {
                findRedCloseButton(bitmap)
            } else {
                null
            }

        if (closeButton != null) {
            tap(
                closeButton.centerX(),
                closeButton.centerY()
            )

            lastPopupActionAt = now
            state = BotState.RECOVERING
            consecutiveGameplayFrames = 0
            consecutiveStaticFrames = 0

            return true
        }

        when (mode) {

            ScreenMode.CONTINUE_DIALOG -> {
                /*
                 * IMPORTANT:
                 * NEVER tap the blue key Continue button.
                 *
                 * The first supplied screenshot shows that button as:
                 * "4 [key]"
                 *
                 * Tapping it spends keys and can open "Not Enough Keys".
                 *
                 * We specifically choose the GREEN "Continue + Ad" button.
                 */
                val adContinue =
                    findLargeGreenButton(
                        bitmap,
                        0.54f,
                        0.75f
                    )

                if (adContinue != null) {
                    tap(
                        adContinue.centerX(),
                        adContinue.centerY()
                    )

                    lastPopupActionAt = now
                    deathRecoveryCount++
                    resetAfterRecovery()

                    return true
                }

                /*
                 * If the green Continue button is not confidently found,
                 * DO NOTHING. Waiting is safer than spending keys or
                 * pressing a random location.
                 */
                return false
            }

            ScreenMode.PLAY_OR_RESULT -> {
                val play =
                    findLargeGreenButton(
                        bitmap,
                        0.76f,
                        0.995f
                    )

                if (play != null) {
                    tap(
                        play.centerX(),
                        play.centerY()
                    )

                    lastStartTapAt = now
                    lastPopupActionAt = now

                    state = BotState.STARTING
                    resetPerception()

                    /*
                     * Do NOT wait 3 seconds. Start observing almost
                     * immediately after the PLAY tap.
                     */
                    startGraceUntil = now + 320L

                    consecutiveGameplayFrames = 0
                    consecutiveStaticFrames = 0

                    return true
                }

                return false
            }

            else -> return false
        }
    }

    /*
     * ------------------------------------------------------------
     * ACCESSIBILITY UI FALLBACK
     * ------------------------------------------------------------
     */

    private fun handleAccessibilityControls(): Boolean {
        val root = rootInActiveWindow ?: return false

        val now = SystemClock.elapsedRealtime()

        if (now - lastPopupActionAt < 600L) {
            return false
        }

        /*
         * Safe dismiss words only.
         * We do not click arbitrary advertisement actions.
         */
        val dismissNode =
            findNodeByTextOrDescription(
                root,
                listOf(
                    "close ad",
                    "close advertisement",
                    "skip ad",
                    "skip advertisement",
                    "dismiss ad",
                    "dismiss advertisement",
                    "close",
                    "dismiss",
                    "skip",
                    "no thanks",
                    "not now",
                    "cancel",
                    "×"
                )
            )

        if (dismissNode != null) {
            if (clickNodeSafely(dismissNode)) {
                lastPopupActionAt = now
                return true
            }
        }

        val restartNode =
            findNodeByTextOrDescription(
                root,
                listOf(
                    "play again",
                    "retry",
                    "restart",
                    "try again",
                    "tap to play",
                    "tap to continue"
                )
            )

        if (restartNode != null) {
            if (clickNodeSafely(restartNode)) {
                lastPopupActionAt = now
                deathRecoveryCount++
                resetAfterRecovery()
                return true
            }
        }

        return false
    }

    private fun findNodeByTextOrDescription(
        root: AccessibilityNodeInfo,
        phrases: List<String>
    ): AccessibilityNodeInfo? {

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text =
                node.text
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: ""

            val description =
                node.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: ""

            val resource =
                node.viewIdResourceName
                    ?.lowercase()
                    ?: ""

            for (phrase in phrases) {
                val target = phrase.lowercase()
                val normalized =
                    target.replace(" ", "_")

                if (
                    text == target ||
                    description == target ||
                    text.contains(target) ||
                    description.contains(target) ||
                    resource.contains(normalized)
                ) {
                    if (node.isVisibleToUser) {
                        return node
                    }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let {
                    queue.addLast(it)
                }
            }
        }

        return null
    }

    private fun clickNodeSafely(
        node: AccessibilityNodeInfo
    ): Boolean {

        try {
            if (
                node.isClickable &&
                node.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            ) {
                return true
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)

            if (rect.width() > 0 && rect.height() > 0) {
                tap(
                    rect.centerX(),
                    rect.centerY()
                )
                return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    private fun resetAfterRecovery() {
        resetPerception()
        state = BotState.RECOVERING
        consecutiveGameplayFrames = 0
        consecutiveStaticFrames = 0
        lastLaneChangeAt = SystemClock.elapsedRealtime()
        startGraceUntil = SystemClock.elapsedRealtime() + 380L
    }

    /*
     * ------------------------------------------------------------
     * VISUAL COLOR REGION FINDER
     * ------------------------------------------------------------
     *
     * This avoids hard-coding 691x1536 coordinates. The supplied
     * screenshots are scaled to the actual screenshot dimensions.
     */

    /*
     * Detect the red circular X used by the "Not Enough Keys" modal.
     *
     * We intentionally search only in the upper-right part of the screen
     * and require a sufficiently large connected red region. This prevents
     * ordinary red game objects from being mistaken for a close button.
     */
    private fun findRedCloseButton(
        bitmap: Bitmap
    ): VisualRegion? {

        val width = bitmap.width
        val height = bitmap.height

        val sampleStep =
            max(7, min(width, height) / 140)

        val cols =
            max(1, width / sampleStep)

        val rows =
            max(1, height / sampleStep)

        val minXFraction = 0.68f
        val maxXFraction = 0.99f
        val minYFraction = 0.18f
        val maxYFraction = 0.60f

        val active =
            BooleanArray(cols * rows)

        fun index(x: Int, y: Int): Int =
            y * cols + x

        val startX =
            max(0, (cols * minXFraction).toInt())

        val endX =
            min(cols - 1, (cols * maxXFraction).toInt())

        val startY =
            max(0, (rows * minYFraction).toInt())

        val endY =
            min(rows - 1, (rows * maxYFraction).toInt())

        for (gy in startY..endY) {
            val py =
                min(
                    height - 1,
                    gy * sampleStep +
                        sampleStep / 2
                )

            for (gx in startX..endX) {
                val px =
                    min(
                        width - 1,
                        gx * sampleStep +
                            sampleStep / 2
                    )

                val c =
                    bitmap.getPixel(px, py)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (
                    r > 145 &&
                    r > g * 1.35f &&
                    r > b * 1.25f
                ) {
                    active[index(gx, gy)] = true
                }
            }
        }

        val visited =
            BooleanArray(cols * rows)

        val queue =
            ArrayDeque<Int>()

        var best: VisualRegion? = null
        var bestScore = 0f

        for (gy in startY..endY) {
            for (gx in startX..endX) {

                val startIndex =
                    index(gx, gy)

                if (
                    !active[startIndex] ||
                    visited[startIndex]
                ) {
                    continue
                }

                queue.clear()
                queue.addLast(startIndex)
                visited[startIndex] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var count = 0

                while (queue.isNotEmpty()) {
                    val current =
                        queue.removeFirst()

                    val cx =
                        current % cols

                    val cy =
                        current / cols

                    count++

                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    val neighbors =
                        intArrayOf(
                            current - 1,
                            current + 1,
                            current - cols,
                            current + cols
                        )

                    for (n in neighbors) {
                        if (
                            n < 0 ||
                            n >= active.size ||
                            visited[n] ||
                            !active[n]
                        ) {
                            continue
                        }

                        val nx = n % cols
                        val ny = n / cols

                        if (
                            abs(nx - cx) +
                            abs(ny - cy) != 1
                        ) {
                            continue
                        }

                        visited[n] = true
                        queue.addLast(n)
                    }
                }

                val left =
                    minX * sampleStep

                val top =
                    minY * sampleStep

                val right =
                    min(
                        width,
                        (maxX + 1) * sampleStep
                    )

                val bottom =
                    min(
                        height,
                        (maxY + 1) * sampleStep
                    )

                val region =
                    VisualRegion(
                        left,
                        top,
                        right,
                        bottom,
                        count
                    )

                val widthRatio =
                    region.width().toFloat() /
                        width

                val heightRatio =
                    region.height().toFloat() /
                        height

                val centerXRatio =
                    region.centerX().toFloat() /
                        width

                /*
                 * A close icon should be compact, roughly square,
                 * reasonably large, and close to the upper-right corner.
                 */
                val aspect =
                    if (region.height() > 0) {
                        region.width().toFloat() /
                            region.height()
                    } else {
                        99f
                    }

                if (
                    count >= 8 &&
                    widthRatio >= 0.025f &&
                    widthRatio <= 0.16f &&
                    heightRatio >= 0.02f &&
                    heightRatio <= 0.12f &&
                    aspect in 0.55f..1.8f &&
                    centerXRatio >= 0.75f
                ) {
                    val score =
                        count.toFloat() *
                            (0.5f + centerXRatio)

                    if (score > bestScore) {
                        bestScore = score
                        best = region
                    }
                }
            }
        }

        return best
    }

    private fun findLargeGreenButton(
        bitmap: Bitmap,
        yStartFraction: Float,
        yEndFraction: Float
    ): VisualRegion? {
        return findLargeColorRegion(
            bitmap,
            yStartFraction,
            yEndFraction,
            ColorKind.GREEN
        )
    }

    private fun findLargeBlueButton(
        bitmap: Bitmap,
        yStartFraction: Float,
        yEndFraction: Float
    ): VisualRegion? {
        return findLargeColorRegion(
            bitmap,
            yStartFraction,
            yEndFraction,
            ColorKind.BLUE
        )
    }

    private enum class ColorKind {
        GREEN,
        BLUE
    }

    private fun findLargeColorRegion(
        bitmap: Bitmap,
        yStartFraction: Float,
        yEndFraction: Float,
        kind: ColorKind
    ): VisualRegion? {

        val width = bitmap.width
        val height = bitmap.height

        val sampleStep =
            max(8, min(width, height) / 120)

        val cols =
            max(1, width / sampleStep)

        val rows =
            max(1, height / sampleStep)

        val startRow =
            max(
                0,
                (rows * yStartFraction).toInt()
            )

        val endRow =
            min(
                rows - 1,
                (rows * yEndFraction).toInt()
            )

        val active =
            BooleanArray(cols * rows)

        fun index(x: Int, y: Int): Int =
            y * cols + x

        for (gy in startRow..endRow) {
            val py =
                min(
                    height - 1,
                    gy * sampleStep +
                        sampleStep / 2
                )

            for (gx in 0 until cols) {
                val px =
                    min(
                        width - 1,
                        gx * sampleStep +
                            sampleStep / 2
                    )

                val color =
                    bitmap.getPixel(px, py)

                if (isTargetColor(color, kind)) {
                    active[index(gx, gy)] = true
                }
            }
        }

        val visited =
            BooleanArray(cols * rows)

        val regions =
            ArrayList<VisualRegion>()

        val queue = ArrayDeque<Int>()

        for (gy in startRow..endRow) {
            for (gx in 0 until cols) {
                val startIndex = index(gx, gy)

                if (!active[startIndex] ||
                    visited[startIndex]
                ) {
                    continue
                }

                queue.clear()
                queue.addLast(startIndex)
                visited[startIndex] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var count = 0

                while (queue.isNotEmpty()) {
                    val current =
                        queue.removeFirst()

                    val cx =
                        current % cols
                    val cy =
                        current / cols

                    count++

                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    val neighbors =
                        intArrayOf(
                            current - 1,
                            current + 1,
                            current - cols,
                            current + cols
                        )

                    for (n in neighbors) {
                        if (n < 0 ||
                            n >= active.size ||
                            visited[n] ||
                            !active[n]
                        ) {
                            continue
                        }

                        val nx =
                            n % cols
                        val ny =
                            n / cols

                        if (abs(nx - cx) +
                            abs(ny - cy) != 1
                        ) {
                            continue
                        }

                        visited[n] = true
                        queue.addLast(n)
                    }
                }

                val left =
                    minX * sampleStep

                val top =
                    minY * sampleStep

                val right =
                    min(
                        width,
                        (maxX + 1) * sampleStep
                    )

                val bottom =
                    min(
                        height,
                        (maxY + 1) * sampleStep
                    )

                val region =
                    VisualRegion(
                        left,
                        top,
                        right,
                        bottom,
                        count
                    )

                val widthRatio =
                    region.width().toFloat() /
                        width

                val heightRatio =
                    region.height().toFloat() /
                        height

                /*
                 * Buttons are large connected rectangles.
                 * Small green objects/coins are rejected here.
                 */
                if (
                    count >= 14 &&
                    widthRatio >= 0.20f &&
                    heightRatio >= 0.025f
                ) {
                    regions.add(region)
                }
            }
        }

        /*
         * The lowest valid region is the Play button on the
         * main/result screen. This also avoids the Watch Video
         * button above it.
         */
        return regions.maxByOrNull {
            it.bottom
        }
    }

    private fun isTargetColor(
        color: Int,
        kind: ColorKind
    ): Boolean {

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return when (kind) {

            ColorKind.GREEN -> {
                g >= 105 &&
                    g > r * 1.12f &&
                    g > b * 0.92f &&
                    r < 150
            }

            ColorKind.BLUE -> {
                b >= 105 &&
                    b > r * 1.18f &&
                    g > r * 1.05f &&
                    b > g * 0.92f
            }
        }
    }

    private fun averageLuminance(
        bitmap: Bitmap,
        y0Fraction: Float,
        y1Fraction: Float,
        x0Fraction: Float,
        x1Fraction: Float
    ): Float {

        val width = bitmap.width
        val height = bitmap.height

        val x0 =
            (width * x0Fraction).toInt()

        val x1 =
            (width * x1Fraction).toInt()

        val y0 =
            (height * y0Fraction).toInt()

        val y1 =
            (height * y1Fraction).toInt()

        val step =
            max(
                8,
                min(width, height) / 100
            )

        var total = 0L
        var count = 0

        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                total += luminance(
                    bitmap.getPixel(x, y)
                )
                count++
                x += step
            }
            y += step
        }

        return if (count == 0) {
            0f
        } else {
            total.toFloat() / count
        }
    }

    private fun whiteRatio(
        bitmap: Bitmap,
        y0Fraction: Float,
        y1Fraction: Float,
        x0Fraction: Float,
        x1Fraction: Float
    ): Float {

        val width = bitmap.width
        val height = bitmap.height

        val x0 =
            (width * x0Fraction).toInt()

        val x1 =
            (width * x1Fraction).toInt()

        val y0 =
            (height * y0Fraction).toInt()

        val y1 =
            (height * y1Fraction).toInt()

        val step =
            max(
                8,
                min(width, height) / 100
            )

        var white = 0
        var total = 0

        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c =
                    bitmap.getPixel(x, y)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (
                    r > 175 &&
                    g > 175 &&
                    b > 175
                ) {
                    white++
                }

                total++
                x += step
            }
            y += step
        }

        return if (total == 0) {
            0f
        } else {
            white.toFloat() / total
        }
    }

    /*
     * ------------------------------------------------------------
     * GAMEPLAY DETECTION
     * ------------------------------------------------------------
     */

    private fun detectGameplayScore(
        bitmap: Bitmap,
        previous: Bitmap?
    ): Float {

        if (
            bitmap.width < 200 ||
            bitmap.height < 200
        ) {
            return 0f
        }

        val width = bitmap.width
        val height = bitmap.height

        /*
         * Ignore top HUD and very bottom navigation area.
         */
        val y0 =
            (height * 0.16f).toInt()

        val y1 =
            (height * 0.91f).toInt()

        val stepX =
            max(12, width / 45)

        val stepY =
            max(12, height / 55)

        var samples = 0
        var structural = 0
        var motion = 0

        var y = y0

        while (y < y1) {
            var x = stepX

            while (x < width - stepX) {
                val c =
                    bitmap.getPixel(x, y)

                val right =
                    bitmap.getPixel(
                        min(width - 1, x + stepX),
                        y
                    )

                val down =
                    bitmap.getPixel(
                        x,
                        min(height - 1, y + stepY)
                    )

                val l =
                    luminance(c)

                val lr =
                    luminance(right)

                val ld =
                    luminance(down)

                if (
                    abs(l - lr) > 28 ||
                    abs(l - ld) > 28
                ) {
                    structural++
                }

                if (
                    previous != null &&
                    x < previous.width &&
                    y < previous.height
                ) {
                    val p =
                        previous.getPixel(x, y)

                    if (
                        abs(
                            l -
                                luminance(p)
                        ) > 24
                    ) {
                        motion++
                    }
                }

                samples++
                x += stepX
            }

            y += stepY
        }

        if (samples == 0) return 0f

        val structuralRatio =
            structural.toFloat() /
                samples

        val motionRatio =
            motion.toFloat() /
                samples

        return (
            structuralRatio * 0.35f +
                motionRatio * 1.15f
            ).coerceIn(0f, 1f)
    }

    /*
     * ------------------------------------------------------------
     * GAME BRAIN
     * ------------------------------------------------------------
     *
     * Input: per-lane obstacle position (inverse depth) and the measured
     * closing speed. Output: one action. Time-to-collision is
     *
     *     ttc = (z_obstacle - Z_NEAR) / speed
     *
     * so a far obstacle and an obstacle in front of the runner produce
     * different actions. Nothing here is random: the same scene always
     * gives the same decision.
     */

    private object GameBrain {

        fun decide(
            scans: Array<LaneScan>,
            lane: Int,
            speed: Float,
            framePeriodSec: Float,
            now: Long,
            lastLaneChangeAt: Long,
            laneBusy: Boolean,
            airborne: Boolean
        ): Decision {

            if (laneBusy) return none("gesture or lane change in progress")

            val here = lane.coerceIn(0, 2)
            val cur = scans[here]
            if (!cur.evaluated) return none("current lane not readable")

            val curT = ttc(cur, speed)

            // Start reacting early enough that several frames are left.
            val threatHorizon = 1.0f + 1.5f * framePeriodSec
            val canMove = now - lastLaneChangeAt >= 380L

            // Best adjacent lane that is clear for long enough to enter.
            var bestLane = -1
            var bestScore = -1f
            var bestTtc = 0f

            for (t in intArrayOf(here - 1, here + 1)) {
                if (t < 0 || t > 2) continue
                val sc = scans[t]
                if (!sc.evaluated) continue

                val tt = ttc(sc, speed)
                if (sc.beside || tt < 0.95f) continue

                val score = min(tt, 4f) + if (sc.coins > 0) 0.2f else 0f
                if (score > bestScore) {
                    bestScore = score
                    bestLane = t
                    bestTtc = tt
                }
            }

            val threat = cur.obstacleZ < Geo.NO_OBSTACLE && curT < threatHorizon

            if (!threat) {
                // Nothing dangerous ahead. Only chase coins when everything
                // around is comfortably clear and no recent lane change.
                if (canMove &&
                    now - lastLaneChangeAt >= 2500L &&
                    curT >= 3.0f
                ) {
                    var coinLane = -1
                    var coinCount = cur.coins

                    for (t in intArrayOf(here - 1, here + 1)) {
                        if (t < 0 || t > 2) continue
                        val sc = scans[t]
                        if (!sc.evaluated || sc.beside) continue
                        if (ttc(sc, speed) < 3.0f) continue
                        if (sc.coins >= 3 && sc.coins > coinCount) {
                            coinCount = sc.coins
                            coinLane = t
                        }
                    }

                    if (coinLane >= 0) {
                        return move(here, coinLane, "coins in lane $coinLane")
                    }
                }
                return none("clear")
            }

            // ---- threat in the current lane
            if (cur.kind == Geo.KIND_LOW) {
                // Prefer a clearly free neighbour lane over a jump.
                if (bestLane >= 0 && bestScore >= 2.2f && canMove) {
                    return move(here, bestLane, "low obstacle, lane $bestLane clear ${fmt(bestTtc)}s")
                }

                val windowHi = 0.55f + 0.6f * framePeriodSec
                if (!airborne && curT in 0.12f..windowHi) {
                    return Decision(Action.JUMP, "low obstacle ttc=${fmt(curT)}s")
                }

                if (bestLane >= 0 && canMove && curT < 0.6f) {
                    return move(here, bestLane, "low obstacle, jump unavailable")
                }

                return none("low obstacle, waiting for jump window (ttc=${fmt(curT)}s)")
            }

            // Tall obstacle (train / wall): only a lane change helps.
            if (bestLane >= 0 && canMove) {
                return move(here, bestLane, "tall obstacle ttc=${fmt(curT)}s, lane $bestLane clear ${fmt(bestTtc)}s")
            }

            // No lane is clear for long enough. Take the neighbour that at
            // least gives clearly more time than staying.
            if (canMove) {
                var fallLane = -1
                var fallTtc = curT + 0.5f

                for (t in intArrayOf(here - 1, here + 1)) {
                    if (t < 0 || t > 2) continue
                    val sc = scans[t]
                    if (!sc.evaluated || sc.beside) continue
                    val tt = ttc(sc, speed)
                    if (tt > fallTtc) {
                        fallTtc = tt
                        fallLane = t
                    }
                }

                if (fallLane >= 0) {
                    return move(here, fallLane, "least-bad lane $fallLane (${fmt(fallTtc)}s)")
                }
            }

            return none("blocked, no better lane (ttc=${fmt(curT)}s)")
        }

        private fun ttc(sc: LaneScan, speed: Float): Float {
            if (sc.obstacleZ >= Geo.NO_OBSTACLE) return 99f
            return max(0f, (sc.obstacleZ - Geo.Z_NEAR) / max(0.3f, speed))
        }

        private fun move(from: Int, to: Int, why: String): Decision {
            return Decision(
                if (to < from) Action.LEFT else Action.RIGHT,
                why
            )
        }

        private fun none(why: String): Decision = Decision(Action.NONE, why)

        private fun fmt(v: Float): String =
            (Math.round(v * 10f) / 10f).toString()
    }

    /*
     * ------------------------------------------------------------
     * GESTURE CONTROLLER
     * ------------------------------------------------------------
     */

    private fun performDecision(
        decision: Decision,
        width: Int,
        height: Int,
        now: Long
    ) {
        if (!enabled || !controllerActive || !gameForeground || durationExpired()) return
        if (gestureBusy) return
        if (now - lastActionAt < 140L) return

        when (decision.action) {
            Action.LEFT -> {
                if (currentLane <= 0 || pendingLaneTarget != null) return
                // Only a REQUEST: currentLane stays until the runner is seen
                // in the new lane.
                pendingLaneOrigin = currentLane
                pendingLaneTarget = currentLane - 1
                pendingLaneRequestedAt = now
                pendingLaneAttempts = 0
                pendingLaneFrames = 0
                gestureBusy = true
                dispatchHorizontalSwipe(-1, width, height)
            }

            Action.RIGHT -> {
                if (currentLane >= 2 || pendingLaneTarget != null) return
                pendingLaneOrigin = currentLane
                pendingLaneTarget = currentLane + 1
                pendingLaneRequestedAt = now
                pendingLaneAttempts = 0
                pendingLaneFrames = 0
                gestureBusy = true
                dispatchHorizontalSwipe(1, width, height)
            }

            Action.JUMP -> {
                if (airborne || pendingVertical != null) return
                pendingVertical = Action.JUMP
                verticalRequestedAt = now
                lastVerticalAt = now
                airborne = true
                airborneSince = now
                gestureBusy = true
                dispatchVerticalSwipe(1, width, height)
            }

            Action.ROLL -> {
                if (pendingVertical != null) return
                pendingVertical = Action.ROLL
                verticalRequestedAt = now
                lastVerticalAt = now
                gestureBusy = true
                dispatchVerticalSwipe(-1, width, height)
            }

            Action.TAP -> {
                gestureBusy = true
                tap(width / 2, (height * 0.58f).toInt())
                handler.postDelayed({ gestureBusy = false }, 140L)
            }

            Action.NONE -> return
        }

        lastActionAt = now
        Log.d(
            TAG,
            "${decision.action} lane=$currentLane x=${playerCenterX} " +
                "conf=${playerConfidence} speed=$speedEst :: ${decision.reason}"
        )
    }

    private fun playerPixelX(width: Int): Int =
        if (playerCenterX > 0f) (playerCenterX * width).toInt() else width / 2

    /**
     * Starts at the runner's visible x, shifted inward only as far as needed
     * so the whole swipe stays on screen. A swipe that gets clipped at the
     * screen edge becomes too short for the game to read as a lane change.
     */
    private fun dispatchHorizontalSwipe(
        direction: Int,
        width: Int,
        height: Int
    ) {
        val margin = (width * 0.04f).toInt()
        val distance = min(
            (width * 0.34f * swipeBoost).toInt(),
            (width * 0.80f).toInt()
        )

        val playerX = playerPixelX(width)
        val startX = if (direction > 0) {
            playerX.coerceIn(margin, width - margin - distance)
        } else {
            playerX.coerceIn(margin + distance, width - margin)
        }
        val endX = startX + direction * distance
        val y = (height * 0.80f).toInt()

        val path = Path().apply {
            moveTo(startX.toFloat(), y.toFloat())
            lineTo(endX.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // Completed != the runner moved. Only the next frames
                    // can confirm that.
                    gestureBusy = false
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    pendingLaneRequestedAt = SystemClock.elapsedRealtime()
                }
            },
            null
        )

        // Safety net in case a callback never arrives.
        handler.postDelayed({ gestureBusy = false }, 500L)

        if (!accepted) {
            Log.w(TAG, "dispatchGesture rejected the horizontal swipe")
            gestureBusy = false
            pendingLaneTarget = null
            pendingLaneAttempts = 0
        }
    }

    /**
     * direction  1 = JUMP (swipe up),  -1 = ROLL (swipe down).
     * Roll starts mid-screen so the full swipe length is available; the old
     * version started at 80% height and was clipped to a tiny swipe.
     */
    private fun dispatchVerticalSwipe(
        direction: Int,
        width: Int,
        height: Int
    ) {
        val x = playerPixelX(width)
            .coerceIn((width * 0.12f).toInt(), (width * 0.88f).toInt())

        val startY: Int
        val endY: Int

        if (direction > 0) {
            startY = (height * 0.80f).toInt()
            endY = (startY - height * 0.32f * vertBoost)
                .toInt()
                .coerceAtLeast((height * 0.12f).toInt())
        } else {
            startY = (height * 0.52f).toInt()
            endY = (startY + height * 0.30f * vertBoost)
                .toInt()
                .coerceAtMost((height * 0.95f).toInt())
        }

        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({ gestureBusy = false }, 60L)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                }
            },
            null
        )

        handler.postDelayed({ gestureBusy = false }, 500L)

        if (!accepted) {
            Log.w(TAG, "dispatchGesture rejected the vertical swipe")
            gestureBusy = false
            pendingVertical = null
            airborne = false
        }
    }

    private fun tap(
        x: Int,
        y: Int
    ) {

        val path =
            Path().apply {
                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        60L
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun luminance(color: Int): Int {
        return (
            Color.red(color) * 299 +
                Color.green(color) * 587 +
                Color.blue(color) * 114
            ) / 1000
    }
}
