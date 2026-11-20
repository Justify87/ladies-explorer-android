"""Reuse only a successful checked APK of the exact integrated Git tree.

Same-repository two-parent PR merges only; no downloaded files are executed.
Missing or ambiguous evidence always falls back to full CI.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from ci_scope import docs_only, git, oid

ARTIFACT = "ladies-explorer-android-arm64-debug"
PROOF = "ci-proof.json"


def api(path, **query):
    args = ["gh", "api", "--method", "GET", path]
    for key, value in query.items():
        args.extend(("-f", f"{key}={value}"))
    return json.loads(subprocess.check_output(args, timeout=30))


def snapshot(commit="HEAD"):
    sha = oid(git("rev-parse", commit).decode().strip())
    tree = oid(git("rev-parse", sha + "^{tree}").decode().strip())
    parents = git("rev-list", "--parents", "-n", "1", sha).decode().split()
    if not parents or parents[0] != sha:
        raise ValueError("unverified checkout")
    return {"commit": sha, "tree": tree, "parents": parents[1:]}


def apk_identity(directory):
    files = [p for p in directory.glob("*.apk") if p.is_file() and not p.is_symlink()]
    if len(files) != 1 or files[0].stat().st_size == 0:
        raise ValueError("expected one nonempty debug APK")
    with files[0].open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"name": files[0].name, "sha256": digest}


def record(directory, env, source=None):
    current = snapshot()
    if current["commit"] != env["GITHUB_SHA"]:
        raise ValueError("checkout differs from event")
    proof = {
        "version": 1, **current, "event": env["GITHUB_EVENT_NAME"],
        "repository": env["GITHUB_REPOSITORY"], "run": int(env["GITHUB_RUN_ID"]),
        "attempt": int(env["GITHUB_RUN_ATTEMPT"]), "apk": apk_identity(directory),
    }
    if source is None:
        proof.update(native=env["NATIVE_REQUIRED"], measurement=env["PERFORMANCE_REQUIRED"])
    else:
        proof.update(source_run=source)
    (directory / PROOF).write_text(json.dumps(proof, sort_keys=True) + "\n")


def merged_pr(repository, current, before):
    if len(current["parents"]) != 2 or current["parents"][0] != before:
        return None
    matches = []
    for pr in api(f"repos/{repository}/commits/{current['commit']}/pulls"):
        if (pr.get("merge_commit_sha") == current["commit"] and pr.get("merged_at")
                and pr.get("base", {}).get("ref") == "main"
                and pr["base"]["repo"]["full_name"] == repository
                and pr["head"]["repo"]["full_name"] == repository
                and pr["head"]["sha"] == current["parents"][1]):
            matches.append(pr)
    return matches[0] if len(matches) == 1 else None


def latest_run(repository, sha, event):
    query = {"head_sha": sha, "event": event, "per_page": 1}
    if event == "push":
        query["branch"] = "main"
    runs = api(f"repos/{repository}/actions/workflows/android.yml/runs", **query)["workflow_runs"]
    if not runs:
        raise ValueError("no source CI")
    run = runs[0]
    if not (run["head_sha"] == sha and run["event"] == event
            and run["status"] == "completed" and run["conclusion"] == "success"
            and run["path"].split("@", 1)[0] == ".github/workflows/android.yml"
            and run["repository"]["full_name"] == repository
            and run["head_repository"]["full_name"] == repository
            and (event != "push" or run["head_branch"] == "main")):
        raise ValueError("latest source CI not successful or trusted")
    return run


def verify_proof(proof, run, expected_tree, expected_parents, expected_commit, apk,
                 flags=None):
    if not (proof["version"] == 1 and proof["tree"] == expected_tree
            and proof["parents"] == expected_parents
            and (expected_commit is None or proof["commit"] == expected_commit)
            and proof["repository"] == run["repository"]["full_name"]
            and proof["event"] == run["event"]
            and proof["run"] == run["id"] and proof["attempt"] == run["run_attempt"]
            and proof["apk"] == apk):
        raise ValueError("source proof does not match integrated build")
    oid(proof["commit"])
    if flags is not None and (proof.get("native"), proof.get("measurement")) != flags:
        raise ValueError("required qualification differs from source")


def restore(directory, env):
    repository = env["GITHUB_REPOSITORY"]
    current = snapshot()
    before = oid(env.get("BEFORE_SHA", ""))
    if env["GITHUB_REF"] != "refs/heads/main" or current["commit"] != env["GITHUB_SHA"]:
        raise ValueError("not the integrated main head")
    pr = merged_pr(repository, current, before)
    if pr is None:
        raise ValueError("not an exact same-repository PR merge")
    pr_run = latest_run(repository, pr["head"]["sha"], "pull_request")
    raw = git("diff", "--raw", "-z", "--no-renames", before, "HEAD")
    if docs_only(raw):
        # CI01 permits strictly allowlisted docs to reuse the exact green base.
        run = latest_run(repository, before, "push")
        expected = snapshot(before)
        expected_commit = before
        flags = None
    else:
        run = pr_run
        expected = current
        expected_commit = None
        flags = (env["NATIVE_REQUIRED"], env["PERFORMANCE_REQUIRED"])
    subprocess.run(["gh", "run", "download", str(run["id"]), "--repo", repository,
                    "--name", ARTIFACT, "--dir", str(directory)], check=True, timeout=60)
    proof_path = directory / PROOF
    if not proof_path.is_file() or proof_path.is_symlink():
        raise ValueError("source artifact has no tree proof")
    proof = json.loads(proof_path.read_text())
    verify_proof(proof, run, expected["tree"], expected["parents"], expected_commit,
                 apk_identity(directory), flags)
    if set(directory.iterdir()) != {directory / proof["apk"]["name"], proof_path}:
        raise ValueError("unexpected source files")
    record(directory, env, source=run["id"])
    return run["id"]


def main():
    env = os.environ
    directory = Path(sys.argv[2])
    if sys.argv[1] == "record":
        record(directory, env)
        return
    if sys.argv[1] != "restore":
        raise ValueError("unknown operation")
    try:
        directory.mkdir(parents=True, exist_ok=False)
        source = restore(directory, env)
    except (OSError, ValueError, KeyError, TypeError, AttributeError,
            subprocess.SubprocessError) as exc:
        shutil.rmtree(directory, ignore_errors=True)
        print(f"Reuse unavailable ({type(exc).__name__}): full CI fallback.")
        return
    with open(env["GITHUB_OUTPUT"], "a") as output:
        output.write(f"source_run={source}\n")
    with open(env["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(f"Verified prior APK and CI: run {source}; no rebuild required.\n")


if __name__ == "__main__":
    main()
