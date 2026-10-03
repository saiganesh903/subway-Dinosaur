# Human Surfer 1.0

Android app that launches Subway Surfers, performs adaptive swipe gestures through an AccessibilityService, and optionally streams the device screen to one or two RTMP/RTMPS endpoints.

## What this version includes

- Play duration: 1, 5, 10, 30, 60 minutes, or Unlimited.
- Unlimited means the bot keeps running until the user presses STOP, the game leaves the foreground, or Android stops the service/session.
- Human-like timing variation rather than a fixed macro sequence.
- Context-dependent lane selection based on visual risk scores.
- Occasional low-risk route variation.
- Left/right lane changes, jump and roll gestures.
- YouTube-only streaming.
- Facebook-only streaming.
- YouTube + Facebook simultaneously through one encoded stream with two RTMP outputs.
- Internal game audio capture on Android 10+ where the game permits playback capture.
- Foreground service notification while streaming.

## Important streaming detail

Do NOT paste a public YouTube/Facebook channel or watch URL. The app needs the encoder/RTMP(S) endpoint that includes the stream key, or an endpoint in the exact form required by the platform.

YouTube's encoder workflow uses a Stream URL plus Stream Key; RTMPS is supported and recommended by YouTube. See the official YouTube Live encoder documentation.

## Build

1. Open this folder in Android Studio.
2. Let Gradle sync. Internet access is required because RootEncoder is pulled from JitPack.
3. Build and install the debug APK.
4. Install Subway Surfers from Google Play.
5. Open Human Surfer.
6. Tap Enable accessibility service and enable Human Surfer.
7. Select duration.
8. Optionally select YouTube/Facebook and enter the RTMPS endpoint.
9. Tap START HUMAN SURFER.
10. If streaming is selected, Android will show the screen-capture consent dialog. Approve it.

## Current bot vision

The included vision engine is deliberately self-contained and uses Android screenshots plus lightweight pixel/edge/motion heuristics. It is NOT a trained Subway Surfers object-detection model. Therefore this is a functional baseline rather than a guaranteed high-score player.

For a stronger production bot, replace GameBot.obstacleLikelihood() with a trained detector/classifier for the current Subway Surfers build. The action layer and streaming layer can remain unchanged.

## Platform requirements

- Android 11+ for this build.
- Android AccessibilityService permission.
- Android screen-capture consent for streaming.
- Internet connection for live streaming.
- YouTube/Facebook account eligibility and live-stream setup as required by those platforms.

## Safety / platform use

Use the app on your own device and accounts and follow the game/platform rules. Do not expose your stream keys in screenshots, source control, or public posts.
