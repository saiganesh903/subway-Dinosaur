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
        val approach: Float
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

        val packageName = event.packageName?.toString()

        if (packageName == GAME_PACKAGE) {
            /*
             * The game is the only window we are allowed to control.
             */
            gameForeground = true

            if (enabled) {
                beginController()
            }
        } else if (packageName != null && gameForeground) {
            /*
             * The game lost foreground focus.
             *
             * IMPORTANT: stop all pending screenshot callbacks immediately.
             * Otherwise a previously scheduled frame can dispatch a swipe
             * after Subway Surfers has already closed or an ad/system screen
             * has appeared.
             */
            gameForeground = false
            stopController()
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

    private fun resetSession() {
        state = BotState.WAITING_FOR_GAME
        currentLane = 1
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

            if (
                bitmap.width < 300 ||
                bitmap.height < 500
            ) {
                return null
            }

            val observations =
                Array(3) { lane ->
                    analyzeLane(
                        bitmap,
                        previous,
                        lane
                    )
                }

            val rawValues =
                observations.map {
                    it.rawRisk
                }

            val baseline =
                median(rawValues)

            val lanes =
                observations.map {
                    it.copy(
                        risk = (
                            0.50f +
                                (it.rawRisk - baseline) *
                                2.80f
                            ).coerceIn(0f, 1f)
                    )
                }

            val current =
                lanes[currentLane]

            val left =
                if (currentLane > 0) {
                    lanes[currentLane - 1]
                } else {
                    null
                }

            val right =
                if (currentLane < 2) {
                    lanes[currentLane + 1]
                } else {
                    null
                }

            val laneChangeAllowed =
                now - lastLaneChangeAt >= 430L

            /*
             * SAFETY RULE 1:
             * If the current lane is becoming dangerous and one adjacent
             * lane is clearly safer, change early.
             */
            if (
                laneChangeAllowed &&
                current.risk >= 0.57f
            ) {

                val leftRisk =
                    left?.risk ?: 1f

                val rightRisk =
                    right?.risk ?: 1f

                val leftSafe =
                    left != null &&
                        leftRisk + 0.08f <
                        current.risk

                val rightSafe =
                    right != null &&
                        rightRisk + 0.08f <
                        current.risk

                if (leftSafe && rightSafe) {
                    return if (
                        leftRisk <= rightRisk
                    ) {
                        decision(
                            Action.LEFT,
                            current.risk,
                            true,
                            random
                        )
                    } else {
                        decision(
                            Action.RIGHT,
                            current.risk,
                            true,
                            random
                        )
                    }
                }

                if (leftSafe) {
                    return decision(
                        Action.LEFT,
                        current.risk,
                        true,
                        random
                    )
                }

                if (rightSafe) {
                    return decision(
                        Action.RIGHT,
                        current.risk,
                        true,
                        random
                    )
                }
            }

            /*
             * SAFETY RULE 2:
             * If collision risk is already high, choose the safest
             * adjacent lane even when the difference is small.
             */
            if (
                laneChangeAllowed &&
                current.risk >= 0.72f
            ) {

                val leftRisk =
                    left?.risk ?: 1f

                val rightRisk =
                    right?.risk ?: 1f

                if (
                    left != null &&
                    leftRisk < current.risk
                ) {
                    if (
                        right == null ||
                        leftRisk <= rightRisk
                    ) {
                        return decision(
                            Action.LEFT,
                            current.risk,
                            true,
                            random
                        )
                    }
                }

                if (
                    right != null &&
                    rightRisk < current.risk
                ) {
                    return decision(
                        Action.RIGHT,
                        current.risk,
                        true,
                        random
                    )
                }
            }

            /*
             * SAFETY RULE 3:
             * If both lanes look bad, use a vertical action only when
             * the image suggests a vertical collision is approaching.
             *
             * Lower-heavy structure -> jump.
             * Upper/mid-heavy structure -> roll.
             */
            if (current.risk >= 0.66f) {

                val lowerObstacle =
                    current.nearEdge >
                        current.midEdge + 0.035f

                val upperObstacle =
                    current.midEdge >
                        current.nearEdge + 0.035f

                if (
                    lowerObstacle &&
                    current.approach > 0.025f
                ) {
                    return decision(
                        Action.JUMP,
                        current.risk,
                        false,
                        random
                    )
                }

                if (upperObstacle) {
                    return decision(
                        Action.ROLL,
                        current.risk,
                        false,
                        random
                    )
                }
            }

            /*
             * No convincing danger:
             * stay in lane.
             *
             * This is important. A bot that moves every few hundred ms
             * will eventually move into an obstacle.
             */
            return Decision(
                action = Action.NONE,
                confidence = 0.70f,
                cooldownMs = 180L,
                nextCaptureMs =
                    82L +
                        random.nextLong(0L, 28L),
                gestureMs = 110L
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
            val rawRisk =
                near.motion * 0.48f +
                    near.edge * 0.28f +
                    near.strongEdge * 0.16f +
                    mid.motion * 0.28f +
                    mid.edge * 0.18f +
                    near.darkness * 0.05f

            val approach =
                near.motion - mid.motion

            return LaneInfo(
                rawRisk = rawRisk.coerceIn(0f, 1f),
                risk = 0f,
                nearEdge = near.edge,
                midEdge = mid.edge,
                nearMotion = near.motion,
                midMotion = mid.motion,
                nearDark = near.darkness,
                approach = approach
            )
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

        /*
         * Last-line safety gate.
         * If the game is no longer foreground, absolutely no gameplay
         * gesture is allowed.
         */
        if (
            !enabled ||
            !controllerActive ||
            !gameForeground ||
            durationExpired()
        ) {
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        val centerY =
            (height * 0.73f).toInt()

        val laneX =
            laneCenterX(
                currentLane,
                width
            )

        when (decision.action) {

            Action.LEFT -> {
                if (currentLane > 0) {
                    swipe(
                        laneX,
                        centerY,
                        laneCenterX(
                            currentLane - 1,
                            width
                        ),
                        centerY,
                        decision.gestureMs
                    )

                    currentLane--
                    lastLaneChangeAt = now
                }
            }

            Action.RIGHT -> {
                if (currentLane < 2) {
                    swipe(
                        laneX,
                        centerY,
                        laneCenterX(
                            currentLane + 1,
                            width
                        ),
                        centerY,
                        decision.gestureMs
                    )

                    currentLane++
                    lastLaneChangeAt = now
                }
            }

            Action.JUMP -> {
                swipe(
                    laneX,
                    centerY,
                    laneX,
                    (height * 0.37f).toInt(),
                    decision.gestureMs
                )
            }

            Action.ROLL -> {
                swipe(
                    laneX,
                    centerY,
                    laneX,
                    (height * 0.92f).toInt(),
                    decision.gestureMs
                )
            }

            Action.TAP -> {
                tap(
                    width / 2,
                    (height * 0.58f).toInt()
                )
            }

            Action.NONE -> return
        }

        lastActionAt = now
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
