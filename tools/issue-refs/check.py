#!/usr/bin/env python3
"""Fail on prose that calls a closed issue still pending.

"Until #30 lands, the recap shows one record per exercise" is true the day it is written and a
trap the day #30 closes: the sentence keeps describing a workaround as temporary long after it
became the shipped behaviour, and a reader — a person or an agent — trusts it and does not look
for the bug it now hides. Nothing in the build notices, because the code beside it still
compiles. This finds every sentence that speaks of an issue as not done yet and asks GitHub
whether it is; a closed one is reported at the line that mentions it.

What reads as "not done yet", in a comment, a doc or a string resource:

  #30 has not landed / is not yet merged          the issue, then a negated past
  until #35 lands / when the engine (#30) lands   the issue, then a present-tense arrival
  disabled until #54 / blocked on #12             a waiting word, then the issue, then nothing
  TODO(#88)                                       a to-do pinned to an issue

Past tense is left alone — "until #38 this was two rows" is history, and history stays true.

A phrase in quotation marks or in a `code span` is an example of the wording, not a claim, and
is left alone too. ``--pr`` also counts the issues a pull request closes, so the change that
finishes #54 can be checked for what it made untrue before it merges.

Usage:
    ./check.py                 check every tracked text file
    ./check.py --pr 231        also treat the issues PR #231 closes as closed

Needs read access to the repository's issues: GITHUB_TOKEN or GH_TOKEN, or a logged-in ``gh``.
Exits 1 on any finding and 2 when GitHub cannot be asked.
"""

from __future__ import annotations

import argparse
import bisect
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SELF = Path(__file__).resolve()

# Files whose prose can make a promise. Generated and binary files cannot, and are skipped.
TEXT_SUFFIXES = frozenset({".kt", ".kts", ".java", ".md", ".py", ".xml", ".yml", ".yaml", ".toml", ".properties", ".sh"})
TEXT_NAMES = frozenset({"Makefile"})

# `#30`, `(#30)` — the number is the group every pattern below names `n`.
REF = r"\(?#(?P<n>\d+)\)?"
# A present-tense arrival: the thing has not happened yet. Only verbs that mean nothing else.
ARRIVES = r"(?:lands|ships|merges|is\s+merged|arrives)"
# Verbs that also describe what shipped code does ("the quest board #104 builds the card
# from"), so they read as an arrival only after a word that waits: "until #54 builds it".
ARRIVES_AFTER_WAITING = r"(?:builds|does|is\s+done|exists|is\s+in)"
WAITING = r"(?:until|once|when|before|after|as\s+soon\s+as)"

PENDING = [
    # "#30 has not landed", "(#30) is not yet merged", "#30 hasn't shipped"
    re.compile(
        REF + r"\s+(?:has|have|is|are)(?:\s+not|n't)\s+(?:yet\s+)?(?:been\s+)?(?:landed|merged|shipped|built|done)\b",
        re.IGNORECASE,
    ),
    # "#35 lands", "the engine (#30) lands"
    re.compile(REF + r"\s+" + ARRIVES + r"\b", re.IGNORECASE),
    # "until #54 builds what is behind it", "when the engine (#30) does" — up to four words between
    re.compile(
        r"\b" + WAITING + r"\s+(?:[\w'-]+\s+){0,4}?" + REF + r"\s+" + ARRIVES_AFTER_WAITING + r"\b",
        re.IGNORECASE,
    ),
    # "disabled until #54", "blocked on #12" — the issue ends the clause. Followed by a word, it
    # is the subject of something else ("until #214 nothing read them"), which is history.
    re.compile(
        r"\b(?:until|pending|awaiting|blocked\s+(?:on|by)|waiting\s+(?:on|for))\s+" + REF + r"(?!\s*[\w(])",
        re.IGNORECASE,
    ),
    # "TODO(#88)", "FIXME #12: ..."
    re.compile(r"\b(?:TODO|FIXME)\b\W{0,3}" + REF),
]

# A sentence in the past tense is history, whatever word it waits on: "Was two rows until #38."
# Not "landed" and its kin, which "has not landed" — the most pending phrase of all — contains;
# a bare "until #35 landed" is already left alone by the patterns, which want a word to wait on.
PAST = re.compile(r"\b(?:was|were|had|used\s+to)\b", re.IGNORECASE)

# How far either side of a match its sentence is read, in characters — see `sentence_bounds`.
SENTENCE_REACH = 240

# What opens a comment or a list item, stripped so a sentence wrapped over several comment lines
# reads as one: ` * until the engine (#30)` + ` * lands` is "until the engine (#30) lands".
LINE_PREFIX = re.compile(r"^\s*(?:/\*\*?|\*/|\*|//+|#+|<!--|-->|-\s|>)?\s*")


@dataclass(frozen=True)
class Reference:
    path: Path
    line: int
    issue: int
    sentence: str


def tracked_text_files() -> list[Path]:
    listed = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout
    files = []
    for name in listed.decode().split("\0"):
        path = ROOT / name
        if name and (path.suffix in TEXT_SUFFIXES or path.name in TEXT_NAMES) and path != SELF:
            files.append(path)
    return files


