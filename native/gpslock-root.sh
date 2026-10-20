#!/system/bin/sh
# Explicit process-lifetime control; no boot hooks, vendor-file or NV writes.
# B8 keeps the primary enabled; verified B6 masks perform actual SV exclusion.
set -u
umask 077
DIR=/data/adb/gpslock
HAL=$DIR/gpslock-hal
APPEND=$DIR/gpslock-qmi-sv-append
GET=$DIR/gpslock-qmi-sv-split
PENDING=/data/user/0/com.yuliwen.gnsslock/files/pending_policy.txt
KEY=gnss_satellite_blocklist
PID=0 STATE=stock MASK=unknown VERIFIED=false EXCLUDED=false
APID= LOCKED=false MUTATING=false
BEFORE_PRESENT=false BEFORE_RAW= BEFORE_LOGICAL= BEFORE_STATE=stock
ROLLBACK=not_needed

emit() {
    printf '{"ok":%s,"state":"%s","strategy":"bds_sv_append","pid":%s,"bds_mask":"%s","modem_verified":%s,"bds_excluded":%s,"rollback":"%s","error":"%s"}\n' \
        "$1" "$STATE" "$PID" "$MASK" "$VERIFIED" "$EXCLUDED" "$ROLLBACK" "$2"
}
stop_append() {
    if [ -n "$APID" ]; then
        kill -TERM "$APID" 2>/dev/null || :
        # Native cancellation restores both hardware slots before detaching.
        wait "$APID" 2>/dev/null || :
        APID=
    fi
}
cleanup() {
    stop_append
    if [ "$LOCKED" = true ]; then
        rm -f "$DIR/.lock/owner" 2>/dev/null || :
        rmdir "$DIR/.lock" 2>/dev/null || :
    fi
}
trap cleanup EXIT

# Binder shell commands cannot receive stdout/stderr files in /data/adb under
# the system_server SELinux domain. Capture through pipes, then let this shell
# write the private log; no security policy change is needed.
settings_safe() {
    ss_output=$(settings "$@" 2>&1)
    ss_rc=$?
    printf '%s\n' "$ss_output"
    return "$ss_rc"
}

