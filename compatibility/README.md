# iOS capability reporting

The Android camera now advertises `minimumZoom` and `maximumZoom` in status and stream-information packets. Android Control Device reads these capabilities from the connected camera and uses its actual minimum for the left preset. It does not infer a hardware limit from the current zoom position.

The existing iOS repository's CameraManager already calculates its minimum/maximum display zoom, but CameraCommandServer does not send them. `ios-minimum-zoom.patch` adds those status fields and changes the iOS Viewing Device's first preset to use `camera.minimumZoom`. Its 1× and 3× presets remain unchanged.

Apply in the KittenCamiOS repository:

```sh
git apply --check /path/to/KittenCam/compatibility/ios-minimum-zoom.patch
git apply /path/to/KittenCam/compatibility/ios-minimum-zoom.patch
```

Then build and install through your existing Xcode project. The patch was checked against the supplied public Swift sources; this workspace does not contain an iOS Xcode project, so it has not been compiled or installed here.

Recognized legacy iOS cameras now use a 0.5× minimum fallback in Android. Reported bounds take priority. Updating an Android controller cannot make an already-installed iPhone app report fields absent from its protocol.

## Video format reporting

`ios-video-formats.patch` publishes `supportedVideoFormats` in `/status`, using CameraManager’s existing per-resolution `availableFrameRates(for:)` results. Each entry contains resolution, width, height, and its supported FPS list. Android uses these actual capabilities instead of its legacy iOS controls.

```sh
git apply --check /path/to/KittenCam/compatibility/ios-video-formats.patch
git apply /path/to/KittenCam/compatibility/ios-video-formats.patch
```

Both patches were checked individually and applied together to temporary copies of the public Swift sources. The format patch has not been compiled or installed on an iPhone from this Android workspace.

The existing Windows controller has static menus for 720p/1080p/1440p/4K and 24/30/50/60 FPS; it does not read a supported-format list. Android now offers the same command options for identified legacy iOS cameras. These are candidate settings rather than a claim that every pair is supported; the iPhone checks each request. Unknown peers remain read-only until they advertise capabilities, and Android cameras continue exposing only their discovered formats.
