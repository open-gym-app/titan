#!/usr/bin/env python3
"""Fill the device rows of the accessibility pass record from the phone on adb.

The record at the bottom of ``docs/ACCESSIBILITY.md`` has two kinds of row. Five are facts
about the phone the pass ran on - which phone, which Android, which TalkBack and Scanner, and
when - and this reads every one of them off the device so none is typed by hand or goes stale.
The other four (Scanner errors, the TalkBack walk, the 2x walk, deviations found) are what the
person holding the phone saw; nothing on the device holds them - Accessibility Scanner keeps
its results in its own private storage, which adb cannot read on an unrooted phone - so they
come in as flags, and a row whose flag is not given is left as it was.

Usage:
    ./record.py                                      fill the device rows only
    ./record.py --scanner-errors 0 --talkback-walk "every action reached on all twenty" ...
    ./record.py --adb /path/to/adb                   adb off PATH (the Makefile passes $(ADB))
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs" / "ACCESSIBILITY.md"
RECORD_HEADING = "### Record"

# TalkBack ships under two names: Google's, from the Play Store's Accessibility Suite, and the
# rebrand One UI preloads on Samsung phones. A phone can carry both; the record names each one
# it finds, since either can be the one the walk was done with.
TALKBACK_PACKAGES = {
    "com.google.android.marvin.talkback": "TalkBack",
    "com.samsung.android.accessibility.talkback": "Samsung TalkBack",
}
SCANNER_PACKAGE = "com.google.android.apps.accessibility.auditor"

# The rows the flags fill, by the label the table carries.
JUDGEMENT_ROWS = {
    "scanner_errors": "Scanner errors",
    "talkback_walk": "TalkBack walk",
    "font_scale": "2× font scale",
    "deviations": "Deviations found",
}


class Device:
    """One phone on adb, read through ``adb shell``."""

    def __init__(self, adb: str) -> None:
        self.adb = adb

    def shell(self, *args: str) -> str:
        result = subprocess.run(
            [self.adb, "shell", *args],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            sys.exit(f"`{self.adb} shell {' '.join(args)}` failed: {detail or 'no device on adb?'}")
        # adb on some hosts hands the phone's line endings back untouched.
        return result.stdout.replace("\r", "").strip()

    def prop(self, name: str) -> str:
        return self.shell("getprop", name)

    def version_name(self, package: str) -> str | None:
        """The installed ``versionName`` of ``package``, or ``None`` when it is not installed."""
        installed = self.shell("pm", "list", "packages", package).splitlines()
        if f"package:{package}" not in installed:
            return None
        dump = self.shell("dumpsys", "package", package)
        match = re.search(r"versionName=(\S+)", dump)
        return match.group(1) if match else "installed, version unknown"


def device_rows(device: Device) -> dict[str, str]:
    manufacturer = device.prop("ro.product.manufacturer").capitalize()
    model = device.prop("ro.product.model")
    release = device.prop("ro.build.version.release")
    sdk = device.prop("ro.build.version.sdk")
    patch = device.prop("ro.build.version.security_patch")

    talkbacks = [
        f"{label} {version}"
        for package, label in TALKBACK_PACKAGES.items()
        if (version := device.version_name(package)) is not None
    ]
    scanner = device.version_name(SCANNER_PACKAGE)

    return {
        "Device": f"{manufacturer} {model}",
        "Android version": f"{release} (API {sdk}, security patch {patch})",
        "TalkBack version": ", ".join(talkbacks) or "not installed",
        "Accessibility Scanner version": scanner or "not installed",
        "Date": dt.date.today().isoformat(),
    }


def cell(value: str) -> str:
    """One table cell: a pipe would end it, and a newline would end the row."""
    return " ".join(value.split()).replace("|", "\\|")


def fill(text: str, rows: dict[str, str]) -> str:
    """Rewrite the value column of each row in ``rows`` in the record table, in place."""
    heading = text.find(RECORD_HEADING)
    if heading < 0:
        sys.exit(f"{DOC} has no '{RECORD_HEADING}' heading to fill.")
    head, tail = text[:heading], text[heading:]

    for label, value in rows.items():
        pattern = re.compile(rf"^\| {re.escape(label)} \|.*\|$", re.MULTILINE)
        tail, count = pattern.subn(lambda _: f"| {label} | {cell(value)} |", tail, count=1)
        if count == 0:
            sys.exit(f"The record table in {DOC} has no '{label}' row.")
    return head + tail


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--adb", default="adb", help="the adb binary (default: adb on PATH)")
    for flag, label in JUDGEMENT_ROWS.items():
        parser.add_argument(
            f"--{flag.replace('_', '-')}",
            metavar="TEXT",
            help=f"the '{label}' row",
        )
    return parser.parse_args(argv)


def main(argv: list[str]) -> None:
    args = parse_args(argv)
    rows = device_rows(Device(args.adb))
    for flag, label in JUDGEMENT_ROWS.items():
        if value := getattr(args, flag):
            rows[label] = value

    DOC.write_text(fill(DOC.read_text(encoding="utf-8"), rows), encoding="utf-8")

    for label, value in rows.items():
        print(f"  {label}: {value}")
    left = [label for label in JUDGEMENT_ROWS.values() if label not in rows]
    if left:
        print(f"Still to fill by hand in {DOC.relative_to(ROOT)}: {', '.join(left)}.")


if __name__ == "__main__":
    main(sys.argv[1:])
