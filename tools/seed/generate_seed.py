#!/usr/bin/env python3
"""Regenerate the bundled exercise catalogue from the upstream dataset.

Source: https://github.com/yuhonas/free-exercise-db (Unlicense / public domain).

The upstream tarball is pinned by commit in ``source.lock`` so a regeneration is
reproducible; ``--update`` re-pins to the tip of the default branch. Outputs:

  core/data/src/main/assets/seed/exercises.sql   one SQL statement per line
  core/data/src/main/assets/seed/pace_tags.sql   the SPEED/SWIM tags, linked by name
  core/data/src/main/assets/seed/images/*.webp   one downscaled photo per exercise

``exercises.sql`` is replayed verbatim by ``DatabaseSeed`` in ``onCreate``. Its
format is deliberately dumb - every line is either blank, a ``--`` comment, or a
complete statement - so the Kotlin side needs no SQL parser. Nothing in it may
therefore contain a raw newline; instruction paragraphs are re-joined with
``char(10)`` concatenation instead.

``pace_tags.sql`` has the same format and is replayed on every open and after
every restore, for a database seeded before the SPEED and SWIM tags existed -
see ``build_pace_tags_sql``.

Usage:
    ./generate_seed.py            regenerate from the pinned commit
    ./generate_seed.py --update   re-pin to upstream HEAD, then regenerate
    ./generate_seed.py --check    fail if the committed output is stale
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO = "yuhonas/free-exercise-db"
BRANCH = "main"

TOOL_DIR = Path(__file__).resolve().parent
ROOT = TOOL_DIR.parents[1]
LOCK_FILE = TOOL_DIR / "source.lock"
CACHE_DIR = TOOL_DIR / ".cache"
ASSET_DIR = ROOT / "core/data/src/main/assets/seed"
SQL_OUT = ASSET_DIR / "exercises.sql"
PACE_TAGS_OUT = ASSET_DIR / "pace_tags.sql"
IMAGE_DIR = ASSET_DIR / "images"

# --- Taxonomy -------------------------------------------------------------
#
# These lists ARE the seeded taxonomy: they land in `muscle_group` and `tag`
# with the explicit ids used by every generated link row, so reordering one
# reorders the ids. Ids are local rowids and never leave the device, but the
# regenerated SQL must be committed alongside any edit here.

MUSCLE_GROUPS = [
    "ABDOMINALS", "ABDUCTORS", "ADDUCTORS", "BICEPS", "CALVES", "CHEST",
    "FOREARMS", "GLUTES", "HAMSTRINGS", "LATS", "LOWER_BACK", "MIDDLE_BACK",
    "NECK", "QUADRICEPS", "SHOULDERS", "TRAPS", "TRICEPS",
]

# CARRY has no upstream equivalent; it stays seeded for user-tagged exercises.
# DYNAMIC, SPEED and SWIM are appended rather than inserted: the ids are
# positions in this list, so putting one anywhere else would renumber every tag
# link below it.
SYSTEM_TAGS = [
    "PUSH", "PULL", "STATIC", "CARRY", "COMPOUND", "ISOLATION", "DYNAMIC",
    "SPEED", "SWIM",
]

# --- Upstream -> schema mappings ------------------------------------------

# Upstream `equipment` -> exercise.equipment_type (EquipmentType). Upstream's
# own "other" bucket and a missing value both land on OTHER: leaving the column
# NULL would defeat the unique index on (name, equipment_type, machine_brand),
# because SQLite treats NULLs as distinct and would stop deduplicating.
EQUIPMENT = {
    "barbell": "BARBELL",
    "dumbbell": "DUMBBELL",
    "machine": "MACHINE",
    "body only": "BODYWEIGHT",
    "cable": "CABLE",
    "kettlebells": "KETTLEBELL",
    "bands": "BAND",
    "e-z curl bar": "EZ_CURL_BAR",
    "medicine ball": "MEDICINE_BALL",
    "exercise ball": "EXERCISE_BALL",
    "foam roll": "FOAM_ROLL",
    "other": "OTHER",
    None: "OTHER",
}

# Upstream `category` -> (exercise.discipline, exercise.exercise_type).
#
# The two columns answer different questions and neither derives the other back.
# `exercise_type` is a *logging mode* - how a set is recorded - so the five
# barbell-shaped disciplines all fold into STRENGTH. `discipline` keeps what
# that fold destroys: without it, a plyometric box jump and a bench press are
# indistinguishable rows.
CATEGORY = {
    "strength": ("STRENGTH", "STRENGTH"),
    "powerlifting": ("POWERLIFTING", "STRENGTH"),
    "olympic weightlifting": ("OLYMPIC_WEIGHTLIFTING", "STRENGTH"),
    "strongman": ("STRONGMAN", "STRENGTH"),
    "plyometrics": ("PLYOMETRICS", "STRENGTH"),
    "cardio": ("CARDIO", "ENDURANCE"),
    "stretching": ("STRETCHING", "ISOMETRIC"),
}

MECHANIC = {"compound": "COMPOUND", "isolation": "ISOLATION", None: None}
DIFFICULTY = {"beginner": "BEGINNER", "intermediate": "INTERMEDIATE", "expert": "EXPERT"}
FORCE_TAG = {"push": "PUSH", "pull": "PULL", "static": "STATIC"}

# The `force` values that mean a movement travels through a range rather than
# holding a position. Used only for DYNAMIC below; the STATIC tag above is the
# same column read the other way round, and the two never disagree.
MOVING_FORCES = ("push", "pull")

# exercise.is_unilateral is not an upstream field. These markers appear only in
# genuinely one-side-at-a-time movement names, so the inference is safe; an
# exercise without a marker keeps the column's `false` default rather than
# being guessed at.
UNILATERAL = re.compile(
    r"\b(one[- ]arm|one[- ]armed|single[- ]arm|one[- ]leg|one[- ]legged"
    r"|single[- ]leg|one[- ]handed|alternating)\b",
    re.IGNORECASE,
)

# How a cardio movement reads how fast it went (#225): `PaceStyle` in the app.
# A personal best pace is stored once and shown as a speed for the movements a
# rider or a rower thinks of in km/h, and per 100 m for a swim; anything else is
# a pace per km or mile. No upstream field says which, so the name does, in the
# way upstream names its variants (`Bicycling, Stationary`, `Rowing, Stationary`).
# Matched on every cardio row rather than on a list of slugs, so a cardio row
# added later (a hand-authored `Kayaking` or `Swimming, Freestyle`) is tagged
# without an entry of its own.
#
# Rowing reads as a speed on purpose. Rowers often quote a split per 500 m, but
# that would be a fourth `PaceStyle` and a fourth unit for one movement; km/h
# is the reading a rower's best shares with every other machine in the gym.
PACE_TAGS = ("SPEED", "SWIM")
SPEED_NAMES = re.compile(r"bicycl|\bbike\b|cycling|rowing|skating|kayak", re.IGNORECASE)
SWIM_NAMES = re.compile(r"swim|aqua", re.IGNORECASE)

IMAGE_MAX_EDGE = 400
IMAGE_QUALITY = 72

# SQLite compiles multi-row VALUES into a compound SELECT, capped at 500 terms
# by SQLITE_MAX_COMPOUND_SELECT. Exercise rows carry instruction text, so they
# also have to stay clear of SQLITE_MAX_SQL_LENGTH (1 MB) once batched.
EXERCISE_BATCH = 50
LINK_BATCH = 200


# --- SQL emission ---------------------------------------------------------


def sql_text(value: str | None) -> str:
    """Render `value` as a SQL expression that occupies no more than one line."""
    if value is None:
        return "NULL"
    # Tabs and carriage returns carry no meaning here and would survive into the
    # UI as stray whitespace; newlines are the only break worth keeping.
    cleaned = value.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
    paragraphs = [p.replace("'", "''") for p in cleaned.split("\n")]
    return " || char(10) || ".join(f"'{p}'" for p in paragraphs)


def sql_bool(value: bool) -> str:
    return "1" if value else "0"


def batched_insert(table: str, columns: list[str], rows: list[str], size: int) -> list[str]:
    """One `INSERT OR IGNORE` statement per batch of `size` rows, one per line."""
    head = f"INSERT OR IGNORE INTO {table} ({', '.join(columns)}) VALUES "
    return [
        head + ", ".join(rows[i:i + size]) + ";"
        for i in range(0, len(rows), size)
    ]


# --- Upstream fetch -------------------------------------------------------


def read_lock() -> dict:
    return json.loads(LOCK_FILE.read_text())


def resolve_head() -> str:
    url = f"https://api.github.com/repos/{REPO}/commits/{BRANCH}"
    with urllib.request.urlopen(url) as response:
        return json.load(response)["sha"]


def fetch_tarball(commit: str) -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cached = CACHE_DIR / f"{commit}.tar.gz"
    if not cached.exists():
        url = f"https://codeload.github.com/{REPO}/tar.gz/{commit}"
        print(f"  downloading {url} (~95 MB)")
        with urllib.request.urlopen(url) as response:
            cached.write_bytes(response.read())
    return cached


def load_source(commit: str) -> tuple[list[dict], dict[str, bytes]]:
    """Return the exercise records and the raw bytes of each exercise's first photo."""
    archive = fetch_tarball(commit)
    exercises: list[dict] = []
    wanted: dict[str, str] = {}
    images: dict[str, bytes] = {}

    with tarfile.open(archive, "r:gz") as tar:
        members = {m.name.split("/", 1)[1]: m for m in tar.getmembers() if m.isfile()}
        payload = tar.extractfile(members["dist/exercises.json"])
        exercises = json.load(payload)
        # Photos are numbered per exercise; the first frame is the start position
        # and reads best as a thumbnail, so only that one is bundled.
        for record in exercises:
            if record.get("images"):
                wanted[f"exercises/{record['images'][0]}"] = record["id"]
        for path, slug in wanted.items():
            images[slug] = tar.extractfile(members[path]).read()

    return exercises, images


