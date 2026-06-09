#!/usr/bin/env bash
set -euo pipefail

PHONE_SERIAL="${PHONE_SERIAL:-}"
WATCH_SERIAL="${WATCH_SERIAL:-}"
DURATION_SECONDS=90
PACKAGE_NAME="com.aaronjencks.justrun"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/smoke-daily-activity-sync.sh --phone <serial> --watch <serial> [--duration <seconds>]

Examples:
  ./scripts/smoke-daily-activity-sync.sh --phone <phone-serial> --watch <watch-serial>
  ./scripts/smoke-daily-activity-sync.sh --phone 192.168.0.10:5555 --watch 192.168.0.137:39441 --duration 120

Notes:
  Use the first column from `adb devices -l` as the serial.
  This validates the daily activity sync handshake through logcat; it does not simulate sensor steps or heart-rate samples.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --phone)
      PHONE_SERIAL="$2"
      shift 2
      ;;
    --watch)
      WATCH_SERIAL="$2"
      shift 2
      ;;
    --duration)
      DURATION_SECONDS="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required but was not found on PATH." >&2
  exit 1
fi

if [[ -z "$PHONE_SERIAL" || -z "$WATCH_SERIAL" ]]; then
  usage >&2
  echo >&2
  echo "adb devices -l:" >&2
  adb devices -l >&2 || true
  exit 1
fi

PHONE_LOG="$(mktemp)"
WATCH_LOG="$(mktemp)"
PHONE_DIAGNOSTICS="$(mktemp)"
WATCH_DIAGNOSTICS="$(mktemp)"
trap 'rm -f "$PHONE_LOG" "$WATCH_LOG" "$PHONE_DIAGNOSTICS" "$WATCH_DIAGNOSTICS"' EXIT

echo "Clearing logcat on phone and watch..."
adb -s "$PHONE_SERIAL" logcat -c
adb -s "$WATCH_SERIAL" logcat -c
adb -s "$PHONE_SERIAL" shell rm -f "/sdcard/Android/data/${PACKAGE_NAME}/files/diagnostics/activity.log" 2>/dev/null || true
adb -s "$WATCH_SERIAL" shell rm -f "/sdcard/Android/data/${PACKAGE_NAME}/files/diagnostics/activity.log" 2>/dev/null || true

echo "Starting Just Run on both devices..."
adb -s "$PHONE_SERIAL" shell am start -n "${PACKAGE_NAME}/com.example.justrun.MainActivity" >/dev/null 2>&1 || true
adb -s "$WATCH_SERIAL" shell am start -n "${PACKAGE_NAME}/com.example.justrun.wear.WearMainActivity" >/dev/null 2>&1 || true

echo "Waiting ${DURATION_SECONDS}s for daily activity handshake..."
sleep "$DURATION_SECONDS"

adb -s "$PHONE_SERIAL" logcat -d >"$PHONE_LOG"
adb -s "$WATCH_SERIAL" logcat -d >"$WATCH_LOG"
adb -s "$PHONE_SERIAL" shell cat "/sdcard/Android/data/${PACKAGE_NAME}/files/diagnostics/activity.log" >"$PHONE_DIAGNOSTICS" 2>/dev/null || true
adb -s "$WATCH_SERIAL" shell cat "/sdcard/Android/data/${PACKAGE_NAME}/files/diagnostics/activity.log" >"$WATCH_DIAGNOSTICS" 2>/dev/null || true

cat "$PHONE_DIAGNOSTICS" >>"$PHONE_LOG"
cat "$WATCH_DIAGNOSTICS" >>"$WATCH_LOG"

check_log() {
  local label="$1"
  local file="$2"
  local pattern="$3"

  if grep -Fq "$pattern" "$file"; then
    echo "PASS ${label}: ${pattern}"
  else
    echo "FAIL ${label}: missing '${pattern}'"
    return 1
  fi
}

failed=false
check_log "phone" "$PHONE_LOG" "daily activity sync request" || failed=true
check_log "watch" "$WATCH_LOG" "daily activity sync request received" || failed=true
check_log "watch" "$WATCH_LOG" "daily activity sync sent" || failed=true

if grep -Fq "daily activity watch snapshot accepted" "$PHONE_LOG"; then
  echo "PASS phone: daily activity watch snapshot accepted"
elif grep -Fq "daily activity watch snapshot ignored" "$PHONE_LOG"; then
  echo "PASS phone: daily activity watch snapshot received and ignored as stale/current"
else
  echo "FAIL phone: no daily activity watch snapshot accept/ignore decision"
  failed=true
fi

echo
echo "Recent matching phone log lines:"
grep -F "daily activity" "$PHONE_LOG" | tail -20 || true
echo
echo "Recent matching watch log lines:"
grep -F "daily activity" "$WATCH_LOG" | tail -20 || true

if [[ "$failed" == "true" ]]; then
  exit 1
fi
