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
BUILD_TYPE="Debug"
DISCONNECT_WIFI_AFTER_INSTALL="false"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/install-phone-and-watch.sh --phone <serial> --watch <serial> [--target both|phone|watch]
                                      [--release] [--build-type <GradleBuildType>]

Defaults:
  target: both
  build: debug

Examples:
  ./scripts/install-phone-and-watch.sh --phone <phone-serial> --watch <watch-serial>
  ./scripts/install-phone-and-watch.sh --phone <phone-serial> --watch <watch-serial> --target phone
  ./scripts/install-phone-and-watch.sh --phone <phone-serial> --watch <watch-serial> --target watch
  ./scripts/install-phone-and-watch.sh --phone <phone-serial> --watch <watch-serial> --release

Notes:
  --release maps to the non-debuggable batteryTest build used for battery testing.
  --build-type overrides --release if you want a specific Gradle build type.
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
    --debug)
      BUILD_TYPE="Debug"
      DISCONNECT_WIFI_AFTER_INSTALL="false"
      shift
      ;;
    --release)
      BUILD_TYPE="BatteryTest"
      DISCONNECT_WIFI_AFTER_INSTALL="true"
      shift
      ;;
    --build-type)
      BUILD_TYPE="$2"
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

build_dir="$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')"
apk_name_suffix="$build_dir"

echo "Target: $TARGET"
echo "Build type: $BUILD_TYPE"
echo "Using phone: $PHONE_SERIAL"
echo "Using watch: $WATCH_SERIAL"
echo

gradle_tasks=()
if [[ "$TARGET" == "phone" || "$TARGET" == "both" ]]; then
  gradle_tasks+=(":app:assemble${BUILD_TYPE}")
fi
if [[ "$TARGET" == "watch" || "$TARGET" == "both" ]]; then
  gradle_tasks+=(":wear:assemble${BUILD_TYPE}")
fi

echo "Building APKs..."
./gradlew "${gradle_tasks[@]}"

find_apk() {
  local module="$1"
  local variant="$2"

  local apk
  apk="$(find "$module/build/outputs/apk" -type f -name "*.apk" | grep -i "/${variant}/" | head -n 1 || true)"

  if [[ -z "$apk" ]]; then
    echo "Could not find APK for $module variant $variant" >&2
    echo "Available APKs:" >&2
    find "$module/build/outputs/apk" -type f -name "*.apk" >&2 || true
    exit 1
  fi

  echo "$apk"
}

if [[ "$TARGET" == "phone" || "$TARGET" == "both" ]]; then
  PHONE_APK="$(find_apk app "$build_dir")"
  echo "Installing phone app on $PHONE_SERIAL"
  adb -s "$PHONE_SERIAL" install --no-streaming -r "$PHONE_APK"
  echo "Launching phone app"
  adb -s "$PHONE_SERIAL" shell am start -n com.aaronjencks.justrun/com.example.justrun.MainActivity >/dev/null
fi

if [[ "$TARGET" == "watch" || "$TARGET" == "both" ]]; then
  WATCH_APK="$(find_apk wear "$build_dir")"
  echo "Installing watch app on $WATCH_SERIAL"
  adb -s "$WATCH_SERIAL" install --no-streaming -r "$WATCH_APK"
  echo "Launching watch app"
  adb -s "$WATCH_SERIAL" shell am start -n com.aaronjencks.justrun/com.example.justrun.wear.WearMainActivity >/dev/null
fi

if [[ "$DISCONNECT_WIFI_AFTER_INSTALL" == "true" ]]; then
  echo
  echo "Disconnecting Wi-Fi adb sessions to reduce background debug overhead..."
  while IFS=$'\t' read -r serial status; do
    [[ "$serial" == "List of devices attached" || -z "$serial" ]] && continue
    [[ "$status" != "device" ]] && continue
    if [[ "$serial" == *:* ]]; then
      adb disconnect "$serial" >/dev/null || true
      echo "Disconnected Wi-Fi adb device: $serial"
    fi
  done < <(adb devices)
fi

echo
echo "Installed successfully."
