#!/usr/bin/env python3
"""Hold every translation to the English strings it translates.

A missing key is the failure Android hides best: the string falls back to English, the app
keeps running, and nobody finds out until a French user reports one English sentence on an
otherwise French screen. Nothing in the build says a word about it. This walks every module's
``values/strings.xml`` and every ``values-<locale>/strings.xml`` beside it and reports, per
module and per locale:

  missing      a translatable key in ``values/`` with no translation
  extra        a key in the locale with no base key - a rename that missed a file, or a
               ``translatable="false"`` string that got translated anyway
  plurals      a ``<plurals>`` without one of the quantities the language's CLDR rules select
               (French, Spanish and Portuguese all pick ``many`` for a million), or carrying one
               they never select
  placeholders a ``%1$s`` in the translation that is not in the base, or one of the base's
               that the translation dropped - the first crashes, the second loses a value

Exits 0 whatever it finds, so a base string added in a PR is reported without blocking that
PR on its translation; ``--strict`` exits 1 on any finding, which is what "the parity check
reports zero missing keys" means when it is claimed. Under GitHub Actions the findings are
also written as workflow annotations, on the file they are about.

Usage:
    ./check.py                 report every module and locale
    ./check.py --strict        the same, exiting 1 if anything was reported
    ./check.py --locale fr     one locale only
    ./check.py --github        add ::warning annotations (implied under GITHUB_ACTIONS)
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# The plural categories each language's CLDR rules can select, by language subtag. A locale
# whose language is not listed is held to ``other`` alone, which every language has; add the
# line before adding the locale. ``en`` is here so the base files are checked the same way.
#
# ``many`` in French, Spanish and Portuguese is the round-million case (CLDR 38 and later):
# "1 000 000 séries" selects it, and a file without it silently reads ``other`` instead.
PLURAL_CATEGORIES: dict[str, frozenset[str]] = {
    "en": frozenset({"one", "other"}),
    "fr": frozenset({"one", "many", "other"}),
    "es": frozenset({"one", "many", "other"}),
    "pt": frozenset({"one", "many", "other"}),
}

# A resource qualifier that names a locale: ``fr``, ``pt-rBR``, ``b+sr+Latn``. Everything
# else beside ``values/`` - ``values-night``, ``values-sw600dp`` - is not a translation.
LOCALE_QUALIFIER = re.compile(r"^(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)$")

# ``%1$s``, ``%2$d``, ``%%``. The whole specifier is compared, so ``%1$s`` against ``%1$d`` is
# a mismatch: the argument is the same, but the formatter would refuse the value.
FORMAT_SPECIFIER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


@dataclass
class Plurals:
    items: dict[str, str]


@dataclass
class StringsFile:
    path: Path
    strings: dict[str, str] = field(default_factory=dict)
    plurals: dict[str, Plurals] = field(default_factory=dict)
    not_translatable: set[str] = field(default_factory=set)

    @property
    def keys(self) -> set[str]:
        return set(self.strings) | set(self.plurals)


@dataclass
class Finding:
    path: Path
    message: str


def parse(path: Path) -> StringsFile:
    parsed = StringsFile(path)
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if name is None:
            continue
        if element.tag == "string":
            if element.get("translatable") == "false":
                parsed.not_translatable.add(name)
            parsed.strings[name] = element.text or ""
        elif element.tag == "plurals":
            items = {item.get("quantity", ""): item.text or "" for item in element.findall("item")}
            parsed.plurals[name] = Plurals(items)
    return parsed


def specifiers(text: str) -> Counter[str]:
    return Counter(m for m in FORMAT_SPECIFIER.findall(text) if m != "%%")


def language_of(locale: str) -> str:
    if locale.startswith("b+"):
        return locale.split("+")[1]
    return locale.split("-")[0]


def compare(base: StringsFile, translation: StringsFile, locale: str) -> list[Finding]:
    findings: list[Finding] = []
    at = translation.path

    def report(message: str) -> None:
        findings.append(Finding(at, message))

    translatable = base.keys - base.not_translatable
    for key in sorted(translatable - translation.keys):
        report(f"missing: {key}")
    for key in sorted(translation.keys - base.keys):
        report(f"extra: {key} has no base string")
    for key in sorted(translation.keys & base.not_translatable):
        report(f"extra: {key} is translatable=\"false\" and must not be in {at.parent.name}")

    categories = PLURAL_CATEGORIES.get(language_of(locale), frozenset({"other"}))
    for key, plurals in sorted(translation.plurals.items()):
        base_plurals = base.plurals.get(key)
        if base_plurals is None:
            if key in base.strings:
                report(f"kind: {key} is a <string> in the base and a <plurals> here")
            continue
        present = set(plurals.items)
        for quantity in sorted(categories - present):
            report(f"plurals: {key} has no \"{quantity}\" form, which {locale} selects")
        for quantity in sorted(present - categories):
            report(f"plurals: {key} carries a \"{quantity}\" form {locale} never selects")
        # Each form is held to the base form of the same quantity, and a quantity the base
        # does not have (``many``) to the base's ``other``: a placeholder the base form
        # carries must survive, and one it does not carry cannot appear.
        for quantity, text in sorted(plurals.items.items()):
            reference = base_plurals.items.get(quantity, base_plurals.items.get("other", ""))
            expected, actual = specifiers(reference), specifiers(text)
            for spec in sorted((actual - expected).elements()):
                report(f"placeholders: {key}[{quantity}] uses {spec}, which the base form does not")
            for spec in sorted((expected - actual).elements()):
                report(f"placeholders: {key}[{quantity}] dropped {spec}")

    for key, text in sorted(translation.strings.items()):
        if key in base.plurals:
            report(f"kind: {key} is a <plurals> in the base and a <string> here")
            continue
        if key not in base.strings:
            continue
        expected, actual = specifiers(base.strings[key]), specifiers(text)
        for spec in sorted((actual - expected).elements()):
            report(f"placeholders: {key} uses {spec}, which the base does not")
        for spec in sorted((expected - actual).elements()):
            report(f"placeholders: {key} dropped {spec}")

    return findings


def base_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.glob("*/**/src/main/res/values/strings.xml")
        if "build" not in path.parts
    )


def locales_beside(base: Path) -> dict[str, Path]:
    found: dict[str, Path] = {}
    for directory in sorted(base.parents[1].glob("values-*")):
        qualifier = directory.name[len("values-"):]
        if LOCALE_QUALIFIER.match(qualifier) and (directory / "strings.xml").is_file():
            found[qualifier] = directory / "strings.xml"
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--strict", action="store_true", help="exit 1 if anything is reported")
    parser.add_argument("--locale", action="append", help="check this locale only (repeatable)")
    parser.add_argument(
        "--github",
        action="store_true",
        default=bool(os.environ.get("GITHUB_ACTIONS")),
        help="write findings as GitHub Actions warning annotations too",
    )
    args = parser.parse_args()

    bases = {path: parse(path) for path in base_files()}
    if not bases:
        print("No values/strings.xml found under", ROOT, file=sys.stderr)
        return 2

    # Every locale any module carries is expected in every module that has strings: a module
    # with no file for it is one whose every key is missing, and it is reported as such.
    all_locales: set[str] = set()
    per_module: dict[Path, dict[str, Path]] = {}
    for base in bases:
        per_module[base] = locales_beside(base)
        all_locales |= set(per_module[base])
    if args.locale:
        all_locales = {locale for locale in all_locales if locale in set(args.locale)}
        for wanted in set(args.locale) - all_locales:
            print(f"No values-{wanted} directory exists in any module", file=sys.stderr)
            return 2

    total = 0
    for base_path, base in bases.items():
        module = base_path.relative_to(ROOT).parents[4]
        if not base.keys:
            continue
        for locale in sorted(all_locales):
            translation_path = per_module[base_path].get(locale)
            if translation_path is None:
                missing_file = base_path.parent.parent / f"values-{locale}" / "strings.xml"
                findings = [
                    Finding(missing_file, f"missing: {key}")
                    for key in sorted(base.keys - base.not_translatable)
                ]
                headline = "no file"
            else:
                findings = compare(base, parse(translation_path), locale)
                headline = "ok" if not findings else f"{len(findings)} finding(s)"
            print(f"{module} · {locale}: {headline}")
            for finding in findings:
                print(f"    {finding.message}")
                if args.github:
                    rel = finding.path.relative_to(ROOT)
                    print(f"::warning file={rel},title=Translation ({locale})::{finding.message}")
            total += len(findings)

    print()
    if total:
        print(f"{total} finding(s) across {len(all_locales)} locale(s).")
    else:
        print(f"Every module is in step with values/ in {len(all_locales)} locale(s).")
    return 1 if (args.strict and total) else 0


if __name__ == "__main__":
    sys.exit(main())
