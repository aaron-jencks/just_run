#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required but was not found on PATH." >&2
  exit 1
fi

PHONE_SERIAL="${PHONE_SERIAL:-}"
WATCH_SERIAL="${WATCH_SERIAL:-}"
TARGET="both"
PACKAGE_NAME="com.aaronjencks.justrun"
REMOTE_DIR="/sdcard/Android/data/${PACKAGE_NAME}/files/diagnostics"
OUTPUT_ROOT="${ROOT_DIR}/diagnostics"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/pull-diagnostics.sh --phone <serial> --watch <serial> [--target both|phone|watch]

Examples:
  ./scripts/pull-diagnostics.sh --phone <phone-serial> --watch <watch-serial>
  ./scripts/pull-diagnostics.sh --phone <phone-serial> --target phone
  ./scripts/pull-diagnostics.sh --watch <watch-serial> --target watch

Notes:
  Use the first column from `adb devices -l` as the serial.
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
    --target)
      TARGET="$2"
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

case "$TARGET" in
  both|phone|watch) ;;
  *)
    echo "Invalid --target value: $TARGET" >&2
    usage >&2
    exit 1
    ;;
esac

missing_serial=false
if [[ "$TARGET" == "both" || "$TARGET" == "phone" ]]; then
  if [[ -z "$PHONE_SERIAL" ]]; then
    echo "--phone is required when --target is '$TARGET'." >&2
    missing_serial=true
  fi
fi
if [[ "$TARGET" == "both" || "$TARGET" == "watch" ]]; then
  if [[ -z "$WATCH_SERIAL" ]]; then
    echo "--watch is required when --target is '$TARGET'." >&2
    missing_serial=true
  fi
fi
if [[ "$missing_serial" == "true" ]]; then
  usage >&2
  echo >&2
  echo "adb devices -l:" >&2
  adb devices -l >&2 || true
  exit 1
fi

timestamp="$(date +"%Y%m%d-%H%M%S")"

pull_device() {
  local label="$1"
  local serial="$2"
  local safe_serial="${serial//[:\/]/_}"
  local output_dir="${OUTPUT_ROOT}/${label}-${safe_serial}/${timestamp}"

  mkdir -p "$output_dir"
  echo "Pulling ${label} diagnostics from ${serial}"
  adb -s "$serial" pull "$REMOTE_DIR" "$output_dir/files" >/dev/null || {
    echo "No diagnostics directory found on ${label}, continuing." >&2
  }
  adb -s "$serial" logcat -d -t 2000 >"${output_dir}/logcat.txt" || {
    echo "Could not capture logcat from ${label}." >&2
  }
  echo "Saved ${label} diagnostics to ${output_dir}"
}

if [[ "$TARGET" == "both" || "$TARGET" == "phone" ]]; then
  pull_device "phone" "$PHONE_SERIAL"
fi
if [[ "$TARGET" == "both" || "$TARGET" == "watch" ]]; then
  pull_device "watch" "$WATCH_SERIAL"
fi

echo
echo "Diagnostics pull complete."
