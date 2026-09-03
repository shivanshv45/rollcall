# Testing the app

Everything here runs from the project folder. The Android SDK is already installed
locally at `android-sdk/`, so there is nothing to install.

## One-time phone setup

1. On the phone: **Settings → About phone → tap "Build number" 7 times.**
   You'll see "You are now a developer".
2. **Settings → System → Developer options → enable "USB debugging".**
   (On Samsung it's Settings → Developer options.)
3. Plug the phone into the PC with a USB cable. Choose **File transfer / MTP** if asked
   (charging-only mode blocks debugging on some phones).
4. A dialog appears on the phone: **"Allow USB debugging?"** → tick *Always allow* → **Allow**.

## Check the phone is connected

```bash
./android-sdk/platform-tools/adb.exe devices
```

Expected:
```
List of devices attached
R58M12ABCDE     device
```

- `unauthorized` → the phone dialog wasn't accepted. Unplug, replug, accept it.
- empty list → try a different cable (some are charge-only), or a different USB port.

## Build and install

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot"
./gradlew installDebug
```

That builds and pushes it in one step. The app appears as **Roll Call** in the launcher.

To build the APK without installing:
```bash
./gradlew assembleDebug
# lands at app/build/outputs/apk/debug/app-debug.apk
```
You can also copy that .apk to the phone and tap it (allow "install from unknown sources").

## Put the test videos on the phone

```bash
./android-sdk/platform-tools/adb.exe push vids/. /sdcard/Movies/
```

They'll show up in the phone's gallery and in the app's video picker.

## Run the tests

```bash
./gradlew testDebugUnitTest        # JVM tests, no phone needed
./gradlew connectedDebugAndroidTest # on-device tests, phone required
```

The JVM tests cover the clustering, segmentation and quality scoring - the parts that
decide the appearance counts.

## Watch what the app is doing

```bash
./android-sdk/platform-tools/adb.exe logcat -s RollCall:V
```

Leave that running while you use the app; the pipeline logs each stage and its timing.

## Record the screen

```bash
./android-sdk/platform-tools/adb.exe shell screenrecord --time-limit 60 /sdcard/demo.mp4
# stop early with Ctrl+C, then:
./android-sdk/platform-tools/adb.exe pull /sdcard/demo.mp4 .
```

Most phones also have a built-in screen recorder in the quick-settings panel, which is
easier and records at a higher quality.

## If something goes wrong

| Symptom | Fix |
|---|---|
| `adb` not recognised | Use the full path `./android-sdk/platform-tools/adb.exe` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall com.shivansh.rollcall` then install again |
| App installs but crashes at launch | `adb logcat -s AndroidRuntime:E` and read the stack trace |
| Gradle can't find the SDK | Check `local.properties` points at `android-sdk` |
| Build is very slow the first time | Normal - it downloads dependencies once, then caches them |