# Canonical decimal pairs are data only. Keep non-BDS and partial BDS entries.
parse_policy() {
    P_CANON= P_NONBDS= P_PARTIAL= P_LOGICAL= P_EXCLUDE=false
    P_BDS_SET=, P_MASK_HEX=0x0000000000000000
    p_spaced=$(printf '%s' "$1" | tr '\t\r\n' '   ')
    p_input=$(printf '%s' "$p_spaced" | tr -d ' ')
    case "$p_input" in *[!0-9,]*|,*|*,|*,,*) return 1 ;; esac
    [ -n "$p_input" ] || return 0
    printf '%s\n' "$p_spaced" | grep -Eq '^ *([0-9]+ *, *)*[0-9]+ *$' || return 1
    p_oldifs=$IFS
    IFS=,
    set -- $p_input
    IFS=$p_oldifs
    [ $(( $# % 2 )) -eq 0 ] || return 1
    while [ "$#" -gt 0 ]; do
        p_c=$1 p_s=$2
        shift 2
        while [ "${p_c#0}" != "$p_c" ] && [ "${#p_c}" -gt 1 ]; do p_c=${p_c#0}; done
        while [ "${p_s#0}" != "$p_s" ] && [ "${#p_s}" -gt 1 ]; do p_s=${p_s#0}; done
        [ "${#p_c}" -le 10 ] && [ "${#p_s}" -le 10 ] || return 1
        if [ "${#p_c}" -eq 10 ] && [ "$p_c" \> 2147483647 ]; then return 1; fi
        if [ "${#p_s}" -eq 10 ] && [ "$p_s" \> 2147483647 ]; then return 1; fi
        p_pair=$p_c,$p_s
        P_CANON=${P_CANON:+$P_CANON,}$p_pair
        if [ "$p_c" -eq 5 ]; then
            [ "$p_s" -le 63 ] || return 1
            if [ "$p_s" -eq 0 ]; then P_EXCLUDE=true
            else
                P_PARTIAL=${P_PARTIAL:+$P_PARTIAL,}$p_pair
                case "$P_BDS_SET" in *,$p_s,*) : ;; *) P_BDS_SET=$P_BDS_SET$p_s, ;; esac
            fi
        else P_NONBDS=${P_NONBDS:+$P_NONBDS,}$p_pair
        fi
    done
    # Android mksh arithmetic can be only 32 bits. Build each hexadecimal
    # nibble from four membership checks; never shift by a satellite index.
    P_MASK_HEX=0x p_group=15
    while [ "$p_group" -ge 0 ]; do
        p_nibble=0 p_bit=0
        while [ "$p_bit" -lt 4 ]; do
            p_sv=$(( p_group * 4 + p_bit + 1 ))
            case "$P_BDS_SET" in *,$p_sv,*) p_nibble=$(( p_nibble | (1 << p_bit) )) ;; esac
            p_bit=$(( p_bit + 1 ))
        done
        P_MASK_HEX=$P_MASK_HEX$(printf '%x' "$p_nibble")
        p_group=$(( p_group - 1 ))
    done
    [ "$P_MASK_HEX" != 0x7fffffffffffffff ] || P_EXCLUDE=true
    if [ "$P_EXCLUDE" = true ]; then P_LOGICAL=${P_NONBDS:+$P_NONBDS,}5,0
    else P_LOGICAL=$P_CANON
    fi
}
read_current() {
    r_output=$(settings_safe list global) || return 1
    r_entry=$(printf '%s\n' "$r_output" | grep "^$KEY=")
    printf '%s\n' "$r_entry" > "$DIR/settings.log"
    CURRENT_PRESENT=false
    if grep -q "^$KEY=" "$DIR/settings.log"; then CURRENT_PRESENT=true; fi
    CURRENT_RAW=
    if [ "$CURRENT_PRESENT" = true ]; then
        CURRENT_RAW=$(settings_safe get global "$KEY") || return 1
    fi
    [ "${#CURRENT_RAW}" -le 4096 ]
}
write_policy() {
    w_value=$1
    w_now=$(settings_safe get global "$KEY") || return 1
    # Change the raw value to wake the observer even for an unchanged list.
    if [ "$w_now" = "$w_value" ]; then w_value="$w_value "; fi
    settings_safe put global "$KEY" "$w_value" >> "$DIR/transaction.log" || return 1
    w_check=$(settings_safe get global "$KEY") || return 1
    [ "$w_check" = "$w_value" ]
}
patch() {
    h_action=$1 h_expected=$2
    timeout -k 1 5 "$HAL" "$h_action" "$PID" > "$DIR/patch.log" 2>> "$DIR/transaction.log" || return 1
    grep -q '"ok":true' "$DIR/patch.log" &&
        grep -q "\"state\":\"$h_expected\"" "$DIR/patch.log"
}
read_state() {
    patch sv-status enabled && { STATE=enabled; return 0; }
    patch sv-status stock && { STATE=stock; return 0; }
    return 1
}
read_mask() {
    VERIFIED=false EXCLUDED=false MASK=unknown
    timeout -k 1 3 "$GET" --inspect --root "/proc/$PID/root" > "$DIR/receiver.log" 2>> "$DIR/transaction.log" || return 1
    grep -q '"stage":"baseline_ind","complete":true,"modem_status":0,"bds_mask_valid":true' "$DIR/receiver.log" || return 1
    m_value=$(sed -n 's/^.*"baseline_bds_mask":"\(0x[0-9a-f]\{16\}\)".*$/\1/p' "$DIR/receiver.log")
    [ "${#m_value}" -eq 18 ] || return 1
    MASK=$m_value VERIFIED=true
    [ "$MASK" != 0x7fffffffffffffff ] || EXCLUDED=true
    return 0
}
verify_mask() {
    v_expected=$1 v_i=0
    while [ "$v_i" -lt 8 ]; do
        read_mask || return 1
        [ "$MASK" != "$v_expected" ] || return 0
        v_i=$(( v_i + 1 ))
        sleep 0.12
    done
    return 1
}
ensure_patches() {
    patch disable stock || return 1
    patch sv-enable enabled || return 1
    STATE=enabled
}
find_tid() {
    TID=0
    for t_path in /proc/"$PID"/task/*; do
        [ -r "$t_path/comm" ] || continue
        [ "$(cat "$t_path/comm" 2>/dev/null)" = LocApiMsgTask ] || continue
        t_id=${t_path##*/}
        case "$t_id" in ''|*[!0-9]*) continue ;; esac
        if [ "$TID" -eq 0 ] || [ "$t_id" -lt "$TID" ]; then TID=$t_id; fi
    done
    [ "$TID" -gt 1 ]
}
make_bds_range() {
    RANGE=$1 r_n=$2 r_end=$3
    while [ "$r_n" -le "$r_end" ]; do
        RANGE=${RANGE:+$RANGE,}5,$r_n
        r_n=$(( r_n + 1 ))
    done
}
apply_desired() {
    if [ "$DESIRED_EXCLUDE" != true ]; then
        write_policy "$DESIRED" || return 1
        verify_mask "$DESIRED_MASK" || return 1
        return 0
    fi
    find_tid || return 1
    make_bds_range "$DESIRED_NONBDS" 1 32
    write_policy "$RANGE" || return 1
    verify_mask 0x00000000ffffffff || return 1
    : > "$DIR/append.log"
    "$APPEND" --pid "$PID" --tid "$TID" --append-test --seconds 20 > "$DIR/append.log" 2>&1 &
    APID=$!
    a_i=0
    while ! grep -q '"event":"armed"' "$DIR/append.log"; do
        kill -0 "$APID" 2>/dev/null || { stop_append; return 1; }
        [ "$a_i" -lt 30 ] || { stop_append; return 1; }
        a_i=$(( a_i + 1 ))
        sleep 0.1
    done
    make_bds_range "$DESIRED_NONBDS" 33 63
    write_policy "$RANGE" || { stop_append; return 1; }
    a_i=0
    while kill -0 "$APID" 2>/dev/null; do
        [ "$a_i" -lt 100 ] || { stop_append; return 1; }
        a_i=$(( a_i + 1 ))
        sleep 0.1
    done
    wait "$APID"
    a_rc=$?
    APID=
    [ "$a_rc" -eq 0 ] || return 1
    grep -q '"client_status":0,"modem_status":0' "$DIR/append.log" &&
        grep -q '"event":"stack_restore","state":"restored","observed_post":true' "$DIR/append.log" &&
        grep -q '"hardware_restored_and_detached":true' "$DIR/append.log" || return 1
    verify_mask 0x7fffffffffffffff || return 1
    # Final full63 intent is atomically rejected without changing the already
    # aggregated modem mask. Read back again instead of assuming retention.
    make_bds_range "$DESIRED_NONBDS" 1 63
    write_policy "$RANGE" || return 1
    verify_mask 0x7fffffffffffffff
}
rollback() {
    stop_append
    ROLLBACK=failed
    ensure_patches || return 1
    parse_policy "$BEFORE_LOGICAL" || return 1
    rb_clear=$P_NONBDS
    write_policy "$rb_clear" || return 1
    verify_mask 0x0000000000000000 || return 1
    if [ "$BEFORE_STATE" = stock ]; then
        patch sv-disable stock || return 1
        STATE=stock
    fi
    patch disable stock || return 1
    if [ "$BEFORE_PRESENT" = true ]; then write_policy "$BEFORE_LOGICAL" || return 1
    else settings_safe delete global "$KEY" >> "$DIR/transaction.log" || return 1
    fi
    # Report the observed mask even if the restored logical intent is strict.
    read_mask || return 1
    ROLLBACK=temporary_mask_cleared_and_logical_policy_restored
}
fail() {
    f_reason=$1
    trap '' INT TERM HUP
    if [ "$MUTATING" = true ]; then rollback || :; fi
    emit false "$f_reason"
    exit 1
}