def pending_references(path: Path, text: str) -> list[Reference]:
    """Every reference in [text] that reads as not done yet, at the line its number is on."""
    pieces: list[str] = []
    starts: list[int] = []
    offset = 0
    for raw in text.splitlines():
        piece = LINE_PREFIX.sub("", raw, count=1)
        starts.append(offset)
        pieces.append(piece)
        offset += len(piece) + 1
    joined = " ".join(pieces)

    found: dict[tuple[int, int], Reference] = {}
    for pattern in PENDING:
        for match in pattern.finditer(joined):
            left, right = sentence_bounds(joined, match.start(), match.end())
            sentence = " ".join(joined[left:right].split())
            if PAST.search(sentence) or quoted(joined[left : match.start()]):
                continue
            at = match.start("n")
            line = bisect.bisect_right(starts, at)
            issue = int(match.group("n"))
            found.setdefault((line, issue), Reference(path, line, issue, sentence))
    return sorted(found.values(), key=lambda ref: (ref.line, ref.issue))


def sentence_bounds(text: str, start: int, end: int) -> tuple[int, int]:
    """The sentence a match sits in, but never more than [SENTENCE_REACH] either side of it:
    a YAML or Makefile comment has no full stops, and its "sentence" would otherwise be the
    file — long enough to hold a "was" that excuses anything."""
    full_stop = text.rfind(". ", 0, start)
    left = max(full_stop + 2 if full_stop != -1 else 0, start - SENTENCE_REACH)
    next_stop = text.find(". ", end)
    right = min(next_stop + 1 if next_stop != -1 else len(text), end + SENTENCE_REACH)
    return left, right


def quoted(before: str) -> bool:
    """Whether the sentence has an open quotation or code span where the match starts. A quoted
    phrase is an example of the wording — a doc explaining this check, say — and not a claim
    about the issue it names."""
    double = before.count('"') + before.count("\u201c") - before.count("\u201d")
    return double % 2 == 1 or before.count("`") % 2 == 1


def token() -> str | None:
    for name in ("GITHUB_TOKEN", "GH_TOKEN"):
        if os.environ.get(name):
            return os.environ[name]
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, check=True).stdout.strip()
        return out or None
    except (OSError, subprocess.CalledProcessError):
        return None


def repository() -> str:
    url = subprocess.run(
        ["git", "remote", "get-url", "origin"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.strip()
    match = re.search(r"github\.com[:/](?P<slug>[^/]+/[^/]+?)(?:\.git)?$", url)
    if not match:
        raise SystemExit(f"origin is not a GitHub remote: {url}")
    return match.group("slug")


def github(auth: str, path: str, body: dict | None = None) -> dict:
    request = urllib.request.Request(
        f"https://api.github.com{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {auth}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def closed_by_pull_request(auth: str, slug: str, number: int) -> set[int]:
    owner, name = slug.split("/")
    query = """
        query($owner: String!, $name: String!, $number: Int!) {
          repository(owner: $owner, name: $name) {
            pullRequest(number: $number) { closingIssuesReferences(first: 50) { nodes { number } } }
          }
        }
    """
    data = github(auth, "/graphql", {"query": query, "variables": {"owner": owner, "name": name, "number": number}})
    pull = (data.get("data") or {}).get("repository", {}).get("pullRequest") or {}
    return {node["number"] for node in pull.get("closingIssuesReferences", {}).get("nodes", [])}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--pr", type=int, help="also treat the issues this pull request closes as closed")
    args = parser.parse_args()

    references = [
        ref
        for path in tracked_text_files()
        for ref in pending_references(path, path.read_text(encoding="utf-8", errors="replace"))
    ]
    if not references:
        print("No issue is referred to as pending.")
        return 0

    auth = token()
    if auth is None:
        print("No GitHub token: set GITHUB_TOKEN or log in with `gh auth login`.", file=sys.stderr)
        return 2
    slug = repository()

    closing: set[int] = set()
    pr = args.pr
    try:
        if pr:
            closing = closed_by_pull_request(auth, slug, pr)
        states: dict[int, tuple[str, str]] = {}
        for issue in sorted({ref.issue for ref in references}):
            try:
                found = github(auth, f"/repos/{slug}/issues/{issue}")
                states[issue] = (found["state"], found["title"])
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                # Not an issue at all — a number that happens to follow a `#`. Nothing to hold.
                states[issue] = ("missing", "")
    except (urllib.error.URLError, TimeoutError) as error:
        print(f"Could not ask GitHub about issues: {error}", file=sys.stderr)
        return 2

    stale = []
    for ref in references:
        state, title = states[ref.issue]
        if state == "closed" or ref.issue in closing:
            why = f"closed by this pull request (#{pr})" if ref.issue in closing and state != "closed" else "closed"
            stale.append((ref, f"#{ref.issue} ({title}) is {why}, but this still waits on it"))

    for ref, message in stale:
        rel = ref.path.relative_to(ROOT)
        print(f"{rel}:{ref.line}: {message}\n    {ref.sentence}")

    pending = len({ref.issue for ref in references}) - len({ref.issue for ref, _ in stale})
    print(f"\n{len(stale)} stale reference(s); {pending} issue(s) still open and waited on.")
    return 1 if stale else 0


if __name__ == "__main__":
    sys.exit(main())
