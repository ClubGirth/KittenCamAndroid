# Device testing — 2026-11-16

Physical target: Samsung Galaxy S21 Ultra SM-G998U1, Android 15 / API 35.

## Windows landscape metadata correction

Both landscape directions previously advertised `orientation: "landscapeRight"`, even when the numeric rotation was 0°. The Windows controller checks orientation names before numeric degrees, mapping `landscapeLeft` to 0° and `landscapeRight` to 180°. Android now supplies the distinct names and matching clockwise/counterclockwise angles, portrait flags, dimension-swap flags, and change revision. The local camera-preview transform is unchanged by this correction.

The compatibility check ran the unmodified `normalizeDisplayRotation` function from [KittenCamControl renderer.js](https://github.com/ClubGirth/KittenCamControl/blob/bcfdcbd9dcb12c6d6aa994b4ebf16ca6954d76fe/renderer.js#L4411) against fixtures generated from the Android orientation enum. All four angles passed, and the old 0°/landscapeRight regression was reproduced. See [Windows parser results](windows-orientation-compatibility.txt).

The updated metadata and preview transforms also passed [both emulator orientation tests](windows-orientation-emulator-results.txt) at display rotations 0°, 90°, 180°, and 270°. Three new unit tests passed; build and lint passed. The corrected build was subsequently installed on the physical S21 Ultra. Both orientation tests passed there across all four display rotations, including assertions on the actual advertised JSON. See [Samsung metadata regression results](windows-orientation-s21-results.txt). Visual confirmation in the running Windows app remains a manual acceptance check.

## Previous physical-device checks

- [Orientation regression results](s21-orientation-results.txt): four tests passed after removing duplicate sensor rotation from the camera preview. Actual preview transforms and raw-stream metadata were checked at display rotations 0°, 90°, 180°, and 270°, followed by the existing video/recording checks. The preview also updates on surface resize and display changes.
- [Integration results](s21-integration-results.txt): three tests passed, including real camera capture, mDNS service discovery, H.264 decoding, resuming a hidden surface, remote zoom, microphone packets, talkback capture/connection, MediaStore MP4 read-back, and cleanup.
- [Hardware results](s21-hardware-results.txt): one test passed, exercising exposed zoom, brief torch on/off, and all 16 resolution/FPS combinations from the UI. Unsupported combinations were rejected rather than silently accepted.
- [Capability details](s21-capabilities.txt): 720p/1080p/4K encode at 24/30 fps; 1440p and 50/60 fps unavailable through the selected camera. Zoom is 1×–8×; torch brightness control is not exposed.
- [Read-only iOS interoperability](s21-ios-results.txt): one explicitly selected live-camera test passed. It subscribed to the existing iPhone video/audio stream and fetched status; it sent no camera commands and no talkback. The existing Windows connection was not controlled or disconnected by the test.

The default integration suite selects only the camera hosted by the test device, matching its device name and all three actual ports. The iOS test is skipped unless `iosCameraName` is explicitly passed. Its audible monitoring is muted to avoid feedback.

An Android 15 ARM64 emulator named `KittenCam_S24_API35` also passed the three integration checks. Its 1080×2340 display approximates the S24's screen. It runs Google's Android image, not Samsung firmware or camera hardware.

## Re-run

```sh
ANDROID_SERIAL=<device-serial> JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:connectedDebugAndroidTest
```

Keep the phone unlocked before starting. The app keeps the display awake while a mode is active. The hardware test briefly turns on the torch. On a phone used as both camera and controller, monitoring is muted to avoid acoustic feedback.

The assertions verify successful capture/encoding/decoding and readable recording tracks. They do not establish sustained frame cadence, speaker/microphone sound quality, motion sensitivity in a real room, long-session stability, or operation of the Windows app against the Android camera. Those remain manual acceptance checks.

## Galaxy S8+ — Android 9 / API 28

Physical model SM-G955U. All seven instrumentation tests passed on the current build: app context, camera hardware/format checks, explicit read-only iPhone reception, two orientation checks, mode/discovery layout, and full camera-to-controller/recording integration. See [test output](s8-results.txt) and [capabilities](s8-capabilities.txt).

- Default 1080p/30 capture, H.264 transport/decoding, microphone packets, talkback connection/capture, and MP4 read-back passed.
- 720p, 1080p, and 1440p encoding started at 24/30 fps. The selected camera does not expose 3840×2160 (4K).
- Digital zoom 1×–8× and torch on/off passed. Capture-request torch strength control is unavailable.
- All four preview rotations and their Windows-compatible metadata passed.
- The S8+ discovered and decoded the existing iPhone stream and received its microphone packets. The test sent no camera commands or talkback to the iPhone.
- Android 9 uses the app-specific external Movies/KittenCam Recordings directory; the recording test now verifies this path as well as the MediaStore path used on newer Android.

Testing exposed a frame-rate validation gap: stream minimum-duration metadata alone allowed 50/60 fps requests even though normal auto-exposure controls max out at 30 fps. Format changes now also validate the camera's advertised exposure FPS ranges. The hardware test checks that accepted frame rates match the actual capture request. This does not constitute a sustained frame-cadence or thermal benchmark.

## Dynamic format controls

The Android settings screens now filter options through one camera/encoder capability catalog. The catalog is also published in status and stream information as `supportedVideoFormats`; every format command is validated against it.

- [S8+ device results](dynamic-formats-s8-results.txt): ten tests passed, including per-resolution camera/encoder capability checks, metadata round-trips, picker callbacks, legacy-camera behavior, and the existing streaming/recording/orientation/iPhone checks.
- [Actual S8+ menu inspection](dynamic-formats-ui-results.txt): resolution menu was exactly 720p, 1080p, 1440p; FPS menu was exactly 15, 20, 24, 30. No 4K, 50, or 60 options were present.
- [Advertised S8+ catalog](dynamic-formats-s8-capabilities.txt): matches those menus.
- All fifteen unit tests passed. Five new model tests cover size-dependent FPS, missing resolutions, variable exposure ranges, resolution changes requiring a new FPS, and lower-resolution fallbacks.

Synthetic picker tests verified that switching 1080p/60 to 4K sends exactly one valid 4K/30 command, not an intermediate unsupported 4K/60 command. Opening the controls does not send a command. Older camera peers without capability reporting show their current settings without invented alternatives.

The current Windows application does not yet consume this new capability list. Its settings UI and legacy iOS capability reporting are separate integration work.

## Android controller receiving landscape iPhone feeds

Legacy iOS camera metadata uses landscape names whose numeric rotation fields conflict with the interpretation in the working Windows controller. The Android receiver previously trusted the numeric field, causing a 180° discrepancy in either landscape direction. Incoming display rotation now resolves recognized orientation/deviceOrientation/videoOrientation names using the Windows-compatible mapping, then falls back to numeric angles for peers without a recognized name. The encoder's fixed capture-basis name is not mistaken for current display orientation. Outgoing Android orientation metadata and the camera-preview transform are unchanged.

Two added device tests check both conflicting iOS landscape packet fixtures and their resulting TextureView transforms, as well as numeric-only fallbacks. Unit checks also cover all four Android directions and the legacy iOS mismatch.

The live iPhone test received `orientation=landscapeLeft` with `rotationDegreesClockwise=180`; the Android controller selected 0° and decoded 31 frames with zero dropped frames at that checkpoint, matching the Windows interpretation. This was a read-only subscription and status fetch, with no camera commands or talkback. All twelve device tests passed on the S21 Ultra, alongside seventeen unit tests and a clean lint/build check. See [device results](ios-landscape-controller-s21-results.txt). Audible quality, physical rotation of both iPhones, and visual acceptance remain user checks.

## Minimum-zoom presets

The S21 Ultra passed all sixteen device tests with the minimum-zoom change, and all twenty unit tests passed. See [device results](minimum-zoom-s21-results.txt). New checks verify that a 0.6× fixture sends exactly 0.6 while the other buttons still send 1 and 3; switching connected cameras changes the first preset; current zoom is not mistaken for minimum zoom; and a real camera's local button, network status, stream metadata, and applied zoom agree.

The physical S21 Ultra reports minimum 1× and maximum 8×. Its local preset applied 1× successfully. Smaller minima were verified through metadata/UI fixtures, not a claim that this Samsung exposes an ultra-wide zoom ratio through the selected Camera2 interface.

A legacy iPhone without zoom-limit metadata is not probed by changing its zoom. Its first preset is disabled until its app reports the bounds. The source compatibility patch was dry-run checked against the public iOS repository, but not compiled or deployed to the iPhones.

## S21 Ultra real ultra-wide camera routing

The earlier 1× minimum represented only the main rear camera, not every exposed rear lens. The device inventory shows two accessible rear cameras: main ID 0 (6.7 mm focal length, 9.6 mm sensor width) and ultra-wide ID 2 (2.2 mm, 5.6 mm). Each advertises a native 1×–8× range. See [camera inventory](s21-camera-lenses.txt).

The app now normalizes separate lens views relative to the default rear camera using focal length/sensor width, labels relative lens stops in tenths, and routes display zoom to a physical camera plus its native crop. There are no S21 model-name or camera-ID constants in routing. On this device the ultra-wide stop is 0.6×, while 1× and 3× use the main camera.

The physical integration test opened the real ultra-wide camera, verified its camera ID and shorter focal length, confirmed native zoom 1× for the displayed 0.6× view, and confirmed a receiving decoder resumed after the switch. Remote 1×/3× commands returned to the main camera. Both the [live-stream check](ultrawide-live-stream.txt) and [full eighteen-test device suite](ultrawide-s21-results.txt) passed. Twenty-four unit tests passed, including new single/logical/separate-lens routing checks. The tested configuration used the actual camera surfaces; it was not a label-only change.

Lens changes retain network listeners and audio capture. The lens's own format catalog is recomputed and announced. Existing resolution/FPS is preserved if possible; otherwise a compatible format is chosen. A failed wider camera is removed from the routes and the main camera is restored.

## Controller UI and talkback update

The updated debug APK is installed on the physical Galaxy S8+ (Android 9). Build, lint, and all 24 unit tests passed. The full instrumentation run completed with 22 passed and two expected skips: opt-in live iPhone interoperability and the unavailable separate ultrawide camera. See [full device output](controller-audio-s8-results.txt).

The six new tests cover monitoring off on connection, no AudioTrack while muted, single-feed selection and turquoise foreground outline, entering/exiting fullscreen with its same button, selected-feed audio controls, microphone PCM transport through rapid stop/start, holding through scroll gestures, and PCM playback routed to the built-in speaker. The final build also prevents a cancelled capture callback from restoring the talk-ready state after release.

The original supplied logo is included unchanged in legacy/adaptive launcher resources. Microphone capture tries compatible sources and sample rates; the receiver reports PCM delivery/playback counters and speaker volume. Monitoring pauses while talking. Buttons expose accessibility descriptions, 48 dp targets, and keyboard/accessibility talk toggling.

Only the S8+ was connected by USB for this update. Audible S8+ controller → S21 Ultra camera acceptance is pending installation of the updated APK on the S21 Ultra and testing both phones together. Local microphone transport and speaker playback tests do not establish end-to-end audible quality. Temporary USB stay-awake was restored to its original disabled setting after the tests.

## Live zoom and torch brightness sliders

The updated APK is installed on the S8+. Build, lint, and 24 unit tests passed. Nine focused controller/zoom instrumentation tests passed, including a new check that user progress sends before release, 500 queued zoom positions collapse to the newest position, brightness uses its own pending slot, and stale off-status does not reverse an outstanding on-toggle. See [device output](live-slider-s8-results.txt).

Remote zoom uses immediate setZoom during dragging and retains the final release value. Both remote zoom and brightness serialize requests while replacing obsolete waiting values. The torch’s requested state takes priority until camera status acknowledges it; brightness supplies that state at dispatch rather than capturing an old status value. Fullscreen and panel toggles share this state. Existing zoom presets retain smoothZoom. Device-specific brightness support still applies. Live iPhone visual smoothness and physical torch flicker acceptance remain user checks; these tests use fixtures and the local Android camera without changing existing iPhone sessions.

## System Gallery recording storage

Android 7–9 now writes recordings to public Movies/KittenCam Recordings, requests legacy read/write storage permission for manual or motion-triggered recording, and scans finalized MP4s into MediaStore. Invalid empty clips are removed. Android 10+ retains its existing pending MediaStore entry flow and publishes only after finalization. The controller is the device that stores recordings.

The physical S8+ integration test passed with real camera H.264: record through a keyframe, finalize the clip, wait for a scanned content URI, query its video/mp4 media-library row, and read its video track and nonempty sample using MediaExtractor. The test removes its own clip afterward. See [device results](gallery-recording-s8-results.txt). Build, lint, and 24 unit tests passed. The first run exposed missing READ storage permission in the legacy test setup; explicit read/write permissions and grants resolved it. Gallery indexing and playable media were verified, rather than a manual launch of Samsung Gallery. Prior app-private recordings are not moved automatically.

## MP4 camera audio

Recordings now encode received PCM16 as AAC-LC and mux it alongside H.264. This works with monitoring muted and applies to manual and motion-triggered recording. Recording requires the remote microphone to be enabled and PCM configuration available; the app gives a clear message otherwise. Both tracks start at the first video keyframe, use camera microsecond timestamps, and audio is drained on stop before Gallery publication. Audio sample rate/channel changes finalize the existing clip. Pending video is bounded while the encoder supplies its format; invalid/empty clips are removed.

The physical S8+ local camera recording passed: two tracks, readable video samples, AAC samples decoded into PCM, Gallery content URI, and monitoring off. The opt-in existing-iPhone test also passed: 1080p H.264 plus decodable mono 48 kHz AAC in a Gallery MP4 with monitoring muted. That test issued no camera commands or talkback and deleted its short test clip afterward. See [iPhone results](audio-recording-iphone-results.txt).

The original broad loopback test initially failed before recording because the S8+ could not open a second microphone capture while its local camera capture was active. The recording integration check now leaves that unrelated concurrent capture to the dedicated TalkbackAudioTest tests, which use a network receiver without another microphone. Saved-audio decoding checks verify media integrity; audible quality and long-duration lip-sync remain manual checks.

Final verification: build, lint, and 24 unit tests passed. Full S8+ instrumentation run: 24 passed and two expected skips (opt-in iPhone test, separate ultrawide hardware). The recording test also checks audio/video track durations differ by less than 500 ms. See [full S8+ run](audio-recording-s8-results.txt). The updated application is installed on the S8+; temporary USB stay-awake was restored to disabled.

## Fullscreen feed switching and saved nicknames

Removed the redundant selected-camera / Select feed card from the controller panel. Left/right swipes in fullscreen select the next/previous connected feed with wraparound, update its active outline, and bind the fullscreen controls to that feed. A horizontal threshold and direction check reject taps and vertical movement; pinch gestures and zoomed panning retain their existing behavior. “Swipe L/R to Switch Feeds” appears above the toolbar only in fullscreen with multiple feeds and is removed on exit.

Pencil buttons in camera discovery rows, feed captions, and single-feed tabs open a nickname dialog. Save trims and persists the label locally; Cancel preserves it; Reset restores the discovery name. SharedPreferences keys use the original advertised camera name rather than IP address. Connection identities and remote metadata remain unchanged, including iPhone identification for the minimum-zoom fallback. Changing a camera’s advertised name creates a different identity.

Build, lint, and 24 unit tests passed. Full S8+ suite: 27 passed, two expected skips (opt-in iPhone test and unavailable separate ultrawide lens). New tests cover left/right swipes and wraparound, the selected feed and audio control binding, vertical/zoomed-pan rejection, fullscreen hint lifecycle, removal of the Select feed button, saved labels across Activity recreation, nickname captions and accessible pencil labels, and layout bounds. See [full device results](feed-nicknames-s8-results.txt). Follow-up testing exercises the actual dialog Save, Cancel, and Reset actions after allowing their asynchronous callbacks to run: [dialog results](feed-nickname-dialog-s8-results.txt).

Visual QA used disconnected fixture feeds, preserving existing network camera sessions. The hint and controls fit in [portrait](fullscreen-feed-hint-portrait.png) and [landscape](fullscreen-feed-hint-landscape.png); long camera names ellipsize without hiding the pencil or fullscreen buttons. The final APK is installed on the S8+. Temporary USB stay-awake was restored to its original disabled setting.

## Bounded feed-card pinch zoom

Moved display-only magnification from View scale/translation properties to the TextureView content matrix. This fixes the changing touch coordinate frame and keeps card captions and controls stationary. Scaling follows the pinch centroid, one-finger dragging is clamped against the fitted/rotated video bounds, undersized letterboxed axes stay centered, and layout/orientation changes reapply the bounds. Pointer-up selects the surviving pointer as the new pan anchor. Zoom remains 1×–4×; returning to 1× recenters the image. Camera/lens zoom commands are unaffected.

Build, lint, and 27 unit tests passed. Eleven focused instrumentation tests passed on the physical S8+, covering multi-pointer pinch with a moving centroid, finger handoff without a jump, extreme drags, unchanged touch-surface scale/translation, and actual rendered matrix bounds across all four rotations, portrait/landscape/square cards, and three zoom factors. Existing feed-orientation, fullscreen feed-swipe, nickname, and controller UI checks passed alongside them. See [device results](bounded-feed-zoom-s8-results.txt). The corrected APK is installed on the S8+; temporary USB stay-awake was restored to disabled.

## Legacy iOS video format controls

The Windows renderer does not consume a supported-format catalog. Its index.html contains static resolution options 720p/1080p/1440p/4K and frame rates 24/30/50/60; renderer.js sends setVideoFormat for the selected pair. The supplied iOS CameraCommandServer sends current resolution/fps only. A live read-only iPhone inspection confirmed that neither status nor stream-information packets contain a capability catalog: [field-name inspection](ios-format-metadata-inspection.txt).

Android now offers the same command options for identified legacy iOS cameras, with a note that available combinations depend on the camera. These candidates are not represented as measured per-device support. Reported status or stream capabilities take priority, including explicitly empty lists, and unidentified peers remain read-only. The iOS identity uses the original discovery/metadata names, so local nicknames do not disable compatibility handling.

Added compatibility/ios-video-formats.patch to publish the actual per-resolution CameraManager.availableFrameRates results in supportedVideoFormats. It passes git apply checks and applies together with the minimum-zoom patch on temporary copies of the reference Swift sources. It has not been built or installed on iOS; exact filtering on an existing iPhone requires that iOS update.

Build, lint, and all 28 unit tests passed. Five focused S21 Ultra instrumentation tests passed, covering fallback controls, precedence of actual/empty capability lists, dependent FPS selection, the actual Android camera catalog, unknown-peer read-only behavior, and enabled controls for the live iPhone without sending it commands or talkback. See [S21 results](ios-format-controls-s21-results.txt). The updated APK is installed on the S21 Ultra. Live iPhone format changes were not issued, preserving its existing camera settings and Windows sessions.

## Lost feeds and stale discovery cleanup

A failed/closed video stream now signals the controller to remove its card and mark its camera unavailable. Normal EOF/socket timeout closes are quiet. Existing Feed.close/stopRecording cleanup finalizes recordings, stops talk/monitoring, and releases decoder resources. Losing the active fullscreen feed selects another connected camera and retargets controls; losing the last feed exits fullscreen and restores the empty state. Manual Disconnect leaves a reachable camera listed.

Discovery tracks known advertisements separately from currently reachable cameras. A read-only /status check verifies availability initially and every three seconds, with 1.2-second connect/read timeouts and two consecutive failures before pruning an existing listing. Missing command/video services remove availability immediately; late/lost talk services update controls without removing the video feed. Returning reachable cameras reappear in the list without automatic reconnection. Revision checks prevent outdated probe results from republishing an invalidated device, late legacy resolves are ignored after service loss, and repeated unchanged endpoint updates do not invalidate current probes. The existing stream read timeout detects a silent power/network loss after about ten seconds without packets. Nicknames remain in local preferences.

Three new S21 Ultra tests use controlled loopback camera servers: normal socket close removes the card/list entry and retargets fullscreen to a surviving feed; a blackholed connection times out and clears the final fullscreen feed; and discovery verifies live status, tolerates one failed check, prunes dead cached advertisements, restores reachable services, and refreshes talk availability. No commands are sent to existing iPhone cameras.

Final verification: build, lint, and 28 unit tests passed. Full S21 Ultra instrumentation suite: 34 passed, one expected opt-in iPhone skip. See [device results](disconnected-feeds-s21-results.txt). An initial broad run exposed a pre-existing audio test payload smaller than the S21 native playback buffer; the test now fills the reported buffer size and passes, with no application-audio change. The final APK is installed on the S21 Ultra. Temporary USB stay-awake was restored to its original disabled setting.
