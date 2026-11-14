#!/usr/bin/env python3
"""Regenerate the bundled exercise catalogue from the upstream dataset.

Source: https://github.com/yuhonas/free-exercise-db (Unlicense / public domain).

The upstream tarball is pinned in ``source.lock`` by commit and by the sha256 of
its bytes, so a regeneration is reproducible and reads exactly the archive that
was reviewed when it was pinned; ``--update`` re-pins to the tip of the default
branch. Outputs:

  core/data/src/main/assets/seed/exercises.sql   one SQL statement per line
  core/data/src/main/assets/seed/images/*.webp   one downscaled photo per exercise

``exercises.sql`` is replayed verbatim by ``DatabaseSeed`` in ``onCreate``. Its
format is deliberately dumb - every line is either blank, a ``--`` comment, or a
complete statement - so the Kotlin side needs no SQL parser. Nothing in it may
therefore contain a raw newline; instruction paragraphs are re-joined with
``char(10)`` concatenation instead.

The images are encoded by Pillow's bundled libwebp, so they are byte-reproducible
only with the Pillow pinned in ``requirements.txt``; CI installs that file with
``--require-hashes``.

Usage:
    ./generate_seed.py            regenerate from the pinned commit
    ./generate_seed.py --update   re-pin to upstream HEAD, then regenerate
    ./generate_seed.py --rehash   pin the sha256 of the commit already in
                                  source.lock (after Renovate moves it), then
                                  regenerate
    ./generate_seed.py --check    fail if the committed output is stale
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

REPO = "yuhonas/free-exercise-db"
BRANCH = "main"

TOOL_DIR = Path(__file__).resolve().parent
ROOT = TOOL_DIR.parents[1]
LOCK_FILE = TOOL_DIR / "source.lock"
REQUIREMENTS = TOOL_DIR / "requirements.txt"
CACHE_DIR = TOOL_DIR / ".cache"
ASSET_DIR = ROOT / "core/data/src/main/assets/seed"
SQL_OUT = ASSET_DIR / "exercises.sql"
IMAGE_DIR = ASSET_DIR / "images"

# Everything read from source.lock or from upstream that ends up in a path or a
# URL is held to these shapes first. A commit is a full lowercase SHA-1, never a
# branch name or anything containing a separator; a slug names one file inside
# the images directory, so `..`, `/` and an absolute path can never reach the
# join that builds it.
COMMIT = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
SLUG = re.compile(r"[A-Za-z0-9_-]+")

# C0 controls other than the three whitespace characters `sql_text` folds. NUL
# would end the statement early where SQLite reads it as a C string, and the
# rest have no business in an exercise name or instruction.
CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")

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
    control = CONTROL.search(value)
    if control:
        raise SystemExit(
            f"upstream text contains control character {control.group()!r}: {value[:60]!r}"
        )
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


def write_lock(commit: str, sha256: str) -> None:
    # The key order is load-bearing: Renovate's regex manager in renovate.json
    # reads `repo`, `branch` and `commit` as one consecutive match.
    LOCK_FILE.write_text(json.dumps(
        {"repo": REPO, "branch": BRANCH, "commit": commit, "tarball_sha256": sha256},
        indent=2,
    ) + "\n")


def checked_commit(commit: object) -> str:
    """`commit` if it is a full lowercase SHA-1; stops the run otherwise.

    The commit names the cache file and goes into the download URL, so a value
    such as `../planted` would read a file outside the cache or fetch a ref
    nobody pinned.
    """
    if not isinstance(commit, str) or not COMMIT.fullmatch(commit):
        raise SystemExit(f"commit {commit!r} is not 40 lowercase hex characters")
    return commit


def checked_sha256(digest: object) -> str:
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise SystemExit(
            f"source.lock tarball_sha256 {digest!r} is not 64 lowercase hex characters"
        )
    return digest


# Seconds a connection or a single read may stall before the run stops, so a
# dead network ends in a message instead of a hang.
NETWORK_TIMEOUT = 60


def fetch(url: str) -> bytes:
    """The body at `url`; any HTTP or network failure stops the run with the URL."""
    try:
        with urllib.request.urlopen(url, timeout=NETWORK_TIMEOUT) as response:
            return response.read()
    except (urllib.error.URLError, TimeoutError) as error:
        raise SystemExit(f"GET {url} failed: {error}") from error


def github_api(path: str) -> dict:
    return json.loads(fetch(f"https://api.github.com/repos/{REPO}/{path}"))


def resolve_head() -> str:
    return checked_commit(github_api(f"commits/{BRANCH}")["sha"])


def require_on_branch(commit: str) -> None:
    """Stop unless `commit` is reachable from upstream's `BRANCH`.

    GitHub shares objects across a fork network, so the upstream URLs also
    resolve a commit that exists only in a fork. Comparing it with the branch
    tells the two apart: the branch is `ahead` of (or `identical` to) any commit
    in its history, and `behind` or `diverged` from one that is not.
    """
    status = github_api(f"compare/{commit}...{BRANCH}").get("status")
    if status not in ("ahead", "identical"):
        raise SystemExit(
            f"{commit} is not in the history of {REPO}@{BRANCH} (compare status "
            f"{status!r}) - refusing to pin it"
        )


def download(commit: str) -> bytes:
    url = f"https://codeload.github.com/{REPO}/tar.gz/{commit}"
    print(f"  downloading {url} (~95 MB)")
    return fetch(url)


def cache(commit: str, data: bytes) -> None:
    """Store `data` as the cached tarball for `commit`, all or nothing.

    The bytes go to a temporary file in the cache directory first and are renamed
    over the cache entry, so an interrupted run leaves no partial tarball under
    the name a later run would read.
    """
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    fd, partial = tempfile.mkstemp(dir=CACHE_DIR, prefix=f"{commit}.", suffix=".part")
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(data)
        os.replace(partial, CACHE_DIR / f"{commit}.tar.gz")
    except BaseException:
        Path(partial).unlink(missing_ok=True)
        raise


def fetch_tarball(commit: str, sha256: str | None) -> bytes:
    """The upstream tarball for `commit`, checked against `sha256`.

    The check runs on the bytes in memory, whether they came from the network or
    from the cache, so the archive parsed afterwards is the one that was hashed,
    and a download that fails the check is never cached. `sha256=None` means the
    caller is pinning the commit: the cache is bypassed and the digest of what
    codeload serves now becomes the pin.
    """
    commit = checked_commit(commit)
    cached = CACHE_DIR / f"{commit}.tar.gz"
    downloaded = sha256 is None or not cached.exists()
    data = download(commit) if downloaded else cached.read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    if sha256 is not None and actual != sha256:
        raise SystemExit(
            f"tarball sha256 mismatch for {commit} "
            f"({'downloaded' if downloaded else cached}):\n"
            f"  source.lock {sha256}\n"
            f"  actual      {actual}\n"
            f"A stale or tampered cache clears with `make seed-clean`. If Renovate "
            f"has just moved the commit, `make seed-rehash` pins the new tarball."
        )
    if downloaded:
        cache(commit, data)
    return data


def load_source(archive: bytes) -> tuple[list[dict], dict[str, bytes]]:
    """Return the exercise records and the raw bytes of each exercise's first photo.

    Every record's id becomes a file name and an asset path, so each is checked
    against `SLUG` here, before anything is written. A malformed archive stops the
    run with a message naming what is missing rather than a bare traceback, since
    the person reading it is usually reviewing an upstream bump, not this script.
    """
    exercises: list[dict] = []
    wanted: dict[str, str] = {}
    images: dict[str, bytes] = {}

    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        members = {
            m.name.split("/", 1)[1]: m for m in tar.getmembers() if m.isfile() and "/" in m.name
        }
        if "dist/exercises.json" not in members:
            raise SystemExit("upstream archive has no dist/exercises.json")
        payload = tar.extractfile(members["dist/exercises.json"])
        try:
            exercises = json.load(payload)
        except json.JSONDecodeError as error:
            raise SystemExit(f"upstream dist/exercises.json is not valid JSON: {error}") from None
        if not isinstance(exercises, list):
            raise SystemExit("upstream dist/exercises.json is not a JSON array")
        for index, record in enumerate(exercises):
            if not isinstance(record, dict):
                raise SystemExit(f"upstream exercise #{index} is not a JSON object")
            slug = record.get("id")
            if not isinstance(slug, str) or not SLUG.fullmatch(slug):
                raise SystemExit(f"upstream exercise id {slug!r} is not a safe slug [A-Za-z0-9_-]+")
        # Photos are numbered per exercise; the first frame is the start position
        # and reads best as a thumbnail, so only that one is bundled.
        for record in exercises:
            photos = record.get("images")
            if not photos:
                continue
            if not isinstance(photos, list) or not isinstance(photos[0], str):
                raise SystemExit(f"upstream exercise {record['id']!r} has a malformed images list")
            wanted[f"exercises/{photos[0]}"] = record["id"]
        for path, slug in wanted.items():
            if path not in members:
                raise SystemExit(f"upstream exercise {slug!r} names a photo the archive lacks: {path}")
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


# --- Images ---------------------------------------------------------------


def encode_image(blob: bytes) -> bytes:
    """The bundled WebP for one upstream photo.

    Upstream ships JPEGs only, so no other decoder is offered the bytes. The
    output is deterministic for a given Pillow build, which is what lets
    `--check` compare it byte for byte with the committed file.
    """
    from PIL import Image

    with Image.open(io.BytesIO(blob), formats=["JPEG"]) as image:
        image = image.convert("RGB")
        image.thumbnail((IMAGE_MAX_EDGE, IMAGE_MAX_EDGE), Image.LANCZOS)
        out = io.BytesIO()
        image.save(out, "WEBP", quality=IMAGE_QUALITY, method=6)
    return out.getvalue()


def image_paths(slugs: list[str], target: Path) -> dict[str, Path]:
    """The output file for each slug, each one checked to sit directly in `target`.

    `load_source` already rejects any slug that could leave the directory; this
    is the same promise checked where the paths are made, against the normalised
    path rather than the slug's spelling. Normalised, not resolved: resolving
    would follow a symlink already sitting at an expected name, and the check
    is about where the name points, not where a planted link does.
    """
    root = Path(os.path.abspath(target))
    paths = {}
    for slug in slugs:
        path = Path(os.path.abspath(root / f"{slug}.webp"))
        if path.parent != root:
            raise SystemExit(f"image for {slug!r} would be written outside {target}")
        paths[slug] = path
    return paths


def encode_images(blobs: dict[str, bytes]) -> dict[str, bytes]:
    """`encode_image` of every value in `blobs`, under the same keys.

    Each photo is independent and the encode is CPU-bound, so the work is spread
    over a process per core; the bytes each encode produces do not depend on the
    process that ran it.
    """
    if len(blobs) < 2:
        return {key: encode_image(blob) for key, blob in blobs.items()}
    with ProcessPoolExecutor() as pool:
        return dict(zip(blobs, pool.map(encode_image, blobs.values(), chunksize=16)))


def write_images(images: dict[str, bytes], target: Path) -> None:
    # Every path is checked and every photo encoded before the directory is
    # touched, so a bad slug or an undecodable photo leaves the committed images
    # as they were.
    paths = image_paths(sorted(images), target)
    encoded = encode_images({slug: images[slug] for slug in paths})
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    for slug, path in paths.items():
        path.write_bytes(encoded[slug])


def stale_images(images: dict[str, bytes], target: Path) -> dict[str, list[str]]:
    """Every way the files in `target` differ from what `write_images` would write.

    Each entry in the directory counts, whatever its extension, so a stray file
    cannot ride along in the APK. Expected files are re-encoded in memory and
    compared byte for byte; a symlink or directory under an expected name is a
    mismatch, not a match.
    """
    expected = {path.name: slug for slug, path in image_paths(sorted(images), target).items()}
    present = {p.name: p for p in target.iterdir()} if target.is_dir() else {}
    shared = sorted(expected.keys() & present.keys())
    regular = [n for n in shared if not present[n].is_symlink() and present[n].is_file()]
    encoded = encode_images({name: images[expected[name]] for name in regular})
    changed = [
        name for name in shared
        if name not in encoded or present[name].read_bytes() != encoded[name]
    ]
    return {
        "missing": sorted(expected.keys() - present.keys()),
        "unexpected": sorted(present.keys() - expected.keys()),
        "changed": changed,
    }


def pinned_pillow() -> str | None:
    match = re.search(r"^pillow==(\S+)", REQUIREMENTS.read_text(), re.MULTILINE | re.IGNORECASE)
    return match.group(1) if match else None


# --- Entry point ----------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--update", action="store_true",
                      help="re-pin source.lock to upstream HEAD before generating")
    mode.add_argument("--rehash", action="store_true",
                      help="pin the sha256 of the commit already in source.lock, "
                           "downloaded afresh, before generating")
    mode.add_argument("--check", action="store_true",
                      help="verify the committed output is up to date; generate nothing")
    args = parser.parse_args(argv)

    lock = read_lock()
    pinning = args.update or args.rehash
    commit = checked_commit(resolve_head() if args.update else lock.get("commit"))
    if args.update and commit != lock.get("commit"):
        print(f"re-pinning {str(lock.get('commit'))[:12]} -> {commit[:12]}")
    if pinning:
        require_on_branch(commit)
    sha256 = None if pinning else checked_sha256(lock.get("tarball_sha256"))

    print(f"reading {REPO} @ {commit[:12]}")
    archive = fetch_tarball(commit, sha256)
    exercises, images = load_source(archive)
    sql = build_sql(exercises, commit, set(images))

    if args.check:
        stale_sql = not SQL_OUT.exists() or SQL_OUT.read_text() != sql
        stale = stale_images(images, IMAGE_DIR)
        if stale_sql or any(stale.values()):
            print("seed assets are stale - run `make seed` and commit the result", file=sys.stderr)
            if stale_sql:
                print(f"  {SQL_OUT.relative_to(ROOT)} differs", file=sys.stderr)
            for kind, names in stale.items():
                if names:
                    print(f"  {len(names)} image(s) {kind}, e.g. {names[:3]}", file=sys.stderr)
            if stale["changed"]:
                from PIL import __version__ as installed
                pinned = pinned_pillow()
                if installed != pinned:
                    print(f"  (Pillow {installed} is installed but {REQUIREMENTS.relative_to(ROOT)} "
                          f"pins {pinned}; another encoder writes other bytes)", file=sys.stderr)
            return 1
        print(f"seed assets are up to date ({len(exercises)} exercises, {len(images)} images)")
        return 0

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    SQL_OUT.write_text(sql)
    write_images(images, IMAGE_DIR)
    if pinning:
        write_lock(commit, hashlib.sha256(archive).hexdigest())

    total = sum(p.stat().st_size for p in IMAGE_DIR.glob("*.webp"))
    print(f"wrote {SQL_OUT.relative_to(ROOT)} ({SQL_OUT.stat().st_size // 1024} KiB)")
    print(f"wrote {len(images)} images to {IMAGE_DIR.relative_to(ROOT)} ({total // 1024} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