if [ "$#" -ne 1 ]; then emit false invalid_action; exit 2; fi
case "$1" in status|enable|disable|apply) ACTION=$1 ;; *) emit false invalid_action; exit 2 ;; esac
if [ "$(id -u)" -ne 0 ]; then emit false root_required; exit 2; fi
mkdir -p "$DIR" 2>/dev/null || { emit false private_directory_unavailable; exit 2; }
chmod 700 "$DIR" 2>/dev/null || { emit false private_directory_unavailable; exit 2; }
if ! mkdir "$DIR/.lock" 2>/dev/null; then
    l_owner=$(cat "$DIR/.lock/owner" 2>/dev/null)
    case "$l_owner" in ''|*[!0-9]*) emit false transaction_busy; exit 3 ;; esac
    if [ -d "/proc/$l_owner" ]; then emit false transaction_busy; exit 3; fi
    rm -f "$DIR/.lock/owner" 2>/dev/null
    rmdir "$DIR/.lock" 2>/dev/null || { emit false transaction_busy; exit 3; }
    mkdir "$DIR/.lock" 2>/dev/null || { emit false transaction_busy; exit 3; }
fi
LOCKED=true
printf '%s\n' "$$" > "$DIR/.lock/owner"
set -- $(pidof android.hardware.gnss-aidl-service-qti)
[ "$#" -eq 1 ] || { emit false expected_one_gnss_service; exit 3; }
case "$1" in ''|*[!0-9]*) emit false invalid_service_pid; exit 3 ;; esac
PID=$1
export LD_LIBRARY_PATH="/system/lib64:/proc/$PID/root/vendor/lib64:/proc/$PID/root/vendor/lib64/hw:/proc/$PID/root/odm/lib64:/vendor/lib64:/vendor/lib64/hw:/odm/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
for b_path in "$HAL" "$APPEND" "$GET"; do
    [ -x "$b_path" ] || { emit false missing_root_component; exit 3; }
