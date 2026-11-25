# Special Commander (Android controller)

Special Commander is an Android controller for the local Special-Commander
engine. The APK does not contain the attack engine or third-party tooling; it
connects to the engine over `127.0.0.1:8765` and displays engine events.

## Scope

Use only on networks and devices you own or are explicitly authorized to test.
The engine and controller are intended for an isolated lab environment.

## Build

Requirements:

- JDK 17+
- Android SDK platform 35
- Android Gradle Plugin / Gradle wrapper included in this repository

```sh
./gradlew assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The shipped debug build is signed with V1, V2 and V3 schemes and targets
SDK 35.

## Features

- Engine connection state and active/passive mode
- Wi-Fi AP scanning with cached AP replay on reconnect
- Client capture from a monitor adapter, with incremental client events
- DEAUTH launch from the Sniper tab with no in-app allow-list gate
- Bluetooth discovery and MAC spoof commands
- Fluxion Handshake Snooper launch and live status output
- Cached replay of clients, deauth result, Fluxion lines and engine logs after
  an app restart

## Launcher icon

The launcher icon uses a professional navy/cyan command-radar design:

- background: `#0B1F2A`
- accent: `#38BDF8`
- detail: `#E5E7EB`

Adaptive and legacy launcher assets are included for all densities from
`mdpi` through `xxxhdpi`.

## Current release

```text
APK MD5: 426cc4365a6d4d0b09d30520e3f6707c
```

The engine and attack tooling are maintained separately from this repository.
