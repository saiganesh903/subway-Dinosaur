package com.ganesh.humansurfer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Color
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private enum class Action {
        NONE,
        LEFT,
        RIGHT,
        JUMP,
        ROLL,
        HOVERBOARD,
        TAP
    }

    private data class Decision(
        val action: Action,
        val confidence: Float,
        val cooldownMs: Long,
        val nextCaptureMs: Long,
        val gestureMs: Long
    )

    private data class LaneInfo(
        val risk: Float,
        val motion: Float,
        val edges: Float,
        val darkness: Float,
        val centerBrightness: Float
    )

    private val handler = Handler(Looper.getMainLooper())

    private val random = Random(System.currentTimeMillis())

    private var controllerActive = false

    private var state = BotState.WAITING_FOR_GAME

    private var lastScreenshotAt = 0L
    private var lastActionAt = 0L
    private var lastRecoveryActionAt = 0L
    private var lastStartTapAt = 0L
    private var lastPopupActionAt = 0L

    private var lastFrame: Bitmap? = null
    private var previousFrame: Bitmap? = null

    private var currentLane = 1

    private var missedFrames = 0

    private var gameForeground = false

    private var gameStartedAt = 0L

    private var consecutiveGameplayFrames = 0

    private var consecutiveStaticFrames = 0

    private var deathRecoveryCount = 0

    private var lastKnownScoreScreen = false

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

            requestedStartTime =
                SystemClock.elapsedRealtime()

            enabled = true

            resetSession()

            beginController()
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        if (event == null) return

        val packageName =
            event.packageName?.toString()

        if (packageName == GAME_PACKAGE) {
            gameForeground = true

            if (enabled) {
                beginController()
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

    private fun resetSession() {

        state = BotState.WAITING_FOR_GAME

        currentLane = 1

        missedFrames = 0

        consecutiveGameplayFrames = 0

        consecutiveStaticFrames = 0

        deathRecoveryCount = 0

        lastScreenshotAt = 0L
        lastActionAt = 0L
        lastRecoveryActionAt = 0L
        lastStartTapAt = 0L
        lastPopupActionAt = 0L

        gameStartedAt = 0L

        lastKnownScoreScreen = false

        previousFrame?.recycle()
        previousFrame = null

        lastFrame?.recycle()
        lastFrame = null
    }

    private fun beginController() {

        if (!enabled) return

        if (controllerActive) return

        controllerActive = true

        captureNextFrame(250L)
    }

    private fun stopController() {

        controllerActive = false

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
            SystemClock.elapsedRealtime() -
                requestedStartTime

        return elapsed >=
            requestedDurationMinutes * 60_000L
    }

    private fun captureNextFrame(delayMs: Long) {

        if (!controllerActive ||
            !enabled ||
            durationExpired()
        ) {
            stopController()
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        val minimumFrameGap =
            130L

        val wait =
            max(
                delayMs,
                minimumFrameGap -
                    (now - lastScreenshotAt)
            )

        handler.postDelayed(
            {
                takeGameScreenshot()
            },
            wait
        )
    }

    private fun takeGameScreenshot() {

        if (!controllerActive ||
            !enabled ||
            durationExpired()
        ) {
            stopController()
            return
        }

        if (!gameForeground) {

            state = BotState.WAITING_FOR_GAME

            captureNextFrame(500L)

            return
        }

        if (Build.VERSION.SDK_INT < 30) {

            stopController()

            return
        }

        lastScreenshotAt =
            SystemClock.elapsedRealtime()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot: ScreenshotResult
                ) {

                    val buffer:
                        HardwareBuffer =
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
                            if (missedFrames > 3) {
                                400L
                            } else {
                                200L
                            }
                        )

                        return
                    }

                    previousFrame?.recycle()

                    previousFrame = lastFrame

                    lastFrame = bitmap

                    missedFrames = 0

                    processFrame(bitmap)

                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    missedFrames++

                    captureNextFrame(
                        if (missedFrames > 3) {
                            400L
                        } else {
                            200L
                        }
                    )
                }
            }
        )
    }

    private fun processFrame(bitmap: Bitmap) {

        if (!gameForeground) {

            state =
                BotState.WAITING_FOR_GAME

            captureNextFrame(500L)

            return
        }

        /*
         * FIRST PRIORITY:
         *
         * Handle Android/game UI before
         * attempting gameplay gestures.
         */
        if (handleVisibleControls()) {

            state =
                BotState.RECOVERING

            captureNextFrame(350L)

            return
        }

        /*
         * Detect whether the screen looks
         * like an active gameplay frame.
         */
        val gameplayScore =
            detectGameplayScore(
                bitmap,
                previousFrame
            )

        /*
         * If the screen is changing enough
         * to look like active gameplay,
         * move into PLAYING state.
         */
        if (gameplayScore > 0.52f) {

            consecutiveGameplayFrames++

            consecutiveStaticFrames = 0

        } else {

            consecutiveStaticFrames++

            consecutiveGameplayFrames = 0
        }

        /*
         * If gameplay has been observed
         * repeatedly, we trust that the
         * actual run has started.
         */
        if (consecutiveGameplayFrames >= 3) {

            if (state != BotState.PLAYING) {

                state = BotState.PLAYING

                if (gameStartedAt == 0L) {
                    gameStartedAt =
                        SystemClock.elapsedRealtime()
                }
            }
        }

        /*
         * Static game screen:
         *
         * attempt a safe center tap after
         * waiting for UI to settle.
         *
         * This handles:
         *
         * Tap to play
         * Play
         * Continue
         * Start
         */
        if (state != BotState.PLAYING) {

            attemptStartTap(
                bitmap.width,
                bitmap.height
            )

            captureNextFrame(500L)

            return
        }

        /*
         * Gameplay brain.
         */
        val decision =
            GameBrain.decide(
                bitmap = bitmap,
                previous = previousFrame,
                currentLane = currentLane,
                random = random
            )

        if (decision != null) {

            val now =
                SystemClock.elapsedRealtime()

            if (
                now - lastActionAt >=
                decision.cooldownMs
            ) {

                performDecision(
                    decision,
                    bitmap.width,
                    bitmap.height
                )
            }

            captureNextFrame(
                decision.nextCaptureMs
            )

        } else {

            captureNextFrame(160L)
        }
    }

    /*
     * ---------------------------------------------------------
     * UI / POPUP / RECOVERY CONTROLLER
     * ---------------------------------------------------------
     */

    private fun handleVisibleControls(): Boolean {

        val root =
            rootInActiveWindow
                ?: return false

        if (
            SystemClock.elapsedRealtime() -
            lastPopupActionAt < 800L
        ) {
            return false
        }

        /*
         * First handle explicit dismiss controls.
         */
        val dismissNode =
            findNodeByTextOrDescription(
                root,
                listOf(
                    "close",
                    "dismiss",
                    "skip",
                    "no thanks",
                    "not now",
                    "cancel",
                    "×",
                    "x"
                )
            )

        if (dismissNode != null) {

            if (clickNodeSafely(dismissNode)) {

                lastPopupActionAt =
                    SystemClock.elapsedRealtime()

                return true
            }
        }

        /*
         * Handle game-over/restart controls.
         */
        val restartNode =
            findNodeByTextOrDescription(
                root,
                listOf(
                    "play again",
                    "retry",
                    "restart",
                    "try again",
                    "play",
                    "continue",
                    "tap to play",
                    "tap to continue"
                )
            )

        if (restartNode != null) {

            if (clickNodeSafely(restartNode)) {

                deathRecoveryCount++

                lastRecoveryActionAt =
                    SystemClock.elapsedRealtime()

                lastPopupActionAt =
                    SystemClock.elapsedRealtime()

                currentLane = 1

                state =
                    BotState.RECOVERING

                consecutiveGameplayFrames = 0

                consecutiveStaticFrames = 0

                return true
            }
        }

        /*
         * Sometimes the game exposes no
         * useful accessibility text.
         *
         * In that case we use the visual
         * controller's recovery logic later.
         */
        return false
    }

    private fun findNodeByTextOrDescription(
        root: AccessibilityNodeInfo,
        phrases: List<String>
    ): AccessibilityNodeInfo? {

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node =
                queue.removeFirst()

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

                val target =
                    phrase.lowercase()

                if (
                    text == target ||
                    description == target ||
                    text.contains(target) ||
                    description.contains(target) ||
                    resource.contains(
                        target.replace(" ", "_")
                    )
                ) {

                    if (
                        node.isVisibleToUser
                    ) {
                        return node
                    }
                }
            }

            for (
                index in 0 until node.childCount
            ) {

                node.getChild(index)
                    ?.let {
                        queue.add(it)
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
                    AccessibilityNodeInfo
                        .ACTION_CLICK
                )
            ) {
                return true
            }

            /*
             * Some game/overlay controls
             * don't expose ACTION_CLICK.
             *
             * Fall back to tapping the
             * node's screen bounds.
             */
            val rect =
                android.graphics.Rect()

            node.getBoundsInScreen(rect)

            if (
                rect.width() > 0 &&
                rect.height() > 0
            ) {

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

    private fun attemptStartTap(
        width: Int,
        height: Int
    ) {

        val now =
            SystemClock.elapsedRealtime()

        /*
         * Don't repeatedly hammer the
         * screen.
         */
        if (
            now - lastStartTapAt < 1800L
        ) {
            return
        }

        /*
         * Give the game time to settle.
         */
        if (
            gameStartedAt != 0L &&
            now - gameStartedAt < 1000L
        ) {
            return
        }

        /*
         * Start/tap zone.
         *
         * We deliberately avoid the
         * very top/bottom UI areas.
         */
        val x =
            width / 2

        val y =
            (height * 0.58f).toInt()

        tap(x, y)

        lastStartTapAt = now

        state =
            BotState.STARTING
    }

    /*
     * ---------------------------------------------------------
     * GAMEPLAY GESTURE CONTROLLER
     * ---------------------------------------------------------
     */

    private fun performDecision(
        decision: Decision,
        width: Int,
        height: Int
    ) {

        val now =
            SystemClock.elapsedRealtime()

        val centerY =
            (height * 0.70f).toInt()

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

                } else {

                    /*
                     * Can't go left.
                     *
                     * Prefer vertical action
                     * rather than wasting a
                     * gesture.
                     */
                    swipe(
                        laneX,
                        centerY,
                        laneX,
                        (height * 0.42f).toInt(),
                        decision.gestureMs
                    )
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

                } else {

                    swipe(
                        laneX,
                        centerY,
                        laneX,
                        (height * 0.42f).toInt(),
                        decision.gestureMs
                    )
                }
            }

            Action.JUMP -> {

                swipe(
                    laneX,
                    centerY,
                    laneX,
                    (height * 0.38f).toInt(),
                    decision.gestureMs
                )
            }

            Action.ROLL -> {

                swipe(
                    laneX,
                    centerY,
                    laneX,
                    (height * 0.91f).toInt(),
                    decision.gestureMs
                )
            }

            Action.HOVERBOARD -> {

                /*
                 * Subway Surfers hoverboard
                 * activation is a double tap.
                 */
                doubleTap(
                    laneX,
                    centerY
                )
            }

            Action.TAP -> {

                tap(
                    width / 2,
                    (height * 0.58f).toInt()
                )
            }

            Action.NONE -> {
                return
            }
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
                 * Slightly curved movement
                 * instead of perfectly straight
                 * machine-like movement.
                 */
                quadTo(
                    (
                        (x1 + x2) / 2f
                    ),
                    (
                        min(y1, y2) -
                            abs(y2 - y1) * 0.08f
                    ),
                    x2.toFloat(),
                    y2.toFloat()
                )
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0,
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
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0,
                            70L
                        )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun doubleTap(
        x: Int,
        y: Int
    ) {

        val path1 =
            Path().apply {
                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val path2 =
            Path().apply {
                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val builder =
            GestureDescription.Builder()

        builder.addStroke(
            GestureDescription
                .StrokeDescription(
                    path1,
                    0L,
                    55L
                )
        )

        builder.addStroke(
            GestureDescription
                .StrokeDescription(
                    path2,
                    140L,
                    55L
                )
        )

        dispatchGesture(
            builder.build(),
            null,
            null
        )
    }

    /*
     * ---------------------------------------------------------
     * GAMEPLAY DETECTION
     * ---------------------------------------------------------
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

        val width =
            bitmap.width

        val height =
            bitmap.height

        val y0 =
            (height * 0.20f).toInt()

        val y1 =
            (height * 0.90f).toInt()

        var samples = 0

        var variation = 0

        var previousDifference = 0

        val stepX =
            max(10, width / 40)

        val stepY =
            max(12, height / 45)

        var y = y0

        while (y < y1) {

            var x = stepX

            while (x < width - stepX) {

                val c =
                    bitmap.getPixel(x, y)

                val right =
                    bitmap.getPixel(
                        min(
                            width - 1,
                            x + stepX
                        ),
                        y
                    )

                val down =
                    bitmap.getPixel(
                        x,
                        min(
                            height - 1,
                            y + stepY
                        )
                    )

                val l =
                    luminance(c)

                val lr =
                    luminance(right)

                val ld =
                    luminance(down)

                if (
                    abs(l - lr) > 30 ||
                    abs(l - ld) > 30
                ) {
                    variation++
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
                        ) > 25
                    ) {
                        previousDifference++
                    }
                }

                samples++

                x += stepX
            }

            y += stepY
        }

        if (samples == 0) {
            return 0f
        }

        val structural =
            variation.toFloat() /
                samples.toFloat()

        val movement =
            previousDifference.toFloat() /
                samples.toFloat()

        return (
            structural * 0.55f +
                movement * 0.90f
            ).coerceIn(
                0f,
                1f
            )
    }

    private fun luminance(
        color: Int
    ): Int {

        return (
            Color.red(color) * 299 +
                Color.green(color) * 587 +
                Color.blue(color) * 114
            ) / 1000
    }

    /*
     * ---------------------------------------------------------
     * GAME BRAIN
     * ---------------------------------------------------------
     */

    private object GameBrain {

        fun decide(
            bitmap: Bitmap,
            previous: Bitmap?,
            currentLane: Int,
            random: Random
        ): Decision? {

            if (
                bitmap.width < 200 ||
                bitmap.height < 200
            ) {
                return null
            }

            val lanes =
                Array(3) {
                    analyzeLane(
                        bitmap,
                        previous,
                        it
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

            /*
             * Strong danger in current lane.
             */
            if (current.risk > 0.68f) {

                val leftSafe =
                    left != null &&
                        left.risk < 0.48f

                val rightSafe =
                    right != null &&
                        right.risk < 0.48f

                /*
                 * Choose the safer adjacent lane.
                 */
                if (
                    leftSafe &&
                    rightSafe
                ) {

                    /*
                     * Don't always choose
                     * the numerically safer
                     * lane if the difference
                     * is tiny.
                     */
                    if (
                        abs(
                            left!!.risk -
                                right!!.risk
                        ) < 0.10f
                    ) {

                        return if (
                            random.nextBoolean()
                        ) {
                            decision(
                                Action.LEFT,
                                0.76f,
                                random
                            )
                        } else {
                            decision(
                                Action.RIGHT,
                                0.76f,
                                random
                            )
                        }
                    }

                    return if (
                        left!!.risk <
                            right!!.risk
                    ) {
                        decision(
                            Action.LEFT,
                            0.90f,
                            random
                        )
                    } else {
                        decision(
                            Action.RIGHT,
                            0.90f,
                            random
                        )
                    }
                }

                if (leftSafe) {

                    return decision(
                        Action.LEFT,
                        0.91f,
                        random
                    )
                }

                if (rightSafe) {

                    return decision(
                        Action.RIGHT,
                        0.91f,
                        random
                    )
                }

                /*
                 * No adjacent lane looks safe.
                 *
                 * Try a vertical movement.
                 *
                 * This is intentionally not
                 * random every time.
                 */
                return if (
                    current.edges >
                        0.25f
                ) {

                    decision(
                        Action.JUMP,
                        0.72f,
                        random
                    )

                } else {

                    decision(
                        Action.ROLL,
                        0.65f,
                        random
                    )
                }
            }

            /*
             * Medium risk:
             *
             * Begin positioning before the
             * obstacle becomes critical.
             */
            if (
                current.risk > 0.50f
            ) {

                val safeLeft =
                    left != null &&
                        left.risk <
                        current.risk - 0.08f

                val safeRight =
                    right != null &&
                        right.risk <
                        current.risk - 0.08f

                if (
                    safeLeft &&
                    safeRight
                ) {

                    return if (
                        left!!.risk <=
                            right!!.risk
                    ) {

                        decision(
                            Action.LEFT,
                            0.71f,
                            random
                        )

                    } else {

                        decision(
                            Action.RIGHT,
                            0.71f,
                            random
                        )
                    }
                }

                if (safeLeft) {

                    return decision(
                        Action.LEFT,
                        0.73f,
                        random
                    )
                }

                if (safeRight) {

                    return decision(
                        Action.RIGHT,
                        0.73f,
                        random
                    )
                }
            }

            /*
             * Low danger.
             *
             * Do NOT constantly move.
             *
             * Real gameplay needs periods
             * of simply staying in a lane.
             */
            if (
                current.risk < 0.34f
            ) {

                /*
                 * Small probability of
                 * repositioning if another
                 * lane looks significantly
                 * better.
                 */
                val betterLeft =
                    left != null &&
                        left.risk <
                        current.risk - 0.18f

                val betterRight =
                    right != null &&
                        right.risk <
                        current.risk - 0.18f

                if (
                    betterLeft &&
                    random.nextFloat() < 0.12f
                ) {

                    return decision(
                        Action.LEFT,
                        0.58f,
                        random
                    )
                }

                if (
                    betterRight &&
                    random.nextFloat() < 0.12f
                ) {

                    return decision(
                        Action.RIGHT,
                        0.58f,
                        random
                    )
                }

                return Decision(
                    Action.NONE,
                    0.50f,
                    240L +
                        random.nextLong(
                            0,
                            160
                        ),
                    130L +
                        random.nextLong(
                            0,
                            80
                        ),
                    110L
                )
            }

            /*
             * Moderate uncertainty.
             *
             * Keep observing rather than
             * making unnecessary movements.
             */
            return Decision(
                Action.NONE,
                0.45f,
                220L +
                    random.nextLong(
                        0,
                        120
                    ),
                130L +
                    random.nextLong(
                        0,
                        80
                    ),
                110L
            )
        }

        private fun analyzeLane(
            bitmap: Bitmap,
            previous: Bitmap?,
            lane: Int
        ): LaneInfo {

            val width =
                bitmap.width

            val height =
                bitmap.height

            val x0 =
                (width *
                    (lane / 3.0)
                ).toInt()

            val x1 =
                (width *
                    ((lane + 1) / 3.0)
                ).toInt()

            /*
             * The lower portion is more
             * important because obstacles
             * closer to the player appear
             * there.
             */
            val y0 =
                (height * 0.36f)
                    .toInt()

            val y1 =
                (height * 0.88f)
                    .toInt()

            val stepX =
                max(
                    7,
                    (x1 - x0) / 26
                )

            val stepY =
                max(
                    8,
                    (y1 - y0) / 34
                )

            var samples = 0

            var edgeCount = 0

            var strongEdgeCount = 0

            var darkCount = 0

            var motionCount = 0

            var brightnessTotal = 0

            var y = y0

            while (
                y < y1 - stepY
            ) {

                var x =
                    x0 + stepX

                while (
                    x < x1 - stepX
                ) {

                    val pixel =
                        bitmap.getPixel(
                            x,
                            y
                        )

                    val right =
                        bitmap.getPixel(
                            min(
                                width - 1,
                                x + stepX
                            ),
                            y
                        )

                    val down =
                        bitmap.getPixel(
                            x,
                            min(
                                height - 1,
                                y + stepY
                            )
                        )

                    val lum =
                        luminance(pixel)

                    val rightLum =
                        luminance(right)

                    val downLum =
                        luminance(down)

                    val gradient =
                        abs(
                            lum -
                                rightLum
                        ) +
                            abs(
                                lum -
                                    downLum
                            )

                    if (
                        gradient > 38
                    ) {
                        edgeCount++
                    }

                    if (
                        gradient > 76
                    ) {
                        strongEdgeCount++
                    }

                    if (
                        lum < 55
                    ) {
                        darkCount++
                    }

                    brightnessTotal += lum

                    if (
                        previous != null &&
                        x < previous.width &&
                        y < previous.height
                    ) {

                        val previousPixel =
                            previous.getPixel(
                                x,
                                y
                            )

                        val previousLum =
                            luminance(
                                previousPixel
                            )

                        if (
                            abs(
                                lum -
                                    previousLum
                            ) > 30
                        ) {
                            motionCount++
                        }
                    }

                    samples++

                    x += stepX
                }

                y += stepY
            }

            if (samples == 0) {

                return LaneInfo(
                    0f,
                    0f,
                    0f,
                    0f,
                    0f
                )
            }

            val edges =
                edgeCount.toFloat() /
                    samples

            val strongEdges =
                strongEdgeCount.toFloat() /
                    samples

            val darkness =
                darkCount.toFloat() /
                    samples

            val motion =
                if (previous != null) {
                    motionCount.toFloat() /
                        samples
                } else {
                    0f
                }

            val brightness =
                brightnessTotal.toFloat() /
                    samples /
                    255f

            /*
             * Risk isn't simply "dark = obstacle".
             *
             * Combine multiple visual signals.
             */
            val risk =
                (
                    edges * 0.38f +
                        strongEdges * 0.24f +
                        darkness * 0.12f +
                        motion * 0.52f
                    ).coerceIn(
                        0f,
                        1f
                    )

            return LaneInfo(
                risk = risk,
                motion = motion,
                edges = edges,
                darkness = darkness,
                centerBrightness = brightness
            )
        }

        private fun decision(
            action: Action,
            confidence: Float,
            random: Random
        ): Decision {

            /*
             * Human-like timing variation.
             *
             * Importantly, variation is applied
             * AFTER the visual decision rather
             * than replacing the decision.
             */
            val reaction =
                when {
                    confidence > 0.85f ->
                        random.nextLong(
                            120L,
                            240L
                        )

                    confidence > 0.70f ->
                        random.nextLong(
                            170L,
                            310L
                        )

                    else ->
                        random.nextLong(
                            220L,
                            380L
                        )
                }

            val cooldown =
                random.nextLong(
                    230L,
                    410L
                )

            val gesture =
                random.nextLong(
                    90L,
                    160L
                )

            return Decision(
                action = action,
                confidence = confidence,
                cooldownMs = cooldown,
                nextCaptureMs = reaction,
                gestureMs = gesture
            )
        }

        private fun luminance(
            color: Int
        ): Int {

            return (
                Color.red(color) * 299 +
                    Color.green(color) * 587 +
                    Color.blue(color) * 114
                ) / 1000
        }
    }
}
