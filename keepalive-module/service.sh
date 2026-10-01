#!/system/bin/sh

export PATH="/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH"

PKG="com.jin.doubaolongpressvoice"
COMP_FULL="com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService"
COMP_SHORT="com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"
# ColorOS re-strips or unbinds the service during boot/unlock and deep-sleep cleanup.
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
  if [ -x /system/bin/settings ]; then
    run_cmd /system/bin/settings get secure "$key"
  else
    run_cmd /system/bin/cmd settings get secure "$key"
  fi
}

put_secure() {
  key="$1"
  value="$2"
  if [ -x /system/bin/settings ]; then
    run_cmd /system/bin/settings put secure "$key" "$value"
  else
    run_cmd /system/bin/cmd settings put secure "$key" "$value"
  fi
}

apply_whitelists_and_grants() {
  /system/bin/pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  /system/bin/dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true
  /system/bin/cmd appops set "$PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
  /system/bin/cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
  /system/bin/cmd appops set "$PKG" SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION allow >/dev/null 2>&1 || true
  log_line "whitelists and WRITE_SECURE_SETTINGS applied for $PKG"
}

is_service_bound() {
  dump_out=$(/system/bin/dumpsys accessibility 2>/dev/null)
  case "$dump_out" in
    *"Service[label=豆包语音发送助手"*)
      return 0
      ;;
  esac
  return 1
}

strip_our_component() {
  raw_list="$1"
  cleaned=""
  old_ifs="$IFS"
  IFS=":"
  for item in $raw_list; do
    case "$item" in
      ""|"null"|"$COMP_FULL"|"$COMP_SHORT")
        ;;
      *)
        if [ -z "$cleaned" ]; then
          cleaned="$item"
        else
          cleaned="${cleaned}:$item"
        fi
        ;;
    esac
  done
  IFS="$old_ifs"
  printf '%s' "$cleaned"
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

  stripped=$(strip_our_component "$cur")
  if [ -z "$stripped" ]; then
    target_val="$COMP_FULL"
  else
    target_val="${stripped}:$COMP_FULL"
  fi

  if [ "$has_component" -eq 1 ]; then
    ensure_accessibility_enabled
    if is_service_bound; then
      return 0
    fi
    sleep 2
    if is_service_bound; then
      return 0
    fi
    put_secure enabled_accessibility_services "$stripped" >/dev/null
    /system/bin/am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    put_secure enabled_accessibility_services "$target_val" >/dev/null
    rc=$?
    ensure_accessibility_enabled
    if [ "$rc" -eq 0 ]; then
      log_line "repair applied: toggled unbound service target=$target_val"
    else
      log_line "repair failed: toggle unbound service rc=$rc"
    fi
    return 0
  fi

  put_secure enabled_accessibility_services "$target_val" >/dev/null
  rc=$?
  if [ "$rc" -eq 0 ]; then
    log_line "repair applied: enabled_accessibility_services=$target_val"
  else
    log_line "repair failed: set enabled_accessibility_services rc=$rc value=$target_val"
  fi
  ensure_accessibility_enabled
  return 0
}

wait_boot_completed
log_line "service started interval=${INTERVAL}s component=$COMP_FULL"
apply_whitelists_and_grants

while true; do
  repair_round
  sleep "$INTERVAL"
done