# --- Transformation -------------------------------------------------------


def muscle_name(upstream: str) -> str:
    name = upstream.strip().upper().replace(" ", "_")
    if name not in MUSCLE_GROUPS:
        raise SystemExit(
            f"upstream muscle '{upstream}' is not in MUSCLE_GROUPS - add it there "
            f"and regenerate, or the link row would dangle"
        )
    return name


def dynamic_tag(record: dict) -> str | None:
    """`"DYNAMIC"` when this movement warms a muscle up rather than stretching it out.

    The warm-up/cool-down suggestion (#214) needs to tell leg swings from a seated
    hamstring stretch, and no upstream field says so on its own. Two rules cover it:

    * **plyometrics** are dynamic by definition - a box jump is nothing else - bar the
      single upstream record whose ``force`` is ``static``;
    * a **stretch** is dynamic when ``force`` says it pushes or pulls, i.e. the body
      travels, and static when it says ``static`` or says nothing.

    Every other category is neither: a bench press is not mobility work, and tagging it
    would put it in a warm-up card next to leg swings.
    """
    category, force = record["category"], record.get("force")
    if category == "plyometrics":
        return None if force == "static" else "DYNAMIC"
    if category == "stretching" and force in MOVING_FORCES:
        return "DYNAMIC"
    return None


