"""Regression tests for generate_seed.py's handling of hostile or broken input.

Run with `make seed-test`, or `python3 -m unittest` from this directory. Every
test works on a temporary tree with its own lock file, cache and asset
directory, and fails if anything reaches for the network, so none of them
needs the real upstream tarball.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_seed  # noqa: E402

COMMIT = "0123456789abcdef0123456789abcdef01234567"


def jpeg(colour: tuple[int, int, int] = (200, 40, 40)) -> bytes:
    from PIL import Image

    out = io.BytesIO()
    Image.new("RGB", (32, 24), colour).save(out, "JPEG")
    return out.getvalue()


def record(slug: str) -> dict:
    return {
        "id": slug,
        "name": "Test Press",
        "category": "strength",
        "equipment": "barbell",
        "primaryMuscles": ["chest"],
        "secondaryMuscles": [],
        "instructions": ["Press."],
        "images": [f"{slug}/0.jpg"],
    }


def tarball(records: list[dict]) -> bytes:
    """An upstream-shaped archive: `dist/exercises.json` plus each record's first photo."""
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w:gz") as tar:
        def add(name: str, data: bytes) -> None:
            info = tarfile.TarInfo(f"free-exercise-db-{COMMIT}/{name}")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))

        add("dist/exercises.json", json.dumps(records).encode())
        for r in records:
            add(f"exercises/{r['images'][0]}", jpeg())
    return out.getvalue()


def snapshot(root: Path) -> dict[str, bytes]:
    return {
        str(p.relative_to(root)): p.read_bytes()
        for p in sorted(root.rglob("*")) if p.is_file()
    }


def no_network(*args, **kwargs):
    raise AssertionError(f"unexpected network access: {args}")


