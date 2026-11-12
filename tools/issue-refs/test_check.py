"""Regression tests for what check.py prints about an issue it did not write.

Run with `make issue-refs-test`, or `python3 -m unittest` from this directory. GitHub is
replaced by a stub that answers with a hostile title, so nothing here needs a token or the
network, and the repository it reads is a temporary directory holding one file.
"""

from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check  # noqa: E402

# Clears the screen, homes the cursor, rings the bell, then returns to the start of the line
# to print something else over what was there; the last is the single-byte C1 CSI.
HOSTILE_TITLE = "Harmless\x1b[2J\x1b[H\x07\rAll issues are open\x9b31m"
CONTROLS = ("\x1b", "\x07", "\r", "\x9b")
# Spliced into the sentence below rather than written beside its `#`, so that check.py, which
# reads this file like any other, does not find a pending reference in it.
ISSUE = 30


class PrintableTest(unittest.TestCase):
    def test_every_control_character_is_spelled_out(self) -> None:
        self.assertEqual(check.printable("a\x1bb\x07c\rd\x00e\x7ff\x9bg"), "a\\x1bb\\x07c\\x0dd\\x00e\\x7ff\\x9bg")

    def test_ordinary_text_is_left_alone(self) -> None:
        title = "Rest timer: «30 s» — set 2 of 5 (#30)"
        self.assertEqual(check.printable(title), title)


class ReportTest(unittest.TestCase):
    """The report as the developer's terminal receives it, for a closed issue with a hostile
    title waited on by a sentence that carries control characters of its own."""

    def setUp(self) -> None:
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        doc = root / "NOTES.md"
        doc.write_text(f"The chart is flat until #{ISSUE} lands.\x1b[8m\n", encoding="utf-8")

        self.enterContext(mock.patch.object(check, "ROOT", root))
        self.enterContext(mock.patch.object(check, "tracked_text_files", return_value=[doc]))
        self.enterContext(mock.patch.object(check, "token", return_value="token"))
        self.enterContext(mock.patch.object(check, "repository", return_value="owner/repo"))
        self.enterContext(
            mock.patch.object(check, "github", return_value={"state": "closed", "title": HOSTILE_TITLE})
        )
        self.enterContext(mock.patch.object(sys, "argv", ["check.py"]))

    def run_check(self) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = check.main()
        return code, out.getvalue()

    def test_stdout_holds_no_escape_bell_or_carriage_return(self) -> None:
        code, out = self.run_check()

        self.assertEqual(code, 1)
        self.assertIn(f"NOTES.md:1: #{ISSUE} (Harmless", out)
        for control in CONTROLS:
            self.assertNotIn(control, out)

    def test_the_title_is_still_there_to_read(self) -> None:
        _, out = self.run_check()

        self.assertIn("Harmless\\x1b[2J\\x1b[H\\x07\\x0dAll issues are open\\x9b31m", out)


if __name__ == "__main__":
    unittest.main()
