"""Conservative docs-only admission for Android and Rust CI.

Only a verified main push may supply the full-build evidence. Any ambiguity
(including API or shallow-history failures) requests a fresh full build.
"""
import json
import os
import subprocess

DOCS = frozenset({
    b"README.md",
    b"docs/ROADMAP.md",
    b"docs/M05-PLAN.md",
    b"docs/M05-TASKS.md",
    b"docs/M05-PARITY.md",
    b"docs/ARCHITECTURE.md",
    b"docs/SOURCE.md",
    b"docs/DATA-IMPORT.md",
    b"docs/DIAGNOSTICS.md",
    b"docs/MAP.md",
    b"tools/M05-REFERENCE.md",
})


def docs_only(raw):
    """Accept only ordinary content modifications to explicitly allowed docs."""
    entries = raw.split(b"\0")
    if not raw or entries[-1] != b"" or len(entries) % 2 != 1:
        return False
    for header, path in zip(entries[:-1:2], entries[1:-1:2]):
        fields = header.split()
        if (len(fields) != 5 or fields[:2] != [b":100644", b"100644"]
                or fields[-1] != b"M" or path not in DOCS):
            return False
    return True


def verified_main_run(response, base):
    # The API query is scoped to android.yml, push and this exact main SHA.
    # Do not select an older success if the newest attempt is pending/failed.
    runs = response.get("workflow_runs", [])
    if not runs:
        return None
    run = runs[0]
    if (run.get("head_sha") == base and run.get("head_branch") == "main"
            and run.get("event") == "push" and run.get("status") == "completed"
            and run.get("conclusion") == "success"
            and isinstance(run.get("id"), int)):
        return run["id"]
    return None


def git(*args):
    return subprocess.check_output(["git", *args], timeout=30)


def classify(env):
    if env.get("GITHUB_EVENT_NAME") != "pull_request" or env.get("BASE_REF") != "main":
        return "full", "Full CI for main, manual and non-main-base runs."
    base = env.get("BASE_SHA", "")
    try:
        # GitHub's PR checkout is the synthetic merge, not just the latest
        # push; check its actual first parent and the entire resulting diff.
        parents = git("rev-list", "--parents", "-n", "1", "HEAD").decode().split()
        if len(parents) != 3 or parents[1] != base:
            return "full", "PR merge base could not be confirmed."
        if not docs_only(git("diff", "--raw", "-z", "--no-renames", base, "HEAD")):
            return "full", "PR includes non-allowlisted changes or an empty diff."
        response = json.loads(subprocess.check_output([
            "gh", "api", "--method", "GET",
            f"repos/{env['GITHUB_REPOSITORY']}/actions/workflows/android.yml/runs",
            "-f", f"head_sha={base}", "-f", "branch=main",
            "-f", "event=push", "-f", "per_page=1",
        ], timeout=30))
        run_id = verified_main_run(response, base)
        if run_id is not None:
            return "docs", f"Documentation-only PR; full main CI evidence: run {run_id}, SHA {base}."
        return "full", "No successful latest main push CI for the exact base."
    except (OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.SubprocessError):
        return "full", "Could not establish documentation-only build evidence."


def main():
    mode, reason = classify(os.environ)
    print(reason)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"mode={mode}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"### CI scope: {mode}\n\n{reason}\n")


if __name__ == "__main__":
    main()
