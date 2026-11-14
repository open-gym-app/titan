#!/usr/bin/env bash
# Runs settings.sh against a fake `adb` on PATH whose phone is a directory of files, one per
# setting. The fake hands `adb shell`'s arguments to `sh -c` joined by spaces, as adb does, so
# a value that settings.sh failed to quote would be run there; the first case's value carries
# a command that would leave a file behind. `make a11y-settings-test` runs it, and so does CI.
set -euo pipefail

script="${A11Y_SETTINGS:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/settings.sh}"
work="$(mktemp -d "${TMPDIR:-/tmp}/a11y-settings.XXXXXX")"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat >"$work/bin/adb" <<'FAKE'
#!/usr/bin/env bash
set -eu
# `adb -s SERIAL ...`, the way `make ADB="adb -s SERIAL"` calls it.
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
  get-serialno) echo "FAKE-0001"; exit 0 ;;
  shell) shift ;;
  *) echo "fake adb: $1 is not faked" >&2; exit 1 ;;
esac
# A real phone's `settings`, over files. `get` answers with the CRLF a pty adds on some hosts.
exec sh -c '
settings() {
  file="$PHONE/$2/$3"
  case "$1" in
    get) if [ -f "$file" ]; then cat "$file"; else printf null; fi; printf "\r\n" ;;
    put) mkdir -p "$PHONE/$2"; printf %s "$4" >"$file" ;;
    delete) rm -f "$file" ;;
  esac
}
'"$*"
FAKE
chmod +x "$work/bin/adb"

export PATH="$work/bin:$PATH" PHONE="$work/phone" A11Y_STATE_DIR="$work/state"
unset ADB

SCANNER=com.google.android.apps.accessibility.auditor/com.google.android.apps.accessibility.auditor.ScannerService
TALKBACK=com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
OWN=com.example.switch/com.example.switch.SwitchService
HOSTILE="$OWN:x/y;touch $work/pwned;\$(touch $work/pwned)'\"\`touch $work/pwned\`"

failures=0
fail() { echo "FAIL: $case_name: $*"; failures=$((failures + 1)); }

# setting NAMESPACE KEY -> its value, or `<unset>`
setting() { if [[ -f "$PHONE/$1/$2" ]]; then cat "$PHONE/$1/$2"; else printf '<unset>'; fi; }
expect() {
  local got
  got="$(setting "$1" "$2")"
  [[ "$got" == "$3" ]] || fail "$1/$2 is [$got], want [$3]"
}
phone() {
  rm -rf "$PHONE" "$A11Y_STATE_DIR" "$work/pwned"
  mkdir -p "$PHONE/secure" "$PHONE/system"
}
seed() { printf %s "$3" >"$PHONE/$1/$2"; }
run() { bash "$script" "$@" >"$work/out" 2>&1 || { fail "settings.sh $* exited non-zero: $(cat "$work/out")"; }; }

case_name="a developer's own service survives the pass, however odd its list"
phone
seed secure enabled_accessibility_services "$HOSTILE"
seed secure accessibility_enabled 1
seed system font_scale 1.15
run enable-service "$SCANNER"
expect secure enabled_accessibility_services "$HOSTILE:$SCANNER"
run enable-service "$TALKBACK"
expect secure enabled_accessibility_services "$HOSTILE:$SCANNER:$TALKBACK"
run font-scale 2.0
expect system font_scale 2.0
run reset "$SCANNER" "$TALKBACK"
expect secure enabled_accessibility_services "$HOSTILE"
expect secure accessibility_enabled 1
expect system font_scale 1.15
[[ ! -e "$work/pwned" ]] || fail "a value read off the phone was run by the phone's shell"
[[ -z "$(ls -A "$A11Y_STATE_DIR")" ]] || fail "the saved state outlived the reset"

case_name="settings that were never written are deleted again, not left empty"
phone
run enable-service "$SCANNER"
run font-scale 1.5
run reset "$SCANNER"
expect secure enabled_accessibility_services "<unset>"
expect secure accessibility_enabled "<unset>"
expect system font_scale "<unset>"

