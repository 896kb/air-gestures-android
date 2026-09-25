# air-gestures-android

Use a **Meta Neural Band** with an Android phone — the Android side of
[air-gestures](https://gitlab.com/896kb/air-gestures) (Linux desktop), which is a Rust port
of [callbacked/kinesis](https://github.com/callbacked/kinesis) (macOS, MIT).

> **Status: 0.3.6, working.** On a Xiaomi 17 Pro (HyperOS, Android 16) it keeps a band
> connected in the background (a foreground service that reconnects on its own) and runs its
> gestures: media, volume, brightness, screen swipes, Back, Home and Recent apps, each gesture
> mappable. It uses the owner key of a band claimed with the desktop app; claiming from the
> phone isn't supported yet.
>
> Findings: the owned band only advertises its name in pairing mode (hold 3 s), which the
> phone needs to find it the first time; Android bonds during the PSM read; the insecure
> L2CAP channel works; the band gave the phone PSM 129 (the desktop saw 255).

## Install

Download the latest APK and install it on an Android 10+ phone:

<https://gitlab.com/896kb/air-gestures-android/-/releases/permalink/latest/downloads/air-gestures.apk>

The app uses the owner key of a band claimed with the Linux app — see
[Try it with a band claimed on the desktop](#try-it-with-a-band-claimed-on-the-desktop).
Claiming a band from the phone is not supported yet.

Tested on a Xiaomi 17 Pro. On HyperOS, turn on **Autostart** for the app and set its
battery saver to **No restrictions**, or the background service gets stopped.

## How it's built

- `rust/band-core` — the sans-IO band protocol core, copied from air-gestures (see its `ORIGIN.md`).
- `rust/bridge` — a small JNI bridge (`libairgestures_bridge.so`): session, keepalive timing
  and gesture de-duplication; Kotlin passes socket bytes in and gets bytes + event lines out.
- `app` — a plain Android app (Kotlin, no AndroidX): finds the band by name, reads its
  input-channel PSM over GATT, opens an L2CAP channel (API 29+) and runs the session.
  The band video on the main screen is `app/src/main/assets/meta-band.mp4`.

## Build

Needs the Android SDK (platform 36) and NDK, JDK 21, Rust with the `aarch64-linux-android`
target and `cargo-ndk`.

```sh
./build-rust.sh                       # rust/bridge → app/src/main/jniLibs/arm64-v8a
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`cd rust && cargo test` runs band-core's and the bridge's tests against a simulated band.

## Try it with a band claimed on the desktop

1. Open the app once, then `scripts/push-identity.sh` (copies `owner.key` and `band.json`
   from `~/.local/state/air-gestures` into the app's private storage).
2. `air-gestures disconnect` on the desktop — the band talks to one host at a time.
3. Tap **Connect**. The log (also `adb logcat -s AirGestures`) shows each step, then
   `connected` and one line per gesture.

## Releasing

Tags build, sign and publish an APK through GitLab CI (`.gitlab-ci.yml`); the release links
it as `air-gestures.apk`, which is what the permanent link above serves.

1. Create the release keystore once and keep it safe — without it the app can't be updated
   on phones that have it installed. Never commit it.

   ```sh
   keytool -genkey -v -keystore release.jks -alias release \
           -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Set four masked, protected CI/CD variables (Settings → CI/CD → Variables):
   `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD`.
3. Bump `versionName` in `app/build.gradle.kts`, then tag and push:

   ```sh
   git tag v0.3.6 && git push origin v0.3.6
   ```

The version code is the pipeline's IID. Locally, `./gradlew assembleRelease` signs when
`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set, and otherwise
builds an unsigned APK.

## License

MIT — see [`LICENSE`](LICENSE). band-core derives from kinesis; its notice is kept in
[`LICENSE-kinesis`](LICENSE-kinesis).