class SeedTreeTest(unittest.TestCase):
    """Points the generator's paths at a throwaway tree laid out like the repository."""

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.tool_dir = self.root / "tools/seed"
        self.cache_dir = self.tool_dir / ".cache"
        self.asset_dir = self.root / "core/data/src/main/assets/seed"
        self.image_dir = self.asset_dir / "images"
        self.lock = self.tool_dir / "source.lock"
        self.cache_dir.mkdir(parents=True)
        self.image_dir.mkdir(parents=True)
        (self.image_dir / "committed.webp").write_bytes(b"committed")

        for patcher in (
            mock.patch.multiple(
                generate_seed,
                ROOT=self.root,
                LOCK_FILE=self.lock,
                CACHE_DIR=self.cache_dir,
                ASSET_DIR=self.asset_dir,
                SQL_OUT=self.asset_dir / "exercises.sql",
                IMAGE_DIR=self.image_dir,
            ),
            mock.patch("urllib.request.urlopen", no_network),
            mock.patch("sys.stdout", new_callable=io.StringIO),
            mock.patch("sys.stderr", new_callable=io.StringIO),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def pin(self, commit: str, archive: bytes) -> None:
        self.lock.write_text(json.dumps({
            "repo": generate_seed.REPO,
            "branch": generate_seed.BRANCH,
            "commit": commit,
            "tarball_sha256": hashlib.sha256(archive).hexdigest(),
        }))

    def assert_exits_writing_nothing(self, *argv: str) -> None:
        before = snapshot(self.root)
        with self.assertRaises(SystemExit) as raised:
            generate_seed.main(list(argv))
        self.assertNotIn(raised.exception.code, (0, None))
        self.assertEqual(before, snapshot(self.root))


class HostileIdTest(SeedTreeTest):
    def test_traversal_and_absolute_ids_stop_the_run(self) -> None:
        for slug in ("../x", "/x", "..", "a/b", "x.webp", ""):
            with self.subTest(slug=slug):
                archive = tarball([record(slug)])
                self.pin(COMMIT, archive)
                (self.cache_dir / f"{COMMIT}.tar.gz").write_bytes(archive)
                for argv in ([], ["--check"]):
                    self.assert_exits_writing_nothing(*argv)
                self.assertFalse((self.asset_dir / "x.webp").exists())
                self.assertFalse(Path("/x.webp").exists())

    def test_a_non_string_id_stops_the_run(self) -> None:
        with self.assertRaises(SystemExit):
            generate_seed.load_source(tarball([{**record("ok"), "id": ["../x"]}]))

    def test_write_images_refuses_a_path_outside_its_target(self) -> None:
        # load_source would have stopped these already; write_images checks the
        # normalised path on its own, and does so before clearing the directory.
        for slug in ("../x", "/x"):
            with self.subTest(slug=slug):
                before = snapshot(self.root)
                with self.assertRaises(SystemExit):
                    generate_seed.write_images({"fine": jpeg(), slug: jpeg()}, self.image_dir)
                self.assertEqual(before, snapshot(self.root))
                self.assertFalse(Path("/x.webp").exists())

    def test_write_images_does_not_follow_a_link_at_an_expected_name(self) -> None:
        outside = self.root / "outside.webp"
        outside.write_bytes(b"outside")
        os.symlink(outside, self.image_dir / "fine.webp")
        generate_seed.write_images({"fine": jpeg()}, self.image_dir)
        self.assertEqual(b"outside", outside.read_bytes())
        self.assertFalse((self.image_dir / "fine.webp").is_symlink())


def raw_tarball(files: dict[str, bytes]) -> bytes:
    """An archive holding exactly `files`, for shapes `tarball` cannot express."""
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w:gz") as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(f"free-exercise-db-{COMMIT}/{name}")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return out.getvalue()


class MalformedArchiveTest(unittest.TestCase):
    """Each malformed shape stops with a message, never a KeyError or TypeError."""

    def assert_stops_with(self, archive: bytes, fragment: str) -> None:
        with self.assertRaises(SystemExit) as stop:
            generate_seed.load_source(archive)
        self.assertIn(fragment, str(stop.exception.code))

    def test_a_missing_catalogue_stops_the_run(self) -> None:
        self.assert_stops_with(raw_tarball({"README.md": b"hi"}), "no dist/exercises.json")

    def test_a_catalogue_that_is_not_json_stops_the_run(self) -> None:
        self.assert_stops_with(raw_tarball({"dist/exercises.json": b"{"}), "not valid JSON")

    def test_a_catalogue_that_is_not_an_array_stops_the_run(self) -> None:
        self.assert_stops_with(raw_tarball({"dist/exercises.json": b"{}"}), "not a JSON array")

    def test_a_record_that_is_not_an_object_stops_the_run(self) -> None:
        self.assert_stops_with(raw_tarball({"dist/exercises.json": b'["x"]'}), "#0 is not a JSON object")

    def test_a_malformed_images_list_stops_the_run(self) -> None:
        for images in ("ok/0.jpg", [7]):
            with self.subTest(images=images):
                catalogue = json.dumps([{**record("ok"), "images": images}]).encode()
                self.assert_stops_with(raw_tarball({"dist/exercises.json": catalogue}), "malformed images")

    def test_a_photo_missing_from_the_archive_stops_the_run(self) -> None:
        catalogue = json.dumps([record("ok")]).encode()
        self.assert_stops_with(raw_tarball({"dist/exercises.json": catalogue}), "exercises/ok/0.jpg")


class HostileCommitTest(SeedTreeTest):
    def test_a_commit_that_is_not_a_full_sha_stops_before_any_read(self) -> None:
        # A planted archive the traversal would reach, pinned by its real digest,
        # so only the shape check stands between it and the generator.
        archive = tarball([record("planted")])
        (self.tool_dir / "planted.tar.gz").write_bytes(archive)
        for commit in ("../planted", "../../tools/seed/planted", "/tmp/planted", "main",
                       COMMIT.upper(), COMMIT[:12], COMMIT + "/x", None):
            with self.subTest(commit=commit):
                self.pin(commit, archive)
                for argv in ([], ["--check"], ["--rehash"]):
                    self.assert_exits_writing_nothing(*argv)
                self.assertFalse((self.image_dir / "planted.webp").exists())


class TarballPinTest(SeedTreeTest):
    def test_a_cached_tarball_that_does_not_match_the_pin_is_rejected(self) -> None:
        archive = tarball([record("good")])
        self.pin(COMMIT, archive)
        (self.cache_dir / f"{COMMIT}.tar.gz").write_bytes(tarball([record("forged")]))
        for argv in ([], ["--check"]):
            self.assert_exits_writing_nothing(*argv)

    def test_a_download_that_does_not_match_the_pin_is_never_cached(self) -> None:
        self.pin(COMMIT, tarball([record("good")]))
        with mock.patch.object(generate_seed, "download", return_value=b"forged"):
            self.assert_exits_writing_nothing()
        self.assertEqual([], list(self.cache_dir.iterdir()))

    def test_an_interrupted_cache_write_leaves_no_file_behind(self) -> None:
        with mock.patch("os.replace", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                generate_seed.cache(COMMIT, b"partial")
        self.assertEqual([], list(self.cache_dir.iterdir()))

    def test_a_matching_tarball_generates(self) -> None:
        archive = tarball([record("good")])
        self.pin(COMMIT, archive)
        (self.cache_dir / f"{COMMIT}.tar.gz").write_bytes(archive)
        lock = self.lock.read_bytes()
        self.assertEqual(0, generate_seed.main([]))
        self.assertEqual(["good.webp"], sorted(p.name for p in self.image_dir.iterdir()))
        self.assertEqual(lock, self.lock.read_bytes())
        self.assertEqual(0, generate_seed.main(["--check"]))


class PinningTest(SeedTreeTest):
    def test_a_commit_outside_the_branch_is_never_downloaded_or_pinned(self) -> None:
        # What the compare API reports for a fork-only commit, and a reply with
        # no status at all.
        self.pin(COMMIT, tarball([record("good")]))
        for status in ("behind", "diverged", None):
            with self.subTest(status=status):
                with mock.patch.object(generate_seed, "github_api", return_value={"status": status}), \
                        mock.patch.object(generate_seed, "download", side_effect=no_network):
                    self.assert_exits_writing_nothing("--rehash")

    def test_rehash_pins_the_digest_of_a_commit_on_the_branch(self) -> None:
        archive = tarball([record("good")])
        self.pin(COMMIT, b"the archive before Renovate moved the commit")
        with mock.patch.object(generate_seed, "github_api", return_value={"status": "ahead"}), \
                mock.patch.object(generate_seed, "download", return_value=archive):
            self.assertEqual(0, generate_seed.main(["--rehash"]))
        lock = json.loads(self.lock.read_text())
        self.assertEqual((COMMIT, hashlib.sha256(archive).hexdigest()),
                         (lock["commit"], lock["tarball_sha256"]))
        self.assertEqual(0, generate_seed.main(["--check"]))


class CheckTest(SeedTreeTest):
    def setUp(self) -> None:
        super().setUp()
        self.images = {"a": jpeg((10, 200, 10)), "b": jpeg((10, 10, 200))}
        generate_seed.write_images(self.images, self.image_dir)

    def stale(self) -> dict[str, list[str]]:
        return generate_seed.stale_images(self.images, self.image_dir)

    def test_freshly_written_images_are_up_to_date(self) -> None:
        self.assertEqual({"missing": [], "unexpected": [], "changed": []}, self.stale())

    def test_a_swapped_image_is_changed(self) -> None:
        (self.image_dir / "a.webp").write_bytes((self.image_dir / "b.webp").read_bytes())
        self.assertEqual(["a.webp"], self.stale()["changed"])

    def test_a_symlink_to_identical_bytes_is_changed(self) -> None:
        elsewhere = self.root / "a.webp"
        elsewhere.write_bytes((self.image_dir / "a.webp").read_bytes())
        (self.image_dir / "a.webp").unlink()
        os.symlink(elsewhere, self.image_dir / "a.webp")
        self.assertEqual(["a.webp"], self.stale()["changed"])

    def test_any_file_the_generator_does_not_write_is_unexpected(self) -> None:
        for name in ("extra.webp", "notes.txt", ".hidden", "noextension"):
            (self.image_dir / name).write_bytes(b"x")
        (self.image_dir / "dir").mkdir()
        self.assertEqual(
            sorted([".hidden", "dir", "extra.webp", "notes.txt", "noextension"]),
            self.stale()["unexpected"],
        )

    def test_a_deleted_image_is_missing(self) -> None:
        (self.image_dir / "b.webp").unlink()
        self.assertEqual(["b.webp"], self.stale()["missing"])

    def test_only_jpeg_is_decoded(self) -> None:
        from PIL import Image

        png = io.BytesIO()
        Image.new("RGB", (8, 8)).save(png, "PNG")
        with self.assertRaises(Exception):
            generate_seed.encode_image(png.getvalue())


class SqlTextTest(unittest.TestCase):
    def test_whitespace_controls_fold(self) -> None:
        self.assertEqual("'a b' || char(10) || 'c' || char(10) || 'd'",
                         generate_seed.sql_text("a\tb\r\nc\rd"))

    def test_nul_and_other_c0_controls_stop_the_run(self) -> None:
        for char in ("\x00", "\x01", "\x0b", "\x0c", "\x1b", "\x1f"):
            with self.subTest(char=repr(char)):
                with self.assertRaises(SystemExit):
                    generate_seed.sql_text(f"Bench{char}Press")


if __name__ == "__main__":
    unittest.main()
