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
# Beside what the phone had, the file holds what each step left it at. A setting that no
# longer matches was changed outside the pass, by hand or after a pass that never reached
# `reset`, so what the phone has now is the developer's own: the next step saves it in place
# of the old value, and `reset` keeps it rather than writing back a snapshot from weeks ago.
#
# `adb shell` hands its arguments to the phone's shell as one command line, which parses them
# again; a value read off the phone and passed back bare would be run as shell syntax there.
# So every value is written back single-quoted, and component names are checked first.
set -euo pipefail

# `make ADB="adb -s SERIAL"` picks one of several devices, so ADB is a command line, split
# into words the way the Makefile's own `$(ADB) shell` lines split it.
read -ra ADB <<<"${ADB:-adb}"
STATE_DIR="${A11Y_STATE_DIR:-build/a11y}"
SERVICES=enabled_accessibility_services
SWITCH=accessibility_enabled
FONT_SCALE=font_scale
# The three settings, in the order the state file and the NOW, SAVED and LEFT arrays keep them.
SPACES=(secure secure system)
KEYS=("$SERVICES" "$SWITCH" "$FONT_SCALE")
NOW=() SAVED=() LEFT=() STATE=""

die() { echo "$*" >&2; exit 1; }

# $1 quoted for a POSIX shell: inside single quotes, nothing is special but the quote itself.
quote() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

# `settings get` answers `null` for a setting that was never written, which is a different
# state from an empty one and is put back by deleting the setting.
get() { "${ADB[@]}" shell settings get "$1" "$2" | tr -d '\r'; }
put() { "${ADB[@]}" shell "settings put $1 $2 $(quote "$3")" >/dev/null; }
restore() {
  if [[ "$3" == null ]]; then
    "${ADB[@]}" shell settings delete "$1" "$2" >/dev/null
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
  serial="$("${ADB[@]}" get-serialno | tr -d '\r')"
  [[ -n "$serial" && "$serial" != unknown ]] || die "No device on adb."
  printf '%s/%s' "$STATE_DIR" "${serial//[^A-Za-z0-9._-]/_}"
}

# NOW: the three settings as the phone has them.
read_now() {
  local i
  for i in 0 1 2; do NOW[i]="$(get "${SPACES[i]}" "${KEYS[i]}")"; done
}

# SAVED, what the phone had before the pass, and LEFT, what its last step left the phone at,
# from the state file $1. Fails when there is no such file.
read_state() {
  [[ -f "$1" ]] || return 1
  local i
  {
    for i in 0 1 2; do IFS= read -r "SAVED[$i]" || SAVED[i]=""; done
    for i in 0 1 2; do IFS= read -r "LEFT[$i]" || LEFT[i]=""; done
  } <"$1"
}

# Settles SAVED before a step changes anything. A setting still at what the pass left it at
# keeps the value saved before the pass: the second target run must not record the first
# one's changes as the phone's own. Any other setting was changed outside the pass, and what
# it holds now is what the pass will put back.
begin() {
  local i have=1
  STATE="$(state_file)"
  read_now
  read_state "$STATE" || have=0
  for i in 0 1 2; do
    if ((have == 0)) || [[ "${NOW[i]}" != "${LEFT[i]}" ]]; then SAVED[i]="${NOW[i]}"; fi
  done
}

# Writes SAVED and, read back off the phone, what this step left it at.
finish() {
  read_now
  mkdir -p "$STATE_DIR"
  printf '%s\n' "${SAVED[@]}" "${NOW[@]}" >"$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
}

# $1, a colon-separated service list, without the services named after it and without empty
# entries. `${a[@]+...}` because bash 3.2, macOS's, calls an empty array unbound under `set -u`.
without() {
  local list="$1" entries kept=() entry service
  shift
  [[ "$list" == null ]] && list=""
  IFS=: read -ra entries <<<"$list"
  for entry in ${entries[@]+"${entries[@]}"}; do
    for service in "$@"; do
      [[ "$entry" == "$service" ]] && continue 2
    done
    [[ -n "$entry" ]] && kept+=("$entry")
  done
  (IFS=:; printf '%s' "${kept[*]+"${kept[*]}"}")
}

# Takes the pass's own services out of the list the phone has, and switches the master switch
# off if that empties it.
strip_services() {
  local services
  services="$(without "${NOW[0]}" "$@")"
  put secure "$SERVICES" "$services"
  if [[ -z "$services" ]]; then put secure "$SWITCH" 0; fi
}

# Adds one service to the colon-separated list Android keeps, rather than replacing the list:
# switching the Scanner on must not switch TalkBack off, or the two halves of the pass cannot
# be run together. `accessibility_enabled` is the master switch the list is read behind.
enable_service() {
  local service="$1"
  check_component "$service"
  begin
  case ":${NOW[0]}:" in
    *":$service:"*) ;;
    :null: | ::) put secure "$SERVICES" "$service" ;;
    *) put secure "$SERVICES" "${NOW[0]}:$service" ;;
  esac
  put secure "$SWITCH" 1
  finish
}

font_scale() {
  [[ "$1" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "Not a font scale: $1"
  begin
  put system "$FONT_SCALE" "$1"
  finish
}

reset() {
  local file
  file="$(state_file)"
  read_now
  if ! read_state "$file"; then
    strip_services "$@"
    put system "$FONT_SCALE" 1.0
    echo "Nothing saved for this device: switched off only the pass's own services, font scale 1.0."
    return
  fi

  # A setting changed since the pass last touched it keeps that change: the service list loses
  # only the pass's own services, and the font scale stays where the developer put it.
  local kept=0
  if [[ "${NOW[0]}" == "${LEFT[0]}" ]]; then
    restore secure "$SERVICES" "${SAVED[0]}"
    if [[ "${NOW[1]}" == "${LEFT[1]}" ]]; then
      restore secure "$SWITCH" "${SAVED[1]}"
    else
      kept=1
    fi
  else
    strip_services "$@"
    kept=1
  fi
  if [[ "${NOW[2]}" == "${LEFT[2]}" ]]; then
    restore system "$FONT_SCALE" "${SAVED[2]}"
  else
    kept=1
  fi
  rm -f "$file"
  if ((kept)); then
    echo "Put back what the phone had before the pass, but kept what was changed on it since."
  else
    echo "Accessibility services and font scale are back to what they were before the pass."
  fi
}

case "${1:-}" in
  enable-service) [[ $# -eq 2 ]] || die "usage: $0 enable-service COMPONENT"; enable_service "$2" ;;
  font-scale) [[ $# -eq 2 ]] || die "usage: $0 font-scale SCALE"; font_scale "$2" ;;
  reset) shift; reset "$@" ;;
  *) die "usage: $0 enable-service COMPONENT | font-scale SCALE | reset COMPONENT..." ;;
esac