def pace_tag(record: dict) -> str | None:
    """`"SPEED"` or `"SWIM"` when this cardio movement reads its pace that way.

    Only ``cardio`` rows: a ``Rowing`` strength exercise is a lift, and a pace
    reading on it would be one nobody asked for. ``SPEED`` is checked first,
    though no name matches both.
    """
    if record["category"] != "cardio":
        return None
    if SPEED_NAMES.search(record["name"]):
        return "SPEED"
    if SWIM_NAMES.search(record["name"]):
        return "SWIM"
    return None


def build_pace_tags_sql(exercises: list[dict], commit: str) -> str:
    """The SPEED and SWIM tags and their links, addressed by name rather than id.

    ``exercises.sql`` already carries these rows, but it only runs when a
    database is created, so a database seeded before #225 - or restored from a
    backup taken on one - would keep reading a ride's best as a pace per km.
    This file is replayed over such a database on every open and after every
    restore. It cannot use the ids ``exercises.sql`` does, because the tag ids
    in an older database are whatever it happened to assign; it matches the tag
    by its unique name and the exercise by its (unique, seeded) name instead,
    and skips custom exercises, which the seed never tagged. A tag is only
    written where an exercise will carry it, and every statement is
    ``INSERT OR IGNORE``, so a replay over a tagged database - or over a
    restored one with none of these exercises - changes nothing.
    """
    names: dict[str, list[str]] = {}
    for record in sorted(exercises, key=lambda e: e["id"]):
        tag = pace_tag(record)
        if tag:
            names.setdefault(tag, []).append(sql_text(record["name"]))

    lines = [
        "-- Generated by tools/seed/generate_seed.py - DO NOT EDIT.",
        f"-- Source: github.com/{REPO} @ {commit}",
        "-- The pace tags of exercises.sql, by name, for a database seeded without them.",
        "",
    ]
    for tag in PACE_TAGS:
        if tag not in names:
            continue
        seeded = f"e.is_custom = 0 AND e.name IN ({', '.join(names[tag])})"
        # The tag only where something will carry it: a restored backup with
        # none of these exercises comes back exactly as the file had it.
        lines.append(
            f"INSERT OR IGNORE INTO tag (name, is_system) SELECT '{tag}', 1 "
            f"WHERE EXISTS (SELECT 1 FROM exercise e WHERE {seeded});"
        )
        lines.append(
            "INSERT OR IGNORE INTO exercise_tag (exercise_id, tag_id) "
            f"SELECT e.id, t.id FROM exercise e JOIN tag t ON t.name = '{tag}' WHERE {seeded};"
        )
    return "\n".join(lines) + "\n"


