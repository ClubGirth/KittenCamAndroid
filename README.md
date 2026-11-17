# KittenCam for Android

Native Android port of [ClubGirth/KittenCamiOS](https://github.com/ClubGirth/KittenCamiOS), using the same local-network services and packet formats. Minimum Android 7.0 (API 24); primary physical test target: Samsung Galaxy S21 Ultra.

## Run on the S21 Ultra

1. Open this directory in Android Studio and let Gradle sync. Use Android Studio's bundled JDK (the project requests Java 25 for Gradle).
2. Enable Developer options and USB debugging on the phone, connect it by USB, and accept the debugging prompt.
3. Select the phone and run the `app` configuration. Alternatively, install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
4. Connect both phones to the same Wi-Fi network. Select **Viewing Device** on the phone that supplies the camera, and grant camera/microphone permissions. Select **Control Device** on the other phone and tap **Connect**. Either phone can run the iOS reference or this Android app.
5. Keep the app open while using a camera or watching feeds. Switching modes or leaving the foreground closes the connections and finalizes recordings, as in the reference's screen lifecycle.

The names deliberately follow the iOS app: **Viewing Device is the camera**, and **Control Device watches it**.

## Implemented behavior

- Dark gradient mode selection, camera preview/HUD, auto-hiding controls, zoom presets, pinch zoom, torch, microphone toggle, and video settings.
- Bonjour/mDNS discovery and advertisement for `_kittencam._tcp`, `_kittencam-video._tcp`, and `_kittencam-talk._tcp`, with dynamically allocated ports.
- HTTP `GET /status` and `POST /command`: `setZoom`, `smoothZoom`, `setTorch`, `setMicrophone`, and `setVideoFormat`. Status field names match iOS.
- Camera2 rear-camera capture directly into a hardware H.264 encoder surface; video configuration, keyframe flags, microsecond timestamps, Annex B frames, orientation metadata, and heartbeats follow protocol v1.
- Multiple live feeds, grid/single selection, fullscreen, local pinch/pan, portrait/landscape layouts, and per-feed camera controls.
- PCM16 live microphone monitoring and hold-to-talk audio, with monitoring muted while speaking. Talkback has the distinct five-byte framing used by iOS.
- Manual and motion-triggered MP4 recording with H.264 video and AAC camera audio, independent of live monitoring mute. Enable the remote microphone before starting a recording. Motion uses 32×18 luminance samples, the same four sensitivity thresholds, and a 1–600 second quiet-time cutoff (default 8 seconds).
- Finished recordings appear in the system Gallery/Photos library under `Movies/KittenCam Recordings`. Android 10+ uses MediaStore; Android 7–9 requests storage permission, saves in public Movies, and indexes each completed clip. Recordings are stored on the control device that records the feed.
- Runtime camera/microphone permissions and, on Android 17+, local network permission. Camera resources, sockets, discovery listeners, multicast locks, and recordings are closed when exiting a mode.

## Device-specific video settings

Android's camera settings and remote-camera control panel now use a shared capability model rather than fixed resolution/FPS menus. The camera checks public Camera2 capture and preview sizes, nominal exposure rates, per-size minimum frame durations, and the selected H.264 encoder's size/rate limits. The server uses the same list to validate format commands. A resolution change updates the FPS menu and, when necessary, chooses the nearest valid rate in the same command.

The app offers compatible presets from 720p, 1080p, 1440p, and 4K. If none work, it discovers a compatible lower-resolution fallback rather than exposing unusable presets. Available nominal rates come from reported camera exposure ranges, not phone-model names. A variable exposure range advertises its upper nominal rate; actual cadence may decrease in low light.

Both `GET /status` and stream-information packets advertise:

```json
{
  "supportedVideoFormats": [
    { "resolution": "1080p", "width": 1920, "height": 1080, "fps": [15, 20, 24, 30] }
  ]
}
```

Android controllers read this list from the connected camera. Identified legacy iOS cameras without capability metadata get the same candidate format controls as Windows; other unknown peers remain read-only. The iOS reporting patch enables exact capability filtering. Windows currently uses static format menus rather than reading `supportedVideoFormats`; this change does not modify its executable.

The installed S8+ build reports 720p/1080p/1440p with 15/20/24/30 fps. All ten device tests and fifteen unit tests passed; the actual settings menus were also inspected. See [capability test results](docs/testing/dynamic-formats-s8-results.txt).

## Device-specific minimum zoom

Both Android modes use the viewing camera's reported minimum for the left zoom preset. For example, a camera exposing 0.6× gets a 0.6× button; 1× and 3× remain as the other presets. Android reads each camera's native zoom range and discovers separately exposed wider rear lenses. A 0.6× preset can therefore switch to the real ultra-wide camera even when the main lens alone reports a 1× minimum. Single-camera digital-zoom devices retain their native minimum. The camera publishes `minimumZoom`/`maximumZoom` in status and stream information, and the controller's slider respects these bounds when supplied.

The controller updates the preset when selecting a different camera or receiving new capabilities. A recognized legacy iOS peer without zoom limits uses the 0.5× fallback; other unknown peers show an unavailable `Min` button. The existing iOS app needs [the compatibility patch](compatibility/README.md) to report its already-calculated bounds; that patch also updates its Viewing Device preset.

The S21 Ultra now exposes a combined 0.6×–8× display range. Its main camera reports 1×–8×, while a separate ultra-wide camera also calls its own optical view 1×. The app identifies that wider lens from focal length and sensor-size metadata and labels its relative stop 0.6×. Selecting it opens camera 2 on the tested S21 Ultra; 1× and 3× return to the main camera. No phone-model or camera-ID constants determine routing. Tests cover 0.5×, 0.6×, 0.625×, and 1× minima, preserving the exact requested minimum even when its display label is rounded.

## Hardware and parity limits

- This is a native Android rendering of the SwiftUI screens; iOS material blur, SF Symbols, and system sheet styling are approximated with Android controls.
- Public Camera2 capabilities determine available lenses, zoom ranges, frame rates, and resolutions. The default rear camera is the 1× reference. Separately exposed wider lenses are normalized from focal length/sensor width and their relative lens stops rounded to tenths. Requests below 1× switch cameras when needed; within each lens, native zoom/crop controls are used. Cameras not exposed to the app cannot be selected.
- Torch brightness is applied through the capture request only on Android 15+ devices that report strength control. Other devices support on/off and return a clear error for an unsupported brightness change. See [Android's capture request reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#FLASH_STRENGTH_LEVEL).
- Format changes stop any active recording. Actual camera-session/encoder support still needs physical-device verification for each resolution/FPS combination.
- Motion sampling uses displayed decoded frames. Keep a feed visible when using its motion trigger; hidden feeds continue receiving video and can record manually.
- The app implements the reference's local, unauthenticated TCP protocol. It has no account, cloud relay, or internet pairing.
- Android 17 permission behavior follows [Android's local-network permission guidance](https://developer.android.com/privacy-and-security/local-network-permission).

Lens changes keep the TCP servers and microphone stream alive. Camera/encoder surfaces are rebuilt for the selected lens, so a short preview pause is possible. The current resolution/FPS is retained when supported; otherwise a compatible format is selected and advertised. If the H.264 configuration changes, a controller finalizes its current recording as it already does for other format changes.

## Validation

From the project directory on this Mac:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Six protocol tests cover fragmented TCP input, adjacent packets, talkback framing, malformed lengths, truncation/version rejection, and three-/four-byte Annex B delimiters. The starter unit test also remains. Lint has no errors; remaining warnings concern localization, accessibility of touch gestures, and starter dependency/resource cleanup.

Three device integration tests passed on both an Android 15 emulator with an S24-sized display and a physical Galaxy S21 Ultra (SM-G998U1, Android 15). They exercised mode selection/rotation, all three mDNS services, live H.264 capture and decoding, hiding/restoring the decoder surface, remote zoom, microphone packets, opening/closing talkback, MP4 writing/read-back, and cleanup. The physical camera ran at 1080p/30. Loopback audio monitoring is muted to prevent acoustic feedback when both ends run on one phone.

The automated tests select only the camera hosted by the device under test, matching its service name and all three advertised ports. Existing iPhone/Windows sessions are not test targets.

See [physical test output](docs/testing/s21-integration-results.txt). The separate hardware-capability check also passed, along with an opt-in read-only iPhone interoperability test. The latter received and decoded the existing iOS camera feed and microphone packets without issuing camera commands or talkback. Audible two-device talkback, Windows-specific interoperability, motion sensitivity tuning, and sustained thermal/network testing remain manual checks.

### Galaxy S21 Ultra results (SM-G998U1, Android 15)

| Capability | Observed result |
| --- | --- |
| Default capture → network → decoder → MP4 | Passed at 1080p/30 |
| 720p, 1080p, 4K | Camera/encoder started at 24 and 30 fps |
| 1440p | Not exposed at 2560×1440 by the selected camera |
| 50 / 60 fps | Rejected by the selected camera's exposed frame durations |
| Zoom | Combined 0.6×–8×; 0.6× selects the separate ultra-wide camera |
| Torch | On/off passed; capture-request strength control unavailable |
| iPhone → Samsung | Video decoding, status read, and microphone packets passed |

These are capability and short integration checks, not sustained frame-rate, image-quality, thermal, or audio-quality benchmarks. Full results are in [the testing folder](docs/testing/README.md).

### Galaxy S8+ results (SM-G955U, Android 9)

All seven device tests passed, including the legacy recording-storage path, all four orientations, camera streaming, and read-only iPhone video/audio reception. This device exposed 720p/1080p/1440p at 24/30 fps, digital zoom 1×–8×, and torch on/off. 4K was not exposed by the selected camera. Frame-rate validation was tightened to reject 50/60 fps when the normal camera exposure controls cannot deliver them. See [S8+ results](docs/testing/s8-results.txt).

### Physical test checklist

- [ ] Launch, deny camera permission, and verify Control Device still works; then grant camera and microphone permissions.
- [ ] Android camera → iOS controller: discovery, picture orientation, microphone, zoom, torch, and remote format commands.
- [ ] iOS camera → Android controller: discovery, video/audio playback, format changes, and hold-to-talk.
- [ ] Android camera → Android controller, then two cameras together; switch grid/single/fullscreen and rotate both phones.
- [ ] Record and play an MP4 from the Gallery; verify orientation and that Stop, Disconnect, and leaving the app finalize it.
- [ ] Enable each motion sensitivity, trigger movement, and verify recording stops after the configured quiet interval.
- [ ] Test 720p/1080p/1440p/4K and 24/30/50/60 FPS; confirm unsupported combinations report an error.
- [ ] Disconnect Wi-Fi, leave/return to modes, and verify cameras/sockets are released and a new connection succeeds.

### Controller controls and audio update

New connections start with audio monitoring off. Monitoring is enabled separately for each feed. Hold to talk captures the controller microphone and routes received speech to the camera phone’s built-in speaker; it pauses monitoring while talking. Check the viewing phone’s media volume if speech is inaudible. Touch and hold to speak, release to stop; keyboard and accessibility activation toggle speech on/off.

The selected feed has a turquoise outline. In single-feed mode, the camera-name strip switches the visible feed. Each feed’s fullscreen button also exits fullscreen. Fullscreen includes compact torch, talk, and audio buttons with 48 dp touch targets and accessibility labels. The supplied logo is used for adaptive and legacy launcher icons.

The updated debug build passed build, unit tests, and lint. Six focused controller/audio tests passed on the physical S8+, including real microphone packets, rapid talk restart, scroll-safe holding, built-in speaker playback, feed selection, and fullscreen controls. These checks do not establish audible S8+ → S21 Ultra talkback quality; both phones need the updated build for that acceptance check.

Legacy iOS cameras identified by an iPhone/iPad/iPod name or explicit iOS/iPadOS platform now get an enabled 0.5× minimum preset when zoom-limit metadata is missing. Reported limits always take priority, and the iOS camera clamps commands to its supported zoom. This is a fallback, not a measured capability. A renamed iOS camera without an Apple device name or platform metadata cannot be identified reliably; publishing limits with the included iOS compatibility patch remains the precise solution.

Zoom sliders now send immediate `setZoom` updates during user movement, with a final update on release. Remote slider commands retain only the latest waiting value per control, avoiding a queue of obsolete zoom/brightness positions on slower networks. Preset buttons keep their smooth-zoom behavior. Torch brightness preserves the latest requested on/off state while waiting for camera status, including when a toggle is immediately followed by a brightness adjustment.

Recording now encodes the incoming camera’s PCM audio as AAC-LC and combines it with H.264 video in the saved Gallery MP4. Live audio monitoring can stay muted. The remote microphone must be enabled and its audio configuration received before recording starts; otherwise the app explains what to enable. Audio/video timestamps share the camera timeline, with a fallback alignment for peers using different clocks. Stop/disconnect drains the audio encoder before publishing the clip. A change in audio sample rate/channel count finalizes the current clip.

Camera names have a pencil button in the discovery list, feed captions, and single-feed tabs. Nicknames are saved locally on that control phone and applied throughout its controller UI; Save changes the label, Cancel leaves it alone, and Reset restores the discovered name. Discovery/network identities remain unchanged. Nicknames are keyed by advertised camera service name rather than IP address, so reconnecting at a different address retains the label; changing the camera’s advertised name creates a different identity.

In fullscreen, swipe left for the next connected camera or right for the previous one, wrapping at either end. The selected feed and its torch/talk/audio controls switch together. “Swipe L/R to Switch Feeds” appears just above the controls only in fullscreen with multiple feeds. Pinch-to-zoom and zoomed dragging remain available; return to 1× display magnification to swipe between feeds. The redundant selected-camera / Select feed panel has been removed.

Feed-card display zoom now transforms the video inside a fixed touch surface. Pinching follows the focal point, and panning is bounded to the actual fitted video edges. Letterboxed axes stay centered until magnification fills them. Finger handoff after a pinch reanchors panning to prevent jumps; resizing and rotation reapply the bounds. Returning to 1× recenters the video, and fullscreen feed swipes remain available at normal display magnification.

For legacy iPhones that omit format capabilities, the controller now offers the same 720p/1080p/1440p/4K and 24/30/50/60 controls as the Windows app. The camera validates requests; not every pair is guaranteed on every iPhone. Actual `supportedVideoFormats` metadata takes priority, including an explicitly empty list. [The iOS compatibility patch](compatibility/README.md#video-format-reporting) publishes the real per-device list for precise filtering. Unknown non-iOS peers remain read-only when capabilities are absent.

When a connected video stream closes or fails, its feed card is removed automatically and the camera is removed from the available list. Silent power/network loss is detected by the existing 10-second stream read timeout. Discovery also checks each known camera’s status every three seconds; two consecutive failures remove stale advertisements. Reachable cameras reappear in discovery without automatically reconnecting their feeds. Missing command/video advertisements remove availability immediately, while talk-service changes refresh controls. Manual Disconnect leaves a reachable camera available. Losing the active fullscreen feed selects another connected camera, or exits fullscreen when the last feed is gone. Saved nicknames remain intact.