case_name="an empty list stays an empty list"
phone
seed secure enabled_accessibility_services ""
seed secure accessibility_enabled 0
run enable-service "$SCANNER"
expect secure enabled_accessibility_services "$SCANNER"
expect secure accessibility_enabled 1
run reset "$SCANNER"
expect secure enabled_accessibility_services ""
expect secure accessibility_enabled 0

case_name="the second step of a pass does not save the first one's changes"
phone
seed secure enabled_accessibility_services "$OWN"
seed secure accessibility_enabled 1
run enable-service "$SCANNER"
run enable-service "$SCANNER"
expect secure enabled_accessibility_services "$OWN:$SCANNER"
run reset "$SCANNER"
expect secure enabled_accessibility_services "$OWN"

case_name="a pass abandoned without a reset does not hand its snapshot to the next one"
phone
seed secure enabled_accessibility_services "$OWN"
seed secure accessibility_enabled 1
seed system font_scale 1.0
run enable-service "$SCANNER"
# Weeks later, by hand: the Scanner off, TalkBack on for good, a larger font.
seed secure enabled_accessibility_services "$OWN:$TALKBACK"
seed system font_scale 1.3
run enable-service "$SCANNER"
run reset "$SCANNER"
expect secure enabled_accessibility_services "$OWN:$TALKBACK"
expect secure accessibility_enabled 1
expect system font_scale 1.3

case_name="a reset keeps what was changed by hand since the pass, and puts back the rest"
phone
seed secure enabled_accessibility_services "$OWN"
seed secure accessibility_enabled 1
seed system font_scale 1.15
run enable-service "$SCANNER"
run font-scale 2.0
seed secure enabled_accessibility_services "$OWN:$SCANNER:$TALKBACK"
run reset "$SCANNER"
expect secure enabled_accessibility_services "$OWN:$TALKBACK"
expect secure accessibility_enabled 1
expect system font_scale 1.15
[[ -z "$(ls -A "$A11Y_STATE_DIR")" ]] || fail "the saved state outlived the reset"

case_name="a font scale changed by hand during the pass stays"
phone
seed secure enabled_accessibility_services "$OWN"
seed secure accessibility_enabled 1
seed system font_scale 1.0
run enable-service "$SCANNER"
run font-scale 2.0
seed system font_scale 1.3
run reset "$SCANNER"
expect secure enabled_accessibility_services "$OWN"
expect system font_scale 1.3

case_name="with nothing saved, only the pass's own services go"
phone
seed secure enabled_accessibility_services "$SCANNER:$OWN:$TALKBACK"
seed secure accessibility_enabled 1
seed system font_scale 2.0
run reset "$SCANNER" "$TALKBACK"
expect secure enabled_accessibility_services "$OWN"
expect secure accessibility_enabled 1
expect system font_scale 1.0

case_name="with nothing saved and nothing else on, the master switch goes off"
phone
seed secure enabled_accessibility_services "$SCANNER"
seed secure accessibility_enabled 1
run reset "$SCANNER" "$TALKBACK"
expect secure enabled_accessibility_services ""
expect secure accessibility_enabled 0

case_name="a component that is not a component name is refused before anything changes"
phone
seed secure enabled_accessibility_services "$OWN"
if bash "$script" enable-service "a/b;touch $work/pwned" >/dev/null 2>&1; then
  fail "settings.sh accepted it"
fi
expect secure enabled_accessibility_services "$OWN"
[[ ! -e "$work/pwned" ]] || fail "the component was run by the phone's shell"
[[ ! -e "$A11Y_STATE_DIR" ]] || fail "it saved state for a step it refused"

case_name="an ADB that names its device, as make ADB=\"adb -s SERIAL\" passes it"
phone
seed secure enabled_accessibility_services "$OWN"
ADB="adb -s FAKE-0001" run enable-service "$SCANNER"
expect secure enabled_accessibility_services "$OWN:$SCANNER"
ADB="adb -s FAKE-0001" run reset "$SCANNER"
expect secure enabled_accessibility_services "$OWN"

if ((failures)); then
  echo "$failures check(s) failed."
  exit 1
fi
echo "All accessibility settings cases passed."
