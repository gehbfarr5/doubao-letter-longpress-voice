#!/system/bin/sh

COMP_FULL="com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService"
COMP_SHORT="com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"
# ColorOS re-strips the service repeatedly during the volatile window right
# after boot/unlock, so keep the interval short to close that gap quickly.
INTERVAL=30
LOG="/data/local/tmp/doubaovoicesend_keepalive.log"
LOG_LIMIT=65536

trim_log_if_needed() {
  size=0
  if [ -f "$LOG" ]; then
    size=$(wc -c < "$LOG")
  fi
  case "$size" in
    ''|*[!0-9]*)
      size=0
      ;;
  esac
  if [ "$size" -gt "$LOG_LIMIT" ]; then
    : > "$LOG"
    echo "$(date '+%Y-%m-%d %H:%M:%S') log truncated after exceeding ${LOG_LIMIT} bytes" >> "$LOG"
  fi
}

log_line() {
  trim_log_if_needed
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

run_cmd() {
  err_file="/data/local/tmp/doubaovoicesend_keepalive.err.$$"
  out=$("$@" 2>"$err_file")
  rc=$?
  if [ -s "$err_file" ]; then
    err_text=$(cat "$err_file")
    log_line "cmd stderr: $* :: $err_text"
  fi
  rm -f "$err_file"
  printf '%s' "$out"
  return "$rc"
}

get_secure() {
  key="$1"
  run_cmd /system/bin/settings get secure "$key"
}

put_secure() {
  key="$1"
  value="$2"
  run_cmd /system/bin/settings put secure "$key" "$value"
}

ensure_accessibility_enabled() {
  enabled=$(get_secure accessibility_enabled)
  rc=$?
  if [ "$rc" -ne 0 ]; then
    log_line "repair failed: read accessibility_enabled rc=$rc"
    return 1
  fi
  if [ "$enabled" != "1" ]; then
    put_secure accessibility_enabled 1 >/dev/null
    rc=$?
    if [ "$rc" -eq 0 ]; then
      log_line "repair applied: accessibility_enabled=1"
      return 0
    fi
    log_line "repair failed: put accessibility_enabled=1 rc=$rc"
    return 1
  fi
  return 0
}

wait_boot_completed() {
  while true; do
    boot_completed=$(run_cmd /system/bin/getprop sys.boot_completed)
    rc=$?
    if [ "$rc" -eq 0 ] && [ "$boot_completed" = "1" ]; then
      return 0
    fi
    if [ "$rc" -ne 0 ]; then
      log_line "boot wait: getprop sys.boot_completed rc=$rc"
    fi
    sleep 1
  done
}

repair_round() {
  cur=$(get_secure enabled_accessibility_services)
  rc=$?
  if [ "$rc" -ne 0 ]; then
    log_line "repair failed: read enabled_accessibility_services rc=$rc"
    return 1
  fi

  has_component=0
  case "$cur" in
    *"$COMP_FULL"*)
      has_component=1
      ;;
  esac
  if [ "$has_component" -eq 0 ]; then
    case "$cur" in
      *"$COMP_SHORT"*)
        has_component=1
        ;;
    esac
  fi
  if [ "$has_component" -eq 1 ]; then
    ensure_accessibility_enabled
    return 0
  fi

  case "$cur" in
    ""|null)
      put_secure enabled_accessibility_services "$COMP_FULL" >/dev/null
      rc=$?
      if [ "$rc" -eq 0 ]; then
        log_line "repair applied: enabled_accessibility_services=$COMP_FULL"
      else
        log_line "repair failed: set enabled_accessibility_services rc=$rc value=$COMP_FULL"
      fi
      ensure_accessibility_enabled
      return 0
      ;;
    *)
      new_value="${cur}:$COMP_FULL"
      put_secure enabled_accessibility_services "$new_value" >/dev/null
      rc=$?
      if [ "$rc" -eq 0 ]; then
        log_line "repair applied: appended enabled_accessibility_services=$new_value"
      else
        log_line "repair failed: append enabled_accessibility_services rc=$rc value=$new_value"
      fi
      ensure_accessibility_enabled
      return 0
      ;;
  esac
}

wait_boot_completed
log_line "service started interval=${INTERVAL}s component=$COMP_FULL"

while true; do
  repair_round
  sleep "$INTERVAL"
done