done
trap 'fail interrupted' INT TERM HUP
read_state || fail patch_status_unavailable
if [ "$ACTION" = status ]; then
    patch status stock || fail obsolete_or_unknown_constellation_patch
    read_mask || fail receiver_readback_unavailable
    emit true ''
    exit 0
fi
read_current || fail settings_unavailable
parse_policy "$CURRENT_RAW" || fail invalid_existing_policy
BEFORE_RAW=$CURRENT_RAW BEFORE_PRESENT=$CURRENT_PRESENT BEFORE_LOGICAL=$P_LOGICAL BEFORE_STATE=$STATE
printf '%s\n' "$BEFORE_RAW" > "$DIR/before_policy.txt"
printf 'action=%s\npid=%s\nbefore_present=%s\nbefore_state=%s\nbefore_logical=%s\n' \
    "$ACTION" "$PID" "$BEFORE_PRESENT" "$BEFORE_STATE" "$BEFORE_LOGICAL" > "$DIR/transaction.log"
if [ "$ACTION" = apply ]; then
    [ -f "$PENDING" ] || fail pending_policy_missing
    DESIRED=$(head -c 4097 "$PENDING" 2>> "$DIR/transaction.log") || fail pending_policy_unreadable
    [ "${#DESIRED}" -le 4096 ] || fail pending_policy_too_large
else DESIRED=$BEFORE_LOGICAL
fi
parse_policy "$DESIRED" || fail invalid_desired_policy
DESIRED=$P_CANON DESIRED_NONBDS=$P_NONBDS DESIRED_EXCLUDE=$P_EXCLUDE
DESIRED_MASK=$P_MASK_HEX
DISABLE_LOGICAL=$P_LOGICAL
MUTATING=true
ensure_patches || fail patch_enable_failed
if [ "$ACTION" = disable ]; then
    write_policy "$DESIRED_NONBDS" || fail clear_policy_write_failed
    verify_mask 0x0000000000000000 || fail receiver_clear_failed
    patch sv-disable stock || fail sv_patch_restore_failed
    STATE=stock
    patch disable stock || fail constellation_patch_restore_failed
    if [ "$BEFORE_PRESENT" = true ]; then write_policy "$DISABLE_LOGICAL" || fail logical_policy_restore_failed
    else settings_safe delete global "$KEY" >> "$DIR/transaction.log" || fail logical_policy_restore_failed
    fi
    verify_mask 0x0000000000000000 || fail receiver_restore_verification_failed
else apply_desired || fail receiver_transaction_failed
fi
MUTATING=false
emit true ''
exit 0
