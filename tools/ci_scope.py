"""Central CI event policy; expensive cache qualification is manual only."""
import os
import re
import subprocess
from typing import NamedTuple

OID = re.compile(r"[0-9a-f]{40}")
RAW_OID = re.compile(rb"[0-9a-f]{7,64}")
MODES = {b"000000", b"100644", b"100755", b"120000", b"160000"}
DIFF_ERRORS = (OSError, ValueError, UnicodeError, subprocess.SubprocessError)


class Change(NamedTuple):
    path: bytes
    status: bytes
    old_mode: bytes
    new_mode: bytes


class Decision(NamedTuple):
    mode: str
    reason: str
    native_qualification: bool
    performance_measurement: bool


def parse_diff(raw):
    """Parse all records before making any decision; Git runs with --no-renames."""
    entries = raw.split(b"\0")
    if not raw or entries[-1] != b"" or len(entries) % 2 != 1:
        raise ValueError("empty or incomplete raw diff")
    changes = []
    seen = set()
    for header, path in zip(entries[:-1:2], entries[1:-1:2]):
        fields = header.split(b" ")
        if len(fields) != 5 or not fields[0].startswith(b":"):
            raise ValueError("invalid raw header")
        old, new = fields[0][1:], fields[1]
        status = fields[-1]
        if (old not in MODES or new not in MODES or
                not all(RAW_OID.fullmatch(oid) for oid in fields[2:4]) or
                status not in {b"A", b"D", b"M", b"T"} or
                (old == b"000000") != (status == b"A") or
                (new == b"000000") != (status == b"D") or
                (status == b"M" and old[:3] != new[:3]) or
                (status == b"T" and old[:3] == new[:3]) or
                any(part in {b"", b".", b".."} for part in path.split(b"/")) or
                path in seen):
            raise ValueError("unsupported raw diff record")
        seen.add(path)
        changes.append(Change(path, status, old, new))
    return changes


def is_documentation_path(path):
    # Keep Markdown under app/, rust/, Gradle and other build-input trees in full CI.
    return path.endswith(b".md") and (
        b"/" not in path or path.startswith(b"docs/") or
        (path.startswith(b"tools/") and path.count(b"/") == 1)
    )


def is_docs_only(changes):
    return bool(changes) and all(
        is_documentation_path(c.path) and
        (c.status, c.old_mode, c.new_mode) in {
            (b"M", b"100644", b"100644"),
            (b"A", b"000000", b"100644"),
            (b"D", b"100644", b"000000"),
        } for c in changes
    )


def docs_only(raw):
    """Only regular Markdown in documentation locations; fail closed on invalid diffs."""
    try:
        return is_docs_only(parse_diff(raw))
    except ValueError:
        return False


def git(*args):
    return subprocess.check_output(["git", *args], timeout=30)


def oid(value):
    if not OID.fullmatch(value) or value == "0" * 40:
        raise ValueError("invalid event SHA")
    return value


def verified_pr_base(env):
    """Bind the checked candidate to both event parents, not merely its base."""
    merge = oid(env.get("GITHUB_SHA", ""))
    base = oid(env.get("BASE_SHA", ""))
    head = oid(env.get("PR_HEAD_SHA", ""))
    parents = git("rev-list", "--parents", "-n", "1", "HEAD").decode().split()
    if parents != [merge, base, head]:
        raise ValueError("PR merge candidate/head/base not verified")
    return base


def manual_flag(env, name):
    value = env.get(name, "")
    if value not in {"", "false", "true"}:
        raise ValueError(f"invalid manual input: {name}")
    return value == "true"


def classify(env):
    event = env.get("GITHUB_EVENT_NAME")
    if event == "workflow_dispatch":
        return Decision("full", "Manual full CI; explicit qualification inputs.",
                        manual_flag(env, "VERIFY_NATIVE_CACHE"), manual_flag(env, "MEASURE_ANDROID_BUILD"))
    if event != "pull_request":
        return Decision("full", "Unknown event; conservative full CI.", False, False)
    # An unknown diff warrants full CI; an unverified PR identity cannot authorize any merge.
    base = verified_pr_base(env)
    try:
        changes = parse_diff(git("diff", "--raw", "-z", "--no-renames", base, "HEAD"))
    except DIFF_ERRORS:
        return Decision("full", "Could not verify complete event diff; full CI required.", False, False)
    if is_docs_only(changes) and env.get("BASE_REF") == "main":
        return Decision("docs", "Verified documentation-only diff; no build inputs changed.",
                        False, False)
    return Decision("full", "Verified event diff; routine full CI.", False, False)


def main():
    decision = classify(os.environ)
    print(decision.reason)
    identity = ""
    if os.environ.get("GITHUB_EVENT_NAME") == "pull_request":
        identity = (f"PR head: {os.environ['PR_HEAD_SHA']}\n"
                    f"PR base: {os.environ['BASE_SHA']}\n"
                    f"PR merge: {os.environ['GITHUB_SHA']}\n")
        print(identity, end="")
    fields = (('mode', decision.mode),
              ('native_qualification', str(decision.native_qualification).lower()),
              ('performance_measurement', str(decision.performance_measurement).lower()))
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for name, value in fields:
            output.write(f"{name}={value}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"### CI scope: {decision.mode}\n\n{decision.reason}\n\n")
        if identity:
            summary.write(f"```text\n{identity}```\n\n")
        for name, value in fields[1:]:
            summary.write(f"- {name}: {value}\n")


if __name__ == "__main__":
    main()