def build_sql(exercises: list[dict], commit: str, has_image: set[str]) -> str:
    muscle_ids = {name: i + 1 for i, name in enumerate(MUSCLE_GROUPS)}
    tag_ids = {name: i + 1 for i, name in enumerate(SYSTEM_TAGS)}

    # Sorting by the upstream slug pins each exercise to a stable id, so a
    # regeneration against an unchanged dataset produces a byte-identical file.
    exercises = sorted(exercises, key=lambda e: e["id"])

    exercise_rows: list[str] = []
    muscle_rows: list[str] = []
    tag_rows: list[str] = []

    for index, record in enumerate(exercises, start=1):
        slug = record["id"]
        category = record["category"]
        if category not in CATEGORY:
            raise SystemExit(f"unmapped upstream category '{category}' on {slug}")
        equipment = record.get("equipment")
        if equipment not in EQUIPMENT:
            raise SystemExit(f"unmapped upstream equipment '{equipment}' on {slug}")
        discipline, exercise_type = CATEGORY[category]

        paragraphs = [p.strip() for p in record.get("instructions") or [] if p.strip()]
        instructions = "\n".join(paragraphs) or None
        icon = f"seed/images/{slug}.webp" if slug in has_image else None

        exercise_rows.append(
            "({}, {}, {}, {}, {}, {}, {}, {}, {}, {})".format(
                index,
                sql_text(record["name"]),
                sql_text(exercise_type),
                sql_text(discipline),
                sql_text(EQUIPMENT[equipment]),
                sql_text(MECHANIC.get(record.get("mechanic"))),
                sql_text(DIFFICULTY.get(record.get("level"))),
                sql_bool(bool(UNILATERAL.search(record["name"]))),
                sql_text(instructions),
                sql_text(icon),
            )
        )

        primary = [muscle_name(m) for m in record.get("primaryMuscles") or []]
        if len(primary) != 1:
            raise SystemExit(
                f"{slug} has {len(primary)} primary muscles - the schema expects "
                f"exactly one is_primary link per exercise"
            )
        muscle_rows.append(f"({index}, {muscle_ids[primary[0]]}, 1)")
        # A handful of records list the same muscle as primary and secondary;
        # the join table's composite key would reject the duplicate.
        seen = set(primary)
        for upstream in record.get("secondaryMuscles") or []:
            name = muscle_name(upstream)
            if name in seen:
                continue
            seen.add(name)
            muscle_rows.append(f"({index}, {muscle_ids[name]}, 0)")

        for tag in (
            FORCE_TAG.get(record.get("force")),
            MECHANIC.get(record.get("mechanic")),
            dynamic_tag(record),
            pace_tag(record),
        ):
            if tag:
                tag_rows.append(f"({index}, {tag_ids[tag]})")

    lines = [
        "-- Generated by tools/seed/generate_seed.py - DO NOT EDIT.",
        f"-- Source: github.com/{REPO} @ {commit}",
        "-- Licence: Unlicense (public domain).",
        f"-- {len(exercises)} exercises, {len(MUSCLE_GROUPS)} muscle groups, "
        f"{len(SYSTEM_TAGS)} system tags, {len(muscle_rows)} muscle links, "
        f"{len(tag_rows)} tag links.",
        "",
    ]
    lines += batched_insert(
        "muscle_group", ["id", "name"],
        [f"({muscle_ids[name]}, '{name}')" for name in MUSCLE_GROUPS], LINK_BATCH,
    )
    lines += batched_insert(
        "tag", ["id", "name", "is_system"],
        [f"({tag_ids[name]}, '{name}', 1)" for name in SYSTEM_TAGS], LINK_BATCH,
    )
    lines += batched_insert(
        "exercise",
        ["id", "name", "exercise_type", "discipline", "equipment_type",
         "mechanic_type", "difficulty_level", "is_unilateral", "instructions", "icon"],
        exercise_rows, EXERCISE_BATCH,
    )
    lines += batched_insert(
        "exercise_muscle_group", ["exercise_id", "muscle_group_id", "is_primary"],
        muscle_rows, LINK_BATCH,
    )
    lines += batched_insert("exercise_tag", ["exercise_id", "tag_id"], tag_rows, LINK_BATCH)
    return "\n".join(lines) + "\n"


