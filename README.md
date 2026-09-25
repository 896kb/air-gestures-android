# air-gestures-android

Use a **Meta Neural Band** with an Android phone — the Android side of
[air-gestures](https://gitlab.com/896kb/air-gestures) (Linux desktop), which is a Rust port
of [callbacked/kinesis](https://github.com/callbacked/kinesis) (macOS, MIT).

> **Status: spike — works.** On a Xiaomi 17 Pro (HyperOS, Android 16) it connects to a band
> the desktop app claimed (sharing the desktop's owner key) and logs every gesture, the pinch
> dial, hand and battery. No gesture actions, background service or claiming yet.
>
> Findings: the owned band only advertises its name in pairing mode (hold 3 s), which the
> phone needs to find it the first time; Android bonds during the PSM read; the insecure
> L2CAP channel works; the band gave the phone PSM 129 (the desktop saw 255).

## How it's built

- `rust/band-core` — the sans-IO band protocol core, copied from air-gestures (see its `ORIGIN.md`).
- `rust/bridge` — a small JNI bridge (`libairgestures_bridge.so`): session, keepalive timing
  and gesture de-duplication; Kotlin passes socket bytes in and gets bytes + event lines out.
- `app` — a plain Android app (Kotlin, no AndroidX): finds the band by name, reads its
  input-channel PSM over GATT, opens an L2CAP channel (API 29+) and runs the session.

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

## License

MIT — see [`LICENSE`](LICENSE). band-core derives from kinesis; its notice is kept in
[`LICENSE-kinesis`](LICENSE-kinesis).
