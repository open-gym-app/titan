#!/usr/bin/env bash
# The device settings the accessibility pass changes, and putting them back. The Makefile's
# a11y-* targets call it; see docs/ACCESSIBILITY.md.
#
#   settings.sh enable-service COMPONENT   add COMPONENT to the enabled accessibility services
#   settings.sh font-scale SCALE           set the system font scale
#   settings.sh reset COMPONENT...         put back what the pass changed
#
# Before the pass changes anything, the first of these saves what the phone had: the enabled
# accessibility services, the master switch they sit behind and the font scale, in
# $A11Y_STATE_DIR (build/a11y by default), one file per device serial. `reset` writes those
# values back, so a developer who uses an accessibility service of their own ends the pass
# with it still on. With nothing saved (a `gradlew clean` took build/ away, say), `reset`
# takes the COMPONENTs it is given out of the list and leaves everything else in it alone.
#
# `adb shell` hands its arguments to the phone's shell as one command line, which parses them
# again; a value read off the phone and passed back bare would be run as shell syntax there.
# So every value is written back single-quoted, and component names are checked first.
set -euo pipefail

ADB="${ADB:-adb}"
STATE_DIR="${A11Y_STATE_DIR:-build/a11y}"
SERVICES=enabled_accessibility_services
SWITCH=accessibility_enabled
FONT_SCALE=font_scale

die() { echo "$*" >&2; exit 1; }

# $1 quoted for a POSIX shell: inside single quotes, nothing is special but the quote itself.
quote() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

# `settings get` answers `null` for a setting that was never written, which is a different
# state from an empty one and is put back by deleting the setting.
get() { "$ADB" shell settings get "$1" "$2" | tr -d '\r'; }
put() { "$ADB" shell "settings put $1 $2 $(quote "$3")" >/dev/null; }
restore() {
  if [[ "$3" == null ]]; then
    "$ADB" shell settings delete "$1" "$2" >/dev/null
  else
    put "$1" "$2" "$3"
  fi
}

# A component name is `package/class`: letters, digits, `.`, `_` and `$`, nothing a shell
# would act on.
check_component() {
  [[ "$1" =~ ^[A-Za-z0-9._]+/[A-Za-z0-9._\$]+$ ]] || die "Not an accessibility service component: $1"
}

state_file() {
  local serial
  serial="$("$ADB" get-serialno | tr -d '\r')"
  [[ -n "$serial" && "$serial" != unknown ]] || die "No device on adb."
  printf '%s/%s' "$STATE_DIR" "${serial//[^A-Za-z0-9._-]/_}"
}

# Saves the three settings, unless an earlier step of this pass already has: the second
# target run must not record the first one's changes as the phone's own.
save_once() {
  local file services switch scale
  file="$(state_file)"
  [[ -f "$file" ]] && return
  services="$(get secure "$SERVICES")"
  switch="$(get secure "$SWITCH")"
  scale="$(get system "$FONT_SCALE")"
  mkdir -p "$STATE_DIR"
  printf '%s\n%s\n%s\n' "$services" "$switch" "$scale" >"$file.tmp"
  mv "$file.tmp" "$file"
}

# Adds one service to the colon-separated list Android keeps, rather than replacing the list:
# switching the Scanner on must not switch TalkBack off, or the two halves of the pass cannot
# be run together. `accessibility_enabled` is the master switch the list is read behind.
enable_service() {
  local service="$1" current
  check_component "$service"
  save_once
  current="$(get secure "$SERVICES")"
  case ":$current:" in
    *":$service:"*) ;;
    :null: | ::) put secure "$SERVICES" "$service" ;;
    *) put secure "$SERVICES" "$current:$service" ;;
  esac
  put secure "$SWITCH" 1
}

font_scale() {
  [[ "$1" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "Not a font scale: $1"
  save_once
  put system "$FONT_SCALE" "$1"
}

reset() {
  local file services switch scale
  file="$(state_file)"
  if [[ -f "$file" ]]; then
    { IFS= read -r services; IFS= read -r switch; IFS= read -r scale; } <"$file"
    restore secure "$SERVICES" "$services"
    restore secure "$SWITCH" "$switch"
    restore system "$FONT_SCALE" "$scale"
    rm -f "$file"
    echo "Accessibility services and font scale are back to what they were before the pass."
    return
  fi

  local current entries kept=() entry service
  current="$(get secure "$SERVICES")"
  [[ "$current" == null ]] && current=""
  IFS=: read -ra entries <<<"$current"
  for entry in "${entries[@]}"; do
    for service in "$@"; do
      [[ "$entry" == "$service" ]] && continue 2
    done
    [[ -n "$entry" ]] && kept+=("$entry")
  done
  current="$(IFS=:; printf '%s' "${kept[*]}")"
  put secure "$SERVICES" "$current"
  [[ -z "$current" ]] && put secure "$SWITCH" 0
  put system "$FONT_SCALE" 1.0
  echo "Nothing saved for this device: switched off only the pass's own services, font scale 1.0."
}

case "${1:-}" in
  enable-service) [[ $# -eq 2 ]] || die "usage: $0 enable-service COMPONENT"; enable_service "$2" ;;
  font-scale) [[ $# -eq 2 ]] || die "usage: $0 font-scale SCALE"; font_scale "$2" ;;
  reset) shift; reset "$@" ;;
  *) die "usage: $0 enable-service COMPONENT | font-scale SCALE | reset COMPONENT..." ;;
esac