def write_images(images: dict[str, bytes], target: Path) -> None:
    from PIL import Image

    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    for slug, blob in sorted(images.items()):
        with Image.open(io.BytesIO(blob)) as image:
            image = image.convert("RGB")
            image.thumbnail((IMAGE_MAX_EDGE, IMAGE_MAX_EDGE), Image.LANCZOS)
            image.save(target / f"{slug}.webp", "WEBP", quality=IMAGE_QUALITY, method=6)


# --- Entry point ----------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", action="store_true",
                        help="re-pin source.lock to upstream HEAD before generating")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed output is up to date; generate nothing")
    args = parser.parse_args()

    lock = read_lock()
    commit = resolve_head() if args.update else lock["commit"]
    if args.update and commit != lock["commit"]:
        print(f"re-pinning {lock['commit'][:12]} -> {commit[:12]}")

    print(f"reading {REPO} @ {commit[:12]}")
    exercises, images = load_source(commit)
    sql = build_sql(exercises, commit, set(images))
    pace_tags_sql = build_pace_tags_sql(exercises, commit)
    digest = hashlib.sha256(sql.encode()).hexdigest()

    if args.check:
        stale = any(
            not path.exists() or path.read_text() != text
            for path, text in ((SQL_OUT, sql), (PACE_TAGS_OUT, pace_tags_sql))
        )
        missing = {f"{slug}.webp" for slug in images} - {p.name for p in IMAGE_DIR.glob("*.webp")}
        if stale or missing:
            print("seed assets are stale - run `make seed` and commit the result", file=sys.stderr)
            if missing:
                print(f"  {len(missing)} image(s) missing, e.g. {sorted(missing)[:3]}", file=sys.stderr)
            return 1
        print(f"seed assets are up to date ({len(exercises)} exercises, sha256 {digest[:12]})")
        return 0

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    SQL_OUT.write_text(sql)
    PACE_TAGS_OUT.write_text(pace_tags_sql)
    write_images(images, IMAGE_DIR)
    LOCK_FILE.write_text(json.dumps(
        {"repo": REPO, "branch": BRANCH, "commit": commit, "sql_sha256": digest},
        indent=2,
    ) + "\n")

    total = sum(p.stat().st_size for p in IMAGE_DIR.glob("*.webp"))
    print(f"wrote {SQL_OUT.relative_to(ROOT)} ({SQL_OUT.stat().st_size // 1024} KiB)")
    print(f"wrote {PACE_TAGS_OUT.relative_to(ROOT)}")
    print(f"wrote {len(images)} images to {IMAGE_DIR.relative_to(ROOT)} ({total // 1024} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
