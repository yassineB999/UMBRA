# Umbra — Android 16 Red Team Agent

Clean-slate Kotlin agent for authorized security testing on Android 16 / One UI 8.5.

## Architecture

| Component | Stack |
|---|---|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| Async | Coroutines + WorkManager |
| Network | OkHttp 4.12 |
| Serialization | kotlinx.serialization |
| Build | AGP 9.2, Gradle 9.4, JDK 21 |

## Package

`dev.yassine.umbra`

## Features (Planned)

### Week 1 — Persistence
- Foreground service with `specialUse` type (survives Doze)
- BOOT_COMPLETED → WorkManager → Service chain (Android 15+ safe)
- Samsung "Never sleeping" battery bypass prompt

### Week 2 — C2 WebSocket
- OkHttp WebSocket transport (WSS)
- AES-256-GCM encrypted payloads
- Node.js C2 server (Express + Socket.IO)

### Week 3 — FCM Hybrid C2
- Firebase Cloud Messaging for Doze/Deep Sleep wake-up
- FCM data-only messages → WebSocket reconnection
- FCM fallback when WebSocket dies

### Week 4 — Staged Payload
- Stage 1 APK: minimal permissions, no RAT code
- Stage 2 DEX: encrypted blob downloaded from C2
- DexClassLoader loads Stage 2 in memory

### Week 5 — Feature Modules
- Camera (CameraX, no preview)
- Location (FusedLocationProvider)
- Files (MediaStore — no MANAGE_EXTERNAL_STORAGE)
- Screen capture (MediaProjection)

### Week 6 — Polish
- R8 obfuscation
- String obfuscation
- Anti-sandbox/emulator detection
- Shell execution

## Test Device

Samsung Galaxy S25 Ultra (SM-S938B)  
One UI 8.5 / Android 16  
ADB: `adb connect 192.168.1.3:5555`

## Build

```bash
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
./gradlew assembleDebug
```

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.yassine.umbra android.permission.POST_NOTIFICATIONS
adb shell am start -n dev.yassine.umbra/.MainActivity
```

## Verify

```bash
adb shell dumpsys activity services dev.yassine.umbra
adb shell ps -A | grep umbra
```

## License

Educational / authorized lab use only.
