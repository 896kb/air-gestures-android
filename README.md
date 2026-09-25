# air-gestures-android

Use a **Meta Neural Band** with an Android phone: thumb swipes, taps, a middle-finger hold
and pinch-and-turn control music, volume, brightness and the screen. The Android side of
[air-gestures](https://gitlab.com/896kb/air-gestures) for Linux, a Rust port of
[callbacked/kinesis](https://github.com/callbacked/kinesis). Not affiliated with Meta.

Site: <https://band.896.ro>

## Install

Download the latest APK and install it on an Android 10+ phone:

<https://gitlab.com/896kb/air-gestures-android/-/releases/permalink/latest/downloads/air-gestures.apk>

Tested on a Xiaomi 17 Pro. On HyperOS, turn on **Autostart** for the app and set its battery
saver to **No restrictions**, or the system stops it in the background.

## Set up

The band can be claimed right on the phone: tap **Claim** under Setup, sign in to Meta on
Meta's own page in the app, then hold the band's button for 3 seconds until it flashes
(pairing mode) and pick it in the system's list, which also links it so Android can wake
the app when the band comes into range. Claiming makes the phone the band's owner: it unlinks the band from Meta's
glasses and app, and a key from a computer that claimed it before stops working. The app
keeps the Meta sign-in encrypted on the phone; **Sign out** under Setup deletes it.

Or use a band that was claimed with the Linux app:

1. On the computer, claim the band with `air-gestures pair`.
2. Copy `owner.key` and `band.json` from `~/.local/state/air-gestures/` to the phone and
   pick them under **Setup → Owner key → Import**.
3. Run `air-gestures disconnect` on the computer (the band talks to one device at a time)
   and tap the power button in the app.

The first connection may need the band in pairing mode (hold its button 3 s); after that the
phone reconnects on its own.

## What it does

Every gesture except the middle hold can be mapped: play or pause, next or previous track,
volume, mute, brightness, swipe the screen up or down, Back, Home, Recent apps. Pinch and turn
is a dial for volume, tracks or brightness. The middle hold turns the controls off and on.
Screen actions need the app's accessibility service; brightness needs **Modify system
settings**. The app shows both under Setup.

## Build

Needs the Android SDK (platform 36) and NDK, JDK 21, and Rust with the
`aarch64-linux-android` target and `cargo-ndk`.

```sh
./build-rust.sh      # the Rust bridge → app/src/main/jniLibs
./gradlew assembleDebug
```

`cd rust && cargo test` runs the protocol and bridge tests against a simulated band.

## License

MIT, see [`LICENSE`](LICENSE). The protocol core derives from kinesis, whose notice is in
[`LICENSE-kinesis`](LICENSE-kinesis). Fonts: Geist (OFL). Icons: Lucide (ISC).
