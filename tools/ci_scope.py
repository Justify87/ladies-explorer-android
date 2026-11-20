"""Central CI event policy; expensive cache qualification is manual only."""
import json
import os
import re
import subprocess
from typing import NamedTuple

DOCS = frozenset({
    b"README.md", b"docs/ROADMAP.md", b"docs/M05-PLAN.md",
    b"docs/M05-TASKS.md", b"docs/M05-PARITY.md", b"docs/ARCHITECTURE.md",
    b"docs/SOURCE.md", b"docs/DATA-IMPORT.md", b"docs/DIAGNOSTICS.md",
    b"docs/MAP.md", b"tools/M05-REFERENCE.md",
})
MEASURE = frozenset({b"tools/install-cargo-ndk.sh", b"tools/measure-android-build.sh"})
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
    reuse_candidate: str = "none"


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


def is_docs_only(changes):
    return bool(changes) and all(c.path in DOCS and c.status == b"M" and
                                 c.old_mode == c.new_mode == b"100644" for c in changes)


def docs_only(raw):
    """Retain the existing CI01 allowlist and fail-closed structural checks."""
    try:
        return is_docs_only(parse_diff(raw))
    except ValueError:
        return False


def verified_main_run(response, base):
    # Only the latest main push for this exact base supplies reusable evidence.
    runs = response.get("workflow_runs", [])
    if not runs:
        return None
    run = runs[0]
    if (run.get("head_sha") == base and run.get("head_branch") == "main"
            and run.get("event") == "push" and run.get("status") == "completed"
            and run.get("conclusion") == "success" and type(run.get("id")) is int):
        return run["id"]
    return None


def git(*args):
    return subprocess.check_output(["git", *args], timeout=30)


def oid(value):
    if not OID.fullmatch(value) or value == "0" * 40:
        raise ValueError("invalid event SHA")
    return value


def event_diff(env, event):
    head = oid(env.get("GITHUB_SHA", ""))
    if event == "pull_request":
        base = oid(env.get("BASE_SHA", ""))
        parents = git("rev-list", "--parents", "-n", "1", "HEAD").decode().split()
        if len(parents) != 3 or parents[0] != head or parents[1] != base:
            raise ValueError("PR merge head/base not verified")
    else:
        base = oid(env.get("BEFORE_SHA", ""))
        if env.get("GITHUB_REF") != "refs/heads/main" or git("rev-parse", "HEAD").decode().strip() != head:
            raise ValueError("push head/ref not verified")
        git("merge-base", "--is-ancestor", base, head)
    return base, parse_diff(git("diff", "--raw", "-z", "--no-renames", base, "HEAD"))


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
    if event not in {"pull_request", "push"}:
        return Decision("full", "Unknown event; conservative full CI.", False, False)
    try:
        base, changes = event_diff(env, event)
    except DIFF_ERRORS:
        return Decision("full", "Could not verify complete event diff; full CI required.", False, False)
    measure = any(c.path in MEASURE for c in changes)
    if event == "pull_request" and env.get("BASE_REF") == "main" and is_docs_only(changes):
        try:
            response = json.loads(subprocess.check_output([
                "gh", "api", "--method", "GET",
                f"repos/{env['GITHUB_REPOSITORY']}/actions/workflows/android.yml/runs",
                "-f", f"head_sha={base}", "-f", "branch=main",
                "-f", "event=push", "-f", "per_page=1",
            ], timeout=30))
            run_id = verified_main_run(response, base)
            if run_id is not None:
                return Decision("docs", f"Documentation-only PR; verified main CI evidence: run {run_id}, SHA {base}.", False, False)
        except DIFF_ERRORS + (KeyError, TypeError, AttributeError):
            pass
        return Decision("full", "No successful latest main push CI for the exact docs base.", False, False)
    candidate = ("docs" if is_docs_only(changes) else "merge") if event == "push" else "none"
    return Decision("full", "Verified event diff; full CI with selected additional qualifications.",
                    False, measure, candidate)


def main():
    decision = classify(os.environ)
    print(decision.reason)
    fields = (('mode', decision.mode),
              ('native_qualification', str(decision.native_qualification).lower()),
              ('performance_measurement', str(decision.performance_measurement).lower()),
              ('reuse_candidate', decision.reuse_candidate))
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for name, value in fields:
            output.write(f"{name}={value}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"### CI scope: {decision.mode}\n\n{decision.reason}\n\n")
        for name, value in fields[1:]:
            summary.write(f"- {name}: {value}\n")


if __name__ == "__main__":
    main()
