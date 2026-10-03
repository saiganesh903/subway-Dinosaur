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
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * HumanSurfer controller.
 *
 * This version is safety-first:
 * 1. Detects the important Subway Surfers UI screens visually.
 * 2. Uses accessibility text only as a secondary recovery path.
 * 3. Never uses a fixed prerecorded move sequence.
 * 4. Detects relative danger between the three lanes instead of
 *    treating every edge/motion in the scene as an obstacle.
 * 5. Reacts quickly when an approaching obstacle is detected.
 *
 * It is still a heuristic vision controller, not a trained game-specific
 * object detector. The thresholds are deliberately conservative.
 */
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
        val confidence: Float,
        val cooldownMs: Long,
        val nextCaptureMs: Long,
        val gestureMs: Long
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

    private data class BandMetrics(
        val edge: Float,
        val strongEdge: Float,
        val motion: Float,
        val darkness: Float
    )

    private data class LaneInfo(
        val rawRisk: Float,
        val risk: Float,
        val nearEdge: Float,
        val midEdge: Float,
        val nearMotion: Float,
        val midMotion: Float,
        val nearDark: Float,
        val approach: Float,
        val target: Float,
        val hardBlock: Float
    )

    private data class PlayerEstimate(
        val lane: Int,
        val centerX: Float,
        val confidence: Float
    )

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())

    private var controllerActive = false
    private var state = BotState.WAITING_FOR_GAME

    private var lastScreenshotAt = 0L
    private var lastActionAt = 0L
    private var lastStartTapAt = 0L
    private var lastPopupActionAt = 0L
    private var lastLaneChangeAt = 0L
    private var startGraceUntil = 0L

    private var lastFrame: Bitmap? = null
    private var previousFrame: Bitmap? = null

    private var currentLane = 1
    private var playerCenterX = 0f
    private var playerConfidence = 0f
    private var pendingLaneTarget: Int? = null
    private var pendingLaneRequestedAt = 0L
    private var pendingLaneAttempts = 0
    private var gestureBusy = false
    private var missedFrames = 0
    private var gameForeground = false
    private var consecutiveGameplayFrames = 0
    private var consecutiveStaticFrames = 0
    private var deathRecoveryCount = 0

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
                gameForeground = true
                if (enabled) beginController()
            }

            packageName == "com.android.vending" -> {
                // Google Play purchase UI opened by the game.
                // Never interact with the purchase controls. Stop gameplay
                // gestures and dismiss the external purchase sheet safely.
                gameForeground = false
                controllerActive = false
                handler.removeCallbacksAndMessages(null)
                state = BotState.RECOVERING
                handler.postDelayed({ dismissExternalPurchaseUi() }, 180L)
            }

            else -> {
                // Ignore incidental accessibility events from other packages.
                // Only a real window switch means Subway Surfers lost focus.
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    gameForeground = false
                    controllerActive = false
                    handler.removeCallbacksAndMessages(null)
                    state = BotState.WAITING_FOR_GAME
                }
            }
        }
    }

    private fun dismissExternalPurchaseUi() {
        if (!enabled || durationExpired()) return

        val root = rootInActiveWindow
        if (root != null) {
            val purchaseSignal = findNodeByTextOrDescription(
                root,
                listOf(
                    "google play",
                    "pay with",
                    "payment method",
                    "view all payment methods",
                    "tap buy",
                    "buy"
                )
            )
            if (purchaseSignal != null) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }

        // If the purchase sheet is still visible but its accessibility
        // tree is sparse, a single BACK is still safer than clicking BUY.
        performGlobalAction(GLOBAL_ACTION_BACK)
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

    private fun resetSession() {
        state = BotState.WAITING_FOR_GAME
        currentLane = 1
        playerCenterX = 0f
        playerConfidence = 0f
        pendingLaneTarget = null
        pendingLaneRequestedAt = 0L
        pendingLaneAttempts = 0
        gestureBusy = false
        missedFrames = 0
        consecutiveGameplayFrames = 0
        consecutiveStaticFrames = 0
        deathRecoveryCount = 0

        lastScreenshotAt = 0L
        lastActionAt = 0L
        lastStartTapAt = 0L
        lastPopupActionAt = 0L
        lastLaneChangeAt = 0L
        startGraceUntil = 0L

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

        previousFrame?.recycle()
        previousFrame = null

        lastFrame?.recycle()
        lastFrame = null

        missedFrames = 0
        handler.removeCallbacksAndMessages(null)
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
        if (!controllerActive || !enabled || durationExpired()) {
            stopController()
            return
        }

        val now = SystemClock.elapsedRealtime()

        // Fast enough to react to approaching obstacles.
        val minimumFrameGap = 85L

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
        if (!controllerActive || !enabled || durationExpired()) {
            stopController()
            return
        }

        if (!gameForeground) {
            state = BotState.WAITING_FOR_GAME
            captureNextFrame(350L)
            return
        }

        if (Build.VERSION.SDK_INT < 30) {
            stopController()
            return
        }

        lastScreenshotAt = SystemClock.elapsedRealtime()

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

                    previousFrame?.recycle()
                    previousFrame = lastFrame
                    lastFrame = bitmap
                    missedFrames = 0

                    processFrame(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    missedFrames++

                    captureNextFrame(
                        if (missedFrames > 3) 250L else 120L
                    )
                }
            }
        )
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!gameForeground) {
            state = BotState.WAITING_FOR_GAME
            captureNextFrame(350L)
            return
        }

        val mode = detectScreenMode(bitmap, previousFrame)

        updatePlayerTracking(bitmap)
        verifyPendingLaneChange(bitmap)

        /*
         * AD HANDLING HAS PRIORITY OVER GAMEPLAY.
         *
         * An interstitial/reward ad can visually cover the game while the
         * package name is still Subway Surfers. Never send a jump/swipe
         * through an ad. First look for explicit Skip/Close controls.
         */
        if (handleAdvertisementControls(bitmap)) {
            captureNextFrame(180L)
            return
        }

        /*
         * IMPORTANT:
         * Visual recovery is checked BEFORE gameplay decisions.
         *
         * This fixes the Continue?/result screens from the screenshots.
         */
        if (handleVisualScreen(mode, bitmap)) {
            captureNextFrame(180L)
            return
        }

        /*
         * Accessibility recovery is secondary because many game
         * controls are rendered on a canvas and expose no text.
         */
        if (handleAccessibilityControls()) {
            state = BotState.RECOVERING
            captureNextFrame(180L)
            return
        }

        if (mode == ScreenMode.PLAY_OR_RESULT) {
            // The visual handler normally consumes this.
            // Keep observing instead of making a blind center tap.
            captureNextFrame(150L)
            return
        }

        val gameplayScore =
            detectGameplayScore(bitmap, previousFrame)

        if (mode == ScreenMode.GAMEPLAY ||
            state == BotState.STARTING ||
            gameplayScore > 0.16f
        ) {
            consecutiveGameplayFrames++
            consecutiveStaticFrames = 0
        } else {
            consecutiveStaticFrames++
            consecutiveGameplayFrames = 0
        }

        if (consecutiveGameplayFrames >= 2 ||
            state == BotState.STARTING
        ) {
            if (state != BotState.PLAYING &&
                state != BotState.RECOVERING
            ) {
                state = BotState.PLAYING
            }
        }

        /*
         * After pressing PLAY, give the runner only a short grace period.
         * The old implementation could wait several seconds before the
         * first real decision, which is too late for early obstacles.
         */
        val now = SystemClock.elapsedRealtime()

        if (state == BotState.STARTING &&
            now < startGraceUntil
        ) {
            captureNextFrame(90L)
            return
        }

        if (state != BotState.PLAYING) {
            captureNextFrame(120L)
            return
        }

        // If we cannot locate the runner with reasonable confidence, do not
        // invent a lane and do not send a blind gesture. Re-observe quickly.
        if (playerConfidence < 0.34f) {
            captureNextFrame(75L)
            return
        }

        val decision =
            GameBrain.decide(
                bitmap = bitmap,
                previous = previousFrame,
                currentLane = currentLane,
                lastLaneChangeAt = lastLaneChangeAt,
                now = now,
                random = random
            )

        if (decision != null) {
            if (now - lastActionAt >= decision.cooldownMs) {
                performDecision(
                    decision,
                    bitmap.width,
                    bitmap.height
                )
            }

            captureNextFrame(decision.nextCaptureMs)
        } else {
            captureNextFrame(95L)
        }
    }

    /*
     * ------------------------------------------------------------
     * PLAYER TRACKING / ACTION VERIFICATION
     * ------------------------------------------------------------
     */

    private fun updatePlayerTracking(bitmap: Bitmap) {
        val estimate = estimatePlayer(bitmap)
        if (estimate != null && estimate.confidence >= 0.34f) {
            playerCenterX = estimate.centerX
            playerConfidence = estimate.confidence
            if (pendingLaneTarget == null) {
                currentLane = estimate.lane
            }
        } else {
            playerConfidence *= 0.82f
        }
    }

    private fun verifyPendingLaneChange(bitmap: Bitmap) {
        val target = pendingLaneTarget ?: return
        val now = SystemClock.elapsedRealtime()
        val estimate = estimatePlayer(bitmap)

        if (estimate != null && estimate.confidence >= 0.36f) {
            playerCenterX = estimate.centerX
            playerConfidence = estimate.confidence

            if (estimate.lane == target) {
                currentLane = target
                pendingLaneTarget = null
                pendingLaneAttempts = 0
                lastLaneChangeAt = now
                gestureBusy = false
                return
            }
        }

        if (now - pendingLaneRequestedAt < 260L) return

        if (pendingLaneAttempts < 1 && gameForeground && controllerActive) {
            pendingLaneAttempts++
            pendingLaneRequestedAt = now
            gestureBusy = true
            val direction = if (target > currentLane) 1 else -1
            dispatchHorizontalSwipe(
                direction = direction,
                width = bitmap.width,
                height = bitmap.height,
                duration = 105L
            )
            return
        }

        // The gesture did not produce a visually confirmed lane change.
        // Never update our internal lane just because dispatchGesture returned.
        pendingLaneTarget = null
        pendingLaneAttempts = 0
        gestureBusy = false
        lastLaneChangeAt = now
    }

    private fun estimatePlayer(bitmap: Bitmap): PlayerEstimate? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 300 || height < 500) return null

        val xMin = (width * 0.12f).toInt()
        val xMax = (width * 0.88f).toInt()
        val yMin = (height * 0.68f).toInt()
        val yMax = (height * 0.96f).toInt()

        val step = max(8, min(width, height) / 115)
        val cols = max(1, (xMax - xMin) / step)
        val rows = max(1, (yMax - yMin) / step)
        val active = BooleanArray(cols * rows)

        fun idx(x: Int, y: Int) = y * cols + x

        for (gy in 0 until rows) {
            val py = min(height - 1, yMin + gy * step + step / 2)
            for (gx in 0 until cols) {
                val px = min(width - 1, xMin + gx * step + step / 2)
                val c = bitmap.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                val lum = luminance(c)

                // The runner is a compact, high-contrast/saturated object in
                // the lower central play corridor. Rails and track are much
                // more repetitive and usually have lower saturation.
                active[idx(gx, gy)] =
                    sat > 0.16f &&
                    lum in 22..245
            }
        }

        val visited = BooleanArray(active.size)
        val queue = ArrayDeque<Int>()
        var best: VisualRegion? = null
        var bestScore = 0f

        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val start = idx(gx, gy)
                if (!active[start] || visited[start]) continue
                queue.clear()
                queue.addLast(start)
                visited[start] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var count = 0

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cx = cur % cols
                    val cy = cur / cols
                    count++
                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    val ns = intArrayOf(
                        cur - 1, cur + 1,
                        cur - cols, cur + cols
                    )
                    for (n in ns) {
                        if (n < 0 || n >= active.size || visited[n] || !active[n]) continue
                        val nx = n % cols
                        val ny = n / cols
                        if (abs(nx - cx) + abs(ny - cy) != 1) continue
                        visited[n] = true
                        queue.addLast(n)
                    }
                }

                val rw = (maxX - minX + 1) * step
                val rh = (maxY - minY + 1) * step
                if (count < 10 || rw < step * 2 || rh < step * 2) continue
                if (rw > width * 0.42f || rh > height * 0.38f) continue

                val centerX = xMin + (minX + maxX + 1) * step / 2f
                val centerY = yMin + (minY + maxY + 1) * step / 2f
                val area = rw.toFloat() * rh.toFloat()
                val fill = count.toFloat() / max(1f, (rw.toFloat() / step) * (rh.toFloat() / step))
                val lowerBonus = ((centerY / height.toFloat()) - 0.70f).coerceIn(0f, 0.25f)
                val compactness = 1f - abs(1f - rw.toFloat() / max(1f, rh.toFloat())) * 0.35f
                val score =
                    count * 0.55f +
                    fill * 20f +
                    compactness * 8f +
                    lowerBonus * 18f +
                    (1f - abs(centerX / width.toFloat() - 0.5f)) * 6f

                if (score > bestScore) {
                    bestScore = score
                    best = VisualRegion(
                        (centerX - rw / 2f).toInt(),
                        (centerY - rh / 2f).toInt(),
                        (centerX + rw / 2f).toInt(),
                        (centerY + rh / 2f).toInt(),
                        count
                    )
                }
            }
        }

        val region = best ?: return null
        val centerX = region.centerX().toFloat()
        val lane = when {
            centerX < width * 0.385f -> 0
            centerX > width * 0.615f -> 2
            else -> 1
        }
        val confidence = (bestScore / 42f).coerceIn(0f, 1f)
        return PlayerEstimate(lane, centerX, confidence)
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
                    currentLane = 1

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
        currentLane = 1
        pendingLaneTarget = null
        pendingLaneAttempts = 0
        gestureBusy = false
        playerCenterX = 0f
        playerConfidence = 0f
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
     * The critical improvement over the old brain:
     *
     * OLD:
     *   "lane has edges/darkness/motion -> lane is dangerous"
     *
     * NEW:
     *   "compare the same collision corridor across all 3 lanes"
     *
     * Camera movement affects all lanes. A real obstacle usually
     * produces a stronger local signal in one lane.
     */

    private object GameBrain {

        fun decide(
            bitmap: Bitmap,
            previous: Bitmap?,
            currentLane: Int,
            lastLaneChangeAt: Long,
            now: Long,
            random: Random
        ): Decision? {
            if (bitmap.width < 300 || bitmap.height < 500) return null

            val observations = Array(3) { lane ->
                analyzeLane(bitmap, previous, lane)
            }

            val baseline = median(observations.map { it.rawRisk })
            val lanes = observations.map {
                it.copy(
                    risk = (0.50f + (it.rawRisk - baseline) * 3.20f)
                        .coerceIn(0f, 1f)
                )
            }

            val current = lanes[currentLane.coerceIn(0, 2)]
            val left = if (currentLane > 0) lanes[currentLane - 1] else null
            val right = if (currentLane < 2) lanes[currentLane + 1] else null
            val laneChangeAllowed = now - lastLaneChangeAt >= 520L

            // Collision estimate. We use the near-band signal much more
            // strongly than generic scene edges, and require an approaching
            // component so rails/background don't trigger lane changes.
            val imminent = current.risk >= 0.60f &&
                (current.approach > 0.010f || current.hardBlock > 0.55f)

            // Vertical action is considered before a lane change only when
            // the lane is boxed in or the obstacle geometry suggests it.
            if (imminent) {
                val leftBlocked = left == null || left.hardBlock > 0.78f
                val rightBlocked = right == null || right.hardBlock > 0.78f

                val lowerObstacle =
                    current.nearEdge > current.midEdge + 0.028f &&
                        current.approach > 0.018f
                val upperObstacle =
                    current.midEdge > current.nearEdge + 0.025f

                if (leftBlocked && rightBlocked && lowerObstacle) {
                    return decision(Action.JUMP, current.risk, false, random)
                }
                if (upperObstacle && current.risk > 0.64f) {
                    return decision(Action.ROLL, current.risk, false, random)
                }
            }

            if (laneChangeAllowed && imminent) {
                val candidates = mutableListOf<Pair<Int, Float>>()
                if (left != null && left.hardBlock < 0.86f) {
                    candidates += 0 to left.risk
                }
                if (right != null && right.hardBlock < 0.86f) {
                    candidates += 2 to right.risk
                }

                if (candidates.isNotEmpty()) {
                    val sorted = candidates.sortedBy { it.second }
                    val safest = sorted.first()
                    val risky = sorted.last()

                    // Calculated entertainment risk: normally prefer a
                    // survivable risky lane, but never an almost-certain hit.
                    val riskyEligible = risky.second < 0.68f &&
                        risky.second < current.risk + 0.08f
                    val chooseRisk = riskyEligible && random.nextFloat() < 0.70f
                    val chosen = if (chooseRisk) risky.first else safest.first

                    return if (chosen < currentLane) {
                        decision(Action.LEFT, current.risk, true, random)
                    } else {
                        decision(Action.RIGHT, current.risk, true, random)
                    }
                }
            }

            // If a target/power-up is visible in an adjacent lane, sometimes
            // chase it. The same safety ceiling prevents target pursuit from
            // becoming a deliberate collision.
            if (laneChangeAllowed) {
                val targetCandidates = mutableListOf<Pair<Int, Float>>()
                if (left != null && left.target > 0.48f && left.hardBlock < 0.68f) {
                    targetCandidates += 0 to left.target
                }
                if (right != null && right.target > 0.48f && right.hardBlock < 0.68f) {
                    targetCandidates += 2 to right.target
                }
                if (targetCandidates.isNotEmpty() && random.nextFloat() < 0.58f) {
                    val chosen = targetCandidates.maxByOrNull { it.second }!!.first
                    return if (chosen < currentLane) {
                        decision(Action.LEFT, 0.62f, true, random)
                    } else {
                        decision(Action.RIGHT, 0.62f, true, random)
                    }
                }
            }

            // Audience-confusion movement: only in a genuinely clear scene,
            // with a cooldown and only toward a lane that is not hard-blocked.
            if (laneChangeAllowed && current.risk < 0.42f && random.nextFloat() < 0.035f) {
                val options = mutableListOf<Int>()
                if (left != null && left.hardBlock < 0.48f) options += 0
                if (right != null && right.hardBlock < 0.48f) options += 2
                if (options.isNotEmpty()) {
                    val chosen = options.random(random)
                    return if (chosen < currentLane) {
                        decision(Action.LEFT, 0.42f, true, random)
                    } else {
                        decision(Action.RIGHT, 0.42f, true, random)
                    }
                }
            }

            return Decision(
                action = Action.NONE,
                confidence = 0.72f,
                cooldownMs = 150L,
                nextCaptureMs = 70L + random.nextLong(0L, 24L),
                gestureMs = 100L
            )
        }

        private fun analyzeLane(
            bitmap: Bitmap,
            previous: Bitmap?,
            lane: Int
        ): LaneInfo {

            val width = bitmap.width
            val height = bitmap.height

            /*
             * Perspective lane windows.
             * Narrower at the top, wider near the player.
             */
            val centerFractions =
                floatArrayOf(
                    0.27f,
                    0.50f,
                    0.73f
                )

            val centerX =
                width *
                    centerFractions[lane]

            val laneHalfWidth =
                width * 0.115f

            val x0 =
                max(
                    0,
                    (centerX - laneHalfWidth).toInt()
                )

            val x1 =
                min(
                    width,
                    (centerX + laneHalfWidth).toInt()
                )

            /*
             * Two collision bands:
             *
             * MID  = object is approaching
             * NEAR = object is close to player
             */
            val mid =
                bandMetrics(
                    bitmap,
                    previous,
                    x0,
                    x1,
                    (height * 0.48f).toInt(),
                    (height * 0.70f).toInt()
                )

            val near =
                bandMetrics(
                    bitmap,
                    previous,
                    x0,
                    x1,
                    (height * 0.70f).toInt(),
                    (height * 0.89f).toInt()
                )

            /*
             * Camera motion affects all lanes.
             * Raw risk is only a relative signal.
             */
            val target = targetScore(bitmap, x0, x1, (height * 0.38f).toInt(), (height * 0.78f).toInt())

            val rawRisk =
                near.motion * 0.42f +
                    near.edge * 0.22f +
                    near.strongEdge * 0.22f +
                    mid.motion * 0.24f +
                    mid.edge * 0.16f +
                    near.darkness * 0.08f

            val approach = near.motion - mid.motion
            val hardBlock = (
                near.strongEdge * 1.25f +
                    near.motion * 0.55f +
                    max(0f, approach) * 1.15f
            ).coerceIn(0f, 1f)

            return LaneInfo(
                rawRisk = rawRisk.coerceIn(0f, 1f),
                risk = 0f,
                nearEdge = near.edge,
                midEdge = mid.edge,
                nearMotion = near.motion,
                midMotion = mid.motion,
                nearDark = near.darkness,
                approach = approach,
                target = target,
                hardBlock = hardBlock
            )
        }

        private fun targetScore(
            bitmap: Bitmap,
            x0: Int,
            x1: Int,
            y0: Int,
            y1: Int
        ): Float {
            val width = bitmap.width
            val height = bitmap.height
            val sx = max(6, (x1 - x0) / 16)
            val sy = max(7, (y1 - y0) / 18)
            var total = 0
            var target = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = bitmap.getPixel(min(width - 1, x), min(height - 1, y))
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    val yellow = r > 185 && g > 120 && b < 105 && r > b * 1.6f
                    val orange = r > 185 && g > 75 && g < 190 && b < 90 && r > g * 1.15f
                    if (yellow || orange) target++
                    total++
                    x += sx
                }
                y += sy
            }
            return if (total == 0) 0f else (target.toFloat() / total * 4.5f).coerceIn(0f, 1f)
        }

        private fun bandMetrics(
            bitmap: Bitmap,
            previous: Bitmap?,
            x0: Int,
            x1: Int,
            y0: Int,
            y1: Int
        ): BandMetrics {

            val width = bitmap.width
            val height = bitmap.height

            val sx =
                max(
                    6,
                    (x1 - x0) / 18
                )

            val sy =
                max(
                    7,
                    (y1 - y0) / 18
                )

            var samples = 0
            var edge = 0
            var strongEdge = 0
            var motion = 0
            var dark = 0

            var y = y0

            while (y < y1) {
                var x = x0 + sx / 2

                while (x < x1) {
                    val c =
                        bitmap.getPixel(
                            x,
                            y
                        )

                    val right =
                        bitmap.getPixel(
                            min(
                                width - 1,
                                x + sx
                            ),
                            y
                        )

                    val down =
                        bitmap.getPixel(
                            x,
                            min(
                                height - 1,
                                y + sy
                            )
                        )

                    val l =
                        luminance(c)

                    val lr =
                        luminance(right)

                    val ld =
                        luminance(down)

                    val gradient =
                        abs(l - lr) +
                            abs(l - ld)

                    if (gradient > 34) {
                        edge++
                    }

                    if (gradient > 72) {
                        strongEdge++
                    }

                    if (l < 58) {
                        dark++
                    }

                    if (
                        previous != null &&
                        x < previous.width &&
                        y < previous.height
                    ) {
                        val p =
                            previous.getPixel(
                                x,
                                y
                            )

                        if (
                            abs(
                                l -
                                    luminance(p)
                            ) > 26
                        ) {
                            motion++
                        }
                    }

                    samples++
                    x += sx
                }

                y += sy
            }

            if (samples == 0) {
                return BandMetrics(
                    0f,
                    0f,
                    0f,
                    0f
                )
            }

            return BandMetrics(
                edge =
                    edge.toFloat() /
                        samples,
                strongEdge =
                    strongEdge.toFloat() /
                        samples,
                motion =
                    motion.toFloat() /
                        samples,
                darkness =
                    dark.toFloat() /
                        samples
            )
        }

        private fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f

            val sorted =
                values.sorted()

            return sorted[
                sorted.size / 2
            ]
        }

        private fun decision(
            action: Action,
            danger: Float,
            laneChange: Boolean,
            random: Random
        ): Decision {

            /*
             * High danger = faster reaction.
             * Small random variation preserves non-identical timing,
             * but randomness never selects the direction.
             */
            val reaction =
                if (danger >= 0.78f) {
                    random.nextLong(72L, 125L)
                } else if (danger >= 0.65f) {
                    random.nextLong(90L, 155L)
                } else {
                    random.nextLong(110L, 190L)
                }

            val cooldown =
                if (laneChange) {
                    random.nextLong(210L, 300L)
                } else {
                    random.nextLong(180L, 260L)
                }

            val gesture =
                random.nextLong(85L, 135L)

            return Decision(
                action = action,
                confidence =
                    danger.coerceIn(0f, 1f),
                cooldownMs = cooldown,
                nextCaptureMs = reaction,
                gestureMs = gesture
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

    /*
     * ------------------------------------------------------------
     * GESTURE CONTROLLER
     * ------------------------------------------------------------
     */

    private fun performDecision(
        decision: Decision,
        width: Int,
        height: Int
    ) {
        if (!enabled || !controllerActive || !gameForeground || durationExpired()) return
        if (gestureBusy) return

        val now = SystemClock.elapsedRealtime()
        val startX = if (playerConfidence >= 0.34f) {
            playerCenterX.toInt().coerceIn((width * 0.12f).toInt(), (width * 0.88f).toInt())
        } else {
            (width * 0.50f).toInt()
        }
        val startY = (height * 0.80f).toInt()

        when (decision.action) {
            Action.LEFT -> {
                if (currentLane <= 0) return
                pendingLaneTarget = currentLane - 1
                pendingLaneRequestedAt = now
                pendingLaneAttempts = 0
                gestureBusy = true
                dispatchHorizontalSwipe(-1, width, height, decision.gestureMs)
            }
            Action.RIGHT -> {
                if (currentLane >= 2) return
                pendingLaneTarget = currentLane + 1
                pendingLaneRequestedAt = now
                pendingLaneAttempts = 0
                gestureBusy = true
                dispatchHorizontalSwipe(1, width, height, decision.gestureMs)
            }
            Action.JUMP -> {
                gestureBusy = true
                dispatchVerticalSwipe(1, startX, startY, width, height, decision.gestureMs)
            }
            Action.ROLL -> {
                gestureBusy = true
                dispatchVerticalSwipe(-1, startX, startY, width, height, decision.gestureMs)
            }
            Action.TAP -> {
                gestureBusy = true
                tap(width / 2, (height * 0.58f).toInt())
                handler.postDelayed({ gestureBusy = false }, 140L)
            }
            Action.NONE -> return
        }
        lastActionAt = now
    }

    private fun dispatchHorizontalSwipe(
        direction: Int,
        width: Int,
        height: Int,
        duration: Long
    ) {
        val startX = if (playerConfidence >= 0.34f) {
            playerCenterX.toInt().coerceIn((width * 0.15f).toInt(), (width * 0.85f).toInt())
        } else {
            (width * 0.50f).toInt()
        }
        val y = (height * 0.80f).toInt()
        val distance = (width * 0.34f).toInt()
        val endX = (startX + direction * distance).coerceIn(4, width - 4)

        val path = Path().apply {
            moveTo(startX.toFloat(), y.toFloat())
            lineTo(endX.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, max(80L, duration)))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // Completion only means AccessibilityService completed the
                    // gesture. Lane movement is still verified visually.
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    pendingLaneRequestedAt = SystemClock.elapsedRealtime()
                }
            },
            null
        )

        if (!accepted) {
            gestureBusy = false
            pendingLaneTarget = null
            pendingLaneAttempts = 0
        }
    }

    private fun dispatchVerticalSwipe(
        direction: Int,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        duration: Long
    ) {
        val distance = (height * 0.32f).toInt()
        val endY = (startY - direction * distance).coerceIn((height * 0.18f).toInt(), (height * 0.96f).toInt())
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(startX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, max(80L, duration)))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({ gestureBusy = false }, 110L)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                }
            },
            null
        )
        if (!accepted) gestureBusy = false
    }

    private fun laneCenterX(
        lane: Int,
        width: Int
    ): Int {

        return when (lane) {
            0 -> (width * 0.27f).toInt()
            2 -> (width * 0.73f).toInt()
            else -> (width * 0.50f).toInt()
        }
    }

    private fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Long
    ) {

        val path =
            Path().apply {
                moveTo(
                    x1.toFloat(),
                    y1.toFloat()
                )

                /*
                 * Slightly curved path.
                 * Direction remains the actual decision.
                 */
                val controlX =
                    (x1 + x2) / 2f

                val controlY =
                    (
                        min(y1, y2) -
                            abs(y2 - y1) * 0.06f
                        )

                quadTo(
                    controlX,
                    controlY,
                    x2.toFloat(),
                    y2.toFloat()
                )
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        duration
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
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
